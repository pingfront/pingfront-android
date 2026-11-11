package dev.pingfront.billing

import android.app.Activity
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The review card's Play path, as far as it runs without a device: Play's own fake manager stands in for the store.
 * Whether Play actually shows the sheet is Play's decision, and only a real install from Play shows it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayReviewTest {
    @Test fun theReviewFlowRunsOnTheHostActivity() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var created = 0
        val review = PlayReview({
            created += 1
            FakeReviewManager(activity)
        }, activity = { activity })
        val asked = async { review.requestReview() }
        // Play completes its tasks on the main looper.
        repeat(3) {
            runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(asked.await(), "Play's flow completed")
        assertTrue(created == 1)
    }

    /** No activity in front: nothing is asked of Play, and the card falls back to the store listing. */
    @Test fun withoutAnActivityPlayIsNotAsked() = runTest {
        var created = 0
        val review = PlayReview({
            created += 1
            error("not created")
        }, activity = { null })
        assertFalse(review.requestReview())
        assertTrue(created == 0)
    }
}
