package dev.pingfront

/** Why an app key was rejected offline. The same checks, in the same order, as the server and every pingfront SDK. */
public enum class KeyProblem(
    /** The wire name, for example `checksum_mismatch`. */
    public val code: String,
    /** The exact, actionable explanation shown in debug banners and logs. */
    public val message: String,
) {
    /** The key is an empty string. */
    Empty("empty", "The app key is empty. Copy it from Dashboard > App > Connect."),

    /** The key has leading or trailing whitespace or line breaks. */
    Whitespace("whitespace", "The app key has spaces or line breaks around it. Remove them."),

    /** The key does not start with `pk_live_` or `pk_test_`. */
    WrongPrefix("wrong_prefix", "This is not a public app key. Public keys start with pk_live_ or pk_test_."),

    /** The key is longer or shorter than a public key. */
    WrongLength("wrong_length", "The app key has the wrong length. It was probably cut off when copying."),

    /** The key contains characters outside base62. */
    BadCharacters("bad_characters", "The app key contains characters that never appear in keys."),

    /** The two check characters do not match the rest of the key (a typo). */
    ChecksumMismatch("checksum_mismatch", "The app key has a typo: its check characters do not match."),
}

/** A public app key that passed the offline checksum. */
public class AppKey private constructor(
    /** The full key, for example `pk_live_…`. */
    public val rawValue: String,
    /** Whether the key belongs to the live or the test environment. */
    public val mode: Mode,
) {
    /** The environment a key belongs to. */
    public enum class Mode { Live, Test }

    /** The key with its body masked, safe for logs and banners. */
    override fun toString(): String = "pk_${mode.name.lowercase()}_…${rawValue.takeLast(4)}"

    override fun equals(other: Any?): Boolean = other is AppKey && other.rawValue == rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    /** Parsing. */
    public companion object {
        /** Validates [key] offline with the CRC-32 check characters. No network. */
        public fun parse(key: String): Result<AppKey> = when (val result = KeyCheck.check(key)) {
            is KeyCheck.Outcome.Valid -> Result.success(AppKey(key, result.mode))
            is KeyCheck.Outcome.Invalid -> Result.failure(PingFrontException.InvalidKey(result.problem))
        }

        /** The problem with [key], or `null` when it is valid. */
        public fun problem(key: String): KeyProblem? = (KeyCheck.check(key) as? KeyCheck.Outcome.Invalid)?.problem
    }
}

/**
 * The checksum from `keys.ts`, mirrored exactly:
 * `n = crc32(prefix + body) mod 3844`, check = `BASE62[n / 62] + BASE62[n % 62]`.
 */
internal object KeyCheck {
    const val BASE62: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val BODY_LENGTH: Int = 22
    private const val RADIX = 62
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val UINT_MASK = 0xFFFFFFFFL

    sealed interface Outcome {
        data class Valid(val mode: AppKey.Mode) : Outcome

        data class Invalid(val problem: KeyProblem) : Outcome
    }

    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    /** CRC-32 (IEEE 802.3, reflected, polynomial 0xEDB88320) of the UTF-8 bytes, as an unsigned value. */
    fun crc32(input: String): Long {
        var crc = -1
        for (byte in input.encodeToByteArray()) {
            crc = table[(crc xor byte.toInt()) and BYTE_MASK] xor (crc ushr BITS_PER_BYTE)
        }
        return (crc xor -1).toLong() and UINT_MASK
    }

    fun checkChars(prefixAndBody: String): String {
        val n = (crc32(prefixAndBody) % (RADIX * RADIX)).toInt()
        return "${BASE62[n / RADIX]}${BASE62[n % RADIX]}"
    }

    fun check(key: String, expectedKind: String = "pk"): Outcome {
        if (key.isEmpty()) return Outcome.Invalid(KeyProblem.Empty)
        if (key.trim() != key) return Outcome.Invalid(KeyProblem.Whitespace)
        val match = Regex("^([a-z]+)_(live|test)_(.*)$", RegexOption.DOT_MATCHES_ALL).find(key)
        if (match == null || match.groupValues[1] != expectedKind) return Outcome.Invalid(KeyProblem.WrongPrefix)
        val mode = if (match.groupValues[2] == "live") AppKey.Mode.Live else AppKey.Mode.Test
        val rest = match.groupValues[3]
        if (rest.length != BODY_LENGTH + 2) return Outcome.Invalid(KeyProblem.WrongLength)
        if (!rest.all { it in BASE62 }) return Outcome.Invalid(KeyProblem.BadCharacters)
        val head = "${expectedKind}_${match.groupValues[2]}_${rest.take(BODY_LENGTH)}"
        if (checkChars(head) != rest.takeLast(2)) return Outcome.Invalid(KeyProblem.ChecksumMismatch)
        return Outcome.Valid(mode)
    }
}
