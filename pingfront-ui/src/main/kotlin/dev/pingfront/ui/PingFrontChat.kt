package dev.pingfront.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pingfront.PingFront
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.ConversationPhase
import dev.pingfront.conversation.ConversationState
import dev.pingfront.internal.debug.DebugGateAccess
import dev.pingfront.look.ChatTopic
import dev.pingfront.model.ColorScheme
import kotlinx.coroutines.launch

/**
 * The chat, embedded: full size inside your layout (a tab, a screen in your navigation).
 *
 * Edge-to-edge aware, the way Compose's own `Scaffold` and app bars are: by default it pads for
 * [WindowInsets.safeDrawing] (the status and navigation bars, the display cutout and the keyboard), so as the whole
 * window its header clears the status bar and its composer rides on the keyboard.
 *
 * ```kotlin
 * PingFrontChat(Modifier.fillMaxSize(), topic = ChatTopic.Billing)
 * ```
 *
 * Insets an ancestor already handled are not padded again: pad and consume them, and the chat pads only what is left.
 * Inside a `Scaffold` with your own bottom bar:
 *
 * ```kotlin
 * Scaffold(bottomBar = { NavigationBar { … } }) { padding ->
 *     PingFrontChat(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize())
 * }
 * ```
 *
 * The status bar is then the Scaffold's, the navigation bar the bottom bar's, and with the keyboard up the composer
 * sits flush on it. A window that already keeps out of the system bars and resizes for the keyboard (not edge to edge)
 * passes `windowInsets = WindowInsets(0)`.
 *
 * In a layout that is not Compose (XML, a `Fragment`'s views, React Native, Flutter), use [PingFrontChatView].
 *
 * @param windowInsets The window insets the chat keeps its header and composer clear of. Defaults to
 *   [PingFrontChatDefaults.windowInsets].
 */
@Composable
public fun PingFrontChat(
    modifier: Modifier = Modifier,
    topic: ChatTopic? = null,
    onClose: (() -> Unit)? = null,
    windowInsets: WindowInsets = PingFrontChatDefaults.windowInsets,
) {
    PingFrontLanguage { ChatScreen(PingFront.instance, topic, onClose, modifier, windowInsets, embedded = true) }
}

/** Defaults for [PingFrontChat]. */
public object PingFrontChatDefaults {
    /** [WindowInsets.safeDrawing]: the system bars, the display cutout and the keyboard. */
    public val windowInsets: WindowInsets
        @Composable get() = WindowInsets.safeDrawing
}

/** Controls a [PingFrontChatSheet]: open it from anywhere in your composition. */
@Stable
public class PingFrontChatState internal constructor() {
    /** True while the sheet is shown. */
    public var isVisible: Boolean by mutableStateOf(false)
        private set

    /** The topic the sheet opened with. */
    public var topic: ChatTopic? by mutableStateOf(null)
        private set

    /** Shows the sheet, optionally for a topic. */
    public fun open(topic: ChatTopic? = null) {
        this.topic = topic
        isVisible = true
    }

    /** Hides the sheet. */
    public fun close() {
        isVisible = false
    }

    internal companion object {
        /** Saves whether the sheet is open and its topic id (the topic is found again in the resolved look). */
        val Saver: Saver<PingFrontChatState, Any> = listSaver(
            save = { listOf(it.isVisible, it.topic?.id.orEmpty()) },
            restore = { saved ->
                PingFrontChatState().apply {
                    val id = saved.getOrNull(1) as? String
                    topic = id?.takeIf { it.isNotEmpty() }?.let { wanted -> PingFront.instance.look.value.topics.firstOrNull { it.id == wanted } }
                    isVisible = saved.getOrNull(0) as? Boolean ?: false
                }
            },
        )
    }
}

/** Remembers a [PingFrontChatState]; an open sheet stays open (with its topic) across rotation and process death. */
@Composable
public fun rememberPingFrontChatState(): PingFrontChatState = rememberSaveable(saver = PingFrontChatState.Saver) { PingFrontChatState() }

/** The chat as a full-height Material bottom sheet, driven by a [PingFrontChatState]. */
@Composable
public fun PingFrontChatSheet(state: PingFrontChatState) {
    PingFrontChatSheet(visible = state.isVisible, onDismiss = state::close, topic = state.topic)
}

/** The chat as a full-height Material bottom sheet (predictive back, drag to dismiss). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PingFrontChatSheet(visible: Boolean, onDismiss: () -> Unit, topic: ChatTopic? = null) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val look by PingFront.instance.look.collectAsStateWithLifecycle()
    val dark = when (look.scheme) {
        ColorScheme.Auto -> isSystemInDarkTheme()
        ColorScheme.Light -> false
        ColorScheme.Dark -> true
    }
    val theme = remember(look, dark) { ChatTheme(look, dark) }
    // At a compact window height (a phone in landscape; Material's size class below 480 dp) every row counts: the drag
    // handle goes (dragging the sheet still works, and the header's close button closes it), so the header, a banner,
    // the welcome and the composer fit, and with the keyboard up the composer and what the person types stay in view.
    val tight = windowHeightDp() < COMPACT_WINDOW_HEIGHT
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.background,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = if (tight) null else ({ BottomSheetDefaults.DragHandle() }),
        // The status bar draws over the scrim and the app under it, not over the sheet (it starts below the status
        // bar): its icons follow what is under them, the app dimmed by the scrim. Apps follow the system's scheme, so
        // the system's scheme decides (light icons over a dark app and scrim); the chat's resolved look only colours
        // the sheet. Without this a light look in dark mode got dark icons on the dark scrim. The navigation bar draws
        // over the sheet, so its icons follow the chat's own scheme.
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = !isSystemInDarkTheme(),
            isAppearanceLightNavigationBars = !dark,
        ),
        // The sheet (and its drag handle) keeps out of the status bar, and of whatever sits on the window's sides in
        // landscape (a 3-button navigation bar, the cutout): the sheet is centred in what is left (audit A-01, B1).
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        PingFrontLanguage {
            ChatScreen(
                PingFront.instance,
                topic,
                onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                Modifier.fillMaxSize(),
                // The sheet itself keeps out of the top and the sides (its modifier above), so the chat inside keeps
                // clear of the bottom alone: the navigation bar and the keyboard.
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            )
        }
    }
}

/** The screen every presentation shares: header, banners, thread or welcome, composer. */
@Composable
internal fun ChatScreen(
    instance: PingFrontInstance,
    topic: ChatTopic?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = PingFrontChatDefaults.windowInsets,
    embedded: Boolean = false,
) {
    val look by instance.look.collectAsStateWithLifecycle()
    val state by instance.conversation.state.collectAsStateWithLifecycle()
    val problem by instance.configurationProblem.collectAsStateWithLifecycle()
    val dark = when (look.scheme) {
        ColorScheme.Auto -> isSystemInDarkTheme()
        ColorScheme.Light -> false
        ColorScheme.Dark -> true
    }
    val theme = remember(look, dark) { ChatTheme(look, dark) }
    // The topic's own prefill only, as on iOS: a preset's sentence start belongs to a tap on its chip, and opening the
    // chat for a topic (openChat, a chat link) never writes a sentence the person did not choose.
    val prefill = topic?.prefill.text()
    // The topic's text is inserted once per presentation, never again after a rotation (review CQ-L15).
    var prefilled by rememberSaveable(topic?.id) { mutableStateOf(false) }
    LaunchedEffect(prefill) {
        if (!prefilled && prefill != null) instance.conversation.insert(prefill)
        prefilled = true
    }
    // "Visible" follows the lifecycle: a chat behind Home or another app is not visible, so pushes show,
    // unread counts and polling resume (review CQ-H4). Disposal always reports hidden.
    // Each chat on screen is one presenter: a sheet that closes over an embedded chat leaves the chat visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val presenter = remember { Any() }
    DisposableEffect(instance, lifecycle, embedded) {
        val observer = LifecycleEventObserver { _, _ ->
            instance.presenterVisibilityChanged(presenter, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), embedded)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            instance.presenterVisibilityChanged(presenter, false, embedded)
        }
    }
    val scope = rememberCoroutineScope()
    val windowWidth = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    val columnWidth = minOf(windowWidth, READING_WIDTH)
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val links = remember(instance, appContext) { ChatLinkHandler(instance, appContext) }
    CompositionLocalProvider(
        LocalChatTheme provides theme,
        LocalMaxBubbleWidth provides maxBubbleWidth(columnWidth),
        androidx.compose.ui.platform.LocalUriHandler provides links,
    ) {
        ChatMaterialTheme(theme) {
            CompositionLocalProvider(LocalContentColor provides theme.text) {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier.background(theme.background).windowInsetsPadding(windowInsets),
                ) {
                    // A phone in landscape with the keyboard up leaves about 80 dp: the header, the banners and the welcome
                    // go so the composer and what the person types stay in view (Google Messages hands the text to the
                    // keyboard's own editor). With the keyboard down the chat keeps them; only a container too short for
                    // the header and the composer together drops the header without a keyboard.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    val imeUp = WindowInsets.isImeVisible
                    val compact = (imeUp && maxHeight < COMPACT_CHAT_HEIGHT) || maxHeight < MIN_CHAT_WITH_HEADER
                    val condensed = windowHeightDp() < COMPACT_WINDOW_HEIGHT
                    val composerRoom = maxHeight - if (compact) 0.dp else HEADER_ROOM
                    val body = chatBody(state)
                    // At large text or a compact height the notices are a row of the content, where text can grow
                    // without taking the screen from the thread and the composer: the bars hold no unbounded text.
                    val noticesInThread = noticesMoveIntoThread(LocalDensity.current.fontScale, windowHeightDp())
                    val inContent: @Composable () -> Unit = { Notices(state, problem, inThread = true) }
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val column = Modifier.fillMaxWidth().widthIn(max = READING_WIDTH)
                        // Chrome stops growing at a large font scale so the thread keeps room; messages scale fully.
                        CappedFontScale {
                            Column(column) {
                                if (!compact && showsHeader(state, onClose, look)) ChatHeader(look, state, onClose)
                                // Compact (the keyboard up in a short window): the composer and what the person types keep the
                                // little room there is; the notice returns with the keyboard down, and a queued message
                                // still says how it waits under its bubble.
                                if (!noticesInThread && !compact) Notices(state, problem, inThread = false)
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when (body) {
                                ChatBody.Thread -> MessageList(
                                    instance,
                                    state,
                                    look,
                                    column.fillMaxSize(),
                                    headerShowsExpectation = !compact,
                                    notices = if (noticesInThread && hasNotices(state, problem)) inContent else null,
                                )
                                ChatBody.Loading -> NoticesAbove(noticesInThread, state, problem, column) {
                                    CircularProgressIndicator(color = theme.accent)
                                }
                                ChatBody.LoadFailed -> NoticesAbove(noticesInThread, state, problem, column) {
                                    LoadFailed { scope.launch { instance.conversation.reload() } }
                                }
                                // Compact: the welcome would show as a sliver above the keyboard; the composer has the room.
                                ChatBody.Welcome -> if (!compact) {
                                    Welcome(
                                        instance,
                                        state,
                                        look,
                                        column.fillMaxSize(),
                                        disabled = problem?.disablesSdk == true,
                                        condensed = condensed,
                                        notices = if (noticesInThread && hasNotices(state, problem)) inContent else null,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = theme.rule)
                        CappedFontScale {
                            CompositionLocalProvider(LocalComposerRoom provides composerRoom) {
                                // Nothing loaded to write into: the composer waits for the retry, like the rest of the screen.
                                Box(column) { Composer(instance, state, look, disabled = problem?.disablesSdk == true || body == ChatBody.LoadFailed) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val READING_WIDTH = 720.dp

/**
 * Material's compact height window size class ends here (developer.android.com/develop/ui/compose/layouts/adaptive/
 * use-window-size-classes: "Compact: height < 480dp"). Read from the window directly: `currentWindowAdaptiveInfo()`
 * would add material3-adaptive and androidx.window to every app that uses the SDK for this one threshold.
 */
private val COMPACT_WINDOW_HEIGHT = 480.dp

/** With the keyboard up and less than this, the chat drops its header: the composer and a little of the thread need it. */
private val COMPACT_CHAT_HEIGHT = 240.dp

/** Shorter than this, the header and the composer don't both fit, keyboard or not. */
private val MIN_CHAT_WITH_HEADER = 160.dp

@Composable
private fun windowHeightDp(): androidx.compose.ui.unit.Dp =
    with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }

/** What the header takes when it shows (one 64 dp row and its padding). */
private val HEADER_ROOM = 72.dp
private const val CHROME_MAX_FONT_SCALE = 1.5f

@Composable
private fun CappedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= CHROME_MAX_FONT_SCALE) return content()
    CompositionLocalProvider(LocalDensity provides Density(density.density, CHROME_MAX_FONT_SCALE), content = content)
}

/**
 * Notices are content, not bar, at large text (Android's 150 % font size and up, where the chrome stops scaling) and at
 * a compact window height, as iOS moves them at accessibility sizes and a compact vertical size class.
 */
internal fun noticesMoveIntoThread(fontScale: Float, windowHeight: androidx.compose.ui.unit.Dp): Boolean =
    fontScale >= CHROME_MAX_FONT_SCALE || windowHeight < COMPACT_WINDOW_HEIGHT

/** True when there is a notice to show: a setup problem (debug builds), the SDK off, or the chat out of reach. */
@Composable
private fun hasNotices(state: ConversationState, problem: dev.pingfront.ConfigurationProblem?): Boolean =
    state.isOffline || (problem != null && (problem.disablesSdk || (LocalChatPreview.current.debug ?: DebugGateAccess.isDebug())))

/**
 * Setup problems, then the offline or unreachable notice: under the header, or a row of the content ([inThread]). Out
 * of reach while online stays distinct from offline: saying "offline" would send the person to their Wi-Fi.
 */
@Composable
private fun Notices(state: ConversationState, problem: dev.pingfront.ConfigurationProblem?, inThread: Boolean) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(if (inThread) 8.dp else 0.dp)) {
        problem?.let {
            if (LocalChatPreview.current.debug ?: DebugGateAccess.isDebug()) {
                Banner(
                    it.message,
                    R.drawable.pf_ic_error,
                    problem = true,
                    title = stringResource(R.string.pf_setup_problem),
                    footnote = stringResource(R.string.pf_debug_only),
                    inThread = inThread,
                )
            } else if (it.disablesSdk) {
                Banner(stringResource(R.string.pf_chat_unavailable), icon = R.drawable.pf_ic_info, problem = false, inThread = inThread)
            }
        }
        if (state.isOffline) {
            Banner(
                stringResource(if (state.isServerUnreachable) R.string.pf_unreachable else R.string.pf_offline),
                // Not the Wi-Fi glyph when the device has a network: the service is out of reach.
                icon = if (state.isServerUnreachable) R.drawable.pf_ic_cloud_off else R.drawable.pf_ic_wifi_off,
                problem = false,
                inThread = inThread,
            )
        }
    }
}

/**
 * The loading and failure states, centred; with the notices as content, those come first in a scrolling column. One
 * layout either way, so the state below keeps its place in the composition when the notices come and go.
 */
@Composable
private fun NoticesAbove(
    inThread: Boolean,
    state: ConversationState,
    problem: dev.pingfront.ConfigurationProblem?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shows = inThread && hasNotices(state, problem)
    Column(
        modifier.fillMaxSize().then(
            if (shows) Modifier.verticalScroll(rememberScrollState()).padding(horizontal = LocalChatTheme.current.gutter, vertical = 12.dp) else Modifier,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (shows) Arrangement.spacedBy(24.dp) else Arrangement.Center,
    ) {
        if (shows) Notices(state, problem, inThread = true)
        content()
    }
}

/** What fills the space between the header and the composer. */
internal enum class ChatBody { Thread, Loading, LoadFailed, Welcome }

/**
 * Offline is never a failure screen: the offline banner says it once, and the welcome (or the cached thread) stays
 * usable underneath, with messages queued until the connection is back. Only a server that answered with a failure
 * while the device is online gets the centered "Couldn't load" state with its retry.
 */
internal fun chatBody(state: ConversationState): ChatBody {
    val phase = state.phase
    return when {
        !state.isEmpty -> ChatBody.Thread
        phase is ConversationPhase.Loading -> ChatBody.Loading
        // Refused because the person can't send (the app paused, say): not a failure to retry. The welcome shows, with
        // no promise of a reply, and the notice replaces the composer.
        phase is ConversationPhase.Failed && !phase.offline && !state.isOffline && state.policy.canSend -> ChatBody.LoadFailed
        else -> ChatBody.Welcome
    }
}

/**
 * The header row shows when it has something to say: who you talk to (a thread), the close button, or the draft
 * marker. An embedded chat on its welcome screen has none of them, and an empty 64 dp row read as a gap (the welcome
 * shows the large face itself).
 */
internal fun showsHeader(state: ConversationState, onClose: (() -> Unit)?, look: dev.pingfront.look.ResolvedLook): Boolean =
    !state.isEmpty || onClose != null || look.isDraft

/** The thread could not be loaded although the device is online: a calm centered state with one action. */
@Composable
private fun LoadFailed(retry: () -> Unit) {
    val theme = LocalChatTheme.current
    Column(
        Modifier.widthIn(max = 360.dp).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(R.drawable.pf_ic_error),
            contentDescription = null,
            tint = theme.muted,
            modifier = Modifier.size(32.dp),
        )
        Text(
            stringResource(R.string.pf_couldnt_load),
            style = MaterialTheme.typography.titleMedium,
            color = theme.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        OutlinedButton(onClick = retry, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.pf_try_again))
        }
    }
}
