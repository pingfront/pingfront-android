package dev.pingfront

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.pingfront.conversation.Conversation
import dev.pingfront.internal.CardActions
import dev.pingfront.internal.ChatLink
import dev.pingfront.internal.ChatLinks
import dev.pingfront.internal.Dependencies
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.Taps
import dev.pingfront.internal.android.ActivityTracker
import dev.pingfront.internal.android.DeviceInfo
import dev.pingfront.internal.android.HostInference
import dev.pingfront.internal.android.SystemDynamicColors
import dev.pingfront.internal.android.WorkManagerRetryScheduler
import dev.pingfront.internal.android.processLifecycle
import dev.pingfront.internal.debug.DebugGate
import dev.pingfront.internal.debug.SelfCheck
import dev.pingfront.internal.net.OkHttpTransport
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.SdkDispatchers
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.session.IdentityToken
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.session.ProfileValidation
import dev.pingfront.internal.storage.DataStorePersistence
import dev.pingfront.internal.storage.KeystoreCipher
import dev.pingfront.internal.storage.Persistence
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.Identity
import dev.pingfront.internal.wire.Limits
import dev.pingfront.look.ChatAppearance
import dev.pingfront.look.ChatTopic
import dev.pingfront.look.DynamicColors
import dev.pingfront.look.HostAppInfo
import dev.pingfront.look.PngImage
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.PersonSummary
import dev.pingfront.model.PingFrontColor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** What modules registered. Cleared on teardown, so a reconfigure never duplicates anything. */
internal class ModuleRegistry {
    @Volatile var chatPresenter: ChatPresenter? = null

    @Volatile var offerHandler: OfferHandler? = null

    val offerSources: MutableList<OfferSource> = java.util.concurrent.CopyOnWriteArrayList()
    val entitlementSources: MutableList<EntitlementSource> = java.util.concurrent.CopyOnWriteArrayList()

    @Volatile var reviewRequester: ReviewRequester? = null

    @Volatile var offerPricer: OfferPricer? = null

    @Volatile var purchaseRefresher: PurchaseRefresher? = null

    @Volatile var paymentFixer: PaymentFixer? = null

    @Volatile var reportPresenter: ReportPresenter? = null

    @Volatile var reportContext: ReportContextProvider? = null

    @Volatile var pushTokenSource: PushTokenSource? = null

    fun reset() {
        chatPresenter = null
        offerHandler = null
        offerSources.clear()
        entitlementSources.clear()
        reviewRequester = null
        offerPricer = null
        purchaseRefresher = null
        paymentFixer = null
        reportPresenter = null
        reportContext = null
        pushTokenSource = null
    }
}

/**
 * The SDK's state: observable flows, the conversation, the module registry and the engine for the
 * configured key. `PingFront` delegates to one shared instance; previews and snapshot tests create
 * their own with no engine.
 */
@InternalPingFrontApi
public class PingFrontInstance internal constructor() {
    private val _unreadCount = MutableStateFlow(0)
    private val _look = MutableStateFlow(ResolvedLook.Default)
    private val _person = MutableStateFlow<PersonSummary?>(null)
    private val _problem = MutableStateFlow<ConfigurationProblem?>(null)
    private val _chatVisible = MutableStateFlow(false)
    private val _testDevice = MutableStateFlow(false)
    private val _configured = MutableStateFlow(false)

    /** The running engine; a flow so [teamActions] follows each configure (review AN-5). */
    private val engineFlow = MutableStateFlow<Engine?>(null)
    private val _offerSlots = MutableStateFlow<List<OfferSlot>>(emptyList())
    private val _events = MutableSharedFlow<PingFrontEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    public val unreadCount: StateFlow<Int> = _unreadCount
    public val look: StateFlow<ResolvedLook> = _look
    public val person: StateFlow<PersonSummary?> = _person
    public val configurationProblem: StateFlow<ConfigurationProblem?> = _problem
    public val isChatVisible: StateFlow<Boolean> = _chatVisible
    public val isTestDevice: StateFlow<Boolean> = _testDevice
    public val isConfigured: StateFlow<Boolean> = _configured
    public val offerSlots: StateFlow<List<OfferSlot>> = _offerSlots
    private val _entitlement = MutableStateFlow<Entitlement?>(null)

    /** The person's latest entitlement (for "Current plan" on offer cards). */
    public val entitlement: StateFlow<Entitlement?> = _entitlement
    public val events: SharedFlow<PingFrontEvent> = _events
    public val conversation: Conversation = Conversation()

    @Volatile public var onOffer: ((OfferRequest) -> Unit)? = null

    /**
     * Team actions for the app to do, from the live socket and the catch-up after each session and reconnect.
     * Follows the engine itself, not `isConfigured`: a reconfigure flips that flag off and on before a collector
     * runs, and the conflated flag would leave the collector on the stopped engine (review AN-5).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    public val teamActions: kotlinx.coroutines.flow.Flow<TeamAction> =
        engineFlow.flatMapLatest { it?.teamActions?.actions ?: kotlinx.coroutines.flow.emptyFlow() }

    /**
     * Opens links from the chat ([PingFront.onLink]): return true when the app handled the URL; otherwise the browser
     * opens it. Awaited at most 10 seconds.
     */
    @Volatile public var onLink: (suspend (String) -> Boolean)? = null

    /** Opens a registered screen from a link card ([PingFront.onScreen]): return true when the app opened it. */
    @Volatile public var onScreen: (suspend (dev.pingfront.model.LinkScreen) -> Boolean)? = null

    /** The screens this build registered ([PingFront.registerScreens]), checked; `null` until it registers any. */
    @Volatile internal var screens: List<AppScreen>? = null
        private set

    /** Bad ids registered before `configure`, when the SDK could not yet tell a debug build: checked at configure. */
    @Volatile private var invalidScreenIds: List<String> = emptyList()

    /** True when this build registered [id] (a link card for it goes to [onScreen]); for the chat UI. */
    @InternalPingFrontApi
    public fun isScreenRegistered(id: String): Boolean = screens?.any { it.id == id } == true

    internal fun registerScreens(list: List<AppScreen>) {
        val result = ScreenRules.clean(list)
        if (result.invalid.isNotEmpty()) {
            val message = "registerScreens: ${result.invalid.size} screen id(s) do not match ^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$ " +
                "and were dropped: ${result.invalid.joinToString { "\"$it\"" }}"
            if (DebugGate.isEnabled()) throw IllegalArgumentException(message)
            if (appContext == null) invalidScreenIds = result.invalid
            // Release: the ids stay out of the log.
            Log.e("registerScreens: ${result.invalid.size} screen id(s) do not match ^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$ and were dropped.")
        } else {
            invalidScreenIds = emptyList()
        }
        if (result.repeated.isNotEmpty()) Log.e("registerScreens: ${result.repeated.size} repeated screen id(s) were dropped (each id is sent once).")
        if (result.overflow > 0) Log.e("registerScreens: ${result.overflow} screen(s) beyond the first ${ScreenRules.MAX} were dropped.")
        screens = result.screens
    }

    /** The host's language override (`PingFront.language`); `null` follows the app and the system. */
    public val language: StateFlow<String?> get() = dev.pingfront.internal.runtime.LanguageOverride.tag

    /** Sets the language override. An invalid tag is ignored and logged. */
    internal fun setLanguage(raw: String?) {
        val tag = dev.pingfront.internal.runtime.LanguageOverride.normalized(raw)
        if (raw != null && tag == null) {
            return Log.e("PingFront.language must be a BCP 47 tag such as \"de\" or \"pt-BR\"; the value was ignored.")
        }
        if (dev.pingfront.internal.runtime.LanguageOverride.set(tag)) engine?.languageChanged()
    }

    internal val registry = ModuleRegistry()
    internal val activities = ActivityTracker()

    internal var engine: Engine?
        get() = engineFlow.value
        set(value) {
            taps?.cancelAll()
            taps = value?.let { Taps(it.scope) }
            engineFlow.value = value
        }

    /** What the person tapped and the SDK still waits on, for the current engine ([Taps]). */
    @Volatile private var taps: Taps? = null

    /** Where taps run: the engine's [Taps], or the module scope without an engine (previews, tests). */
    internal fun tapScope(): CoroutineScope = taps?.scope ?: moduleScope()

    /** The host app the look is inferred from: the module override wins over inference (review CQ-L8). */
    internal val host: HostAppInfo? get() = hostOverride ?: inferredHost

    @Volatile private var inferredHost: HostAppInfo? = null

    @Volatile private var hostOverride: HostAppInfo? = null

    @Volatile private var dynamicColors: DynamicColors? = null

    @Volatile private var appearance: ChatAppearance = ChatAppearance()

    @Volatile private var topics: List<ChatTopic>? = null

    /** The look used by previews and snapshots (no engine). */
    @Volatile internal var previewConfig: AppConfig? = null

    @Volatile private var moduleScope: CoroutineScope? = null

    @Volatile private var dispatchers: SdkDispatchers? = null

    @Volatile private var appContext: Context? = null

    @Volatile private var pendingLaunch: Intent? = null

    /** A chat link handled before configure: kept in memory only, applied once by [start] (the last one wins). */
    private val pendingLink = java.util.concurrent.atomic.AtomicReference<ChatLink?>(null)

    /** True once [start] installed the chat UI, so chat links open at once instead of waiting. */
    @Volatile private var linksReady = false

    /** The launch intent held until configure (tests). */
    internal fun heldLaunch(): Intent? = pendingLaunch

    internal fun mainDispatcher(): CoroutineDispatcher = (dispatchers ?: SdkDispatchers.Production).main

    internal fun ioDispatcher(): CoroutineDispatcher = (dispatchers ?: SdkDispatchers.Production).io

    internal fun defaultDispatcher(): CoroutineDispatcher = (dispatchers ?: SdkDispatchers.Production).default

    /** The SDK's injected dispatcher for CPU work, for the UI and report modules (tests replace it). */
    @InternalPingFrontApi
    public fun workDispatcher(): CoroutineDispatcher = defaultDispatcher()

    /** The SDK's injected dispatcher for blocking reads, for the UI and report modules (tests replace it). */
    @InternalPingFrontApi
    public fun blockingDispatcher(): CoroutineDispatcher = ioDispatcher()

    /** The SDK's injected monotonic clock in milliseconds, for modules (virtual time in tests). */
    @InternalPingFrontApi
    public fun monotonicMillis(): Long = (engine?.time ?: dev.pingfront.internal.runtime.TimeSource.System).now().inWholeMilliseconds

    internal fun moduleScope(): CoroutineScope = moduleScope ?: CoroutineScope(SupervisorJob() + defaultDispatcher()).also { moduleScope = it }

    // Configure

    /**
     * Tracks activities from process start (the initializer calls this even without a manifest key), so the first
     * chat opens from a real activity and the debug launch extras are seen when configure runs later.
     */
    internal fun attach(application: Application) {
        // Launch intents are never read for push payloads: any app can start the launcher activity with
        // forged extras (review CS-8). Taps arrive through the SDK's own non-exported PushTapActivity.
        activities.onCreated = { intent -> handleLaunch(intent) }
        activities.register(application)
    }

    /** The result of a setup check: sends "Setup check from <app>" and waits for the server (at most 20 seconds). */
    public suspend fun selfCheck(): SetupCheckResult {
        val engine = engine
            ?: return SetupCheckResult(false, "not_configured", "Call PingFront.configure(context, \"pk_…\") before the check.")
        val result = SelfCheck.check(engine, host?.appName ?: appContext?.packageName ?: "app")
        SelfCheck.emit(result.line)
        return result
    }

    internal fun manifestKey(context: Context): String? = try {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        info.metaData?.getString(MANIFEST_KEY)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    internal fun start(context: Context, rawKey: String?, config: PingFrontConfig, override: Dependencies? = null) {
        val app = context.applicationContext
        if (engine != null || _configured.value) {
            Log.i("PingFront.configure was called again; restarting with the new configuration.")
            teardown()
        }
        appContext = app
        DebugGate.set(override == null && DeviceInfo.isDebuggable(app))
        // registerScreens before configure could not tell a debug build: a bad id fails loudly now.
        invalidScreenIds.takeIf { it.isNotEmpty() && DebugGate.isEnabled() }?.let { ids ->
            invalidScreenIds = emptyList()
            throw IllegalArgumentException("registerScreens: screen ids that do not match ^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$: ${ids.joinToString()}")
        }
        Log.level = config.logLevel
        Log.handler = config.logHandler
        config.language?.let(::setLanguage)
        appearance = config.appearance.snapshot()
        topics = config.topicList
        _problem.value = null
        (app as? Application)?.let(::attach)
        dispatchers = override?.dispatchers ?: SdkDispatchers.Production
        if (override == null && !DeviceInfo.isMainProcess(app)) {
            // DataStore files are single-process: the SDK runs only in the app's main process (review CQ-L11).
            Log.w("PingFront.configure was called in a secondary process; the SDK only runs in the main process.")
            return
        }
        if (override == null) {
            dynamicColors = SystemDynamicColors.read(app)
            moduleScope().launch {
                inferredHost = HostInference.infer(app)
                refreshLook()
                // Only the host look is staged here; module sources were started once by start() (review CQ-M9).
                engine?.hostChanged()
            }
        }
        val modules = discoveredModules(app) + config.moduleList
        val keyString = rawKey ?: manifestKey(app)
        if (keyString == null) {
            report(
                ConfigurationProblem(
                    ConfigurationProblem.Kind.MissingKey,
                    "No app key. Pass it to PingFront.configure(context, \"pk_live_…\") or add <meta-data android:name=\"$MANIFEST_KEY\"> to the manifest.",
                    true,
                ),
            )
            installModules(app, modules)
            applyPendingLink()
            return
        }
        val key = AppKey.parse(keyString).getOrElse { error ->
            val problem = (error as PingFrontException.InvalidKey).problem
            report(ConfigurationProblem(ConfigurationProblem.Kind.InvalidKey(problem), problem.message, true))
            installModules(app, modules)
            applyPendingLink()
            if (DebugGate.isEnabled() &&
                pendingLaunch?.getBooleanExtra(SelfCheck.EXTRA, false) == true
            ) {
                SelfCheck.emit(SelfCheck.line(problem))
            }
            return
        }
        optionProblems(config).forEach { report(it) }
        val deps = override ?: productionDependencies(app, key)
        val engine =
            Engine(this, key, EngineSettings(config.baseUrl, config.logLevel >= LogLevel.Debug, config.presentsChatForMoments, config.wrapperHeader), deps)
        this.engine = engine
        _configured.value = true
        installModules(app, modules)
        engine.start()
        engine.modulesInstalled()
        refreshLook()
        Log.i("pingfront configured with $key")
        pendingLaunch?.let { handleLaunch(it) }
        applyPendingLink()
    }

    // Chat links (the protocol, "Chat links")

    /**
     * Handles a URL the app received: `true` when it is a chat link, which opens the chat or the report now, or once
     * configure has installed the chat UI. Sends nothing itself; the chat starts the session lazily when it appears.
     */
    internal fun handleLink(url: String): Boolean {
        val link = ChatLinks.parse(url)
        if (link !is ChatLink.Chat && link !is ChatLink.Report) return false
        if (linksReady) {
            applyLink(link)
        } else {
            pendingLink.set(link)
            // configure may have finished between the check and the set: apply what is left exactly once.
            if (linksReady) pendingLink.getAndSet(null)?.let(::applyLink)
        }
        return true
    }

    /** An `ACTION_VIEW` intent's data (App Links, a custom scheme, a shortcut). Extras are never read. */
    internal fun handleLink(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val url = intent.dataString ?: return false
        return handleLink(url)
    }

    private fun applyPendingLink() {
        linksReady = true
        pendingLink.getAndSet(null)?.let(::applyLink)
    }

    private fun applyLink(link: ChatLink) {
        when (link) {
            is ChatLink.Chat -> openChat(link.topicId?.let { id -> look.value.topics.firstOrNull { it.id == id } })
            // The report captures the screen first, which needs a resumed activity; links arrive just before one.
            ChatLink.Report -> activities.whenResumed { openReport(null) }
            ChatLink.NotALink, ChatLink.Refused -> Unit
        }
    }

    private fun installModules(context: Context, modules: List<PingFrontModule>) {
        val seen = HashSet<String>()
        for (module in modules) {
            if (!seen.add(module.id)) continue
            module.install(ModuleContext(this, context))
        }
    }

    /** Modules that register themselves through the merged manifest (`pingfront-ui` does), like Firebase's component discovery. */
    private fun discoveredModules(context: Context): List<PingFrontModule> {
        val meta = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return emptyList()
        return moduleNames(meta).mapNotNull { name ->
            try {
                Class.forName(name).getField("INSTANCE").get(null) as? PingFrontModule
            } catch (e: ReflectiveOperationException) {
                Log.e("The pingfront module ${name.substringAfterLast('.')} could not be loaded (${e.javaClass.simpleName}).")
                null
            }
        }
    }

    /**
     * The class names marked as pingfront modules. The app's meta-data holds other libraries' keys too, with any value
     * type (a Flutter app's `flutterEmbedding` is an Integer): only `dev.pingfront.` keys are read, and only a String
     * value counts. `Bundle.getString` on another type logs a ClassCastException, so the value is read untyped and
     * checked (review N12).
     */
    internal fun moduleNames(meta: android.os.Bundle): List<String> = meta.keySet().filter { key ->
        // Bundle.get is deprecated for typed getters (API 33), none of which reads a value of unknown type quietly.
        @Suppress("DEPRECATION")
        key.startsWith(MODULE_PREFIX) && meta.get(key) as? String == MODULE_MARKER
    }.sorted()

    private fun productionDependencies(context: Context, key: AppKey): Dependencies {
        val io = SdkDispatchers.Production.io
        return Dependencies(
            transport = OkHttpTransport(SharedResources.client),
            persistence = SharedResources.persistence(context, key, io),
            time = TimeSource.System,
            dispatchers = SdkDispatchers.Production,
            device = DeviceInfo.provider(context),
            lifecycle = processLifecycle(),
            retry = WorkManagerRetryScheduler(context),
            languageTag = DeviceInfo::languageTag,
            startsInForeground = false,
            context = dev.pingfront.internal.android.AndroidContextSource(context) { appBadgeEnabled },
            signedOut = dev.pingfront.internal.storage.FileSignedOutFlag(SharedResources.directory(context, key), io),
        )
    }

    internal fun teardown() {
        engine?.stop()
        engine = null
        moduleScope?.cancel()
        moduleScope = null
        registry.reset()
        // Modules register their wipe listeners again on install; a reconfigure never stacks them (review CQ-L18).
        wipeListeners.clear()
        linksReady = false
        _configured.value = false
    }

    private fun optionProblems(config: PingFrontConfig): List<ConfigurationProblem> {
        val problems = mutableListOf<ConfigurationProblem>()
        config.topicList?.let { list ->
            if (list.size > MAX_TOPICS) {
                problems += option("topics(...) has ${list.size} topics; the chat shows at most $MAX_TOPICS. Remove ${list.size - MAX_TOPICS}.")
            }
            if (list.map { it.id }.toSet().size !=
                list.size
            ) {
                problems += option("topics(...) has duplicate ids. Give every topic a unique id.")
            }
            list.filterNot { TOPIC_ID.matches(it.id) }.forEach {
                problems +=
                    option("Topic id \"${it.id}\" must be 1–32 characters of a-z, 0-9, _ or -.")
            }
        }
        val scheme = config.baseUrl.substringBefore("://", "")
        if (scheme != "https" &&
            scheme != "http"
        ) {
            problems += option("baseUrl must be an http(s) URL, for example https://api.pingfront.sh/v1.")
        }
        if (scheme == "http" && !DebugGate.isEnabled()) problems += option("baseUrl uses http in a release build. Use https.")
        return problems
    }

    private fun option(message: String) = ConfigurationProblem(ConfigurationProblem.Kind.Option, message, false)

    /** A problem of [kind] went away (the app was resumed in the dashboard): its banner goes. */
    internal fun clearProblem(kind: ConfigurationProblem.Kind) {
        _problem.update { if (it?.kind == kind) null else it }
    }

    internal fun report(problem: ConfigurationProblem) {
        if (_problem.value == null || problem.disablesSdk) _problem.value = problem
        // Loud in debug (Log.e plus the in-chat banner), quiet in release (the SDK disables itself).
        if (DebugGate.isEnabled()) {
            Log.e(
                "pingfront setup problem: ${problem.message}",
            )
        } else {
            Log.w("pingfront setup problem: ${problem.message}")
        }
        emit(PingFrontEvent.ConfigurationProblemFound(problem))
    }

    // Debug launch extras: `--ez pingfront_selfcheck true` and `--es pingfront_preview welcome|conversation`.

    private fun handleLaunch(intent: Intent) {
        val engine = engine
        // Before configure the gate is not known yet: keep the launch intent, it is read once configure runs
        // (React Native and Flutter configure after the first activity was created).
        if (engine == null) {
            pendingLaunch = intent
            if (DebugGate.isEnabled() &&
                _problem.value?.kind is ConfigurationProblem.Kind.InvalidKey &&
                intent.getBooleanExtra(SelfCheck.EXTRA, false)
            ) {
                SelfCheck.emit(SelfCheck.line((_problem.value?.kind as ConfigurationProblem.Kind.InvalidKey).problem))
            }
            return
        }
        pendingLaunch = null
        if (!DebugGate.isEnabled()) return
        if (intent.getBooleanExtra(SelfCheck.EXTRA, false)) {
            engine.scope.launch {
                SelfCheck.emit(SelfCheck.run(engine, host?.appName ?: appContext?.packageName ?: "app"))
            }
        }
        intent.getStringExtra(SelfCheck.PREVIEW_EXTRA)?.let { name ->
            val hooks = dev.pingfront.internal.debug.PreviewHooks.find() ?: return Log.e("Preview mode needs the debug build of the SDK.")
            engine.preview = true
            if (hooks.load(this, name)) openChat(null)
        }
    }

    // Look

    private val _branding = MutableStateFlow(false)

    /** True while the app's org is on Free (`AppConfig.features.branding`): the welcome screen's branding line. */
    @InternalPingFrontApi
    public val showsBranding: StateFlow<Boolean> = _branding

    /** Re-resolves the look from the remote config, code, host app and defaults. */
    public fun refreshLook() {
        val remote = engine?.remoteConfig?.value ?: previewConfig
        _branding.value = remote?.features?.branding ?: false
        // Resolved inside the atomic update: two refreshes at once (host inference and a config) never leave the
        // older result in place (review AN-19).
        _look.update { ResolvedLook.resolve(engine?.remoteConfig?.value ?: previewConfig, appearance, topics, hostOverride ?: host, dynamicColors) }
    }

    internal fun overrideHost(name: String, accent: PingFrontColor?, iconPng: ByteArray?) {
        hostOverride = HostAppInfo(name, accent, iconPng?.let(::PngImage), appContext?.let(DeviceInfo::appVersion) ?: "0")
        refreshLook()
        engine?.hostChanged()
    }

    /** Previews: the host app the look is inferred from. */
    public fun setPreviewHost(info: HostAppInfo?) {
        hostOverride = info
        refreshLook()
    }

    /** Previews: the code-level appearance. */
    public fun setPreviewAppearance(value: ChatAppearance, dynamic: DynamicColors? = null) {
        appearance = value
        dynamicColors = dynamic
        refreshLook()
    }

    /** Previews and snapshots: a configuration problem to show. */
    public fun setPreviewProblem(problem: ConfigurationProblem?) {
        _problem.value = problem
    }

    // State used by the engine

    internal fun setPerson(person: PersonSummary?) {
        _person.value = person
    }

    internal fun setTestDevice(value: Boolean) {
        _testDevice.value = value
    }

    internal fun setUnread(count: Int) {
        if (_unreadCount.getAndUpdate { count } == count) return
        emit(PingFrontEvent.UnreadChanged(count))
    }

    /** One more unread message, counted atomically: two arriving at once both count (review AN-19). */
    internal fun incrementUnread() {
        emit(PingFrontEvent.UnreadChanged(_unreadCount.updateAndGet { it + 1 }))
    }

    /** Sends an event to every `events` collector. */
    public fun emit(event: PingFrontEvent) {
        _events.tryEmit(event)
    }

    internal fun setOffersFromModules(slots: List<OfferSlot>) = setOffers(slots)

    private val wipeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** The resumed activity, for presenters. */
    public fun currentActivityForUi(): android.app.Activity? = activities.current()

    /** Called after logout and erase (the UI clears its in-memory image cache). */
    public fun addWipeListener(listener: () -> Unit) {
        wipeListeners += listener
    }

    internal fun resetForLogout() {
        taps?.cancelAll()
        wipeListeners.forEach { it() }
        _unreadCount.value = 0
        _person.value = null
        _testDevice.value = false
        _entitlement.value = null
        emit(PingFrontEvent.UnreadChanged(0))
    }

    /** The app's Play Store listing (the review card's fallback when the card names no store URL). */
    internal fun packageName(): String? = appContext?.packageName

    internal fun applicationContext(): Context? = appContext

    internal fun storeListingUrl(): String? = appContext?.packageName?.let { "https://play.google.com/store/apps/details?id=$it" }

    /** The price of a card's store offer for this person (from the billing module), or `null`. */
    public suspend fun storeOfferPrice(offer: dev.pingfront.model.StoreOffer): OfferPrice? = registry.offerPricer?.price(offer)

    /** The price of [productId] as an offer card without a named offer buys it, or `null`. */
    @InternalPingFrontApi
    public suspend fun productPrice(productId: String): OfferPrice? = registry.offerPricer?.price(productId)

    private val _pendingProducts = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Products bought but still waiting for payment (Play's pending purchases). */
    @InternalPingFrontApi
    public val pendingProducts: StateFlow<Set<String>> = _pendingProducts

    /** Re-reads the store's purchases (billing module); updates [pendingProducts]. Null when unavailable. */
    @InternalPingFrontApi
    public suspend fun refreshPurchases(): StorePurchases? = registry.purchaseRefresher?.refresh()?.also { _pendingProducts.value = it.pendingProducts }

    /** True when a push module is installed (the chat may offer to turn notifications on). */
    public val pushEnabled: Boolean get() = registry.pushTokenSource != null

    /** True when the published config turned the launcher badge on (`features.appBadge`). */
    public val appBadgeEnabled: Boolean get() = engine?.remoteConfig?.value?.features?.appBadge ?: false

    /** The per-person UUID billing modules pass as `obfuscatedAccountId`. */
    public suspend fun storeAccountToken(): String? = engine?.session?.storeAccountToken()

    /**
     * The person's `storeAccountToken` each time a session starts (it changes after logout). Vendor adapters link
     * it to the vendor's customer (for example RevenueCat's `pingfront_token` attribute) so webhooks find the person.
     */
    public fun storeAccountTokens(): kotlinx.coroutines.flow.Flow<String> = _person.filterNotNull().mapNotNull { storeAccountToken() }.distinctUntilChanged()

    /** Reports a store event detected on the device (only sent when a play listens to it). */
    public suspend fun storeMoment(event: String, props: StoreMomentProps): MomentResult {
        val engine = engine ?: return MomentResult.Failed(PingFrontException.NotConfigured())
        return dev.pingfront.internal.CardActions.moment(engine, this, event, emptyMap(), props)
    }

    // The public API (PingFront delegates here)

    internal fun identify(userId: String, name: String?, email: String?) {
        val engine = engine ?: return notConfigured("identify")
        if (userId.isEmpty()) return Log.e("identify(userId) needs a non-empty userId.")
        // Never truncated: two long ids that share a prefix would become one person (review CQ-L3).
        if (Limits.codePoints(userId) > Limits.USER_ID_CHARS) {
            return Log.e("identify(userId) was ignored: the userId is longer than ${Limits.USER_ID_CHARS} characters.")
        }
        val id = userId
        val cleanEmail = email?.takeIf { ProfileValidation.isEmail(it) }
        if (email != null && cleanEmail == null) Log.e("identify: the email is not valid and was not sent.")
        engine.submit(
            Engine.Command.Stage(
                ProfileChange.SetIdentity(
                    Identity(
                        id,
                        name?.let {
                            Limits.truncate(it, Limits.NAME_CHARS)
                        },
                        cleanEmail,
                    ),
                ),
            ),
        )
    }

    internal fun identify(token: String) {
        val engine = engine ?: return notConfigured("identify")
        val claims = IdentityToken.claims(token)
        if (Limits.codePoints(token) > Limits.IDENTITY_TOKEN_CHARS || claims == null) {
            report(
                option(
                    "identify(token) got something that is not a JWT with a `sub` claim. Sign an HS256 token with the app's identity secret.",
                ),
            )
            return
        }
        engine.submit(Engine.Command.Stage(ProfileChange.SetIdentity(Identity(claims.sub, claims.name, claims.email, token))))
    }

    internal fun setTraits(traits: Map<String, String>) {
        val engine = engine ?: return notConfigured("setTraits")
        val (clean, problems) = ProfileValidation.clean(traits)
        problems.forEach { Log.e("setTraits: $it") }
        engine.submit(Engine.Command.Stage(ProfileChange.Traits(clean)))
    }

    internal fun setEntitlement(entitlement: Entitlement) {
        _entitlement.value = entitlement
        val engine = engine ?: return notConfigured("setEntitlement")
        engine.submit(Engine.Command.Stage(ProfileChange.SetEntitlement(entitlement)))
    }

    internal fun setOffers(offers: List<OfferSlot>) {
        _offerSlots.value = offers
        val engine = engine ?: return notConfigured("setOffers")
        engine.offers.value = offers
        engine.submit(Engine.Command.Stage(ProfileChange.Offers(offers.take(MAX_OFFERS))))
    }

    internal fun setPushToken(token: String?) {
        val engine = engine ?: return notConfigured("setPushToken")
        if (token.isNullOrEmpty()) engine.clearPush() else engine.setPush(token)
    }

    /**
     * A pingfront data message received from FCM (the Push module's service, or the host's own service
     * forwarding `RemoteMessage.data`). Applies its unread count; never opens the chat or starts a session:
     * only a tap on the SDK's notification does that ([pushTapped]).
     */
    internal fun handlePush(data: Map<String, String>): Boolean {
        val push = PushPayload.parse(data) ?: return false
        if (push.kind != PushPayload.KIND_CONFIG) pushReceived(push.unread)
        return true
    }

    /**
     * A received push's unread count is the badge, unless the chat is on screen or the person logged out (review CQ-H3).
     * Before configure it is only a number in memory, so it applies; whether the notification is shown is [acceptsPush].
     */
    private fun pushReceived(unread: Int?) {
        if (unread == null || engine?.pushGate?.accepts() == false || _chatVisible.value) return
        setUnread(unread)
    }

    /**
     * False after logout or erase until the next session: notifications for the previous person are not shown (review
     * CQ-M7), also when a push cold-starts the app after an offline logout (the flag is persisted). Before `configure`
     * (React Native and Flutter configure from script) it reads the stored flags under [context]'s app; with neither an
     * engine nor a context, nobody is known to be signed in and it is false.
     */
    public fun acceptsPush(context: Context? = appContext): Boolean {
        engine?.let { return it.pushGate.accepts() }
        val app = context?.applicationContext ?: return false
        return dev.pingfront.internal.storage.FileSignedOutFlag.anySignedIn(SharedResources.root(app))
    }

    /**
     * The intent a pingfront notification opens: the SDK's non-exported [dev.pingfront.internal.android.PushTapActivity],
     * which only this app can start, so its payload can be trusted (review CS-8).
     */
    public fun pushTapIntent(context: Context, data: Map<String, String>): Intent =
        Intent(context, dev.pingfront.internal.android.PushTapActivity::class.java).apply {
            data.forEach { (key, value) -> if (key.startsWith(PUSH_PREFIX)) putExtra(key, value) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * A tapped pingfront notification from the app's own push code or a bridge: the same path as the SDK's own tap,
     * so a notification addressed to a person who logged out since opens nothing (review AN-18).
     */
    internal fun openPush(data: Map<String, String>): Boolean {
        if (PushPayload.parse(data) == null) return false
        pushTapped(data)
        return true
    }

    /** A notification the SDK showed was tapped (from [dev.pingfront.internal.android.PushTapActivity]). */
    internal fun pushTapped(data: Map<String, String>, context: Context? = appContext) {
        val push = PushPayload.parse(data) ?: return
        if (push.kind == PushPayload.KIND_CONFIG || !acceptsPush(context)) return
        pushReceived(push.unread)
        engine?.pushOpened()
        openChat(null)
    }

    internal suspend fun moment(name: String, props: Map<String, Any>): MomentResult {
        val engine = engine ?: return MomentResult.Failed(PingFrontException.NotConfigured())
        // Runs in the SDK's own scope: a screen that reports a moment as it closes (a paywall) cancels only its wait for
        // the result, never the report (review AN-14). Logout, erase and teardown still stop it.
        return engine.scope.async { dev.pingfront.internal.CardActions.moment(engine, this@PingFrontInstance, name, props) }.await()
    }

    internal suspend fun prepare() {
        val engine = engine ?: throw PingFrontException.NotConfigured()
        engine.session.token(dev.pingfront.internal.wire.SessionReason.Prepare)
    }

    /**
     * Presents the chat through the registered presenter. While an embedded `PingFrontChat` is on screen, that chat is focused instead: it scrolls to the latest message and
     * takes the topic's prefill, and no second chat opens over it.
     */
    public fun openChat(topic: ChatTopic?) {
        topic?.prefill?.resolve(DeviceInfo.languageTag())?.let { conversation.insert(it) }
        if (_embeddedVisible.value) {
            _focusRequests.tryEmit(Unit)
            return
        }
        val presenter =
            registry.chatPresenter
                ?: return Log.e("openChat: no chat UI is installed. Add the sh.pingfront:pingfront dependency (or pingfront-ui).")
        presenter.presentChat(topic)
    }

    internal fun closeChat() {
        registry.chatPresenter?.dismissChat()
    }

    /** The wait for [onLink]'s answer to the last link tapped in a message, replaced by the next one. */
    private val chatLinkJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    /**
     * A link tapped in a message or its preview, for the chat UI (whose `UriHandler` can't wait): the link card URL
     * rule first (the protocol, "Link card URLs"), so a refused link never reaches [onLink] or a bridge that forwards
     * it; then [onLink], awaited as a link card's is; then [openByDefault] when it declines, throws or doesn't answer
     * within 10 seconds. Without [onLink], [openByDefault] runs at once. The wait runs in the engine's tap scope, which
     * logout, erase and stop cancel (a cancelled wait opens nothing), and a newer link replaces one still waiting.
     */
    @InternalPingFrontApi
    public fun openChatLink(url: String, openByDefault: (String) -> Unit) {
        if (!WebLinks.isCardLink(url)) return
        chatLinkJob.getAndSet(null)?.cancel()
        val handler = onLink ?: return openByDefault(url)
        // Started in the tap itself, on the main thread: a handler that answers at once opens the link in the same frame.
        val job = tapScope().launch(mainDispatcher(), start = CoroutineStart.UNDISPATCHED) {
            if (!CardActions.answer(this@PingFrontInstance, "onLink") { handler(url) }) openByDefault(url)
        }
        chatLinkJob.set(job)
    }

    /** [PingFront.openLinkByDefault]: the link card URL rule, then the chat presenter. */
    internal fun openLinkByDefault(url: String): Boolean {
        val cleaned = WebLinks.cleanCardUrl(url)
        if (!WebLinks.isCardLink(cleaned)) return false
        return registry.chatPresenter?.openLink(cleaned) ?: false
    }

    private val presentedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val _presentedChat = MutableStateFlow(false)

    /** True while a chat the SDK presented (its sheet activity) is on screen; never an embedded `PingFrontChat`. */
    @InternalPingFrontApi
    public val presentedChat: StateFlow<Boolean> = _presentedChat

    /**
     * The UI module reports each presented chat coming on (true) and going off (false) screen. Counted, so one chat
     * that stops while another is still on screen (a recreated activity, two in the back stack) doesn't clear it.
     */
    @InternalPingFrontApi
    public fun presentedChatChanged(onScreen: Boolean) {
        val count = presentedCount.updateAndGet { (if (onScreen) it + 1 else it - 1).coerceAtLeast(0) }
        _presentedChat.value = count > 0
    }

    /**
     * Closes the SDK-presented chat, always (one behind Home or the photo picker too), and returns once no presented
     * chat is on screen: at once when none is, and after [CLOSE_WAIT_MS] at most.
     */
    internal suspend fun closeChatAndWait() {
        closeChat()
        if (!_presentedChat.value) return
        // A presenter that never reports back (another app's custom presenter) does not hold the caller for long.
        kotlinx.coroutines.withTimeoutOrNull(CLOSE_WAIT_MS) { _presentedChat.first { !it } }
    }

    internal suspend fun reportBug(text: String, screenshot: ByteArray?) {
        val engine = engine ?: throw PingFrontException.NotConfigured()
        dev.pingfront.internal.CardActions.reportBug(engine, this, text, listOfNotNull(screenshot))
    }

    /** Opens the report flow (Reports module) or the simple text form. */
    public fun openReport(prefill: String?) {
        registry.reportPresenter?.presentReport(prefill) ?: registry.chatPresenter?.presentSimpleReport(prefill)
    }

    internal fun logout() {
        val engine = engine ?: return notConfigured("logout")
        // A tap still waiting for the app's handler belongs to this person: it opens and reports nothing.
        taps?.cancelAll()
        engine.submit(Engine.Command.Logout)
    }

    internal suspend fun eraseMyData() {
        val engine = engine ?: throw PingFrontException.NotConfigured()
        val done = CompletableDeferred<Result<Unit>>()
        engine.submit(Engine.Command.Erase(done))
        done.await().getOrThrow()
    }

    internal suspend fun exportMyData(): String {
        val engine = engine ?: throw PingFrontException.NotConfigured()
        return engine.exportMyData()
    }

    /** The chats on screen, each counted once (a sheet over an embedded chat is two). Guarded by itself. */
    private val presenters = HashMap<Any, Boolean>()
    private val _embeddedVisible = MutableStateFlow(false)
    private val _focusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * True while an embedded `PingFrontChat` is on screen. [openChat] then focuses it instead of presenting another.
     */
    @InternalPingFrontApi
    public val embeddedChatVisible: StateFlow<Boolean> = _embeddedVisible

    /** Asks the embedded chat on screen to come into view (a push tap, a chat link, a moment, `openChat`). */
    @InternalPingFrontApi
    public val chatFocusRequests: SharedFlow<Unit> = _focusRequests

    /** The UI calls this when the chat appears or disappears (one presenter; see [presenterVisibilityChanged]). */
    public fun chatVisibilityChanged(visible: Boolean): Unit = presenterVisibilityChanged(DEFAULT_PRESENTER, visible, embedded = false)

    /**
     * One chat presenter ([presenter], any stable key) came on screen or went off it. The chat is visible while any
     * presenter is: a sheet that closes over an embedded chat leaves the chat visible. [embedded] marks a chat in the
     * app's own layout, which [openChat] focuses instead of presenting another.
     */
    public fun presenterVisibilityChanged(presenter: Any, visible: Boolean, embedded: Boolean) {
        // One lock for the count and the transition it causes, so two presenters never announce out of order.
        synchronized(presenters) {
            val wasVisible = presenters.isNotEmpty()
            if (visible) presenters[presenter] = embedded else presenters.remove(presenter)
            _embeddedVisible.value = presenters.values.any { it }
            val nowVisible = presenters.isNotEmpty()
            if (wasVisible == nowVisible) return
            _chatVisible.value = nowVisible
            emit(if (nowVisible) PingFrontEvent.ChatOpened else PingFrontEvent.ChatClosed)
            if (nowVisible) engine?.chatDidAppear() else engine?.chatDidDisappear()
        }
    }

    private fun notConfigured(call: String) {
        Log.e("$call was called before PingFront.configure, or with a configuration problem. It was ignored.")
    }

    /** Constants and the detached factory. */
    public companion object {
        internal const val MANIFEST_KEY = "pingfront.app_key"
        internal const val MODULE_MARKER = "dev.pingfront.module"

        /** The longest [closeChatAndWait] waits for a presenter to report its chat gone. */
        private const val CLOSE_WAIT_MS = 2_000L

        /** The presenter key of [chatVisibilityChanged]. */
        private val DEFAULT_PRESENTER = Any()

        /** Only pingfront's own classes register as modules. */
        private const val MODULE_PREFIX = "dev.pingfront."
        private const val PUSH_PREFIX = "pf_"
        private const val MAX_TOPICS = 6
        private const val MAX_OFFERS = 20
        private val TOPIC_ID = Regex("^[a-z0-9_-]{1,32}$")

        /** A standalone instance for previews and snapshots: no engine, no network. */
        public fun detached(): PingFrontInstance = PingFrontInstance()
    }
}

/** Process-wide resources: one HTTP client, and one encrypted store per app key (DataStore allows one instance per file). */
internal object SharedResources {
    val client: okhttp3.OkHttpClient by lazy { OkHttpTransport.defaultClient() }
    private val stores = HashMap<String, Persistence>()
    private val scope by lazy { CoroutineScope(SupervisorJob() + SdkDispatchers.Production.io) }

    @Synchronized
    fun persistence(context: Context, key: AppKey, io: CoroutineDispatcher): Persistence = stores.getOrPut(digest(key)) {
        DataStorePersistence(directory(context, key), KeystoreCipher(), scope, io)
    }

    /** Where the SDK keeps its records: under noBackupFilesDir, never backed up. */
    fun root(context: Context): File = File(context.noBackupFilesDir, "pingfront")

    /** One app key's records: namespaced by key, never shared between keys. */
    fun directory(context: Context, key: AppKey): File = File(root(context), digest(key))

    private fun digest(key: AppKey): String = MessageDigest.getInstance("SHA-256").digest(key.rawValue.encodeToByteArray()).joinToString("") {
        "%02x".format(it)
    }.take(16)
}

/** The FCM data payload (the API's `FcmData`): `pf_*` string keys, `pf_v = "1"`. */
@InternalPingFrontApi
public data class PushPayload(
    val kind: String,
    val appId: String,
    val threadId: String?,
    val messageId: String?,
    val unread: Int?,
    val title: String?,
    val body: String?,
) {
    /** Keys and the parser. */
    public companion object {
        /** FCM data keys (`FcmData`). */
        public object Keys {
            public const val VERSION: String = "pf_v"
            public const val KIND: String = "pf_kind"
            public const val APP_ID: String = "pf_app_id"
            public const val THREAD_ID: String = "pf_thread_id"
            public const val MESSAGE_ID: String = "pf_message_id"
            public const val UNREAD: String = "pf_unread"
            public const val TITLE: String = "pf_title"
            public const val BODY: String = "pf_body"
        }

        public const val SUPPORTED_VERSION: String = "1"
        public const val KIND_MESSAGE: String = "message"
        public const val KIND_PING: String = "ping"
        public const val KIND_CONFIG: String = "config"
        private const val TITLE_CHARS = 100
        private const val BODY_CHARS = 500

        /** Parses a pingfront payload, or returns `null` for other notifications and unknown versions. */
        public fun parse(data: Map<String, String>): PushPayload? {
            if (data[Keys.VERSION] != SUPPORTED_VERSION) return null
            // A kind this SDK does not know is still a pingfront payload (shown like a message), never the host's (review CQ-L1).
            val kind = data[Keys.KIND]?.takeIf { it.isNotEmpty() } ?: return null
            val appId = data[Keys.APP_ID] ?: return null
            return PushPayload(
                kind = kind,
                appId = appId,
                threadId = data[Keys.THREAD_ID],
                messageId = data[Keys.MESSAGE_ID],
                unread = data[Keys.UNREAD]?.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } }?.toIntOrNull(),
                // Code points: a title never ends in half an emoji (review CQ-L2).
                title = data[Keys.TITLE]?.let { Limits.truncate(it, TITLE_CHARS) },
                body = data[Keys.BODY]?.let { Limits.truncate(it, BODY_CHARS) },
            )
        }
    }
}
