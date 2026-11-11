package dev.pingfront.adapty

import android.app.Activity
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
import dev.pingfront.model.StoreOffer
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Adapty for pingfront (Adapty Android 4). Activate Adapty as usual, then list the placements offer cards may open:
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") { use(Adapty("main", "winback")) }
 * ```
 *
 * - Reports one offer slot per placement (`source: adapty`): the placement's flow name as the title, `kind` from
 *   the remote config key `pingfront_kind`, its products and prices.
 * - An offer card shows the placement's AdaptyUI flow (paywall) when it has one, else buys its product. A card naming
 *   a Play offer (`basePlanId`/`offerId`) buys the matching product.
 * - Keeps the entitlement current from the profile's access levels.
 * - Sets the custom attribute `pingfront_token`. For webhooks to match, call `Adapty.identify` with the same user id
 *   you pass to `PingFront.identify`.
 */
public object Adapty {
    /** The adapter for these placement ids. */
    public operator fun invoke(vararg placements: String): PingFrontModule = AdaptyModule(placements.toList())
}

internal class AdaptyModule(private val placements: List<String>) : PingFrontModule("adapty") {
    override fun install(context: ModuleContext) = AdaptyAdapter(AdaptySdkClient(), placements).install(context)
}

/** A placement's flow, as plain data. */
internal data class AdFlow(val placement: String, val name: String, val kind: String?, val hasView: Boolean, val products: List<AdProduct>)

internal data class AdProduct(val id: String, val price: String, val period: String?, val trial: String?, val basePlanId: String?, val offerId: String?)

internal data class AdAccessLevel(
    val productId: String?,
    val active: Boolean,
    val lifetime: Boolean,
    val grace: Boolean,
    val trial: Boolean,
    val willRenew: Boolean,
    val expiresAt: Instant?,
)

internal data class AdProfile(val accessLevels: List<AdAccessLevel>)

/** The Adapty calls the adapter makes, behind one seam (tests use a fake). */
internal interface AdaptyClient {
    val isConfigured: Boolean

    suspend fun flow(placement: String): AdFlow?

    suspend fun profile(): AdProfile?

    fun profileUpdates(): Flow<AdProfile>

    fun setCustomAttribute(key: String, value: String)

    suspend fun purchase(activity: Activity, placement: String, product: AdProduct): OfferOutcome

    suspend fun present(activity: Activity, placement: String): OfferOutcome
}

internal class AdaptyAdapter(private val client: AdaptyClient, private val placements: List<String>) :
    OfferSource,
    OfferHandler,
    EntitlementSource {
    @Volatile internal var activity: () -> Activity? = { null }

    fun install(context: ModuleContext) {
        activity = context::currentActivity
        context.addOfferSource(this)
        context.addEntitlementSource(this)
        context.setOfferHandler(this)
        context.scope.launch { linkIdentity(PingFront.instance.storeAccountTokens()) }
    }

    suspend fun linkIdentity(tokens: Flow<String>) {
        tokens.collect { token -> if (client.isConfigured) client.setCustomAttribute(ATTRIBUTE, token) }
    }

    override suspend fun offers(): List<OfferSlot> {
        if (!client.isConfigured) return emptyList()
        val owned = client.profile()?.accessLevels.orEmpty().filter { it.active }.mapNotNull { it.productId }.toSet()
        return placements.mapNotNull { client.flow(it) }.map { slot(it, owned) }
    }

    override fun entitlementUpdates(): Flow<Entitlement> = flow {
        if (!client.isConfigured) return@flow
        client.profile()?.let { emit(entitlement(it)) }
        client.profileUpdates().collect { emit(entitlement(it)) }
    }

    override suspend fun handle(request: OfferRequest): OfferOutcome {
        if (!client.isConfigured) return OfferOutcome.Failed(OfferFailure.BillingUnavailable)
        if (request.offerId !in placements) return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        val flow = client.flow(request.offerId) ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        val host = activity() ?: return OfferOutcome.Failed(OfferFailure.NoActivity)
        product(flow, request.storeOffer)?.let { return client.purchase(host, flow.placement, it) }
        if (flow.hasView) return client.present(host, flow.placement)
        val first = flow.products.firstOrNull() ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        return client.purchase(host, flow.placement, first)
    }

    internal companion object {
        const val ATTRIBUTE: String = "pingfront_token"
        private const val TITLE_CHARS = 60
        private const val PRODUCTS = 10
        private const val SLOT_ID_CHARS = 64
        private val SLOT_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")

        fun slot(flow: AdFlow, owned: Set<String>): OfferSlot {
            val products = flow.products.take(PRODUCTS)
            return OfferSlot(
                id = flow.placement.takeIf { SLOT_ID.matches(it) } ?: flow.placement.filter { it.isLetterOrDigit() || it in "_.-" }.take(SLOT_ID_CHARS),
                title = flow.name.ifBlank { flow.placement }.let {
                    if (it.codePointCount(0, it.length) <=
                        TITLE_CHARS
                    ) {
                        it
                    } else {
                        it.substring(0, it.offsetByCodePoints(0, TITLE_CHARS))
                    }
                },
                kind = when (flow.kind) {
                    "discount" -> OfferSlot.Kind.Discount
                    "winback" -> OfferSlot.Kind.Winback
                    else -> OfferSlot.Kind.Standard
                },
                available = products.isEmpty() || !products.all { it.id in owned },
                products = products.map { OfferSlot.Product(it.id, it.price, it.period, it.trial) },
                source = OfferSlot.Source.Adapty,
            )
        }

        /** The product a card's Play offer names (`basePlanId` and `offerId`). */
        fun product(flow: AdFlow, offer: StoreOffer?): AdProduct? {
            if (offer == null || offer.kind == StoreOffer.Kind.Unknown || offer.basePlanId == null) return null
            return flow.products.firstOrNull {
                (offer.productId == null || it.id == offer.productId) && it.basePlanId == offer.basePlanId && it.offerId == offer.offerId
            }
        }

        /** Access levels to pingfront's state: grace, cancelled (will not renew), lifetime, trial, active. */
        fun entitlement(profile: AdProfile): Entitlement {
            val e = profile.accessLevels.firstOrNull { it.active }
                ?: return Entitlement(
                    if (profile.accessLevels.isEmpty()) Entitlement.State.None else Entitlement.State.Expired,
                    source = Entitlement.Source.Adapty,
                )
            val state = when {
                e.lifetime -> Entitlement.State.Lifetime
                e.grace -> Entitlement.State.Grace
                e.trial -> Entitlement.State.Trial
                !e.willRenew -> Entitlement.State.Cancelled
                else -> Entitlement.State.Active
            }
            return Entitlement(state, e.productId, e.expiresAt.takeIf { state != Entitlement.State.Lifetime }, Entitlement.Source.Adapty)
        }
    }
}
