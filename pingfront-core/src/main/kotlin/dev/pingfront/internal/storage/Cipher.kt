package dev.pingfront.internal.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.pingfront.internal.runtime.Log
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val log = Log.of(Log.Category.Storage)

/** The key or storage cannot be used right now (before first unlock, direct boot, a Keystore failure). Not "not found". */
internal class StorageUnavailableException(cause: Throwable?) : java.io.IOException("Encrypted storage is not available.", cause)

/** Encrypts everything the SDK writes to disk. Output: 12-byte IV followed by AES-GCM ciphertext and tag. */
internal interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Returns `null` when the data cannot be decrypted (for example the key was lost). */
    fun decrypt(sealed: ByteArray): ByteArray?
}

/** Where the storage key lives. Production: the Android Keystore. Tests inject a software source. */
internal interface KeySource {
    /** The key, created on first use. */
    fun load(): SecretKey

    /** Deletes the key for good (it was permanently invalidated); the next [load] creates a new one. */
    fun delete()
}

/** A non-exportable AES-256 key in the Android Keystore (this device only, never backed up). */
internal class AndroidKeySource(private val alias: String = "dev.pingfront.storage") : KeySource {
    override fun load(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    override fun delete() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}

/**
 * AES-256-GCM with the [KeySource] key. Failures are classified (review finding CS-7):
 * - **transient** ([StorageUnavailableException]): the Keystore is locked or not ready (before the first
 *   unlock, direct boot: `IllegalStateException`), or it failed this time (`ProviderException`,
 *   `KeyStoreException`, I/O). The SDK waits and never mints a new install.
 * - **permanent**: the key can no longer be used (`KeyPermanentlyInvalidatedException` and the other
 *   `InvalidKeyException`s, seen after some OS updates). The key is deleted and replaced; data sealed with
 *   it reads as unreadable, so the record store moves it aside and a new install starts.
 */
internal class KeystoreCipher(private val keys: KeySource = AndroidKeySource()) : SecretCipher {
    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = transient { keys.load() }
            cached = key
            return key
        }
    }

    /** The key is unusable for good: delete it, so the next operation creates a new one. */
    private fun replaceKey(cause: Throwable) {
        log.e("The storage key was invalidated (${cause.javaClass.simpleName}); a new key replaces it and older records are moved aside.")
        synchronized(this) {
            cached = null
            transient { keys.delete() }
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray = try {
        transient { AesGcm.encrypt(key(), plain) }
    } catch (e: InvalidKeyException) {
        replaceKey(e)
        transient { AesGcm.encrypt(key(), plain) }
    }

    override fun decrypt(sealed: ByteArray): ByteArray? = try {
        transient { AesGcm.decrypt(key(), sealed) }
    } catch (e: InvalidKeyException) {
        replaceKey(e)
        // Sealed with the key that is gone: unreadable (moved aside by the caller), never "unavailable" forever.
        null
    }

    /** Runs [block], turning transient Keystore failures into [StorageUnavailableException]. `InvalidKeyException` passes through. */
    private inline fun <T> transient(block: () -> T): T = try {
        block()
    } catch (e: InvalidKeyException) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw StorageUnavailableException(e)
    } catch (e: ProviderException) {
        throw StorageUnavailableException(e)
    } catch (e: java.io.IOException) {
        throw StorageUnavailableException(e)
    } catch (e: IllegalStateException) {
        // The Keystore daemon is not ready (for example before the first unlock, or during direct boot).
        throw StorageUnavailableException(e)
    }
}

internal object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: SecretKey, sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Other failures (an unusable key, a provider error) propagate for the caller to classify.
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return try {
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: javax.crypto.AEADBadTagException) {
            // The bytes do not match the key: corrupt, or sealed with a key that no longer exists.
            null
        }
    }
}
