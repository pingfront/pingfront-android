package dev.pingfront.adapty

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.adapty.errors.AdaptyError
import com.adapty.models.AdaptyFlow
import com.adapty.models.AdaptyPaywallProduct
import com.adapty.models.AdaptyProfile
import com.adapty.models.AdaptyPurchaseParameters
import com.adapty.models.AdaptyPurchaseResult
import com.adapty.ui.AdaptyUI
import com.adapty.ui.listeners.AdaptyFlowEventListener
import com.adapty.utils.AdaptyResult
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/** Hosts an AdaptyUI flow for one placement and reports the outcome to the waiting offer card. */
internal class AdaptyFlowActivity : ComponentActivity() {
    private var outcome: OfferOutcome = OfferOutcome.Cancelled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent.getStringExtra(EXTRA_REQUEST)
        val loaded = request?.let { flows.remove(it) } ?: return finish()
        AdaptyUI.getFlowConfiguration(
            loaded.first,
            java.util.Locale.getDefault().toLanguageTag(),
            com.adapty.utils.TimeInterval.seconds(LOAD_SECONDS),
        ) { result ->
            val configuration = (result as? AdaptyResult.Success)?.value
            if (configuration == null) {
                outcome = OfferOutcome.Failed(OfferFailure.StoreError)
                finish()
                return@getFlowConfiguration
            }
            setContentView(AdaptyUI.getFlowView(this, configuration, loaded.second, Listener()))
        }
    }

    override fun onDestroy() {
        if (isFinishing) intent.getStringExtra(EXTRA_REQUEST)?.let { pending.remove(it)?.complete(outcome) }
        super.onDestroy()
    }

    /** Maps AdaptyUI's events to the card's outcome; everything else keeps Adapty's default behavior. */
    private inner class Listener : AdaptyFlowEventListener {
        override fun onActionPerformed(action: AdaptyUI.Action, context: Context) {
            if (action is AdaptyUI.Action.Close) finish()
        }

        override fun onBackPressed(context: Context): Boolean {
            finish()
            return true
        }

        override fun onAwaitingPurchaseParams(
            product: AdaptyPaywallProduct,
            context: Context,
            onPurchaseParamsReceived: AdaptyFlowEventListener.PurchaseParamsCallback,
        ): AdaptyFlowEventListener.PurchaseParamsCallback.IveBeenInvoked {
            onPurchaseParamsReceived(AdaptyPurchaseParameters.Empty)
            return AdaptyFlowEventListener.PurchaseParamsCallback.IveBeenInvoked
        }

        override fun onFinishWebPaymentNavigation(product: AdaptyPaywallProduct?, error: AdaptyError?, context: Context) = Unit

        override fun onLoadingProductsFailure(error: AdaptyError, context: Context): Boolean = false

        override fun onFlowClosed() = Unit

        override fun onFlowShown(context: Context) = Unit

        override fun onProductSelected(product: AdaptyPaywallProduct, context: Context) = Unit

        override fun onPurchaseFailure(error: AdaptyError, product: AdaptyPaywallProduct, context: Context) {
            outcome = OfferOutcome.Failed(OfferFailure.StoreError)
        }

        override fun onPurchaseStarted(product: AdaptyPaywallProduct, context: Context) = Unit

        override fun onPurchaseFinished(purchaseResult: AdaptyPurchaseResult, product: AdaptyPaywallProduct, context: Context) {
            outcome = when (purchaseResult) {
                is AdaptyPurchaseResult.Success -> OfferOutcome.Purchased
                is AdaptyPurchaseResult.Pending -> OfferOutcome.Pending
                else -> OfferOutcome.Cancelled
            }
            if (purchaseResult !is AdaptyPurchaseResult.UserCanceled) finish()
        }

        override fun onError(error: AdaptyError, context: Context) = Unit

        override fun onRestoreFailure(error: AdaptyError, context: Context) = Unit

        override fun onRestoreStarted(context: Context) = Unit

        override fun onRestoreSuccess(profile: AdaptyProfile, context: Context) = Unit

        override fun onAnalyticEvent(name: String, params: Map<String, Any?>, context: Context) = Unit

        override fun onShowAppRate(context: Context) = Unit

        // The adapter never asks for runtime permissions from a paywall.
        override fun onShowRequestPermission(
            permission: String?,
            customArgs: Map<String, String>?,
            callback: AdaptyFlowEventListener.PermissionCallback,
            context: Context,
        ) = callback.invoke(false, permission.orEmpty())
    }

    internal companion object {
        private const val EXTRA_REQUEST = "dev.pingfront.adapty.request"
        private const val LOAD_SECONDS = 5
        private val pending = ConcurrentHashMap<String, CompletableDeferred<OfferOutcome>>()
        private val flows = ConcurrentHashMap<String, Pair<AdaptyFlow, List<AdaptyPaywallProduct>>>()

        /** Opens the flow over [activity] and waits until it closes. */
        suspend fun present(activity: Activity, flow: AdaptyFlow, products: List<AdaptyPaywallProduct>): OfferOutcome {
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<OfferOutcome>().also { pending[id] = it }
            flows[id] = flow to products
            activity.startActivity(Intent(activity, AdaptyFlowActivity::class.java).putExtra(EXTRA_REQUEST, id))
            return try {
                done.await()
            } finally {
                pending.remove(id)
                flows.remove(id)
            }
        }
    }
}
