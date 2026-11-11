package dev.pingfront.internal

import dev.pingfront.AppKey
import dev.pingfront.ConfigurationProblem
import dev.pingfront.ErrorCode
import dev.pingfront.PingFrontEvent
import dev.pingfront.PingFrontException
import dev.pingfront.PingFrontInstance
import dev.pingfront.RateScope
import dev.pingfront.ReceivedMessage
import dev.pingfront.SentMessage
import dev.pingfront.conversation.ConversationDriver
import dev.pingfront.conversation.ConversationPhase
import dev.pingfront.conversation.ConversationState
import dev.pingfront.conversation.LiveState
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.conversation.StoreReceipt
import dev.pingfront.internal.context.ContextReporter
import dev.pingfront.internal.messaging.ConfigStore
import dev.pingfront.internal.messaging.ImagePreparation
import dev.pingfront.internal.messaging.LiveConnection
import dev.pingfront.internal.messaging.MomentGate
import dev.pingfront.internal.messaging.Outbox
import dev.pingfront.internal.messaging.OutboxItem
import dev.pingfront.internal.messaging.SeqTracker
import dev.pingfront.internal.messaging.Uploader
import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.CooldownBook
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.Timings
import dev.pingfront.internal.runtime.WakeSignal
import dev.pingfront.internal.session.PatchCoalescer
import dev.pingfront.internal.session.PatchHandlers
import dev.pingfront.internal.session.PendingLogouts
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.session.ScreenReporter
import dev.pingfront.internal.session.SessionEvent
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.storage.CachedThread
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.Records
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.CardReply
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.Limits
import dev.pingfront.internal.wire.PushRegistration
import dev.pingfront.internal.wire.SendMessageRequest
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.StoreActionResultRequest
import dev.pingfront.internal.wire.UpdateMeResponse
import dev.pingfront.model.Author
import dev.pingfront.model.Card
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.SendPolicy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val log = Log.of(Log.Category.Session)

/** Settings the engine reads from the configuration. */
internal class EngineSettings(
    val baseUrl: String,
    val verbose: Boolean,
    val presentsChatForMoments: Boolean,
    /** `PF-Wrapper` (`react-native/1.0.0`), set by a bridge; `null` for native apps. */
    val wrapper: String? = null,
)

/**
 * Orchestrates the components for one configured app key: session, outbox, live channel, thread
 * order, and the extracted controllers ([UnreadMonitor], [ReadReceipts], [TypingController],
 * [CardActions]). Every long-lived loop runs in [scope] (`SupervisorJob + default dispatcher`),
 * cancelled by [stop]. Mutable state lives in the components (each owns a `Mutex`), in atomics and
 * `StateFlow`s, or in the two single-consumer channels below.
 */
internal class Engine(
    private val instance: PingFrontInstance,
    val key: AppKey,
    settings: EngineSettings,
    private val deps: Dependencies,
) : ConversationDriver {
    sealed interface Command {
        data class Stage(val change: ProfileChange) : Command

        data object Logout : Command

        class Erase(val done: CompletableDeferred<Result<Unit>>) : Command {
            /** The engine stopped before this erase ran or finished. */
            fun abandon() {
                done.complete(Result.failure(PingFrontException.SessionChanged()))
            }
        }
    }

    val time = deps.time
    private val persistence = deps.persistence
    private val records = RecordStore(persistence)
    private val dispatchers = deps.dispatchers
    val scope =
        CoroutineScope(
            SupervisorJob() + dispatchers.default + CoroutineExceptionHandler { _, e ->
                log.e("Unexpected error: ${e.javaClass.simpleName}")
            },
        )
    private val api =
        ApiClient(settings.baseUrl, key.rawValue, deps.transport, CooldownBook(time), dispatchers.io, settings.verbose, deps.jitter, settings.wrapper)
    val coalescer = PatchCoalescer(scope, records)

    /** Device context that rides on session requests and PATCHes the SDK sends anyway. */
    val contextReporter = ContextReporter(deps.context, records, time, dispatchers.io)
    val session = SessionStore(api, records, time, coalescer, scope, deps.device, contextReporter, ScreenReporter(records) { instance.screens })

    /**
     * The one default network callback (online state and connection context): registered only while a session exists
     * or the chat is on screen, never at launch for a new install, and gone on logout and with the engine (S2).
     */
    private val network: EngineNetwork = EngineNetwork(
        scope,
        deps.context::networkChanges,
        setOffline = { offline, unreachable -> setNetworkOffline(offline, unreachable) },
        returned = ::networkReturned,
        switched = { sender.wake(retryCards = true) },
        recordConnection = { sample ->
            // Connection context is only read and recorded while the app collects that group (review AN-12).
            if (contextReporter.collectsConnection()) sample.connection()?.let { contextReporter.connectionChanged(it) }
        },
        guarded = { block -> guarded("network") { block() } },
        changed = { deps.transport.networkChanged() },
    )
    val client = AuthorizedClient(api, session)
    val outbox = Outbox(persistence, records)
    val uploader = Uploader(client, time)
    val live = LiveConnection(api, session, scope, key.rawValue, deps.jitter, deps.startsInForeground)
    val configStore = ConfigStore(client, records, time)
    val moments = MomentGate(time)
    val pendingLogouts = PendingLogouts(records)
    val unread: UnreadMonitor = UnreadMonitor(instance, client, session, time, deps.startsInForeground, ::loadThread) { held.unreadAnswered() }
    private val reads = ReadReceipts(instance, client, session, scope)
    private val typing = TypingController(instance, live, scope, time)

    /** Messages a closed policy held, and when they go (X1, as on iOS). */
    private val held: HeldMessages by lazy { HeldMessages(outbox, { conversation }, sender::wake) }
    internal val sender = Sender(
        object : SenderHost {
            override suspend fun noteFailure(error: PingFrontException) = this@Engine.noteFailure(error)

            override suspend fun refreshCooldowns() = this@Engine.refreshCooldowns()

            override suspend fun threadReset() = this@Engine.threadReset()

            override suspend fun mergeSent(generation: Int, message: Message) = trackerMutex.withLock {
                if (session.generation() != generation) throw PingFrontException.SessionChanged()
                val before = conversation.state.value.messages
                val known = before.any { it.id == message.id }
                val repliesToPing = before.lastOrNull { !it.isFromPerson && it.author.kind != Author.Kind.System }?.ping != null
                conversation.merge(listOf(message))
                if (!known) {
                    val photos = message.parts.count { it is MessagePart.Image }
                    instance.emit(PingFrontEvent.MessageSent(SentMessage(message.id, message.plainText.length, photos, repliesToPing)))
                }
            }

            override suspend fun sent() {
                unread.noteActivity()
                cacheThread(session.generation())
            }

            override suspend fun guarded(what: String, block: suspend () -> Unit) = this@Engine.guarded(what, block)

            override fun systemOnline(): Boolean = network.online

            // The same as any refused request: the policy closes, and app_paused reports the debug problem too.
            override suspend fun sendRefused(error: PingFrontException) {
                if (policyRefusal(error) != null) noteFailure(error)
            }
        },
        scope,
        { instance.conversation },
        outbox,
        session,
        client,
        uploader,
        time,
        deps,
        { unread.noteActivity() },
    )

    /**
     * Ordered commands. An erase that is never handled (the engine stopped first) still completes, so
     * `eraseMyData()` never hangs (review AN-11).
     */
    private val commands = Channel<Command>(Channel.UNLIMITED) { command -> (command as? Command.Erase)?.abandon() }

    // Thread order: the sequence tracker is read and written only under this lock.
    private val trackerMutex = Mutex()
    private var tracker = SeqTracker()

    val remoteConfig = MutableStateFlow<AppConfig?>(null)
    val offers = MutableStateFlow<List<OfferSlot>>(emptyList())

    /** False while the app is in the background (no socket, no unread polling). */
    private val foreground = AtomicBoolean(deps.startsInForeground)

    /** Team actions for the host app (live event and catch-up), with their acks. */
    val teamActions = TeamActions(client, records, scope, deps.jitter, { session.generation() }) { session.isActive() }

    /** Store action cards (Google Play deep links and restore). */
    val storeActions = StoreActions(
        refresh = { instance.refreshPurchases() },
        fixer = { instance.registry.paymentFixer },
        current = { instance.entitlement.value },
        setEntitlement = instance::setEntitlement,
        packageName = instance::packageName,
        open = { url -> withContext(instance.mainDispatcher()) { openPlay(url) } },
        receipt = { cardId, receipt -> conversation.update { it.copy(storeReceipts = it.storeReceipts + (cardId to receipt)) } },
        report = { card, messageId, receipt -> StoreActionReports.report(scope, session, client, conversation, card, messageId, receipt) },
    )

    /** The Play Store app when installed, else the browser. */
    private fun openPlay(url: String) {
        val context = instance.activities.current() ?: instance.applicationContext() ?: return
        if (!PlayStoreApp.open(context, url)) instance.registry.chatPresenter?.openUrl(url)
    }

    /** The last push token the app gave; re-staged for the next person after logout (review CQ-L9). */
    private val pushToken = AtomicReference<String?>(null)

    /** Pushes for the previous person are dropped after logout or erase until the next session (review CQ-M7). */
    val pushGate = PushGate(deps.signedOut)
    private val draftJob = AtomicReference<Job?>(null)
    private val hostLookVersionSent = AtomicReference<String?>(null)
    private val resetting = AtomicBoolean(false)

    /** Preview mode (fixture data): no thread traffic, no typing, no polling. */
    @Volatile var preview: Boolean = false
        set(value) {
            field = value
            unread.paused = value
            typing.enabled = !value
        }

    val presentsChatForMoments: Boolean = settings.presentsChatForMoments

    private val conversation get() = instance.conversation

    // Start and stop

    fun start() {
        conversation.driver = this
        conversation.time = time
        teamActions.start()
        // Collectors subscribe before start() returns (UNDISPATCHED), so no session or lifecycle event is missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { guarded("session event") { handle(it) } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { deps.lifecycle.collect { guarded("lifecycle") { lifecycle(it) } } }
        // Offline comes from the system's network state and from requests, never from the socket alone (audit A-03).
        // A request that got through never clears what the system reports: while it says offline, nothing is sent.
        api.reachability = { reachable -> setNetworkOffline(!reachable || !network.online, unreachable = !reachable && network.online) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { coalescer.rejections.collect { guarded("patch failure") { noteFailure(it) } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { live.signals.collect { guarded("live event") { handle(it) } } }
        scope.launch {
            coalescer.setHandlers(
                PatchHandlers(
                    send = { patch -> client.send(Endpoints.updateMe(patch)) },
                    applied = { patched(it) },
                    context = { contextReporter.forPatch() },
                    contextDelivered = { contextReporter.delivered(dev.pingfront.internal.context.ContextReport(it, sessionStart = false)) },
                ),
            )
            // Device facts (time zone, locale, versions) go out with the next PATCH when they changed.
            guarded("device facts") { coalescer.stage(ProfileChange.SetDevice(deps.device("").copy(installId = null))) }
            // One failing command (a full disk, a locked Keystore) never ends the loop (review CS-6).
            for (command in commands) handle(command)
        }
        sender.start()
        scope.launch { combine(outbox.changes, sender.placeholders) { queued, local -> queued to local }.collect { (q, l) -> syncPending(q, l) } }
        scope.launch { unread.run() }
        scope.launch { guarded("restore") { restoreLocalState() } }
    }

    /**
     * Runs [block], logging any failure except cancellation. Long-lived loops use it so one unexpected
     * failure never ends them. Real cancellation (stop, logout) always propagates.
     */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log.w("$what was interrupted by a session change.")
        } catch (e: PingFrontException) {
            log.w("$what failed: ${e.logDescription}")
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // A bug or an unexpected platform failure: logged and survived, never a dead loop.
            log.e("$what failed unexpectedly (${e.javaClass.simpleName}).")
        }
    }

    /** Back from offline: the outbox drains and a thread that failed to load loads again, with no tap needed. */
    private fun networkReturned() {
        sender.wake(retryCards = true)
        val failed = conversation.state.value.phase is ConversationPhase.Failed
        if (failed && instance.isChatVisible.value && !preview) scope.launch { openThread() }
    }

    private fun setNetworkOffline(offline: Boolean, unreachable: Boolean = false) {
        val state = conversation.state.value
        if (state.networkOffline != offline || state.serverUnreachable != unreachable) {
            conversation.update { it.copy(networkOffline = offline, serverUnreachable = unreachable) }
        }
    }

    /** The inferred or overridden host app changed: report its look (once per app version). */
    fun hostChanged() {
        scope.launch { guarded("host look") { stageHostLookIfNeeded() } }
    }

    /** Called after modules registered: start entitlement, offer and push sources, stage the host look. */
    fun modulesInstalled() {
        for (source in instance.registry.entitlementSources) {
            scope.launch { source.entitlementUpdates().collect { instance.setEntitlement(it) } }
        }
        val sources = instance.registry.offerSources
        if (sources.isNotEmpty()) {
            scope.launch {
                val slots = sources.flatMap { it.offers() }
                if (slots.isNotEmpty() && offers.value.isEmpty()) instance.setOffersFromModules(slots)
            }
        }
        instance.registry.pushTokenSource?.let { source -> scope.launch { source.currentToken()?.let { setPush(it) } } }
        hostChanged()
    }

    fun stop() {
        // Cancel, not close: queued commands are dropped, and each queued erase completes (review AN-11).
        commands.cancel()
        sender.close()
        if (conversation.driver === this) conversation.driver = null
        // Cancelling the scope cancels every loop; the live socket closes in its `finally`.
        scope.cancel()
    }

    /** The host changed `PingFront.language`: the server learns it with the next PATCH. */
    fun languageChanged() = submit(Command.Stage(ProfileChange.SetDevice(deps.device("").copy(installId = null))))

    fun submit(command: Command) {
        // After stop() nothing runs it: an erase completes at once instead of waiting forever.
        if (commands.trySend(command).isFailure) (command as? Command.Erase)?.abandon()
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Stage -> guarded("profile change") { coalescer.stage(command.change) }
            Command.Logout -> guarded("logout") { performLogout() }
            // Always completed, so eraseMyData() returns (or throws) instead of hanging.
            is Command.Erase -> command.done.complete(
                try {
                    erase()
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    command.done.complete(Result.failure(PingFrontException.SessionChanged()))
                    throw e
                } catch (e: PingFrontException) {
                    Result.failure(e)
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    log.e("Erase failed unexpectedly (${e.javaClass.simpleName}).")
                    Result.failure(PingFrontException.StorageUnavailable(e))
                },
            )
        }
    }

    private suspend fun restoreLocalState() {
        try {
            outbox.load()
            records.read(StoredRecords.draft)?.let { if (conversation.draft.value.isEmpty()) conversation.setDraftSilently(it) }
            records.read(StoredRecords.thread)?.let { cached ->
                trackerMutex.withLock {
                    if (conversation.state.value.messages.isEmpty()) {
                        conversation.update {
                            it.copy(
                                messages = cached.messages,
                                team = cached.team,
                                hasOlder = cached.hasOlder,
                                phase = ConversationPhase.Ready,
                            )
                        }
                        tracker = SeqTracker(cached.messages.maxOfOrNull { it.seq } ?: 0)
                    }
                }
            }
            hostLookVersionSent.compareAndSet(null, records.read(StoredRecords.hostLookVersion))
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("Local state is not readable yet (${e.logDescription}).")
        }
        configStore.cached()?.let { apply(it) }
    }

    private suspend fun stageHostLookIfNeeded() {
        val host = instance.host ?: return
        if (hostLookVersionSent.get() == null) {
            try {
                hostLookVersionSent.set(records.read(StoredRecords.hostLookVersion))
            } catch (_: PingFrontException.StorageUnavailable) {
                return
            }
        }
        if (hostLookVersionSent.get() == host.appVersion) return
        coalescer.stage(ProfileChange.SetHostLook(host.hostLook))
        session.setPendingHostLook(host.hostLook)
    }

    private suspend fun hostLookDelivered() {
        val host = instance.host ?: return
        if (hostLookVersionSent.getAndSet(host.appVersion) == host.appVersion) return
        records.write(StoredRecords.hostLookVersion, host.appVersion)
    }

    // Session events

    private suspend fun handle(event: SessionEvent) {
        // A session that started before a logout or erase (an erase lets a create in flight finish, review AN-4)
        // announces nothing for the next person.
        val generation = (event as? SessionEvent.Started)?.generation ?: (event as? SessionEvent.Refreshed)?.generation
        if (generation != null && generation != session.generation()) return
        when (event) {
            is SessionEvent.Started -> {
                conversation.clearPaused()
                // The catch-up belongs to this session: after a logout or erase it starts no session of its own.
                scope.launch { teamActions.catchUp(event.generation) }
                pushGate.signedIn()
                instance.setPerson(event.person)
                setTestDevice(event.testDevice)
                hostLookDelivered()
                instance.emit(PingFrontEvent.SessionStarted(event.person))
                event.identityProblem?.let { instance.emit(PingFrontEvent.IdentityProblemReported(it)) }
                scope.launch { guarded("pending logouts") { drainPendingLogouts() } }
                network.sessionChanged(live = true, chatVisible = instance.isChatVisible.value)
                sender.wake()
            }
            is SessionEvent.Refreshed -> {
                pushGate.signedIn()
                conversation.clearPaused()
                instance.setPerson(event.person)
                setTestDevice(event.testDevice)
                network.sessionChanged(live = true, chatVisible = instance.isChatVisible.value)
                sender.wake()
                // Answers queued in an earlier launch go out now that a session exists.
                teamActions.wakeUp()
            }
            SessionEvent.Revoked -> {
                // The server revoked the install (for example the person erased their data on another
                // device). Everything local goes, including queued messages: nothing of theirs is sent
                // under the new guest session. The session store already forgot the install.
                // The session store already reset the context reporter before the new guest's create.
                wipeEngineState(resetContext = false)
                instance.emit(PingFrontEvent.SessionRevoked)
            }
            SessionEvent.IdentityRejected ->
                instance.emit(PingFrontEvent.IdentityProblemReported(dev.pingfront.model.IdentityProblem.UserMismatch))
        }
    }

    /** Logout, erase or revocation: the callback goes, and the next session sends its context afresh. */
    private suspend fun stopContext(reset: Boolean) {
        network.sessionChanged(live = false, chatVisible = instance.isChatVisible.value)
        if (reset) contextReporter.reset()
    }

    /** `isTestDevice` follows every create and refresh; turning on forces a config fetch so the draft look shows now (review CQ-M4). */
    private suspend fun setTestDevice(value: Boolean) {
        val was = instance.isTestDevice.value
        instance.setTestDevice(value)
        if (value && !was && instance.isChatVisible.value) configStore.refresh(true, force = true)?.let { apply(it) }
    }

    /** A `PATCH /me` response, applied only if no logout or erase happened while it was in flight (see [PatchHandlers]). */
    private suspend fun patched(response: UpdateMeResponse) {
        instance.setPerson(response.person)
        hostLookDelivered()
        response.identityProblem?.let { instance.emit(PingFrontEvent.IdentityProblemReported(it)) }
        // The server reports a verified user switch in rejected[], not as an error (review CQ-M2).
        val mismatch = response.rejected.any { it.code == ErrorCode.IdentityUserMismatch.raw }
        if (mismatch) {
            instance.emit(PingFrontEvent.IdentityProblemReported(dev.pingfront.model.IdentityProblem.UserMismatch))
        } else if (response.identityProblem == null && response.person.level == dev.pingfront.model.PersonSummary.Level.Verified) {
            held.signedIn()
        }
    }

    /** Maps request failures that reveal configuration problems (unknown key, paused app) or a reset thread. */
    suspend fun noteFailure(error: PingFrontException) {
        // Any refusal that says the person can't send (a session refresh, the thread, a send) closes the policy: the
        // notice replaces the composer and nothing promises a reply until the server says otherwise (audit 4, B1).
        policyRefusal(error)?.let { closed ->
            conversation.update { it.copy(policy = closed) }
            held.policyClosed(closed.reason)
        }
        setupProblem(error, key.toString())?.let(instance::report)
        if (error.code == ErrorCode.ThreadReset) threadReset()
        refreshCooldowns()
    }

    /**
     * A thread load says the app isn't paused: the debug "app paused" banner goes, and the live channel, kept shut while
     * paused, opens (the foreground may have come before this load said so).
     */
    private suspend fun pauseLifted(wasPaused: Boolean) {
        instance.clearProblem(ConfigurationProblem.Kind.AppPaused)
        if (wasPaused && instance.isChatVisible.value && foreground.get()) live.start(api.liveUrl(remoteConfig.value?.liveUrl))
    }

    /** `409 thread_reset`: the thread changed (a merge). Drop the cache, keep the draft and pending rows, reload. */
    suspend fun threadReset() {
        if (!resetting.compareAndSet(false, true)) return
        try {
            api.threadRev.set(null)
            trackerMutex.withLock {
                conversation.dropMessages()
                tracker = SeqTracker()
            }
            records.remove(StoredRecords.thread)
            loadThread()
        } finally {
            resetting.set(false)
        }
    }

    // Chat visibility

    fun chatDidAppear() {
        unread.noteActivity()
        network.chatChanged(instance.isChatVisible.value)
        if (preview) return
        scope.launch { openThread() }
    }

    fun chatDidDisappear() {
        unread.noteActivity()
        network.chatChanged(instance.isChatVisible.value)
        typing.setTyping(false)
        scope.launch { live.stop() }
    }

    suspend fun openThread() {
        conversation.update {
            if (it.messages.isEmpty() &&
                it.phase != ConversationPhase.Ready
            ) {
                it.copy(phase = ConversationPhase.Loading)
            } else {
                it
            }
        }
        try {
            session.token(SessionReason.ChatOpened)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            noteFailure(e)
            conversation.update {
                val phase = if (it.messages.isEmpty() &&
                    it.phase != ConversationPhase.Ready
                ) {
                    ConversationPhase.Failed(e.isOffline)
                } else {
                    it.phase
                }
                it.copy(phase = phase, live = LiveState.Waiting(Duration.ZERO, e.isOffline))
            }
            // A paused app, a blocked person or required verification won't change in five seconds: no retry storm.
            // The chat opens again on the next chat open or app foreground.
            if (policyRefusal(e) == null) {
                scope.launch {
                    delay(Timings.openRetry)
                    if (instance.isChatVisible.value && !session.isActive()) openThread()
                }
            }
            return
        }
        configStore.refresh(session.isTestDevice())?.let { apply(it) }
        loadThread()
        // LiveConnection refuses to start while the app is in the background (review CS-5). Nor does it start while
        // the app is paused: the upgrade would be a certain 403. A blocked or unverified person still gets live replies
        // (the server refuses only their sends).
        resumeLive()
        sender.wake()
    }

    suspend fun loadThread() {
        try {
            val generation = session.generation()
            val response = client.send(Endpoints.thread(), generation)
            val wasPaused = isPaused(conversation.state.value.policy)
            val applied = trackerMutex.withLock {
                // A logout, erase or revocation while the request was in flight wins (review CS-2).
                if (session.generation() != generation) return@withLock false
                response.thread?.rev?.let { api.threadRev.set(it.toString()) }
                conversation.update { current ->
                    val acknowledged = response.messages.mapNotNullTo(HashSet()) { it.clientId }
                    current.copy(
                        messages = response.messages.sortedBy { it.seq },
                        hasOlder = response.hasOlder,
                        team = response.team.ifEmpty { current.team },
                        replyExpectation = response.replyExpectation,
                        policy = response.policy,
                        status = response.thread?.status ?: current.status,
                        teamReadSeq = maxOf(current.teamReadSeq, response.thread?.teamReadSeq ?: 0),
                        pending = current.pending.filterNot { it.clientId in acknowledged },
                        phase = ConversationPhase.Ready,
                    )
                }
                // A full load sets the position; only live events and successful repairs move it after this.
                tracker = SeqTracker(maxOf(response.thread?.lastSeq ?: 0, response.messages.maxOfOrNull { it.seq } ?: 0))
                instance.setUnread(response.thread?.unread ?: 0)
                cacheThreadLocked(generation)
                true
            }
            if (applied) threadLoaded(response.policy, wasPaused)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: PingFrontException.SessionChanged) {
            // Expected: the person logged out while loading. The result belongs to them, so it is dropped.
            log.d("Thread load discarded: the session changed.")
        } catch (e: PingFrontException) {
            if (e.code != ErrorCode.ThreadReset) noteFailure(e)
            conversation.update { if (it.messages.isEmpty()) it.copy(phase = ConversationPhase.Failed(e.isOffline)) else it }
        }
    }

    /** A thread load was applied: read receipts, the end of a pause, and the messages a closed policy held. */
    private suspend fun threadLoaded(policy: SendPolicy, wasPaused: Boolean) {
        // No read receipt while the app is paused: POST /thread/read is refused then (and only then).
        val pausedNow = isPaused(policy)
        if (instance.isChatVisible.value && !pausedNow) reads.schedule()
        // No longer paused: the debug banner goes, and the live channel, kept shut while paused, opens (the
        // foreground may have come before this load said so).
        if (!pausedNow) pauseLifted(wasPaused)
        held.threadAnswered(policy)
    }

    /** Writes the thread cache if [generation] is still current. Call with [trackerMutex] held, so a wipe cannot interleave. */
    private suspend fun cacheThreadLocked(generation: Int) {
        if (session.generation() != generation) return
        val current = conversation.state.value
        val cached = CachedThread(
            current.messages.takeLast(Limits.PAGE_SIZE),
            current.team,
            current.hasOlder || current.messages.size > Limits.PAGE_SIZE,
        )
        try {
            records.write(StoredRecords.thread, cached)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.d("The thread cache was not written (${e.logDescription}).")
        }
    }

    private suspend fun cacheThread(generation: Int) = trackerMutex.withLock { cacheThreadLocked(generation) }

    // Live events (one collector, so events apply in order)

    private suspend fun handle(signal: LiveConnection.Signal) {
        when (signal) {
            is LiveConnection.Signal.State -> {
                conversation.update { it.copy(live = signal.state) }
                if (signal.state == LiveState.Live) sender.wake(retryCards = true)
            }
            is LiveConnection.Signal.Event -> apply(signal.event)
            is LiveConnection.Signal.Refused -> {
                conversation.update { it.copy(live = LiveState.Stopped) }
                val error = signal.error
                if (error != null) noteFailure(error) else scope.launch { guarded("refused live") { loadThread() } }
            }
        }
    }

    suspend fun apply(event: ServerEvent) {
        when (event) {
            is ServerEvent.Hello -> {
                // Every (re)connect: team actions sent while the socket was down arrive now.
                scope.launch { teamActions.catchUp(session.generation()) }
                event.rev?.let { api.threadRev.set(it.toString()) }
                conversation.update { state ->
                    state.copy(teamReadSeq = maxOf(state.teamReadSeq, event.teamReadSeq ?: 0), status = event.status ?: state.status)
                }
                val repaired = trackerMutex.withLock {
                    if (event.lastSeq > tracker.lastSeq) repairLocked(tracker.lastSeq) else emptyList()
                }
                repaired.filter { !it.isFromPerson }.forEach { received(it) }
            }
            is ServerEvent.NewMessage -> applyMessage(event.seq, event.message)
            is ServerEvent.MessageUpdated -> applyMessage(event.seq, event.message)
            is ServerEvent.Typing -> typing.received(event.isAi, event.name, event.on)
            is ServerEvent.Read -> conversation.update { it.copy(teamReadSeq = maxOf(it.teamReadSeq, event.upToSeq)) }
            is ServerEvent.Thread -> conversation.update { it.copy(status = event.status) }
            ServerEvent.Reset -> {
                // Keep the draft and the pending rows; only the delivered messages are replaced.
                trackerMutex.withLock {
                    conversation.dropMessages()
                    tracker = SeqTracker()
                }
                loadThread()
            }
            is ServerEvent.ThreadReset -> {
                // Drop the cached thread, its messages, the stored revision and the unread count; keep the draft and
                // the outbox (an unsent message starts the new thread), and load the empty chat.
                instance.setUnread(0)
                threadReset()
            }
            is ServerEvent.Config -> {
                val testDevice = session.isTestDevice()
                if (event.draft && !testDevice) return
                configStore.refresh(testDevice, force = true)?.let {
                    apply(it)
                    instance.emit(PingFrontEvent.LookChanged(it.revision, it.draft))
                }
            }
            // No seq and no gap repair: it never touches the thread.
            is ServerEvent.TeamAction -> teamActions.received(event.action)
            ServerEvent.Reauth, is ServerEvent.Unknown -> Unit
        }
    }

    private suspend fun applyMessage(seq: Int, message: Message) {
        val generation = session.generation()
        val newlyReceived = trackerMutex.withLock {
            val known = conversation.state.value.messages.mapTo(HashSet()) { it.id }
            when (val decision = tracker.decide(seq)) {
                SeqTracker.Decision.Apply -> {
                    conversation.merge(listOf(message))
                    tracker = tracker.advance(seq)
                    if (message.id !in known) listOf(message) else emptyList()
                }
                SeqTracker.Decision.Duplicate -> {
                    conversation.merge(listOf(message))
                    emptyList()
                }
                is SeqTracker.Decision.Gap -> {
                    // The `after=` page includes the triggering message; on failure keep lastSeq and show the message.
                    val repaired = repairLocked(decision.after)
                    if (conversation.state.value.messages.none { it.id == message.id }) conversation.merge(listOf(message))
                    val fresh = repaired.filter { it.id !in known }.toMutableList()
                    if (message.id !in known && fresh.none { it.id == message.id }) fresh += message
                    fresh
                }
            }
        }
        if (!message.isFromPerson) typing.clear()
        newlyReceived.filter { !it.isFromPerson }.forEach { received(it) }
        cacheThread(generation)
    }

    private fun received(message: Message) {
        unread.received(onVisible = reads::schedule)
        instance.emit(
            PingFrontEvent.MessageReceived(
                ReceivedMessage(
                    message.id,
                    message.author.name,
                    Limits.truncate(message.plainText, PREVIEW_CHARS),
                    message.ping != null,
                    message.author.kind == Author.Kind.Ai,
                ),
            ),
        )
    }

    /**
     * Fetches everything after [after] (gap repair). Call with [trackerMutex] held. Advances the tracker
     * only when every page arrived; returns the messages that were not known before.
     */
    private suspend fun repairLocked(after: Int): List<Message> {
        val known = conversation.state.value.messages.mapTo(HashSet()) { it.id }
        val collected = mutableListOf<Message>()
        var cursor = after
        val generation = session.generation()
        try {
            while (true) {
                val page = client.send(Endpoints.messagesAfter(cursor, Limits.PAGE_SIZE_MAX), generation)
                collected += page.messages
                val last = page.messages.maxOfOrNull { it.seq } ?: break
                if (!page.hasMore || last <= cursor) break
                cursor = last
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: PingFrontException.SessionChanged) {
            // Expected: the session changed mid-repair; nothing of the old thread is applied.
            return emptyList()
        } catch (e: PingFrontException) {
            log.i("Gap repair failed: ${e.logDescription}")
            conversation.merge(collected)
            if (e.code == ErrorCode.ThreadReset) scope.launch { threadReset() }
            return emptyList()
        }
        if (session.generation() != generation) return emptyList()
        conversation.merge(collected)
        collected.maxOfOrNull { it.seq }?.let { tracker = tracker.advance(it) }
        return collected.filter { it.id !in known }
    }

    suspend fun lastSeq(): Int = trackerMutex.withLock { tracker.lastSeq }

    private fun apply(config: AppConfig) {
        remoteConfig.value = config
        // The config's team is current: a teammate who changed their name or photo shows the change at once,
        // even in a thread whose team came from an earlier load or the cache.
        conversation.update { state ->
            if (state.team.isEmpty()) {
                state.copy(team = config.team)
            } else {
                state.copy(team = state.team.map { member -> config.team.firstOrNull { it.id == member.id } ?: member })
            }
        }
        instance.refreshLook()
    }

    // Outbox (the Sender owns the queue and the delivery loop)

    private fun syncPending(queued: List<PendingMessage>, local: List<PendingMessage>) {
        conversation.update { current ->
            val acknowledged = current.messages.mapNotNullTo(HashSet()) { it.clientId }
            val queuedIds = queued.mapTo(HashSet()) { it.clientId }
            current.copy(pending = (queued + local.filter { it.clientId !in queuedIds }).filter { it.clientId !in acknowledged })
        }
    }

    /** Messages still being prepared, before they reach the outbox (tests watch them). */
    val placeholders: kotlinx.coroutines.flow.StateFlow<List<PendingMessage>> get() = sender.placeholders

    override fun send(text: String?, photos: List<ByteArray>, replyTo: CardReply?) = sender.send(text, photos, replyTo)

    override fun retry(clientId: String) = sender.retry(clientId)

    override fun discard(clientId: String) = sender.discard(clientId)

    /** One drain for the WorkManager retry. Returns `true` when nothing transient is left. */
    suspend fun drainForWorker(): Boolean {
        // Logouts that could not reach the server go first (review CQ-M7); they need no session.
        drainPendingLogouts()
        val logoutsLeft = pendingLogouts.all().isNotEmpty()
        if (outbox.all().isEmpty()) return !logoutsLeft
        try {
            session.token(SessionReason.ChatOpened)
        } catch (e: PingFrontException) {
            return !e.isTransient && !logoutsLeft
        }
        return sender.drainOnce() !is Outbox.DrainResult.RetryLater && !logoutsLeft
    }

    private suspend fun drainPendingLogouts() {
        pendingLogouts.drain(api)
        if (pendingLogouts.all().isNotEmpty()) deps.retry.scheduleOutboxRetry()
    }

    suspend fun deliver(item: OutboxItem): Message = sender.deliver(item)

    suspend fun refreshCooldowns() {
        val deadlines = api.cooldowns.deadlines()
        conversation.update { it.copy(cooldownDeadlines = deadlines) }
    }

    private suspend fun lifecycle(event: AppLifecycleEvent) {
        when (event) {
            AppLifecycleEvent.Foreground -> {
                foreground.set(true)
                storeActions.onForeground()
                unread.setForeground(true)
                live.setForeground(true)
                unread.noteActivity()
                // While the app is paused the upgrade would be a certain 403: the chat, visible again, reopens its
                // thread (chatDidAppear) and that load decides whether the channel opens.
                if (instance.isChatVisible.value && !preview && !isPaused(conversation.state.value.policy)) {
                    live.reconnectNow(api.liveUrl(remoteConfig.value?.liveUrl))
                }
                // The badge catches up once with push (review CQ-H3), and a paused app is asked once (UnreadMonitor).
                if (!instance.isChatVisible.value && !preview) unread.onForeground()
            }
            AppLifecycleEvent.Background -> {
                foreground.set(false)
                unread.setForeground(false)
                live.setForeground(false)
            }
        }
    }

    // Conversation driver

    override fun loadOlder() {
        val first = conversation.state.value.messages.firstOrNull() ?: return
        conversation.update { it.copy(isLoadingOlder = true) }
        scope.launch {
            try {
                // Older pages never move the sequence position.
                val generation = session.generation()
                val page = client.send(Endpoints.messagesBefore(first.seq, Limits.PAGE_SIZE), generation)
                trackerMutex.withLock {
                    if (session.generation() != generation) return@withLock
                    conversation.merge(page.messages)
                    conversation.update { it.copy(hasOlder = page.hasMore) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("SwallowedException") e: PingFrontException.SessionChanged) {
                // Expected: the person logged out while loading. The page belongs to them, so it is dropped.
                log.d("Older page discarded: the session changed.")
            } catch (e: PingFrontException) {
                log.i("Load older failed: ${e.logDescription}")
                noteFailure(e)
            } finally {
                conversation.update { it.copy(isLoadingOlder = false) }
            }
        }
    }

    override fun act(card: Card, messageId: String, choiceId: String?, rating: Int?) {
        unread.noteActivity()
        instance.tapScope().launch { guarded("card action") { CardActions.act(this@Engine, instance, card, messageId, choiceId, rating) } }
    }

    override fun setTyping(on: Boolean) = typing.setTyping(on)

    /** Runs in the engine's scope, so a caller that goes away does not cancel the reload; logout and stop still do. */
    override suspend fun reload() {
        if (preview) return
        scope.async { openThread() }.await()
    }

    override fun markRead() {
        if (preview || isPaused(conversation.state.value.policy)) return
        scope.launch { guarded("mark read") { reads.markRead(requireVisible = false) } }
    }

    override fun draftChanged(text: String) {
        typing.setTyping(text.isNotEmpty())
        draftJob.getAndSet(
            scope.launch {
                delay(Timings.draftDebounce)
                try {
                    if (text.isEmpty()) records.remove(StoredRecords.draft) else records.write(StoredRecords.draft, text)
                } catch (e: PingFrontException) {
                    log.d("The draft was not saved (${e.logDescription}).")
                }
            },
        )?.cancel()
    }

    override suspend fun photoData(photo: PendingMessage.Photo): ByteArray? = outbox.photoData(photo)

    override suspend fun imageUrl(image: MessagePart.Image): String? = if (image.removed) {
        // Retention deleted it: GET /attachments/{id} would only 404.
        null
    } else {
        try {
            uploader.url(image.attachmentId)
        } catch (_: PingFrontException) {
            image.url
        }
    }

    /** `GET /me/export`: the person's `PersonExport` JSON, as the server sent it. Nothing is stored or logged. */
    suspend fun exportMyData(): String = client.send(Endpoints.exportMe())

    // Push

    fun setPush(token: String) {
        pushToken.set(token)
        unread.setPushRegistered(true)
        submit(Command.Stage(ProfileChange.Push(PushRegistration(PUSH_PROVIDER, token))))
    }

    fun clearPush() {
        pushToken.set(null)
        unread.setPushRegistered(false)
        submit(Command.Stage(ProfileChange.Push(null)))
    }

    fun pushOpened() {
        scope.launch {
            try {
                session.token(SessionReason.PushOpened)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                noteFailure(e)
            }
        }
    }

    // Logout and erase

    private suspend fun performLogout() {
        try {
            session.installCredentials()?.let { pendingLogouts.add(it) }
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("Logout could not read the install (${e.logDescription}); local data is wiped anyway.")
        }
        wipeLocal()
        drainPendingLogouts()
    }

    /**
     * `DELETE /me`, then the local wipe. While the request is in flight the session store refuses every
     * token request and the staged profile is already gone, so nothing can re-register the person being
     * erased (review CS-3). A failed erase restores nothing; the host retries it (it is idempotent).
     */
    private suspend fun erase() {
        // Everything that runs on the old session stops first: the drain, the socket, the PATCH in flight.
        sender.stopLoop()
        live.stop()
        try {
            // The install to erase, read once a session create in flight has finished (review AN-4).
            val credentials: InstallCredentials? = session.beginErase()
            try {
                credentials?.let { api.send(Endpoints.eraseMe(it)) }
            } catch (e: PingFrontException) {
                // Idempotent: an install the server already erased (or never knew) is gone either way.
                if (e.code != ErrorCode.SessionRevoked && e.code != ErrorCode.NotFound) throw e
            }
        } catch (e: PingFrontException) {
            // The person is still signed in: sessions work again and the open chat gets its live channel back.
            session.endErase()
            sender.startLoop()
            resumeLive()
            throw e
        }
        wipeLocal()
    }

    /** Opens the live channel when the chat would have it open (visible, foreground, not paused, not a preview). */
    private suspend fun resumeLive() {
        if (preview || !instance.isChatVisible.value || !foreground.get()) return
        if (!isPaused(conversation.state.value.policy)) live.start(api.liveUrl(remoteConfig.value?.liveUrl))
    }

    private suspend fun wipeLocal() {
        // Persisted first: a push that cold-starts the app after this point shows nothing for the person.
        pushGate.signedOut()
        // The drain is cancelled first, so nothing queued before logout is sent as the next person.
        sender.stopLoop()
        live.stop()
        // Bumps the generation first: every request still in flight discards its result (review CS-2).
        session.wipe()
        wipeEngineState()
    }

    /** Clears everything the engine keeps for the person (the session store is wiped by the caller). */
    private suspend fun wipeEngineState(resetContext: Boolean = true) {
        pushGate.signedOut()
        stopContext(resetContext)
        sender.stopLoop()
        live.stop()
        storageStep("outbox") { outbox.clear() }
        storageStep("team actions") { teamActions.clear() }
        storageStep("card actions") { sender.clearLocal() }
        storageStep("config draft") { configStore.clearDraft() }
        trackerMutex.withLock {
            conversation.clear()
            tracker = SeqTracker()
            storageStep("thread cache") { records.remove(StoredRecords.thread) }
        }
        // A draft save still waiting for its debounce would write the previous person's draft back (review AN-8).
        draftJob.getAndSet(null)?.cancel()
        storageStep("draft") { records.remove(StoredRecords.draft) }
        // Records set aside as unreadable and signed attachment URLs are the person's too (review AN-21).
        storageStep("set-aside records") { persistence.removeQuarantined() }
        uploader.clear()
        api.threadRev.set(null)
        deps.retry.cancel()
        instance.resetForLogout()
        sender.startLoop()
        // The push token belongs to the device, not the person: register it for the next session (review CQ-L9).
        pushToken.get()?.let { coalescer.stage(ProfileChange.Push(PushRegistration(PUSH_PROVIDER, it))) }
    }

    /** One wipe step; a storage failure is logged and the wipe goes on. */
    private suspend fun storageStep(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: PingFrontException) {
            log.w("Wipe: the $what could not be cleared (${e.logDescription}).")
        }
    }

    private companion object {
        const val PREVIEW_CHARS = 140
        const val PUSH_PROVIDER = "fcm"
    }
}
