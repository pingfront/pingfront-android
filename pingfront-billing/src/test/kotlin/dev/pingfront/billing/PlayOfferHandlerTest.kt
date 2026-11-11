package dev.pingfront.billing

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferRequest
import dev.pingfront.model.StoreOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PlayOfferHandlerTest {
    /**
     * Found on the emulator (1 Nov 2026): with no Play account, Play could not connect, the product query came back
     * empty and the tap reported "product not found", so the card showed nothing. Not connecting is "unavailable".
     */
    @Test fun aPlayThatCannotConnectIsUnavailableNotAMissingProduct() = runTest {
        var queried = false
        val play = object : PlayStore {
            override suspend fun connected() = false

            override suspend fun details(ids: List<String>): List<ProductDetails> {
                queried = true
                return emptyList()
            }

            override suspend fun launch(activity: Activity, details: ProductDetails, offer: StoreOffer?) = error("not launched")
        }
        val handler = PlayOfferHandler(play, listOf("pro.yearly"), activity = { null })
        val outcome = handler.handle(dev.pingfront.internal.debug.Fixtures.offerRequest("pro.yearly", null, "card_1", "msg_1"))
        assertEquals(OfferOutcome.Failed(OfferFailure.BillingUnavailable), outcome)
        assertEquals(false, queried)
    }

    /** A promotional card's named Play offer reaches the purchase sheet, for the product the card names. */
    @Test fun aCardsNamedOfferIsWhatThePurchaseSheetBuys() = runTest {
        // ProductDetails has no public constructor; Play builds it from this JSON.
        val details = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            .newInstance("""{"productId":"pro.yearly","type":"subs","title":"Pro","name":"Pro","description":"","subscriptionOfferDetails":[]}""")
        var asked: List<String>? = null
        var bought: StoreOffer? = null
        val play = object : PlayStore {
            override suspend fun connected() = true

            override suspend fun details(ids: List<String>): List<ProductDetails> {
                asked = ids
                return listOf(details)
            }

            override suspend fun launch(activity: Activity, details: ProductDetails, offer: StoreOffer?): OfferOutcome {
                bought = offer
                return OfferOutcome.Purchased
            }
        }
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java).get()
        val offer = dev.pingfront.internal.debug.Fixtures.storeOffer(StoreOffer.Kind.Winback, "comeback40", "pro.yearly", "yearly", "comeback40")
        val handler = PlayOfferHandler(play, listOf("pro.monthly"), activity = { activity })
        val outcome = handler.handle(dev.pingfront.internal.debug.Fixtures.offerRequest("winback", null, "card_1", "msg_1", offer))
        assertEquals(OfferOutcome.Purchased, outcome)
        assertEquals(listOf("pro.yearly"), asked)
        assertEquals(offer, bought)
    }
}
