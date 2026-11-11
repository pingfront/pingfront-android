@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront.testing

import dev.pingfront.internal.Dependencies
import dev.pingfront.internal.RetryScheduler
import dev.pingfront.internal.net.HttpRequest
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.net.HttpTransport
import dev.pingfront.internal.net.LiveSocket
import dev.pingfront.internal.net.SocketFrame
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.runtime.SdkDispatchers
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.storage.AesGcm
import dev.pingfront.internal.storage.Persistence
import dev.pingfront.internal.storage.SecretCipher
import dev.pingfront.internal.storage.StorageUnavailableException
import dev.pingfront.internal.wire.Device
import java.io.IOException
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope

/** Virtual time from the test scheduler: monotonic `now` and a wall clock starting 30 Oct 2026, 18:00 UTC. */
internal class TestTime(private val scope: TestScope) : TimeSource {
    override fun now(): Duration = scope.testScheduler.currentTime.milliseconds

    override fun wall(): Instant = BASE.plusMillis(scope.testScheduler.currentTime)

    companion object {
        val BASE: Instant = Instant.ofEpochSecond(1_793_390_400)
    }
}

/** In-memory persistence; [unavailable] simulates a locked Keystore (before first unlock). */
internal class MemoryPersistence : Persistence {
    private val mutex = Mutex()
    private val files = HashMap<String, ByteArray>()

    @Volatile var unavailable: Boolean = false

    /** Runs before every write, outside the lock: a test can hold a write on a gate. */
    @Volatile var beforeWrite: (suspend (name: String) -> Unit)? = null

    /** Runs before every removal, outside the lock: a test can hold a removal on a gate. */
    @Volatile var beforeRemove: (suspend (name: String) -> Unit)? = null

    private fun check() {
        if (unavailable) throw StorageUnavailableException(IllegalStateException("locked"))
    }

    override suspend fun read(name: String): ByteArray? = mutex.withLock {
        check()
        files[name]
    }

    override suspend fun write(name: String, data: ByteArray) {
        beforeWrite?.invoke(name)
        mutex.withLock {
            check()
            files[name] = data
        }
    }

    // DataStore reads before it writes, so a locked Keystore fails removals too (review finding CS-6).
    override suspend fun remove(name: String) {
        beforeRemove?.invoke(name)
        mutex.withLock {
            check()
            files.remove(name)
        }
    }

    override suspend fun quarantine(name: String) {
        mutex.withLock { files.remove(name)?.let { files["$name.unreadable"] = it } }
    }

    override suspend fun removeQuarantined() {
        mutex.withLock { files.keys.removeAll { it.contains(".unreadable") } }
    }

    suspend fun names(): Set<String> = mutex.withLock { files.keys.toSet() }

    suspend fun raw(name: String): String? = mutex.withLock { files[name]?.decodeToString() }

    suspend fun putRaw(name: String, text: String) = mutex.withLock { files[name] = text.encodeToByteArray() }
}

/** A software AES key (no Android Keystore on the JVM). */
internal class SoftwareCipher(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = AesGcm.encrypt(key, plain)

    override fun decrypt(sealed: ByteArray): ByteArray? = AesGcm.decrypt(key, sealed)
}

/**
 * A scripted server: routes by "METHOD path" (path relative to the base URL, without the query).
 * Handlers suspend, so tests can gate a response on a [CompletableDeferred].
 */
internal class ScriptedTransport(private val base: String = BASE_URL) : HttpTransport {
    private val mutex = Mutex()
    private val routes = HashMap<String, suspend (HttpRequest) -> HttpResponse>()
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    val sockets: MutableList<FakeSocket> = java.util.Collections.synchronizedList(mutableListOf())
    var socketFactory: (url: String, protocols: List<String>) -> FakeSocket = { _, _ -> FakeSocket() }

    fun on(route: String, handler: suspend (HttpRequest) -> HttpResponse) {
        routes[route] = handler
    }

    fun json(route: String, status: Int = 200, body: String, headers: Map<String, String> = emptyMap()) =
        on(route) { HttpResponse(status, headers.mapKeys { it.key.lowercase() }, body.encodeToByteArray()) }

    fun offline(route: String) = on(route) { throw IOException("offline", java.net.UnknownHostException()) }

    fun count(route: String): Int = requests.count { key(it) == route }

    fun last(route: String): HttpRequest? = requests.lastOrNull { key(it) == route }

    fun key(request: HttpRequest): String = "${request.method} ${request.url.removePrefix("$base/").substringBefore('?')}"

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val handler =
            mutex.withLock { routes[key(request)] }
                ?: return HttpResponse(404, emptyMap(), """{"error":{"code":"not_found","message":"no route"}}""".encodeToByteArray())
        return handler(request)
    }

    /** The headers of every socket handshake, in order. */
    val socketHeaders: MutableList<Map<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

    /** Times the engine told the transport the network changed (it evicts pooled connections). */
    @Volatile var networkChanges: Int = 0

    override fun networkChanged() {
        networkChanges += 1
    }

    override fun openSocket(url: String, protocols: List<String>, headers: Map<String, String>): LiveSocket = socketFactory(url, protocols).also {
        socketHeaders += headers
        sockets += it
    }

    companion object {
        const val BASE_URL: String = "https://api.test/v1"
    }
}

/** A live socket driven by the test. [send] can be made to fail (a dead connection). */
internal class FakeSocket : LiveSocket {
    private val channel = Channel<SocketFrame>(Channel.UNLIMITED)
    val sent: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile var closed: Boolean = false

    @Volatile var sendSucceeds: Boolean = true

    override val frames: ReceiveChannel<SocketFrame> = channel

    fun push(text: String) {
        channel.trySend(SocketFrame.Text(text))
    }

    fun fail(status: Int? = null, body: String? = null) {
        channel.trySend(SocketFrame.Failed(status, IOException("dropped"), body?.encodeToByteArray()))
        channel.close()
    }

    fun serverClose(code: Int) {
        channel.trySend(SocketFrame.Closed(code))
        channel.close()
    }

    override fun send(text: String): Boolean {
        sent += text
        return sendSucceeds && !closed
    }

    override fun close() {
        closed = true
        channel.close()
    }
}

internal class RecordingRetryScheduler : RetryScheduler {
    var scheduled: Int = 0
    var cancelled: Int = 0

    override fun scheduleOutboxRetry() {
        scheduled += 1
    }

    override fun cancel() {
        cancelled += 1
    }
}

internal object TestDevice {
    fun device(installId: String): Device = Device(
        installId = installId.ifEmpty { null },
        sdk = Device.Sdk("pingfront-android", "test"),
        app = Device.App("1.0", "1", "dev.pingfront.test"),
        os = "Android 16 (API 36)",
        model = "Google Pixel 8",
        locale = "en-US",
        language = "en-US",
        timeZone = "Europe/Berlin",
    )
}

/** Dependencies for a [TestScope]: virtual time, the test dispatcher everywhere, scripted network, memory storage. */
internal fun TestScope.testDependencies(
    transport: HttpTransport = ScriptedTransport(),
    persistence: MemoryPersistence = MemoryPersistence(),
    lifecycle: MutableSharedFlow<AppLifecycleEvent> = MutableSharedFlow(),
    retry: RetryScheduler = RecordingRetryScheduler(),
    context: dev.pingfront.internal.context.ContextSource = dev.pingfront.internal.context.ContextSource.None,
    signedOut: dev.pingfront.internal.storage.SignedOutFlag = dev.pingfront.internal.storage.SignedOutFlag.Memory(),
): Dependencies {
    val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
    return Dependencies(
        transport = transport,
        persistence = persistence,
        time = TestTime(this),
        dispatchers = SdkDispatchers(dispatcher, dispatcher, dispatcher),
        device = TestDevice::device,
        lifecycle = lifecycle,
        retry = retry,
        languageTag = { "en-US" },
        jitter = { 0.0 },
        context = context,
        signedOut = signedOut,
    )
}

/**
 * The system's default network for tests: a context source whose network changes follow [online]. [callbacks] counts
 * the collectors, which stand for registered network callbacks.
 */
internal class FakeNetwork(initial: Boolean = true) : dev.pingfront.internal.context.ContextSource by dev.pingfront.internal.context.ContextSource.None {
    val online = kotlinx.coroutines.flow.MutableStateFlow(initial)

    /** The default network's handle: a new value is another network (mobile data to Wi-Fi). */
    val network = kotlinx.coroutines.flow.MutableStateFlow<Long?>(1L)

    /** The network has a captive portal (hotel Wi-Fi before sign-in). */
    val captivePortal = kotlinx.coroutines.flow.MutableStateFlow(false)
    val callbacks: Int get() = online.subscriptionCount.value

    override fun networkChanges(): kotlinx.coroutines.flow.Flow<dev.pingfront.internal.context.NetworkSample> =
        kotlinx.coroutines.flow.combine(online, network, captivePortal) { on, id, portal ->
            dev.pingfront.internal.context.NetworkSample(on, id, portal) { null }
        }
}

/** JSON bodies the scripted server answers with. */
internal object Bodies {
    fun session(installId: String = "8a1f6f2e-1111-4222-8333-944455556666", token: String = "tok-1", expiresIn: Int = 900, level: String = "guest") =
        """{"installId":"$installId","accessToken":"$token","accessExpiresAt":"2026-10-30T18:15:00Z","accessExpiresIn":$expiresIn,
            "installSecret":"secret-1","person":{"id":"p_1","level":"$level"},"testDevice":false,
            "storeAccountToken":"0b0c3f5e-7777-4888-9999-aaaabbbbcccc"}"""

    fun refresh(token: String = "tok-2") =
        """{"accessToken":"$token","accessExpiresAt":"2026-10-30T18:15:00Z","accessExpiresIn":900,"person":{"id":"p_1","level":"guest"},"testDevice":false}"""

    fun message(seq: Int, kind: String = "team", text: String = "hi $seq", clientId: String? = null) =
        """{"id":"m$seq","seq":$seq${clientId?.let { ",\"clientId\":\"$it\"" } ?: ""},"author":{"kind":"$kind","name":"Deniz"},
            "parts":[{"type":"text","text":"$text"}],"createdAt":"2026-10-30T18:0${seq % 10}:00Z"}"""

    fun thread(lastSeq: Int, vararg messages: String, rev: Int = 1, unread: Int = 0, team: String = "[]") =
        """{"thread":{"id":"t1","status":"open","lastSeq":$lastSeq,"readSeq":0,"teamReadSeq":0,"unread":$unread,"rev":$rev},
            "messages":[${messages.joinToString(",")}],"hasOlder":false,"team":$team,"policy":{"canSend":true}}"""

    /** One teammate as the server sends it. */
    fun member(id: String, name: String, avatarUrl: String) = """{"id":"$id","name":"$name","role":"Maker of Clockwise","avatarUrl":"$avatarUrl"}"""

    fun page(hasMore: Boolean, vararg messages: String) = """{"messages":[${messages.joinToString(",")}],"hasMore":$hasMore}"""

    fun error(code: String, message: String = "server text", extra: String = "") =
        """{"error":{"code":"$code","message":"$message","docs":"https://pingfront.sh/docs/errors#$code"$extra}}"""

    fun config(revision: Int = 1, team: String = "[]"): String {
        val look = """{"theme":{},"header":{},"welcome":{},"topics":[],"composer":{},"messages":{}}"""
        val features = """{"photos":true,"reports":true,"translation":false,"ai":false}"""
        return """{"schema":1,"revision":$revision,"draft":false,"appName":"Clockwise","look":$look,""" +
            """"team":$team,"features":$features,"liveUrl":"wss://api.test/v1/live"}"""
    }

    fun sent(seq: Int, clientId: String) = """{"message":${message(seq, "person", "sent", clientId)},"threadId":"t1"}"""
}
