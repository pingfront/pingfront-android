package dev.pingfront.superwall

import dev.pingfront.EntitlementSource
import dev.pingfront.ModuleContext
import dev.pingfront.OfferFailure
import dev.pingfront.OfferHandler
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferRequest
import dev.pingfront.OfferSource
import dev.pingfront.PingFront
import dev.pingfront.PingFrontModule
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/** A Superwall placement the app wants offer cards to open. */
public class SuperwallPlacement(
    /** The placement name (also the offer slot id, `[A-Za-z0-9_.-]{1,64}`). */
    public val placement: String,
    /** The title the team sees in the dashboard. */
    public val title: String,
    /** The flavour of the paywall behind it. */
    public val kind: OfferSlot.Kind = OfferSlot.Kind.Standard,
)

/**
 * Superwall for pingfront. Configure Superwall as usual, then list the placements offer cards may open:
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") {
 *     use(Superwall(SuperwallPlacement("campaign_trigger", "Pro yearly")))
 * }
 * ```
 *
 * - Reports one offer slot per placement (`source: superwall`), always available. The adapter never asks Superwall
 *   ahead of time: in superwall-android 2.9.0, `getPresentationResult` confirms the assignment and records holdouts,
 *   so audiences and holdouts decide when the card is tapped.
 * - An offer card registers the placement and reports purchased, cancelled or unavailable. Superwall paywalls
 *   carry their own offers, so a card's `StoreOffer` is ignored.
 * - Keeps the entitlement current from `Superwall.instance.subscriptionStatus`.
 * - Sets the user attribute `pingfront_token`. For webhooks to match, call `Superwall.instance.identify` with the
 *   same user id you pass to `PingFront.identify`.
 */
public object Superwall {
    /** The adapter for these placements. */
    public operator fun invoke(vararg placements: SuperwallPlacement): PingFrontModule = SuperwallModule(placements.toList())
}

internal class SuperwallModule(private val placements: List<SuperwallPlacement>) : PingFrontModule("superwall") {
    override fun install(context: ModuleContext) = SuperwallAdapter(SuperwallSdkClient(), placements).install(context)
}

/** An active Superwall entitlement, as plain data. */
internal data class SwEntitlement(
    val productId: String?,
    val lifetime: Boolean,
    val grace: Boolean,
    val trial: Boolean,
    val willRenew: Boolean?,
    val expiresAt: Instant?,
)

/** Superwall's subscription status: `null` entitlements while unknown. */
internal data class SwStatus(val active: List<SwEntitlement>?)

/** What registering a placement ended with. */
internal enum class SwPresentation { Purchased, Restored, Declined, Skipped, Failed }

/** The Superwall calls the adapter makes, behind one seam (tests use a fake). */
internal interface SuperwallClient {
    val isConfigured: Boolean

    /** Registers the placement (presents its paywall when the audience matches). */
    suspend fun register(placement: String): SwPresentation

    fun statusUpdates(): Flow<SwStatus>

    fun setUserAttributes(attributes: Map<String, Any>)
}

internal class SuperwallAdapter(private val client: SuperwallClient, private val placements: List<SuperwallPlacement>) :
    OfferSource,
    OfferHandler,
    EntitlementSource {
    fun install(context: ModuleContext) {
        context.addOfferSource(this)
        context.addEntitlementSource(this)
        context.setOfferHandler(this)
        context.scope.launch { linkIdentity(PingFront.instance.storeAccountTokens()) }
    }

    suspend fun linkIdentity(tokens: Flow<String>) {
        tokens.collect { token -> if (client.isConfigured) client.setUserAttributes(mapOf(ATTRIBUTE to token)) }
    }

    override suspend fun offers(): List<OfferSlot> {
        if (!client.isConfigured) return emptyList()
        return placements.filter { SLOT_ID.matches(it.placement) }.map { p ->
            OfferSlot(p.placement, p.title, p.kind, available = true, source = OfferSlot.Source.Superwall)
        }
    }

    override fun entitlementUpdates(): Flow<Entitlement> = client.statusUpdates().mapNotNull(::entitlement)

    override suspend fun handle(request: OfferRequest): OfferOutcome {
        if (!client.isConfigured) return OfferOutcome.Failed(OfferFailure.BillingUnavailable)
        if (placements.none { it.placement == request.offerId }) return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        // Superwall paywalls carry their own offers: request.storeOffer is ignored.
        return when (client.register(request.offerId)) {
            SwPresentation.Purchased, SwPresentation.Restored -> OfferOutcome.Purchased
            SwPresentation.Declined -> OfferOutcome.Cancelled
            SwPresentation.Skipped -> OfferOutcome.Unavailable
            SwPresentation.Failed -> OfferOutcome.Failed(OfferFailure.StoreError)
        }
    }

    internal companion object {
        const val ATTRIBUTE: String = "pingfront_token"
        private val SLOT_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** Superwall's active entitlements to pingfront's state (`null` while Superwall does not know yet). */
        fun entitlement(status: SwStatus): Entitlement? {
            val active = status.active ?: return null
            val e = active.firstOrNull() ?: return Entitlement(Entitlement.State.None, source = Entitlement.Source.Superwall)
            val state = when {
                e.lifetime -> Entitlement.State.Lifetime
                e.grace -> Entitlement.State.Grace
                e.trial -> Entitlement.State.Trial
                e.willRenew == false -> Entitlement.State.Cancelled
                else -> Entitlement.State.Active
            }
            return Entitlement(state, e.productId, e.expiresAt.takeIf { state != Entitlement.State.Lifetime }, Entitlement.Source.Superwall)
        }
    }
}
