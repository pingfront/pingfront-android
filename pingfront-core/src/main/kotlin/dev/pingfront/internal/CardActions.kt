package dev.pingfront.internal

import dev.pingfront.CardKind
import dev.pingfront.ErrorCode
import dev.pingfront.MomentResult
import dev.pingfront.OfferRequest
import dev.pingfront.OpenedCard
import dev.pingfront.PingFrontEvent
import dev.pingfront.PingFrontException
import dev.pingfront.PingFrontInstance
import dev.pingfront.StoreMomentProps
import dev.pingfront.WebLinks
import dev.pingfront.internal.messaging.ImagePreparation
import dev.pingfront.internal.messaging.MomentGate
import dev.pingfront.internal.net.Auth
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.wire.CardActionRequest
import dev.pingfront.internal.wire.CreateReportRequest
import dev.pingfront.internal.wire.Limits
import dev.pingfront.internal.wire.MomentRequest
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.Utf8
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.WireRules
import dev.pingfront.internal.wire.withoutPaths
import dev.pingfront.model.Card
import dev.pingfront.model.CardResult
import dev.pingfront.model.MessagePart
import dev.pingfront.model.ReviewMode
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val log = Log.of(Log.Category.Network)

/** Card actions, moments, reports and the self-check: one-shot operations on the engine's components. */
internal object CardActions {
    suspend fun act(engine: Engine, instance: PingFrontInstance, card: Card, messageId: String, choiceId: String?, rating: Int?) {
        val generation = engine.session.generation()
        if (card !is Card.Unknown) instance.emit(PingFrontEvent.CardOpened(OpenedCard(CardKind.of(card), card.id, messageId)))
        val action = when (card) {
            is Card.Choices -> {
                setLocalResult(instance, messageId, card.id, CardResult(engine.time.wall(), choiceId = choiceId))
                "chosen"
            }
            is Card.Rating -> {
                setLocalResult(instance, messageId, card.id, CardResult(engine.time.wall(), rating = rating))
                "rated"
            }
            is Card.Offer -> {
                // An offer kind this SDK does not know only opens the paywall (the protocol, Promotional offers).
                val storeOffer = card.offer?.takeIf { it.kind != dev.pingfront.model.StoreOffer.Kind.Unknown }
                val request = OfferRequest(card.offerId, engine.offers.value.firstOrNull { it.id == card.offerId }, card.id, messageId, storeOffer)
                instance.emit(PingFrontEvent.OfferRequested(request))
                val onOffer = instance.onOffer
                val handler = instance.registry.offerHandler
                when {
                    onOffer != null -> withContext(instance.mainDispatcher()) { onOffer(request) }
                    handler != null -> engine.scope.launch {
                        val outcome = handler.handle(request)
                        // Bought but waiting for payment (Play's pending purchases): the card shows it.
                        if (outcome == dev.pingfront.OfferOutcome.Pending) instance.refreshPurchases()
                        // The store could not be reached: the card says so (as store actions do) until the next try.
                        val unreachable = (outcome as? dev.pingfront.OfferOutcome.Failed)?.failure in STORE_UNREACHABLE
                        instance.conversation.update { state ->
                            val receipts = if (unreachable) {
                                state.storeReceipts + (card.id to dev.pingfront.conversation.StoreReceipt.Unavailable)
                            } else {
                                state.storeReceipts - card.id
                            }
                            state.copy(storeReceipts = receipts)
                        }
                        instance.emit(PingFrontEvent.OfferCompleted(request, outcome))
                    }
                    else -> log.e("An offer card was tapped but no offer handler is set. Set PingFront.onOffer or use the Billing module.")
                }
                "opened"
            }
            is Card.Review -> {
                review(instance, card)
                "opened"
            }
            is Card.Link -> {
                // One report per tap, once the outcome is known: opened, or failed with why (the protocol, Saved links).
                val failure = openLinkCard(instance, card)
                return report(engine, card, CardActionRequest(messageId, if (failure == null) "opened" else "failed", failure = failure?.wire), generation)
            }
            is Card.Report -> {
                withContext(instance.mainDispatcher()) { instance.openReport(null) }
                "opened"
            }
            is Card.StoreAction -> {
                if (!engine.storeActions.act(card, messageId)) return
                "opened"
            }
            is Card.Unknown -> return
        }
        report(engine, card, CardActionRequest(messageId, action, choiceId, rating), generation)
    }

    /** How long a tap waits for the app's `onScreen` or `onLink` answer before it counts as false (iOS's bound too). */
    internal val HANDLER_ANSWER_LIMIT: Duration = 10.seconds

    /**
     * The app's answer from [handler] (`onScreen` or `onLink`, [name] in the log), or false when none comes within
     * [HANDLER_ANSWER_LIMIT] on the injected main dispatcher's clock. A handler still running then is cancelled and
     * its late answer ignored (a bridge waiting for JavaScript or Dart), as on iOS. One that throws, or cancels itself,
     * counts as false and the SDK keeps running; a cancelled tap (logout, erase, stop) still ends here.
     */
    internal suspend fun answer(instance: PingFrontInstance, name: String, handler: suspend () -> Boolean): Boolean = try {
        withContext(instance.mainDispatcher()) { withTimeoutOrNull(HANDLER_ANSWER_LIMIT) { handler() } } ?: false
    } catch (e: CancellationException) {
        // The tap itself was cancelled: nothing more happens for it. Otherwise only the handler gave up.
        currentCoroutineContext().ensureActive()
        log.e("$name was cancelled (${e.javaClass.simpleName}); it counts as false.")
        false
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // The app's handler threw: it counts as false; the SDK keeps running.
        log.e("$name threw ${e.javaClass.simpleName}; it counts as false.")
        false
    }

    /** Why a link card didn't open (`LinkFailure`). */
    internal enum class LinkFailure(val wire: String) { NotHandled("not_handled"), UnknownScreen("unknown_screen"), CannotOpen("cannot_open") }

    /**
     * Opens a link card and says why it didn't open, or `null` when it did. A screen this build registered goes to
     * `onScreen`; any other screen falls back to the card's `url` (`unknown_screen` without one).
     */
    internal suspend fun openLinkCard(instance: PingFrontInstance, card: Card.Link): LinkFailure? {
        val screen = card.screen
        if (screen != null && instance.isScreenRegistered(screen.id)) {
            val handler = instance.onScreen ?: return LinkFailure.NotHandled
            return if (answer(instance, "onScreen") { handler(screen) }) null else LinkFailure.NotHandled
        }
        if (screen != null && card.url.isEmpty()) return LinkFailure.UnknownScreen
        return if (openCardUrl(instance, card.url)) null else LinkFailure.CannotOpen
    }

    /**
     * A link card's URL (the protocol, "Link card URLs"): the rule first, so a refused URL never reaches `onLink` or
     * the system; then the app's `onLink` (awaited, as `onScreen` is), else the presenter (the browser for web links,
     * an `ACTION_VIEW` intent limited to the app's own package for its own scheme). False when nothing opened it.
     */
    private suspend fun openCardUrl(instance: PingFrontInstance, url: String): Boolean {
        val cleaned = WebLinks.cleanCardUrl(url)
        if (!WebLinks.isCardLink(cleaned)) {
            log.e("A card link was not opened: its scheme is never opened from a card.")
            return false
        }
        val onLink = instance.onLink
        if (onLink != null && answer(instance, "onLink") { onLink(cleaned) }) return true
        return withContext(instance.mainDispatcher()) { instance.openLinkByDefault(cleaned) }
    }

    /**
     * Queues the report with a new clientId; the queue retries it until an answer settles it (the protocol, "Retrying
     * writes"). A logout or erase since the tap ([generation]) drops it: it belongs to the previous person (review CS-2).
     */
    private suspend fun report(engine: Engine, card: Card, request: CardActionRequest, generation: Int) {
        if (engine.session.generation() != generation) return
        engine.sender.cardActions.enqueue(card.id, request.copy(clientId = UUID.randomUUID().toString()))
    }

    private val STORE_UNREACHABLE = setOf(dev.pingfront.OfferFailure.BillingUnavailable, dev.pingfront.OfferFailure.StoreError)

    private val reviewTapped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A review card first opens the in-app review (Play In-App Review). Play may decide not to show it and does
     * not say so, so the store listing opens only on a second tap of the same card, or right away for
     * `store_page` cards and when no in-app review is available.
     */
    private suspend fun review(instance: PingFrontInstance, card: Card.Review) {
        val requester = instance.registry.reviewRequester
        val inApp = card.mode == ReviewMode.NativePrompt && requester != null && reviewTapped.add(card.id)
        if (inApp && requester.requestReview()) return
        val url = card.storeUrl ?: instance.storeListingUrl() ?: return
        openWebLink(instance, url)
    }

    /**
     * Review cards open http(s) only (review CS-11): the app's `onLink` first, else the browser. False when nothing
     * opened it.
     */
    private suspend fun openWebLink(instance: PingFrontInstance, url: String): Boolean {
        if (!WebLinks.isWeb(url)) {
            log.e("A card link was not opened: only http and https links are allowed.")
            return false
        }
        val onLink = instance.onLink
        if (onLink != null && answer(instance, "onLink") { onLink(url) }) return true
        return withContext(instance.mainDispatcher()) { instance.registry.chatPresenter?.openLink(url) ?: false }
    }

    private fun setLocalResult(instance: PingFrontInstance, messageId: String, cardId: String, result: CardResult) {
        instance.conversation.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id != messageId) return@map message
                    message.copy(
                        parts = message.parts.map {
                            if (it is MessagePart.CardPart &&
                                it.card.id == cardId
                            ) {
                                it.copy(result = result)
                            } else {
                                it
                            }
                        },
                    )
                },
            )
        }
    }

    private suspend fun refreshListened(engine: Engine, generation: Int) {
        if (engine.moments.isFresh()) return
        val listened = try {
            engine.client.send(Endpoints.listenedMoments())
        } catch (e: PingFrontException) {
            if (e.code != ErrorCode.Unauthorized) throw e
            engine.client.send(Endpoints.listenedMoments(Auth.Bearer), generation)
        }
        engine.moments.update(listened.names, listened.storeEvents)
    }

    suspend fun moment(engine: Engine, instance: PingFrontInstance, name: String, props: Map<String, Any>, store: StoreMomentProps? = null): MomentResult {
        if (!MomentGate.isValid(name)) {
            log.e("moment: \"$name\" is not a valid name. Use [a-z0-9][a-z0-9_.-]{0,63}.")
            return MomentResult.InvalidName
        }
        return try {
            val generation = engine.session.generation()
            refreshListened(engine, generation)
            val listened = if (store != null) engine.moments.containsStoreEvent(name) else engine.moments.contains(name)
            if (!listened) return MomentResult.NotListened
            val json = encodeProps(props)
            val request = MomentRequest(name, json.takeIf { it.isNotEmpty() }, store?.let(::validStore), engine.time.wall(), UUID.randomUUID().toString())
            val response = engine.client.send(Endpoints.moment(request), generation)
            if (response.open) {
                val present = engine.presentsChatForMoments
                if (present) withContext(instance.mainDispatcher()) { instance.openChat(null) }
                return MomentResult.OpenRequested(presented = present)
            }
            when (response.outcome) {
                "scheduled" -> MomentResult.Scheduled
                "delivered" -> MomentResult.Delivered
                "held_out" -> MomentResult.HeldOut
                else -> MomentResult.Ignored
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            engine.noteFailure(e)
            MomentResult.Failed(e)
        }
    }

    /** Store fields the contract would refuse are dropped (the moment still goes out with the rest). */
    private fun validStore(store: StoreMomentProps): StoreMomentProps {
        val json = WireJson.encodeToJsonElement(StoreMomentProps.serializer(), store)
        val problems = WireRules.problems("StoreMomentProps", json).orEmpty()
        if (problems.isEmpty()) return store
        log.e("Store fields not sent because they break the contract's rules: ${problems.joinToString()}")
        return WireJson.decodeFromJsonElement(StoreMomentProps.serializer(), json.withoutPaths(problems))
    }

    fun encodeProps(props: Map<String, Any>): JsonObject {
        val result = linkedMapOf<String, JsonPrimitive>()
        for ((key, value) in props) {
            if (result.size >= Limits.MOMENT_PROPS) {
                log.e("moment: more than ${Limits.MOMENT_PROPS} props; \"$key\" was dropped.")
                continue
            }
            // Code points, never UTF-16 units: a key never ends in half a surrogate pair (review CQ-L2).
            result[Limits.truncate(key, PROP_KEY_CHARS)] = when (value) {
                is String -> JsonPrimitive(Limits.truncate(value, PROP_VALUE_CHARS))
                // NaN and infinities have no JSON form (review CQ-L10).
                is Double, is Float -> if (value.toDouble().isFinite()) {
                    JsonPrimitive(value)
                } else {
                    log.e("moment: prop \"$key\" is not a finite number; it was dropped.")
                    continue
                }
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> {
                    log.e("moment: prop \"$key\" must be a String, Number or Boolean; it was dropped.")
                    continue
                }
            }
        }
        return JsonObject(result)
    }

    suspend fun reportBug(engine: Engine, instance: PingFrontInstance, text: String, screenshots: List<ByteArray>) {
        val generation = engine.session.generation()
        val ids = screenshots.take(3).map { data ->
            val image = withContext(instance.defaultDispatcher()) { ImagePreparation.prepare(data) }
            val requestId = UUID.nameUUIDFromBytes(image.sha256.encodeToByteArray()).toString()
            engine.uploader.upload(image.data, image.width, image.height, image.sha256, requestId, SessionReason.Report, generation = generation)
        }
        val context = instance.registry.reportContext?.reportContext()
        val tail = context?.logTail?.let { Utf8.tail(it, Limits.REPORT_LOG_BYTES) }
        val request =
            CreateReportRequest(
                UUID.randomUUID().toString(),
                Limits.truncate(text, Limits.TEXT_CHARS),
                ids,
                tail,
                context?.state?.takeIf {
                    it.isNotEmpty()
                },
            )
        val response = engine.client.send(Endpoints.createReport(request), generation)
        // Reports never move the sequence position; the message only joins the thread (of this session only).
        if (engine.session.generation() == generation) instance.conversation.merge(listOf(response.message))
        instance.emit(PingFrontEvent.ReportSent)
    }
}

private const val PROP_KEY_CHARS = 64
private const val PROP_VALUE_CHARS = 256
