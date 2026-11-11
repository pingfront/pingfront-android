package dev.pingfront.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pingfront.PingFront
import dev.pingfront.PingFrontInstance
import dev.pingfront.look.ChatTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The embedded chat as an Android `View`, for layouts that are not Compose: an XML layout, a `Fragment`'s views, a
 * React Native view manager or a Flutter `PlatformView`. It shows the same chat as [PingFrontChat] and owns its
 * composition, so the host needs neither the Compose compiler nor its own lifecycle owners or recomposer.
 *
 * ```kotlin
 * val chat = PingFrontChatView(context).apply { topic = ChatTopic.Billing }
 * container.addView(chat, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
 * ```
 *
 * ```xml
 * <dev.pingfront.ui.PingFrontChatView
 *     android:id="@+id/chat"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * - **Composition.** Disposed when the view leaves the window, or when a pooling container such as a `RecyclerView`
 *   releases it ([ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool], Compose's default for a
 *   view in a View hierarchy). A host that takes the view out of the window while it should keep its state (Flutter
 *   does for a platform view that scrolls off screen) sets another strategy with [setViewCompositionStrategy] and calls
 *   [disposeComposition] when it is done.
 * - **Owners.** The window's lifecycle and saved-state owners are used when it has them (any `ComponentActivity`,
 *   `Fragment` or `ComponentDialog`). In a window without them (`FlutterActivity`) the view keeps its own, and its own
 *   recomposer, following the activity's lifecycle. Give the view an id, as any `ComposeView`, for `rememberSaveable`
 *   state (a draft, an open photo) to survive a configuration change.
 * - **Visibility.** The chat counts as on screen (`PingFront.isChatVisible`, read receipts, the unread count) while
 *   the host's lifecycle is started, the view is attached, and neither it nor an ancestor is hidden
 *   (`View.onVisibilityAggregated`). A view that is visible but scrolled out of its parent (a pager page) still counts.
 * - **Insets.** See [insets].
 */
public class PingFrontChatView @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** How the chat keeps its header and composer clear of the system bars, the display cutout and the keyboard. */
    public sealed interface Insets {
        /**
         * The default, for windows that draw edge to edge: the chat pads for the part of
         * `WindowInsets.safeDrawing` (the system bars, the display cutout and the keyboard) that overlaps this view.
         * The space your layout already leaves between the view and the window's edges (a toolbar above it, a bottom
         * navigation bar below it) is not padded again, and the composer rides on the keyboard as it animates.
         */
        public data object SafeDrawing : Insets

        /** No padding: the window is not edge to edge, or your layout keeps the view clear of the bars and keyboard. */
        public data object None : Insets

        /**
         * Exactly this padding, in pixels, which your own layout computed (for example the safe-area padding Flutter
         * left for a platform view). The keyboard is then yours too: lay the view out above it.
         */
        public class Padding(public val left: Int, public val top: Int, public val right: Int, public val bottom: Int) : Insets {
            override fun equals(other: Any?): Boolean =
                other is Padding && other.left == left && other.top == top && other.right == right && other.bottom == bottom

            override fun hashCode(): Int = java.util.Objects.hash(left, top, right, bottom)

            override fun toString(): String = "Padding(left=$left, top=$top, right=$right, bottom=$bottom)"
        }
    }

    /** The topic the chat opens with; its prefill is put into the composer once. A different topic starts afresh. */
    public var topic: ChatTopic? by mutableStateOf(null)

    /** Called by the header's close button; `null` (the default) shows no close button, as for [PingFrontChat]. */
    public var onClose: (() -> Unit)? by mutableStateOf(null)

    /** How the chat keeps clear of the system bars and the keyboard. Defaults to [Insets.SafeDrawing]. */
    public var insets: Insets by mutableStateOf(Insets.SafeDrawing)

    /**
     * Launches the photo picker, the camera and the permission prompt. `null` (the default) uses the activity's own
     * registry (a `ComponentActivity`). An activity without one (`FlutterActivity`) passes a registry that routes
     * results back to the view.
     */
    public var activityResultRegistryOwner: ActivityResultRegistryOwner? by mutableStateOf(null)

    /** The instance the chat shows; tests pass a detached one. */
    internal var instance: PingFrontInstance? = null

    private val owner = ChatOwner()

    /** The space the host's layout leaves around this view in its window, which [Insets.SafeDrawing] doesn't pad. */
    private var edges by mutableStateOf(PaddingValues.Absolute())
    private val location = IntArray(2)

    // A host can move the view with `layout()` outside a traversal (React Native's Fabric does), so no global-layout
    // callback follows: the edges are read again in onLayout and before each frame is drawn (one comparison when
    // nothing moved).
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        updateEdges()
        true
    }
    private var hostLifecycle: Lifecycle? = null
    private var shown = false
    private val hostObserver = LifecycleEventObserver { _, _ -> updateLifecycle() }

    /** The recomposer of a window that has none (no lifecycle owner); lives as long as the composition. */
    private var ownRecomposer: OwnRecomposer? = null

    private val compose = ComposeView(context).apply {
        isSaveEnabled = false
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setViewTreeLifecycleOwner(owner)
        setContent { ChatContent() }
    }

    init {
        compose.addOnAttachStateChangeListener(
            object : OnAttachStateChangeListener {
                // A host may lay this view out before it is in a window (React Native mounts a `<Modal>`'s content
                // before its dialog shows) and not lay it out again. The composition can only be created once
                // attached (onMeasure), so the chat gets its first pass here, at the size the host gave this view.
                override fun onViewAttachedToWindow(view: View) {
                    if (width == 0 && height == 0) return
                    view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                }

                override fun onViewDetachedFromWindow(view: View): Unit = Unit
            },
        )
        // An id from XML (`android:id`) is set by View's constructor, not through setId.
        compose.id = id
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Sets when the composition is disposed (the default is [ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool]). */
    public fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) {
        compose.setViewCompositionStrategy(strategy)
    }

    /** Disposes the composition now, for a host whose [ViewCompositionStrategy] keeps it after the view leaves the window. */
    public fun disposeComposition() {
        compose.disposeComposition()
    }

    override fun setId(id: Int) {
        super.setId(id)
        // The composition saves its state under its view's id (`rememberSaveable`), so it is this view's id.
        compose.id = id
    }

    @Composable
    private fun ChatContent() {
        DisposableEffect(Unit) { onDispose { compositionDisposed() } }
        val results = activityResultRegistryOwner
        if (results == null) {
            Chat()
        } else {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides results) { Chat() }
        }
    }

    @Composable
    private fun Chat() {
        val current = topic
        val (modifier, windowInsets) = when (val mode = insets) {
            Insets.SafeDrawing -> Modifier.fillMaxSize().consumeWindowInsets(edges) to WindowInsets.safeDrawing
            Insets.None -> Modifier.fillMaxSize() to WindowInsets(0, 0, 0, 0)
            is Insets.Padding -> Modifier.fillMaxSize() to WindowInsets(mode.left, mode.top, mode.right, mode.bottom)
        }
        // A new composition per topic, so a different topic inserts its prefill as a fresh presentation would.
        key(current) {
            PingFrontLanguage {
                ChatScreen(instance ?: PingFront.instance, current, onClose, modifier, windowInsets, embedded = true)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isAttachedToWindow) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Measuring the chat creates its composition, which needs the window: before it is attached this view takes
        // the size it is offered, and the chat is measured once attached.
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        // Runs before the chat's own attach, which creates the composition: the owners and recomposer are set first.
        super.onAttachedToWindow()
        val tree = findViewTreeLifecycleOwner()
        hostLifecycle = (tree ?: context.activity() as? LifecycleOwner)?.lifecycle
        hostLifecycle?.addObserver(hostObserver)
        compose.setViewTreeSavedStateRegistryOwner(if (findViewTreeSavedStateRegistryOwner() == null) owner else null)
        // Compose takes its recomposer from the window, which needs a lifecycle owner there; a window without one
        // (`FlutterActivity`) gets this view's own recomposer, unless an ancestor already provides a composition.
        if (findViewTreeCompositionContext() == null && rootView.findViewTreeLifecycleOwner() == null) {
            val own = ownRecomposer ?: OwnRecomposer().also { ownRecomposer = it }
            compose.setParentCompositionContext(own.recomposer)
        } else if (ownRecomposer == null) {
            compose.setParentCompositionContext(null)
        }
        viewTreeObserver.addOnPreDrawListener(preDraw)
        updateLifecycle()
        updateEdges()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateEdges()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        hostLifecycle?.removeObserver(hostObserver)
        hostLifecycle = null
        updateLifecycle()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        updateLifecycle()
    }

    private fun updateEdges() {
        if (!isAttachedToWindow || insets != Insets.SafeDrawing) return
        getLocationInWindow(location)
        val density = resources.displayMetrics.density
        fun dp(px: Int) = (px.coerceAtLeast(0) / density).dp
        // Absolute: a side cutout or a 3-button navigation bar in landscape is on a physical side, whatever the layout
        // direction; so is the space a list beside this view (list-detail) or a drawer leaves.
        val next = PaddingValues.Absolute(
            left = dp(location[0]),
            top = dp(location[1]),
            right = dp(rootView.width - location[0] - width),
            bottom = dp(rootView.height - location[1] - height),
        )
        if (next != edges) edges = next
    }

    /**
     * The chat's lifecycle: the host's while this view is attached and shown, held at CREATED otherwise. The chat
     * reports itself on screen only while it is STARTED (the same rule as [PingFrontChat] in a composition).
     */
    private fun updateLifecycle() {
        val host = if (isAttachedToWindow) hostLifecycle?.currentState ?: Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        val target = when {
            host == Lifecycle.State.DESTROYED || host == Lifecycle.State.INITIALIZED -> Lifecycle.State.CREATED
            shown -> host
            else -> minOf(host, Lifecycle.State.CREATED)
        }
        if (owner.registry.currentState != target) owner.registry.currentState = target
    }

    private fun compositionDisposed() {
        val own = ownRecomposer ?: return
        ownRecomposer = null
        // Ended after the disposal finishes; a later composition (the view attached again) gets a new recomposer.
        android.os.Handler(android.os.Looper.getMainLooper()).post { own.cancel() }
    }

    /** Lifecycle and saved state for the chat; the saved state is used only when the window has none. */
    private class ChatOwner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    /** A recomposer whose frame clock pauses while the chat is not started, as the window's does while stopped. */
    private inner class OwnRecomposer {
        private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.CurrentThread)
        val recomposer = Recomposer(scope.coroutineContext)
        private val clock = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> recomposer.resumeCompositionFrameClock()
                Lifecycle.Event.ON_STOP -> recomposer.pauseCompositionFrameClock()
                else -> Unit
            }
        }

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            if (!owner.registry.currentState.isAtLeast(Lifecycle.State.STARTED)) recomposer.pauseCompositionFrameClock()
            owner.registry.addObserver(clock)
        }

        fun cancel() {
            owner.registry.removeObserver(clock)
            recomposer.cancel()
            scope.cancel()
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
