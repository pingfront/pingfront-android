package dev.pingfront.revenuecat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.ui.revenuecatui.Paywall
import com.revenuecat.purchases.ui.revenuecatui.PaywallListener
import com.revenuecat.purchases.ui.revenuecatui.PaywallOptions
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/** Hosts RevenueCat's `Paywall` for one offering and reports the outcome to the waiting offer card. */
internal class RevenueCatPaywallActivity : ComponentActivity() {
    private var outcome: OfferOutcome = OfferOutcome.Cancelled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val offeringId = intent.getStringExtra(EXTRA_OFFERING) ?: return finish()
        setContent {
            var offering by remember { mutableStateOf<Offering?>(null) }
            LaunchedEffect(offeringId) {
                offering = try {
                    Purchases.sharedInstance.awaitOfferings().all[offeringId]
                } catch (_: PurchasesException) {
                    null
                }
                if (offering == null) {
                    outcome = OfferOutcome.Failed(OfferFailure.ProductNotFound)
                    finish()
                }
            }
            offering?.let { current ->
                val options = remember(current) {
                    PaywallOptions.Builder(dismissRequest = { finish() })
                        .setOffering(current)
                        .setListener(
                            object : PaywallListener {
                                override fun onPurchaseCompleted(customerInfo: CustomerInfo, storeTransaction: StoreTransaction) {
                                    outcome = OfferOutcome.Purchased
                                }

                                override fun onPurchaseCancelled() {
                                    outcome = OfferOutcome.Cancelled
                                }

                                override fun onPurchaseError(error: PurchasesError) {
                                    outcome = OfferOutcome.Failed(OfferFailure.StoreError)
                                }
                            },
                        )
                        .build()
                }
                Paywall(options)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) intent.getStringExtra(EXTRA_REQUEST)?.let { pending.remove(it)?.complete(outcome) }
        super.onDestroy()
    }

    internal companion object {
        private const val EXTRA_OFFERING = "dev.pingfront.revenuecat.offering"
        private const val EXTRA_REQUEST = "dev.pingfront.revenuecat.request"
        private val pending = ConcurrentHashMap<String, CompletableDeferred<OfferOutcome>>()

        /** Opens the paywall over [activity] and waits until it closes. */
        suspend fun present(activity: Activity, offeringId: String): OfferOutcome {
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<OfferOutcome>().also { pending[id] = it }
            activity.startActivity(
                Intent(activity, RevenueCatPaywallActivity::class.java).putExtra(EXTRA_OFFERING, offeringId).putExtra(EXTRA_REQUEST, id),
            )
            return try {
                done.await()
            } finally {
                pending.remove(id)
            }
        }
    }
}
