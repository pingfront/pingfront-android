package dev.pingfront.superwall

import com.superwall.sdk.Superwall as SuperwallSdk
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallPresentationHandler
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.register
import com.superwall.sdk.store.abstractions.product.receipt.LatestPeriodType
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/** The real client over `com.superwall.sdk` (superwall-android 2). The app configures Superwall itself. */
internal class SuperwallSdkClient : SuperwallClient {
    override val isConfigured: Boolean get() = SuperwallSdk.initialized

    override suspend fun register(placement: String): SwPresentation = suspendCancellableCoroutine { continuation ->
        fun finish(result: SwPresentation) {
            if (continuation.isActive) continuation.resume(result)
        }
        val handler = PaywallPresentationHandler().apply {
            onDismiss { _, result ->
                finish(
                    when (result) {
                        is PaywallResult.Purchased -> SwPresentation.Purchased
                        is PaywallResult.Restored -> SwPresentation.Restored
                        else -> SwPresentation.Declined
                    },
                )
            }
            onSkip { finish(SwPresentation.Skipped) }
            onError { finish(SwPresentation.Failed) }
        }
        SuperwallSdk.instance.register(placement, null, handler)
    }

    override fun statusUpdates(): Flow<SwStatus> = SuperwallSdk.instance.subscriptionStatus.map { status ->
        when (status) {
            is SubscriptionStatus.Active -> SwStatus(
                status.entitlements.filter { it.isActive }.map { e ->
                    SwEntitlement(
                        productId = e.latestProductId ?: e.productIds.firstOrNull(),
                        lifetime = e.isLifetime == true,
                        grace = e.isInGracePeriod == true,
                        trial = e.offerType == LatestPeriodType.TRIAL,
                        willRenew = e.willRenew,
                        expiresAt = e.expiresAt?.toInstant(),
                    )
                },
            )
            is SubscriptionStatus.Inactive -> SwStatus(emptyList())
            else -> SwStatus(null)
        }
    }

    override fun setUserAttributes(attributes: Map<String, Any>) = SuperwallSdk.instance.setUserAttributes(attributes)
}
