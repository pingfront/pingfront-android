package dev.pingfront.billing

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.InAppMessageResult.InAppMessageResponseCode
import dev.pingfront.PaymentFix
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Play's in-app message runs on the SDK's injected main dispatcher, never a hard-coded `Dispatchers.Main`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayPaymentFixerTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun theMessageIsShownOnTheInjectedMainDispatcher() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        var ranOn: ContinuationInterceptor? = null
        val fixer = PlayPaymentFixer(
            show = {
                ranOn = currentCoroutineContext()[ContinuationInterceptor]
                InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED
            },
            main = main,
            context = ApplicationProvider.getApplicationContext(),
            activity = { activity },
        )
        assertEquals(PaymentFix.Updated, fixer.showPaymentFix())
        assertSame(main, ranOn)
    }

    @Test fun withoutAnActivityNothingIsShown() = runTest {
        var called = false
        val fixer = PlayPaymentFixer({
            called = true
            0
        }, StandardTestDispatcher(testScheduler), ApplicationProvider.getApplicationContext()) { null }
        assertEquals(PaymentFix.NothingToShow, fixer.showPaymentFix())
        assertEquals(false, called)
    }
}
