package dev.pingfront.revenuecat

import android.app.Activity
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.PeriodType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.GoogleSubscriptionOption
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** The RevenueCat calls the adapter makes, behind one seam (tests use a fake). */
internal interface RevenueCatClient {
    /** True once the app configured `Purchases`. */
    val isConfigured: Boolean

    suspend fun offerings(): List<RcOffering>

    suspend fun customer(): RcCustomer?

    /** Customer updates (purchases, renewals, expiries). */
    fun customerUpdates(): Flow<RcCustomer>

    fun setAttributes(attributes: Map<String, String>)

    /** Buys a package, or the Play option ([option]) of it. */
    suspend fun purchase(activity: Activity, offeringId: String, packageId: String, option: RcOption?): OfferOutcome

    /** Presents the offering's published paywall. */
    suspend fun presentPaywall(activity: Activity, offeringId: String): OfferOutcome
}

/** The real client over `com.revenuecat.purchases` (purchases-android 11). The app configures `Purchases` itself. */
internal class PurchasesClient : RevenueCatClient {
    override val isConfigured: Boolean get() = Purchases.isConfigured

    private val purchases: Purchases get() = Purchases.sharedInstance

    override suspend fun offerings(): List<RcOffering> = try {
        purchases.awaitOfferings().all.values.map(::offering)
    } catch (_: PurchasesException) {
        emptyList()
    }

    override suspend fun customer(): RcCustomer? = try {
        customer(purchases.awaitCustomerInfo())
    } catch (_: PurchasesException) {
        null
    }

    override fun customerUpdates(): Flow<RcCustomer> = callbackFlow {
        val previous = purchases.updatedCustomerInfoListener
        purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { info ->
            previous?.onReceived(info)
            trySend(customer(info))
        }
        awaitClose { purchases.updatedCustomerInfoListener = previous }
    }

    override fun setAttributes(attributes: Map<String, String>) = purchases.setAttributes(attributes)

    override suspend fun purchase(activity: Activity, offeringId: String, packageId: String, option: RcOption?): OfferOutcome {
        val pkg = try {
            purchases.awaitOfferings().all[offeringId]?.availablePackages?.firstOrNull { it.identifier == packageId }
        } catch (_: PurchasesException) {
            null
        } ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        val subscriptionOption = option?.let { wanted ->
            pkg.product.subscriptionOptions?.firstOrNull {
                it is GoogleSubscriptionOption && it.basePlanId == wanted.basePlanId && it.offerId == wanted.offerId
            }
        }
        val params = if (subscriptionOption != null) PurchaseParams.Builder(activity, subscriptionOption) else PurchaseParams.Builder(activity, pkg)
        return try {
            purchases.awaitPurchase(params.build())
            OfferOutcome.Purchased
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) OfferOutcome.Cancelled else OfferOutcome.Failed(OfferFailure.StoreError)
        }
    }

    override suspend fun presentPaywall(activity: Activity, offeringId: String): OfferOutcome = RevenueCatPaywallActivity.present(activity, offeringId)

    private fun offering(o: Offering) = RcOffering(
        id = o.identifier,
        description = o.serverDescription,
        kind = o.metadata["pingfront_kind"] as? String,
        packages = o.availablePackages.map { p ->
            val product = p.product
            RcPackage(
                id = p.identifier,
                productId = product.id,
                price = product.price.formatted,
                period = product.period?.iso8601,
                trial = product.defaultOption?.freePhase?.billingPeriod?.iso8601,
                options = product.subscriptionOptions.orEmpty().filterIsInstance<GoogleSubscriptionOption>().map { RcOption(it.basePlanId, it.offerId) },
            )
        },
        hasPaywall = o.hasPaywall,
    )

    private fun customer(info: CustomerInfo) = RcCustomer(
        active = info.entitlements.active.values.map { e ->
            RcEntitlement(
                productId = e.productIdentifier,
                trial = e.periodType == PeriodType.TRIAL,
                willRenew = e.willRenew,
                billingIssue = e.billingIssueDetectedAt != null,
                expiresAt = e.expirationDate?.toInstant(),
            )
        },
        hadEntitlements = info.entitlements.all.isNotEmpty(),
        ownedProducts = info.activeSubscriptions + info.nonSubscriptionTransactions.map { it.productIdentifier },
    )
}
