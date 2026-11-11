package dev.pingfront

import android.app.Activity
import android.content.Context
import dev.pingfront.look.ChatTopic
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.PingFrontColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * An optional module. Modules register through [ModuleContext], so Core never depends on them.
 *
 * The SDK's modules are objects: `Push`, `Billing`, `Reports` (and the chat UI, registered
 * automatically by `pingfront-ui`). Vendor adapters can ship their own:
 * ```kotlin
 * object RevenueCat : PingFrontModule("revenuecat") {
 *     override fun install(context: ModuleContext) {
 *         context.addEntitlementSource(RevenueCatEntitlements())
 *         context.setOfferHandler(RevenueCatOffers())
 *     }
 * }
 * ```
 */
public abstract class PingFrontModule(
    /** A unique id. Adding the same id twice has no effect. */
    public val id: String,
) {
    /** Called once per `configure`, on the main thread. Register seams; do no network work here. */
    public abstract fun install(context: ModuleContext)
}

/** Presents and dismisses the chat. Provided by `pingfront-ui`. */
public interface ChatPresenter {
    /** Presents the chat, optionally for a topic. */
    public fun presentChat(topic: ChatTopic?)

    /** Dismisses the SDK-presented chat. */
    public fun dismissChat()

    /** Opens a web URL or deep link from a card. */
    public fun openUrl(url: String)

    /**
     * Opens a link card's URL (a web link, or the app's own scheme) and says whether anything opened it (a link card
     * reports `cannot_open` otherwise). The SDK has already checked the link card URL rule.
     */
    public fun openLink(url: String): Boolean {
        openUrl(url)
        return true
    }

    /** Presents a plain text bug report form (used when the reports module is not installed). */
    public fun presentSimpleReport(prefill: String?)
}

/** Handles offer cards. Billing and vendor adapters implement this to show their paywall. */
public interface OfferHandler {
    /** Shows the offer and reports what happened. */
    public suspend fun handle(request: OfferRequest): OfferOutcome
}

/** Produces offer slots (for example from Play Billing products) so the team can reference them in cards. */
public interface OfferSource {
    /** The current offers. */
    public suspend fun offers(): List<OfferSlot>
}

/** Streams the person's entitlement (Play Billing, RevenueCat, …). */
public interface EntitlementSource {
    /** Entitlement updates, starting with the current value. */
    public fun entitlementUpdates(): Flow<Entitlement>
}

/** A store offer's price as the store shows it to this person. */
public class OfferPrice(
    /** What the person pays now (the discounted phase), formatted by the store. */
    public val price: String,
    /** The regular price after the offer, to show struck through; `null` when there is no discount. */
    public val regularPrice: String?,
    /** ISO 8601 billing period of [price] (for example `P1Y`). */
    public val period: String?,
    /** Every pricing phase in order: a free trial, an intro price, then the regular price. */
    public val phases: List<PricePhase> = emptyList(),
    /** How buying it changes the subscription the person has now; `null` when they have none. */
    public val change: PlanChange? = null,
) {
    /** A copy with the given fields replaced. */
    public fun copy(
        price: String = this.price,
        regularPrice: String? = this.regularPrice,
        period: String? = this.period,
        phases: List<PricePhase> = this.phases,
        change: PlanChange? = this.change,
    ): OfferPrice = OfferPrice(price, regularPrice, period, phases, change)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfferPrice) return false
        return price == other.price &&
            regularPrice == other.regularPrice &&
            period == other.period &&
            phases == other.phases &&
            change == other.change
    }

    override fun hashCode(): Int = java.util.Objects.hash(price, regularPrice, period, phases, change)

    override fun toString(): String = "OfferPrice(price=$price, regularPrice=$regularPrice, period=$period, phases=$phases, change=$change)"
}

/** One pricing phase of a store offer. */
public class PricePhase(
    /** The price of one billing period, formatted by the store. */
    public val price: String,
    /** ISO 8601 billing period (`P1W`, `P1M`, `P1Y`). */
    public val period: String,
    /** How many periods the phase lasts; `null` for the recurring (regular) phase. */
    public val cycles: Int?,
    /** True for a free trial. */
    public val free: Boolean,
) {
    /** A copy with the given fields replaced. */
    public fun copy(price: String = this.price, period: String = this.period, cycles: Int? = this.cycles, free: Boolean = this.free): PricePhase =
        PricePhase(price, period, cycles, free)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PricePhase) return false
        return price == other.price &&
            period == other.period &&
            cycles == other.cycles &&
            free == other.free
    }

    override fun hashCode(): Int = java.util.Objects.hash(price, period, cycles, free)

    override fun toString(): String = "PricePhase(price=$price, period=$period, cycles=$cycles, free=$free)"
}

/** How an offer replaces the subscription the person has now (Play's replacement modes). */
public enum class PlanChange {
    /** An upgrade: it replaces the current plan right away, and unused time is credited. */
    Now,

    /** A downgrade: it starts when the current plan renews. */
    AtRenewal,
}

/** What the store reports about the person's purchases right now. */
public class StorePurchases(
    /** The entitlement the purchases grant. */
    public val entitlement: Entitlement,
    /** Products bought but waiting for payment (Play's pending purchases), not yet owned. */
    public val pendingProducts: Set<String> = emptySet(),
) {
    /** A copy with the given fields replaced. */
    public fun copy(entitlement: Entitlement = this.entitlement, pendingProducts: Set<String> = this.pendingProducts): StorePurchases =
        StorePurchases(entitlement, pendingProducts)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StorePurchases) return false
        return entitlement == other.entitlement &&
            pendingProducts == other.pendingProducts
    }

    override fun hashCode(): Int = java.util.Objects.hash(entitlement, pendingProducts)

    override fun toString(): String = "StorePurchases(entitlement=$entitlement, pendingProducts=$pendingProducts)"
}

/** The store's own message for a payment problem, shown in the app (Play's in-app messaging). */
public fun interface PaymentFixer {
    /** Shows the message if the store has one for this person. */
    public suspend fun showPaymentFix(): PaymentFix
}

/** What happened when the store's payment message was asked for. */
public enum class PaymentFix {
    /** The store had no message to show (no payment problem it knows about). */
    NothingToShow,

    /** The person saw the message and left without fixing it. */
    Dismissed,

    /** The person fixed the payment; the subscription is back. */
    Updated,
}

/** Prices store offers (the billing module, from Play's `pricingPhases`). */
public interface OfferPricer {
    /** The price of [offer] for this person, or `null` when the store does not know it. */
    public suspend fun price(offer: dev.pingfront.model.StoreOffer): OfferPrice?

    /** The price of [productId] as a card without a named offer buys it, or `null`. */
    public suspend fun price(productId: String): OfferPrice? = null
}

/** Re-reads the store's purchases on demand (Play Billing `queryPurchasesAsync`), for store action cards. */
public fun interface PurchaseRefresher {
    /** What the store reports now, or null when the store can't be reached. */
    public suspend fun refresh(): StorePurchases?
}

/** Shows the native in-app review flow. */
public interface ReviewRequester {
    /** Requests a review. Returns `false` if the flow could not be shown. */
    public suspend fun requestReview(): Boolean
}

/** Presents the full bug report flow (screenshot, markup). */
public interface ReportPresenter {
    /** Presents the report flow. */
    public fun presentReport(prefill: String?)
}

/** Adds the log tail and device state to bug reports. */
public interface ReportContextProvider {
    /** Recent log lines (at most 64 KB) and state such as screen, battery, network. */
    public suspend fun reportContext(): ReportContext
}

/** Supplies push tokens, so Core never depends on Firebase. `pingfront-push` provides the FCM one. */
public interface PushTokenSource {
    /** The current token, or `null` when push is unavailable. */
    public suspend fun currentToken(): String?
}

/** Extra context attached to a bug report. */
public class ReportContext(
    /** Recent log lines. */
    public val logTail: String? = null,
    /** Device state, for example `mapOf("screen" to "Settings", "battery" to "45%")`. */
    public val state: Map<String, String> = emptyMap(),
) {
    /** A copy with the given fields replaced. */
    public fun copy(logTail: String? = this.logTail, state: Map<String, String> = this.state): ReportContext = ReportContext(logTail, state)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReportContext) return false
        return logTail == other.logTail &&
            state == other.state
    }

    override fun hashCode(): Int = java.util.Objects.hash(logTail, state)

    override fun toString(): String = "ReportContext(logTail=$logTail, state=$state)"
}

/** The registration surface handed to each module's [PingFrontModule.install]. */
public class ModuleContext internal constructor(
    private val instance: PingFrontInstance,
    /** The application context. */
    public val applicationContext: Context,
) {
    /** Sets the chat presenter (`pingfront-ui` does this). */
    public fun setChatPresenter(presenter: ChatPresenter) {
        instance.registry.chatPresenter = presenter
    }

    /** Overrides the host app's name, primary color and icon (inferred automatically otherwise). */
    public fun setHostApp(name: String, accent: PingFrontColor?, iconPng: ByteArray?) {
        instance.overrideHost(name, accent, iconPng)
    }

    /** Sets the offer handler used when the app has not set `onOffer`. */
    public fun setOfferHandler(handler: OfferHandler) {
        instance.registry.offerHandler = handler
    }

    /** Adds a source of offer slots. */
    public fun addOfferSource(source: OfferSource) {
        instance.registry.offerSources += source
    }

    /** Adds an entitlement source. Its updates are forwarded like `setEntitlement`. */
    public fun addEntitlementSource(source: EntitlementSource) {
        instance.registry.entitlementSources += source
    }

    /** Sets the in-app review flow. */
    public fun setReviewRequester(requester: ReviewRequester) {
        instance.registry.reviewRequester = requester
    }

    /** Sets the purchase refresher used by store action cards (the billing module does this). */
    public fun setPurchaseRefresher(refresher: PurchaseRefresher) {
        instance.registry.purchaseRefresher = refresher
    }

    /** Sets the store's in-app payment message (the billing module does this). */
    public fun setPaymentFixer(fixer: PaymentFixer) {
        instance.registry.paymentFixer = fixer
    }

    /** Sets the store offer pricer (the billing module does this). */
    public fun setOfferPricer(pricer: OfferPricer) {
        instance.registry.offerPricer = pricer
    }

    /** Sets the full report flow. */
    public fun setReportPresenter(presenter: ReportPresenter) {
        instance.registry.reportPresenter = presenter
    }

    /** Sets the report context provider (log tail, device state). */
    public fun setReportContextProvider(provider: ReportContextProvider) {
        instance.registry.reportContext = provider
    }

    /** Sets the push token source (FCM in `pingfront-push`). */
    public fun setPushTokenSource(source: PushTokenSource) {
        instance.registry.pushTokenSource = source
    }

    /** The resumed activity, if any (for billing flows, review prompts and screenshots). */
    public fun currentActivity(): Activity? = instance.activities.current()

    /** The SDK's main-thread dispatcher (injected, so tests can replace it). Use it for store UI calls. */
    public val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = instance.mainDispatcher()

    /** The SDK's IO dispatcher (injected, so tests can replace it). */
    public val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = instance.ioDispatcher()

    /** A scope owned by the SDK for long-lived module work. Cancelled when the SDK is reconfigured; it survives logout. */
    public val scope: CoroutineScope get() = instance.moduleScope()
}
