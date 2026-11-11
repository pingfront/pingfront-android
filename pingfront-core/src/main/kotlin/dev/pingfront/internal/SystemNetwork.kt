package dev.pingfront.internal

import dev.pingfront.internal.context.NetworkSample
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What the system says about the network (ConnectivityManager); without a source, the device counts as online.
 * Offline, nothing is sent (queued messages wait as "Waiting for connection" instead of a send that times out); when
 * it returns, the engine drains the outbox at once and reloads a thread that failed to load, with no tap needed.
 */
internal class SystemNetwork {
    /** How a reading changed the network. */
    enum class Change { None, Lost, Returned, Switched }

    @Volatile var online: Boolean = true
        private set

    @Volatile private var id: Long? = null

    /** Records the system's [now] state on network [network] (its handle; null when none). */
    fun update(now: Boolean, network: Long? = id): Change {
        val was = online
        val switched = network != id
        online = now
        id = network
        return when {
            !now -> if (was) Change.Lost else Change.None
            !was -> Change.Returned
            switched -> Change.Switched
            else -> Change.None
        }
    }
}

/**
 * The SDK's own default network callback, collected only while something needs it (S2): [set] starts or cancels the
 * collection, and cancelling it unregisters the callback.
 */
internal class NetworkWatch(
    private val scope: CoroutineScope,
    private val changes: () -> Flow<NetworkSample>,
    private val onSample: suspend (NetworkSample) -> Unit,
) {
    private val job = AtomicReference<Job?>(null)

    /** Starts or stops the callback; true when it stopped (nothing watches the network any more). */
    fun set(needed: Boolean): Boolean = synchronized(job) {
        if (!needed) return job.getAndSet(null)?.also { it.cancel() } != null
        if (job.get()?.isActive != true) job.set(scope.launch { changes().collect { onSample(it) } })
        false
    }
}

/**
 * The engine's view of the network: the SDK's own callback ([NetworkWatch]), watched only while a session exists or
 * the chat is on screen, and what each reading means. The device offline sets the offline flag; back online, the
 * outbox drains and a failed thread loads again; another network (mobile data to Wi-Fi) wakes the outbox and keeps
 * "server unreachable" until a request gets through.
 */
internal class EngineNetwork(
    scope: CoroutineScope,
    changes: () -> Flow<NetworkSample>,
    private val setOffline: (offline: Boolean, unreachable: Boolean) -> Unit,
    private val returned: () -> Unit,
    private val switched: () -> Unit,
    private val recordConnection: suspend (NetworkSample) -> Unit,
    /** The network was lost, came back or changed: the transport drops connections made on the old one. */
    private val changed: () -> Unit,
    guarded: suspend (suspend () -> Unit) -> Unit,
) {
    private val system = SystemNetwork()
    private val watch = NetworkWatch(scope, changes) { sample -> guarded { reading(sample) } }

    @Volatile private var sessionLive = false

    /** The current network has a captive portal (set from the system, not from a failed request). */
    @Volatile private var portal = false

    /** What the system says (true without a reading). */
    val online: Boolean get() = system.online

    /** A session started or ended (logout, erase, revocation). */
    fun sessionChanged(live: Boolean, chatVisible: Boolean) {
        sessionLive = live
        chatChanged(chatVisible)
    }

    /** The chat appeared or went away. */
    fun chatChanged(chatVisible: Boolean) {
        // Nothing watches the network any more: the device counts as online until something needs to know.
        if (watch.set(sessionLive || chatVisible)) {
            system.update(true)
            portal = false
            setOffline(false, false)
        }
    }

    private suspend fun reading(sample: NetworkSample) {
        val change = system.update(sample.online, sample.network)
        if (change != SystemNetwork.Change.None) changed()
        when (change) {
            SystemNetwork.Change.Lost -> setOffline(true, false)
            SystemNetwork.Change.Returned -> {
                setOffline(false, false)
                returned()
            }
            SystemNetwork.Change.Switched -> switched()
            SystemNetwork.Change.None -> Unit
        }
        // Behind a captive portal (hotel or airport Wi-Fi before sign-in) the system says so before any request fails:
        // the chat can't be reached until the portal is passed, and then the outbox tries again.
        val behindPortal = sample.online && sample.captivePortal
        if (behindPortal) {
            setOffline(true, true)
        } else if (portal) {
            setOffline(false, false)
            if (sample.online) switched()
        }
        portal = behindPortal
        recordConnection(sample)
    }
}
