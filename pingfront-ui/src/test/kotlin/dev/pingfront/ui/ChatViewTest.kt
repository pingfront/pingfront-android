package dev.pingfront.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import dev.pingfront.PingFrontInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** N6: the View-based embedding, `PingFrontChatView`, in hosts that are not Compose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatViewTest {
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /**
     * Robolectric leaves the activity's window at GONE (`View.getWindowVisibility`), so the framework never reports the
     * view shown; on a device `dispatchAttachedToWindow` calls `onVisibilityAggregated(true)` for a visible window.
     */
    private fun PingFrontChatView.shownByTheWindow() {
        onVisibilityAggregated(true)
        idle()
    }

    private fun chatView(activity: Activity, instance: PingFrontInstance) = PingFrontChatView(activity).apply {
        this.instance = instance
        activity.setContentView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The same presenter counting as the composable (N3): on screen while started, attached and shown. */
    @Test fun countsAsVisibleOnlyWhileStartedAttachedAndShown() {
        val instance = PingFrontInstance.detached()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = chatView(controller.get(), instance)
        idle()
        assertFalse(instance.isChatVisible.value, "not on screen while the window is not visible")
        view.shownByTheWindow()
        assertTrue(instance.isChatVisible.value, "visible while on screen")
        assertTrue(instance.embeddedChatVisible.value, "counts as an embedded chat")

        // What the framework reports for `visibility = GONE` (or a hidden ancestor) in a visible window.
        view.visibility = View.GONE
        view.onVisibilityAggregated(false)
        idle()
        assertFalse(instance.isChatVisible.value, "a hidden view is not on screen")
        view.visibility = View.VISIBLE
        view.shownByTheWindow()
        assertTrue(instance.isChatVisible.value)

        controller.pause().stop()
        idle()
        assertFalse(instance.isChatVisible.value, "not visible after Home")
        controller.start().resume()
        idle()
        assertTrue(instance.isChatVisible.value, "visible again in front")

        (view.parent as ViewGroup).removeView(view)
        idle()
        assertFalse(instance.isChatVisible.value, "not visible once out of the window")
        controller.pause().stop().destroy()
    }

    /** A window without lifecycle owners (`FlutterActivity` is a plain `Activity`): the view brings its own. */
    @Test fun composesInAWindowWithoutOwners() {
        val instance = PingFrontInstance.detached()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val registry = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                    input: I,
                    options: androidx.core.app.ActivityOptionsCompat?,
                ) = Unit
            }
        }
        val view = PingFrontChatView(controller.get()).apply {
            this.instance = instance
            activityResultRegistryOwner = registry
        }
        controller.get().setContentView(view)
        view.shownByTheWindow()
        val compose = view.getChildAt(0) as ViewGroup
        assertTrue(compose.childCount > 0, "the composition was created")
        assertTrue(instance.isChatVisible.value)
        (view.parent as ViewGroup).removeView(view)
        idle()
        assertFalse(instance.isChatVisible.value)
        // Attached again: a fresh composition with a fresh recomposer.
        controller.get().setContentView(view)
        view.shownByTheWindow()
        assertTrue(instance.isChatVisible.value)
        controller.pause().stop().destroy()
    }

    /** A host may lay the view out before it is in a window (React Native's `<Modal>`): measuring then is safe. */
    @Test fun measuresBeforeItIsAttached() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = PingFrontChatView(controller.get()).apply { instance = PingFrontInstance.detached() }
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 400, 800)
        assertEquals(400, view.measuredWidth)
        val parent = FrameLayout(controller.get())
        controller.get().setContentView(parent)
        parent.addView(view, FrameLayout.LayoutParams(400, 800))
        idle()
        assertEquals(400, view.getChildAt(0).width, "the chat got its first pass once attached")
        controller.pause().stop().destroy()
    }
}
