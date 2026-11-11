package dev.pingfront.adapty

import android.app.Activity
import com.adapty.Adapty as AdaptySdk
import com.adapty.models.AdaptyFlow
import com.adapty.models.AdaptyPaywallProduct
import com.adapty.models.AdaptyPeriodUnit
import com.adapty.models.AdaptyProductDiscountPhase
import com.adapty.models.AdaptyProductSubscriptionPeriod
import com.adapty.models.AdaptyProfile
import com.adapty.models.AdaptyProfileParameters
import com.adapty.models.AdaptyPurchaseResult
import com.adapty.utils.AdaptyResult
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** The real client over `com.adapty` (Adapty Android 4). The app activates Adapty itself. */
internal class AdaptySdkClient : AdaptyClient {
    private val flows = java.util.concurrent.ConcurrentHashMap<String, Pair<AdaptyFlow, List<AdaptyPaywallProduct>>>()

    override val isConfigured: Boolean get() = AdaptySdk.isActivated

    private suspend fun <T> await(call: (com.adapty.utils.ResultCallback<T>) -> Unit): T? = suspendCancellableCoroutine { continuation ->
        call { result -> if (continuation.isActive) continuation.resume((result as? AdaptyResult.Success<T>)?.value) }
    }

    /** The placement's flow and its products (kept for purchases and presentation). */
    suspend fun load(placement: String): Pair<AdaptyFlow, List<AdaptyPaywallProduct>>? {
        val flow = await<AdaptyFlow> { AdaptySdk.getFlow(placement, callback = it) } ?: return null
        val products = await<List<AdaptyPaywallProduct>> { AdaptySdk.getPaywallProducts(flow, it) }.orEmpty()
        return (flow to products).also { flows[placement] = it }
    }

    override suspend fun flow(placement: String): AdFlow? {
        val (flow, products) = load(placement) ?: return null
        val kind = flow.remoteConfigs.firstNotNullOfOrNull { it.dataMap["pingfront_kind"] as? String }
        return AdFlow(placement, flow.name, kind, flow.hasViewConfiguration, products.map(::product))
    }

    override suspend fun profile(): AdProfile? = await<AdaptyProfile> { AdaptySdk.getProfile(it) }?.let(::profile)

    override fun profileUpdates(): Flow<AdProfile> = callbackFlow {
        AdaptySdk.setOnProfileUpdatedListener { trySend(profile(it)) }
        awaitClose { AdaptySdk.setOnProfileUpdatedListener(null) }
    }

    override fun setCustomAttribute(key: String, value: String) {
        AdaptySdk.updateProfile(AdaptyProfileParameters.Builder().withCustomAttribute(key, value).build()) { }
    }

    override suspend fun purchase(activity: Activity, placement: String, product: AdProduct): OfferOutcome {
        val match = flows[placement]?.second?.firstOrNull {
            it.vendorProductId == product.id && it.subscriptionDetails?.basePlanId == product.basePlanId && it.subscriptionDetails?.offerId == product.offerId
        } ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        return suspendCancellableCoroutine { continuation ->
            AdaptySdk.makePurchase(activity, match) { result ->
                if (!continuation.isActive) return@makePurchase
                continuation.resume(
                    when (result) {
                        is AdaptyResult.Success -> when (result.value) {
                            is AdaptyPurchaseResult.Success -> OfferOutcome.Purchased
                            is AdaptyPurchaseResult.Pending -> OfferOutcome.Pending
                            else -> OfferOutcome.Cancelled
                        }
                        else -> OfferOutcome.Failed(OfferFailure.StoreError)
                    },
                )
            }
        }
    }

    override suspend fun present(activity: Activity, placement: String): OfferOutcome {
        val loaded = flows[placement] ?: load(placement) ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        return AdaptyFlowActivity.present(activity, loaded.first, loaded.second)
    }

    private fun product(p: AdaptyPaywallProduct): AdProduct {
        val details = p.subscriptionDetails
        val trial = details?.introductoryOfferPhases?.firstOrNull { it.paymentMode == AdaptyProductDiscountPhase.PaymentMode.FREE_TRIAL }
        return AdProduct(
            id = p.vendorProductId,
            price = p.price.localizedString,
            period = details?.subscriptionPeriod?.let(::iso),
            trial = trial?.subscriptionPeriod?.let(::iso),
            basePlanId = details?.basePlanId,
            offerId = details?.offerId,
        )
    }

    private fun iso(period: AdaptyProductSubscriptionPeriod): String? = when (period.unit) {
        AdaptyPeriodUnit.DAY -> "P${period.numberOfUnits}D"
        AdaptyPeriodUnit.WEEK -> "P${period.numberOfUnits}W"
        AdaptyPeriodUnit.MONTH -> "P${period.numberOfUnits}M"
        AdaptyPeriodUnit.YEAR -> "P${period.numberOfUnits}Y"
        else -> null
    }

    private fun profile(p: AdaptyProfile) = AdProfile(
        p.accessLevels.values.map { level ->
            AdAccessLevel(
                productId = level.vendorProductId,
                active = level.isActive,
                lifetime = level.isLifetime,
                grace = level.isInGracePeriod,
                trial = level.activeIntroductoryOfferType == "free_trial",
                willRenew = level.willRenew,
                expiresAt = level.expiresAt?.let(::instant),
            )
        },
    )

    private fun instant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}
