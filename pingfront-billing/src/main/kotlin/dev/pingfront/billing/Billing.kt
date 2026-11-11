package dev.pingfront.billing

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.eventFlow
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import dev.pingfront.EntitlementSource
import dev.pingfront.ModuleContext
import dev.pingfront.OfferFailure
import dev.pingfront.OfferHandler
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferPrice
import dev.pingfront.OfferPricer
import dev.pingfront.OfferRequest
import dev.pingfront.OfferSource
import dev.pingfront.PaymentFix
import dev.pingfront.PaymentFixer
import dev.pingfront.PingFront
import dev.pingfront.PingFrontModule
import dev.pingfront.PlanChange
import dev.pingfront.PricePhase
import dev.pingfront.PurchaseRefresher
import dev.pingfront.ReviewRequester
import dev.pingfront.StoreMomentProps
import dev.pingfront.StorePurchases
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.StoreOffer
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Google Play: reads the person's subscription state (entitlements), reports your products as offer
 * slots, runs the purchase flow from offer cards, detects lapses (cancel, expiry, billing problems) as
 * store moments, and shows the Play In-App Review flow for review cards.
 *
 * Your app's own purchases stay yours: the module never acknowledges or consumes a purchase your app started. A
 * purchase started from an offer card runs on the SDK's own `BillingClient`, so only the SDK hears about it, and Play
 * refunds a purchase that is not acknowledged within three days. So the SDK acknowledges those itself (a pending one
 * once it is paid), as the iOS module finishes the transactions it started. Pass `onPurchase` to grant and
 * acknowledge them yourself instead. Billing sells subscriptions only, so there is nothing to consume.
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") { use(Billing(products = listOf("pro_monthly", "pro_yearly"))) }
 * ```
 */
public object Billing : PingFrontModule("billing") {
    override fun install(context: ModuleContext): Unit = BillingModule(emptyList(), null).install(context)

    /** With the subscription product ids to report as offers and to buy from offer cards. */
    public operator fun invoke(products: List<String>): PingFrontModule = BillingModule(products, null)

    /**
     * With the product ids, and [onPurchase] for every paid purchase an offer card started. Grant it, then acknowledge
     * it (Play refunds a purchase that is not acknowledged within three days): with this handler the SDK does not.
     */
    public operator fun invoke(products: List<String>, onPurchase: suspend (Purchase) -> Unit): PingFrontModule = BillingModule(products, onPurchase)
}

internal class BillingModule(private val products: List<String>, private val onPurchase: (suspend (Purchase) -> Unit)?) :
    PingFrontModule("billing") {
    override fun install(context: ModuleContext) {
        val play = PlayBilling(context.applicationContext, onPurchase)
        context.addEntitlementSource(play)
        if (products.isNotEmpty()) {
            context.addOfferSource(PlayOffers(play, products))
            context.setOfferHandler(PlayOfferHandler(play, products, context::currentActivity))
            context.setOfferPricer(PlayOfferPricer(play))
        }
        context.setReviewRequester(PlayReview(context.applicationContext, context::currentActivity))
        context.setPurchaseRefresher(PlayPurchaseRefresher(play::purchasesOrNull))
        context.setPaymentFixer(PlayPaymentFixer(play::showInAppMessages, context.mainDispatcher, context.applicationContext, context::currentActivity))
    }
}

/** Store action cards re-read purchases with `queryPurchasesAsync` (restore, and after a trip to Google Play). */
internal class PlayPurchaseRefresher(private val query: suspend () -> List<Purchase>?) : PurchaseRefresher {
    override suspend fun refresh(): StorePurchases? = query()?.let(PurchaseSnapshot::of)
}

/** Purchases as pingfront sees them: the entitlement, and pending purchases (bought, waiting for payment). */
internal object PurchaseSnapshot {
    fun of(purchases: List<Purchase>): StorePurchases = StorePurchases(
        EntitlementMapping.entitlement(purchases),
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PENDING }.flatMap { it.products }.toSet(),
    )
}

/**
 * Play's in-app message for payment problems (`showInAppMessages`, transactional): Google's own "fix your
 * payment" sheet over the app. Play answers `NO_ACTION_NEEDED` both when it had nothing to show and when the
 * person closed the sheet, so the host activity pausing (the sheet is Play's activity) tells the two apart.
 */
internal class PlayPaymentFixer(
    private val show: suspend (Activity) -> Int?,
    // Play's message is UI: shown on the SDK's injected main dispatcher, never a hard-coded one.
    private val main: CoroutineDispatcher,
    private val context: Context,
    private val activity: () -> Activity?,
) : PaymentFixer {
    override suspend fun showPaymentFix(): PaymentFix {
        val host = activity() ?: return PaymentFix.NothingToShow
        val app = context.applicationContext as? android.app.Application ?: return PaymentFix.NothingToShow
        val shown = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) {
                if (a === host) shown.set(true)
            }

            override fun onActivityCreated(a: Activity, state: android.os.Bundle?) = Unit

            override fun onActivityStarted(a: Activity) = Unit

            override fun onActivityResumed(a: Activity) = Unit

            override fun onActivityStopped(a: Activity) = Unit

            override fun onActivitySaveInstanceState(a: Activity, state: android.os.Bundle) = Unit

            override fun onActivityDestroyed(a: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(watcher)
        try {
            val code = withContext(main) { show(host) } ?: return PaymentFix.NothingToShow
            return PaymentFixMapping.fix(code, shown.get())
        } finally {
            app.unregisterActivityLifecycleCallbacks(watcher)
        }
    }
}

/** Play's in-app message result to pingfront's. Plain, so it is tested without Play. */
internal object PaymentFixMapping {
    fun fix(code: Int, shown: Boolean): PaymentFix = when {
        code == InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED -> PaymentFix.Updated
        shown -> PaymentFix.Dismissed
        else -> PaymentFix.NothingToShow
    }
}

/**
 * One BillingClient, connected on demand. Its listener hears only the purchases this SDK's own purchase sheet
 * started: those are remembered by token and acknowledged once paid (or handed to `onPurchase`). Purchases the host
 * app starts go to the host's own client and are never touched.
 */
internal class PlayBilling(context: Context, private val onPurchase: (suspend (Purchase) -> Unit)? = null) : EntitlementSource, PlayStore {
    private val mutex = Mutex()
    private val pendingFlow = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Pair<BillingResult, List<Purchase>?>>?>(null)

    /** Tokens of purchases the SDK's sheet started that are not settled yet (pending ones complete later). */
    private val started: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val listener = PurchasesUpdatedListener { result, purchases ->
        purchases?.forEach { started.add(it.purchaseToken) }
        pendingFlow.get()?.complete(result to purchases)
    }
    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()
    private val lapses = LapseDetector()

    override suspend fun connected(): Boolean = mutex.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { continuation ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
        }
    }

    /**
     * Shows Play's transactional in-app message; the response code, or null when Play is unavailable. Call it on
     * the main dispatcher ([PlayPaymentFixer] does).
     */
    suspend fun showInAppMessages(activity: Activity): Int? {
        if (!connected()) return null
        val params = InAppMessageParams.newBuilder()
            .addInAppMessageCategoryToShow(InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
            .build()
        return suspendCancellableCoroutine { continuation ->
            client.showInAppMessages(activity, params) { result -> if (continuation.isActive) continuation.resume(result.responseCode) }
        }
    }

    /** The person's subscriptions, or null when Play can't be reached (so "unreachable" never reads as "none"). */
    suspend fun purchasesOrNull(): List<Purchase>? {
        if (!connected()) return null
        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) {
                    result,
                    list,
                ->
                continuation.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else null)
            }
        }
    }

    override suspend fun details(ids: List<String>): List<ProductDetails> {
        if (ids.isEmpty() || !connected()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ids.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
                },
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, details ->
                continuation.resume(
                    if (result.responseCode ==
                        BillingClient.BillingResponseCode.OK
                    ) {
                        details.productDetailsList
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    override suspend fun launch(activity: Activity, details: ProductDetails, offer: StoreOffer?): OfferOutcome {
        // The card's named offer when the person is eligible (Play lists only those), else the base plan.
        val offerToken = OfferSelection.pick(OfferSelection.choices(details), offer)?.token
            ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        // Switching from another subscription product: Play replaces it (upgrade now, downgrade at renewal). When Play
        // can't say what the person has, nothing is bought: a guess could start a second subscription (review AN-2).
        val replacing = PlanSwitch.replacing(purchasesOrNull(), details.productId)
        if (replacing == PlanSwitch.Replacing.Unknown) return OfferOutcome.Failed(OfferFailure.BillingUnavailable)
        val switch = (replacing as? PlanSwitch.Replacing.Product)?.id?.let { old ->
            val oldDetails = details(listOf(old)).firstOrNull()
            val mode = PlanSwitch.change(oldDetails?.let(PlanSwitch::regularPerYear), PlanSwitch.regularPerYear(details))
            BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.newBuilder()
                .setOldProductId(old)
                .setReplacementMode(PlanSwitch.replacementMode(mode))
                .build()
        }
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offerToken)
        switch?.let(product::setSubscriptionProductReplacementParams)
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product.build()))
            .apply { PingFront.instance.storeAccountToken()?.let { setObfuscatedAccountId(it) } }
            .build()
        // One purchase sheet at a time: a second quick tap no longer replaces the first one's result,
        // which left the first caller waiting forever.
        val flow = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
        if (!pendingFlow.compareAndSet(null, flow)) return OfferOutcome.Failed(OfferFailure.InProgress)
        val (result, purchases) = try {
            val launched = client.launchBillingFlow(activity, params)
            if (launched.responseCode != BillingClient.BillingResponseCode.OK) return outcome(launched, null)
            flow.await()
        } finally {
            pendingFlow.compareAndSet(flow, null)
        }
        settle(purchases.orEmpty())
        return outcome(result, purchases)
    }

    /**
     * Settles the paid purchases this SDK's sheet started: `onPurchase` when the host asked for them, else
     * acknowledged here so Play doesn't refund them after three days. A failed acknowledgement stays remembered and is
     * tried again on the next refresh.
     */
    private suspend fun settle(purchases: List<Purchase>) {
        val due = Acknowledgement.due(purchases.map { Acknowledgement.Candidate(it.purchaseToken, it.isPaid(), it.isAcknowledged) }, started)
        for (purchase in purchases.filter { it.purchaseToken in due }) {
            val handler = onPurchase
            if (handler != null) {
                started.remove(purchase.purchaseToken)
                handler(purchase)
            } else if (acknowledge(purchase.purchaseToken)) {
                started.remove(purchase.purchaseToken)
            }
        }
        // Settled elsewhere (the host acknowledged it, or it's gone): forget it.
        purchases.filter { it.isAcknowledged }.forEach { started.remove(it.purchaseToken) }
    }

    private suspend fun acknowledge(token: String): Boolean {
        if (!connected()) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        return suspendCancellableCoroutine { continuation ->
            client.acknowledgePurchase(params) { result ->
                if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }
    }

    private fun Purchase.isPaid(): Boolean = purchaseState == Purchase.PurchaseState.PURCHASED

    /** Entitlement updates: the current state now, then again whenever the app comes to the foreground. */
    override fun entitlementUpdates(): Flow<Entitlement> = callbackFlow {
        suspend fun refresh() {
            val purchases = purchasesOrNull()
            val check = EntitlementCheck.of(purchases, lapses) ?: return
            // A pending purchase from the SDK's sheet that has since been paid.
            if (started.isNotEmpty()) settle(purchases.orEmpty())
            send(check.entitlement)
            check.lapses.forEach { (event, props) -> PingFront.instance.storeMoment(event, props) }
        }
        refresh()
        ProcessLifecycleOwner.get().lifecycle.eventFlow.filter { it == Lifecycle.Event.ON_START }.collect { refresh() }
        awaitClose()
    }

    private fun outcome(result: BillingResult, purchases: List<Purchase>?): OfferOutcome = when (result.responseCode) {
        BillingClient.BillingResponseCode.OK -> if (purchases.orEmpty().any {
                it.purchaseState == Purchase.PurchaseState.PENDING
            }
        ) {
            OfferOutcome.Pending
        } else {
            OfferOutcome.Purchased
        }
        BillingClient.BillingResponseCode.USER_CANCELED -> OfferOutcome.Cancelled
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> OfferOutcome.Unavailable
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> OfferOutcome.Failed(
            OfferFailure.BillingUnavailable,
        )
        else -> OfferOutcome.Failed(OfferFailure.StoreError)
    }
}

/**
 * Which purchases the SDK acknowledges: only those its own purchase sheet started ([started] holds their tokens), once
 * paid, and not yet acknowledged. Never one the host app started.
 */
internal object Acknowledgement {
    data class Candidate(val token: String, val paid: Boolean, val acknowledged: Boolean)

    fun due(purchases: List<Candidate>, started: Set<String>): Set<String> =
        purchases.filter { it.token in started && it.paid && !it.acknowledged }.mapTo(LinkedHashSet()) { it.token }
}

/**
 * One foreground check of Play's purchases: the entitlement and the lapses since the last check. Null when Play
 * could not be reached ([purchases] null): that is never "no purchases", so the entitlement stays and no lapse is
 * reported (review AN-2).
 */
internal object EntitlementCheck {
    class Result(val entitlement: Entitlement, val lapses: List<Pair<String, StoreMomentProps>>)

    suspend fun of(purchases: List<Purchase>?, lapses: LapseDetector): Result? {
        val list = purchases ?: return null
        return Result(EntitlementMapping.entitlement(list), lapses.check(list).orEmpty())
    }
}

/** Maps Play purchases to the protocol's entitlement. Play exposes no trial flag on the device. */
internal object EntitlementMapping {
    fun entitlement(purchases: List<Purchase>): Entitlement {
        val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val best =
            active.firstOrNull { it.isAutoRenewing } ?: active.firstOrNull()
                ?: return Entitlement(Entitlement.State.None, source = Entitlement.Source.Play)
        val state = when {
            best.isSuspended -> Entitlement.State.BillingRetry
            best.isAutoRenewing -> Entitlement.State.Active
            else -> Entitlement.State.Cancelled
        }
        return Entitlement(state, best.products.firstOrNull(), source = Entitlement.Source.Play)
    }
}

/**
 * Lapse detection on the device, for apps without store webhooks: compares purchases between checks
 * (at most once a minute) and reports `subscription_cancelled`, `subscription_expired` and
 * `billing_issue` once per purchase. The server dedupes against provider webhooks by the purchase token.
 */
internal class LapseDetector(private val clock: () -> Long = { PingFront.instance.monotonicMillis() }) {
    private val mutex = Mutex()
    private var last: Map<String, Snapshot>? = null
    private var checkedAt = 0L
    private val sent = HashSet<String>()

    data class Snapshot(val product: String, val orderId: String?, val renewing: Boolean, val suspended: Boolean)

    suspend fun check(purchases: List<Purchase>): List<Pair<String, StoreMomentProps>>? = compare(
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.associate {
            it.purchaseToken to
                Snapshot(it.products.firstOrNull().orEmpty(), it.orderId, it.isAutoRenewing, it.isSuspended)
        },
    )

    suspend fun compare(now: Map<String, Snapshot>): List<Pair<String, StoreMomentProps>>? = mutex.withLock {
        val time = clock()
        if (last != null && time - checkedAt < MIN_INTERVAL_MS) return@withLock null
        checkedAt = time
        val before = last
        last = now
        if (before == null) return@withLock emptyList()
        val events = mutableListOf<Pair<String, StoreMomentProps>>()
        fun emit(event: String, token: String, snap: Snapshot) {
            // Play fields per the contract: purchaseToken and orderId. The token never goes in
            // originalTransactionId (64 characters at most, so every lapse used to get a 422; review CQ-H2).
            if (sent.add("$event:$token")) {
                events +=
                    event to StoreMomentProps(productId = snap.product, purchaseToken = token.takeIf { it.length <= MAX_TOKEN_CHARS }, orderId = snap.orderId)
            }
        }
        for ((token, old) in before) {
            val current = now[token]
            when {
                current == null -> emit(EXPIRED, token, old)
                old.renewing && !current.renewing -> emit(CANCELLED, token, current)
                !old.suspended && current.suspended -> emit(BILLING_ISSUE, token, current)
            }
        }
        events
    }

    companion object {
        const val MIN_INTERVAL_MS: Long = 60_000
        const val CANCELLED: String = "subscription_cancelled"
        const val EXPIRED: String = "subscription_expired"
        const val BILLING_ISSUE: String = "billing_issue"
        private const val MAX_TOKEN_CHARS = 1024
    }
}

/** Reports the configured products as offer slots, with Play's localized prices. */
internal class PlayOffers(private val play: PlayBilling, private val products: List<String>) : OfferSource {
    override suspend fun offers(): List<OfferSlot> = play.details(products).map { details ->
        // The same choice a card without a named offer buys, so the slot never shows a trial it would not give.
        val phases = OfferSelection.pick(OfferSelection.choices(details), null)?.phases.orEmpty()
        val paid = phases.lastOrNull()
        val trial = phases.firstOrNull { it.micros == 0L }
        OfferSlot(
            id = details.productId,
            title = codePoints(details.name, TITLE_CHARS),
            products = listOf(
                OfferSlot.Product(details.productId, paid?.formattedPrice.orEmpty(), paid?.period, trial?.period),
            ),
            source = OfferSlot.Source.Play,
        )
    }

    private companion object {
        const val TITLE_CHARS = 60
    }
}

/** The Play calls an offer purchase makes ([PlayBilling] in the app, scripted in tests). */
internal interface PlayStore {
    suspend fun connected(): Boolean

    suspend fun details(ids: List<String>): List<ProductDetails>

    suspend fun launch(activity: Activity, details: ProductDetails, offer: StoreOffer?): OfferOutcome
}

/** Buys the offer card's product with the Play purchase sheet. */
internal class PlayOfferHandler(
    private val play: PlayStore,
    private val products: List<String>,
    private val activity: () -> Activity?,
) : OfferHandler {
    override suspend fun handle(request: OfferRequest): OfferOutcome {
        val named = request.storeOffer?.productId?.let(::listOf)
        val ids = named ?: request.slot?.products?.map { it.id }?.ifEmpty { null } ?: listOf(request.offerId).filter { it in products }
        // A card with no product to buy is a missing product, whatever Play's state.
        if (ids.isEmpty()) return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        // Play that can't be reached (no account, no Play Store) is not a missing product: the card says so.
        if (!play.connected()) return OfferOutcome.Failed(OfferFailure.BillingUnavailable)
        val details = play.details(ids).firstOrNull() ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        val host = activity() ?: return OfferOutcome.Failed(OfferFailure.NoActivity)
        return play.launch(host, details, request.storeOffer)
    }
}

/** Prices a card's Play offer for this person: the discounted phase and the regular price. */
internal class PlayOfferPricer(private val play: PlayBilling) : OfferPricer {
    override suspend fun price(offer: StoreOffer): OfferPrice? = offer.productId?.let { priced(it, offer) }

    override suspend fun price(productId: String): OfferPrice? = priced(productId, null)

    /** The phases the purchase sheet will charge, and how it changes a plan the person already has. */
    private suspend fun priced(productId: String, offer: StoreOffer?): OfferPrice? {
        val details = play.details(listOf(productId)).firstOrNull() ?: return null
        val price = OfferSelection.pick(OfferSelection.choices(details), offer)?.let(OfferSelection::price) ?: return null
        val old = (PlanSwitch.replacing(play.purchasesOrNull(), productId) as? PlanSwitch.Replacing.Product)?.id ?: return price
        val oldDetails = play.details(listOf(old)).firstOrNull()
        return price.copy(change = PlanSwitch.change(oldDetails?.let(PlanSwitch::regularPerYear), PlanSwitch.regularPerYear(details)))
    }
}

/**
 * Switching between subscription products. Plain logic, tested without Play. Play reports products, not base
 * plans, on the device, so this covers product switches (monthly and yearly as separate products).
 */
internal object PlanSwitch {
    /** What buying a product would replace. */
    sealed interface Replacing {
        /** Nothing: a new subscription. */
        data object Nothing : Replacing

        /** The subscription product it replaces. */
        data class Product(val id: String) : Replacing

        /** Play could not be reached, so it is not known (never read as "nothing"). */
        data object Unknown : Replacing
    }

    /** What buying [productId] replaces, from Play's purchases, or [Replacing.Unknown] when Play could not be reached. */
    fun replacing(purchases: List<Purchase>?, productId: String): Replacing = when (purchases) {
        null -> Replacing.Unknown
        else -> from(purchases, productId)?.let { Replacing.Product(it) } ?: Replacing.Nothing
    }

    /** The subscription the person has that buying [productId] would replace, if any. */
    fun from(purchases: List<Purchase>, productId: String): String? = owned(
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.map { it.products },
        productId,
    )

    fun owned(products: List<List<String>>, productId: String): String? = products.flatten().takeIf { productId !in it }?.firstOrNull()

    /** A pricier plan per year is an upgrade (now, unused time credited); a cheaper one waits for renewal. */
    fun change(oldPerYear: Long?, newPerYear: Long?): PlanChange =
        if (oldPerYear == null || newPerYear == null || newPerYear >= oldPerYear) PlanChange.Now else PlanChange.AtRenewal

    fun replacementMode(change: PlanChange): Int = when (change) {
        PlanChange.Now -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION
        PlanChange.AtRenewal -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
    }

    /** The base plan's regular price over a year, in micros. */
    fun regularPerYear(details: ProductDetails): Long? {
        val base = details.subscriptionOfferDetails.orEmpty().firstOrNull { it.offerId == null } ?: return null
        val regular = base.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
        return perYear(regular.priceAmountMicros, regular.billingPeriod)
    }

    /** Micros per year for a price per ISO 8601 period (`P1W`, `P1M`, `P3M`, `P1Y`, `P30D`). */
    fun perYear(micros: Long, period: String): Long? {
        val match = Regex("^P(\\d+)([DWMY])$").find(period) ?: return null
        val count = match.groupValues[1].toLong().takeIf { it > 0 } ?: return null
        val perYear = when (match.groupValues[2]) {
            "D" -> DAYS_PER_YEAR / count
            "W" -> WEEKS_PER_YEAR / count
            "M" -> MONTHS_PER_YEAR / count
            else -> 1.0 / count
        }
        return (micros * perYear).toLong()
    }

    private const val DAYS_PER_YEAR = 365.0
    private const val WEEKS_PER_YEAR = 52.0
    private const val MONTHS_PER_YEAR = 12.0
}

/**
 * Which Play subscription offer to buy and how to price it. Plain data, so it is tested without Play:
 * [choices] reads `ProductDetails.subscriptionOfferDetails`, which lists only offers the person is eligible for.
 */
internal object OfferSelection {
    data class Phase(val formattedPrice: String, val micros: Long, val period: String, val cycles: Int = 0, val recurring: Boolean = true)

    data class Choice(val basePlanId: String, val offerId: String?, val token: String, val phases: List<Phase>)

    fun choices(details: ProductDetails): List<Choice> = details.subscriptionOfferDetails.orEmpty().map { offer ->
        Choice(
            offer.basePlanId,
            offer.offerId,
            offer.offerToken,
            offer.pricingPhases.pricingPhaseList.map {
                Phase(
                    it.formattedPrice,
                    it.priceAmountMicros,
                    it.billingPeriod,
                    it.billingCycleCount,
                    it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING,
                )
            },
        )
    }

    /** The named offer if present; else the base plan (of the named base plan when given); else any offer. */
    fun pick(choices: List<Choice>, offer: StoreOffer?): Choice? {
        val basePlan = offer?.basePlanId
        if (basePlan != null && offer.offerId != null) {
            choices.firstOrNull { it.basePlanId == basePlan && it.offerId == offer.offerId }?.let { return it }
        }
        return choices.firstOrNull { it.offerId == null && (basePlan == null || it.basePlanId == basePlan) }
            ?: choices.firstOrNull { it.offerId == null }
            ?: choices.firstOrNull()
    }

    /** The first paid phase is what the person pays now; the last (recurring) phase is the regular price. */
    fun price(choice: Choice): OfferPrice? {
        val regular = choice.phases.lastOrNull() ?: return null
        val now = choice.phases.firstOrNull { it.micros > 0 } ?: regular
        val discounted = now !== regular && now.micros < regular.micros
        val phases = choice.phases.map {
            PricePhase(it.formattedPrice, it.period, if (it.recurring) null else it.cycles.coerceAtLeast(1), it.micros == 0L)
        }
        return OfferPrice(now.formattedPrice, if (discounted) regular.formattedPrice else null, now.period, phases)
    }
}

/**
 * The Play In-App Review flow (Play decides whether it is shown). The manager is created on first use; tests pass
 * Play's own `FakeReviewManager`.
 */
internal class PlayReview(private val manager: () -> ReviewManager, private val activity: () -> Activity?) : ReviewRequester {
    constructor(context: Context, activity: () -> Activity?) : this({ ReviewManagerFactory.create(context) }, activity)

    override suspend fun requestReview(): Boolean {
        val host = activity() ?: return false
        val manager = manager()
        val info = suspendCancellableCoroutine<com.google.android.play.core.review.ReviewInfo?> { continuation ->
            manager.requestReviewFlow().addOnCompleteListener { task -> continuation.resume(if (task.isSuccessful) task.result else null) }
        } ?: return false
        return suspendCancellableCoroutine { continuation ->
            manager.launchReviewFlow(host, info).addOnCompleteListener { task -> continuation.resume(task.isSuccessful) }
        }
    }
}

/** At most [max] code points: a title never ends in half an emoji (review CQ-L2). */
internal fun codePoints(text: String, max: Int): String =
    if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
