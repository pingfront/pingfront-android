package dev.pingfront.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.model.Card
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart

/** Which side a bubble sits on. */
internal enum class BubbleSide { Team, Person }

/**
 * Google Messages-style shapes: round corners, with tight corners on the sender's side between
 * consecutive bubbles of a group. No tails. Start and end follow the layout direction (RTL-safe).
 */
@Composable
internal fun bubbleShape(side: BubbleSide, joinTop: Boolean, joinBottom: Boolean): Shape {
    val theme = LocalChatTheme.current
    val r = theme.bubbleRadius
    val j = theme.joinRadius
    return when (side) {
        BubbleSide.Team -> RoundedCornerShape(
            topStart = if (joinTop) j else r,
            topEnd = r,
            bottomEnd = r,
            bottomStart = if (joinBottom) j else r,
        )
        BubbleSide.Person -> RoundedCornerShape(
            topStart = r,
            topEnd = if (joinTop) j else r,
            bottomEnd = if (joinBottom) j else r,
            bottomStart = r,
        )
    }
}

/**
 * Text people or the server wrote takes its direction from its own first letters, as in Google Messages: an English
 * message in an Arabic chat reads "Which alarm do you set most?", not "?Which alarm do you set most".
 */
internal val AuthoredText = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content)

/** Bubbles take at most 75% of the chat column (Google Messages); provided by the chat screen. */
internal val LocalMaxBubbleWidth = androidx.compose.runtime.staticCompositionLocalOf { 300.dp }

/** The bubble width limit for a chat column of [columnWidth]. */
internal fun maxBubbleWidth(columnWidth: Dp): Dp = columnWidth * BUBBLE_FRACTION

private const val BUBBLE_FRACTION = 0.75f

/** Cards are about 280 dp wide (never wider than a bubble may be). */
internal val CardWidth: Dp = 280.dp

@Composable
internal fun TextBubble(text: String, side: BubbleSide, joinTop: Boolean, joinBottom: Boolean, italic: Boolean = false) {
    val theme = LocalChatTheme.current
    val container = if (side == BubbleSide.Person) theme.accent else theme.teamBubble
    val content = if (side == BubbleSide.Person) theme.onAccent else theme.onTeamBubble
    val annotated = remember(text, content) { Links.annotate(text, content) }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyLarge.merge(AuthoredText),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        color = content,
        modifier = Modifier
            .widthIn(max = LocalMaxBubbleWidth.current)
            .clip(bubbleShape(side, joinTop, joinBottom))
            .background(container)
            .padding(horizontal = theme.bubbleHorizontal, vertical = theme.bubbleVertical),
    )
}

@Composable
internal fun ImageBubble(
    image: MessagePart.Image,
    side: BubbleSide,
    joinTop: Boolean,
    joinBottom: Boolean,
    instance: PingFrontInstance,
    cell: Boolean = false,
) {
    if (image.removed) {
        RemovedPhotoBubble(side, joinTop, joinBottom, cell)
    } else {
        PhotoBubble(image, side, joinTop, joinBottom, instance, cell)
    }
}

/**
 * A photo the app's retention deleted (`removed: true`): a quiet muted line where it was, never fetched and
 * never loading. In a photo grid the cell shows the symbol alone.
 */
@Composable
internal fun RemovedPhotoBubble(side: BubbleSide, joinTop: Boolean, joinBottom: Boolean, cell: Boolean = false) {
    val theme = LocalChatTheme.current
    val label = stringResource(R.string.pf_photo_removed)
    if (cell) {
        // Said in words in the grid too, as on iOS, so a removed photo never looks like one still loading.
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f).background(theme.teamBubble).padding(4.dp).clearAndSetSemantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Icon(painterResource(R.drawable.pf_ic_photo_library), contentDescription = null, tint = theme.muted, modifier = Modifier.size(REMOVED_ICON))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = theme.muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    } else {
        Row(
            Modifier
                .widthIn(max = LocalMaxBubbleWidth.current)
                .clip(bubbleShape(side, joinTop, joinBottom))
                .background(theme.teamBubble)
                .padding(horizontal = theme.bubbleHorizontal, vertical = theme.bubbleVertical)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(REMOVED_ICON_GAP),
        ) {
            Icon(
                painterResource(R.drawable.pf_ic_photo_library),
                contentDescription = null,
                tint = theme.muted,
                modifier = Modifier.size(REMOVED_ICON),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = theme.muted)
        }
    }
}

private val REMOVED_ICON = 18.dp
private val REMOVED_ICON_GAP = 6.dp

@Composable
private fun PhotoBubble(image: MessagePart.Image, side: BubbleSide, joinTop: Boolean, joinBottom: Boolean, instance: PingFrontInstance, cell: Boolean) {
    val theme = LocalChatTheme.current
    val widthPx = with(LocalDensity.current) { (if (cell) IMAGE_WIDTH / 2 else IMAGE_WIDTH).roundToPx() }
    val bitmap = rememberChatImage("att:${image.attachmentId}", image.url, widthPx) { instance.conversation.refreshedUrl(image) }
    val ratio = if (cell) 1f else (image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(MIN_RATIO, MAX_RATIO)
    var viewing by remember { mutableStateOf(false) }
    // The viewer decodes at the screen's width, not a fixed 1080 dp (about 3000 px; review CQ-M14).
    val fullPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.coerceAtLeast(widthPx)
    val full = if (viewing) {
        rememberChatImage("att:${image.attachmentId}", image.url, fullPx) {
            instance.conversation.refreshedUrl(image)
        }
    } else {
        null
    }
    if (viewing) (full ?: bitmap)?.let { PhotoViewer(it) { viewing = false } }
    val openLabel = stringResource(R.string.pf_open_photo)
    Box(
        Modifier
            .widthIn(max = IMAGE_WIDTH)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .then(if (cell) Modifier else Modifier.clip(bubbleShape(side, joinTop, joinBottom)))
            .background(theme.teamBubble)
            .clickable(enabled = bitmap != null, onClickLabel = openLabel) { viewing = true },
    ) {
        if (bitmap !=
            null
        ) {
            Image(
                bitmap,
                contentDescription = stringResource(R.string.pf_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            )
        }
    }
}

/**
 * Two or more photos of one message: a two-column grid of square cells inside one bubble shape (audit X-06),
 * instead of a stack of full-size photos. Each cell opens the viewer on that photo.
 */
// `cell` is an item renderer (like LazyColumn's item content), called once per photo with its index; each
// call is keyed by that index, so no slot state is shared between photos.
@Suppress("ContentSlotReused")
@Composable
internal fun PhotoGrid(count: Int, side: BubbleSide, joinTop: Boolean, joinBottom: Boolean, cell: @Composable (index: Int) -> Unit) {
    Column(
        Modifier.width(IMAGE_WIDTH).clip(bubbleShape(side, joinTop, joinBottom)),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        for (first in 0 until count step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                Box(Modifier.weight(1f)) { androidx.compose.runtime.key(first) { cell(first) } }
                if (first + 1 < count) {
                    Box(Modifier.weight(1f)) { androidx.compose.runtime.key(first + 1) { cell(first + 1) } }
                } else {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

private val GRID_GAP = 2.dp
private val IMAGE_WIDTH = 220.dp
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2f

/** A photo the outbox still holds (decrypted bytes), shown while it sends. */
@Composable
internal fun PendingPhotoBubble(photo: PendingMessage.Photo, joinTop: Boolean, joinBottom: Boolean, instance: PingFrontInstance, cell: Boolean = false) {
    val theme = LocalChatTheme.current
    val widthPx = with(LocalDensity.current) { IMAGE_WIDTH.roundToPx() }
    val still = LocalChatPreview.current.still
    var bitmap by remember(photo.sha256) {
        mutableStateOf(
            ChatImages.cached("pending:${photo.sha256}")
                ?: if (still) ChatImages.fixtureNow("pending:${photo.sha256}", "${ChatImages.FIXTURE}image/pending", widthPx) else null,
        )
    }
    androidx.compose.runtime.LaunchedEffect(photo.sha256) {
        if (bitmap ==
            null
        ) {
            bitmap = instance.conversation.photoData(photo)?.let { ChatImages.decodeLocal("pending:${photo.sha256}", it, widthPx) }
        }
    }
    val ratio = if (cell) 1f else (photo.width.toFloat() / photo.height.coerceAtLeast(1)).coerceIn(MIN_RATIO, MAX_RATIO)
    Box(
        Modifier.widthIn(max = IMAGE_WIDTH).fillMaxWidth().aspectRatio(ratio)
            .then(if (cell) Modifier else Modifier.clip(bubbleShape(BubbleSide.Person, joinTop, joinBottom)))
            .background(theme.teamBubble),
    ) {
        bitmap?.let {
            Image(
                it,
                contentDescription = stringResource(R.string.pf_pending_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            )
        }
    }
}

/** The parts of one delivered message, each its own bubble, grouped with the rows around it. */
@Composable
internal fun Parts(message: Message, side: BubbleSide, texts: List<String>?, joinTop: Boolean, joinBottom: Boolean, instance: PingFrontInstance) {
    val theme = LocalChatTheme.current
    var textIndex = 0
    val visible = message.parts.filterNot { it is MessagePart.CardPart && it.card is Card.Unknown && message.fallback == null }
    val images = visible.filterIsInstance<MessagePart.Image>()
    val firstImage = visible.indexOfFirst { it is MessagePart.Image }
    Column(
        verticalArrangement = Arrangement.spacedBy(theme.rowSpacing),
        horizontalAlignment = if (side == BubbleSide.Person) Alignment.End else Alignment.Start,
    ) {
        visible.forEachIndexed { index, part ->
            val top = joinTop || index > 0
            val bottom = joinBottom || index < visible.lastIndex
            when (part) {
                is MessagePart.Text -> {
                    val text = texts?.getOrNull(textIndex++) ?: part.text
                    TextBubble(text, side, top, bottom)
                    if (LinkPreviews.allowedFor(message)) Links.first(text)?.let { LinkPreview(it) }
                }
                is MessagePart.Image -> when {
                    images.size < 2 -> ImageBubble(part, side, top, bottom, instance)
                    index == firstImage -> PhotoGrid(images.size, side, top, joinBottom || visible.drop(index).any { it !is MessagePart.Image }) {
                        ImageBubble(images[it], side, top, bottom, instance, cell = true)
                    }
                    else -> Unit
                }
                is MessagePart.CardPart -> if (part.card is Card.Unknown) {
                    TextBubble(
                        message.fallback ?: stringResource(R.string.pf_unknown_content),
                        side,
                        top,
                        bottom,
                        italic =
                            message.fallback == null,
                    )
                } else {
                    CardView(part, message, instance)
                }
                is MessagePart.Receipt -> ReceiptLine(part)
                is MessagePart.Unknown -> TextBubble(
                    message.fallback ?: stringResource(R.string.pf_unknown_content),
                    side,
                    top,
                    bottom,
                    italic =
                        message.fallback == null,
                )
            }
        }
    }
}

/** A centered system line with a green check ("Trial extended to 14 Nov"). */
@Composable
internal fun ReceiptLine(text: String, check: Boolean = true) {
    val theme = LocalChatTheme.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (check) {
            Icon(
                painterResource(R.drawable.pf_ic_check_circle),
                contentDescription = null,
                tint = theme.success,
                modifier = Modifier.size(16.dp).testTag(RECEIPT_CHECK_TAG),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.merge(SystemLineStyle).merge(AuthoredText),
            color = theme.muted,
            textAlign = if (check) null else androidx.compose.ui.text.style.TextAlign.Center,
            modifier = if (check) Modifier.padding(start = 6.dp) else Modifier,
        )
    }
}

/** Test tag of a receipt line's green check. */
internal const val RECEIPT_CHECK_TAG: String = "pf_receipt_check"

/** A receipt's line: the green check means something was done for the person, so a hand-off has none. */
@Composable
internal fun ReceiptLine(receipt: MessagePart.Receipt) = ReceiptLine(receipt.text, check = receipt.action != dev.pingfront.model.ReceiptAction.HandedOff)

/** Cards: Material cards inside the thread with one clear button; choices are chips; rating is a star row. */
@Composable
internal fun CardView(part: MessagePart.CardPart, message: Message, instance: PingFrontInstance) {
    val conversation = instance.conversation
    val look by instance.look.collectAsState()
    when (val card = part.card) {
        is Card.Choices -> Choices(card, part.result?.choiceId, message, instance)
        is Card.Rating -> Rating(card, part.result?.rating, message, instance)
        is Card.Offer -> if (card.id in LocalSupersededOffers.current) {
            // A newer offer card exists (any offer): this one collapses to a single quiet line, as on iOS and the web.
            val theme = LocalChatTheme.current
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.pf_ic_history), contentDescription = null, tint = theme.muted, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.pf_offer_replaced),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.muted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        } else {
            val owned = ownsOffer(card.offerId, instance)
            val slots by instance.offerSlots.collectAsState()
            // The app reports the offer as no longer available (OfferSlot.available): a card already in the thread
            // says so, muted, without its price, and its button does nothing.
            val ended = !owned && slots.firstOrNull { it.id == card.offerId }?.available == false
            val productIds = slots.firstOrNull { it.id == card.offerId }?.products?.map { it.id }.orEmpty()
            // A named store offer is priced as named; otherwise the slot's product, as the purchase sheet buys it.
            val storeOffer = card.offer?.takeIf { it.kind != dev.pingfront.model.StoreOffer.Kind.Unknown }
            val product = storeOffer?.productId ?: productIds.firstOrNull()
            val promo by androidx.compose.runtime.produceState<dev.pingfront.OfferPrice?>(null, storeOffer, product) {
                value = storeOffer?.let { instance.storeOfferPrice(it) } ?: product?.let { instance.productPrice(it) }
            }
            // A pending purchase (Play: bought, waiting for payment) is not owned yet: the card says so.
            val pendingProducts by instance.pendingProducts.collectAsState()
            androidx.compose.runtime.LaunchedEffect(card.id) { instance.refreshPurchases() }
            val pending = (productIds + listOfNotNull(storeOffer?.productId)).any { it in pendingProducts }
            val chat by instance.conversation.state.collectAsState()
            val unreachable = chat.storeReceipts[card.id] == dev.pingfront.conversation.StoreReceipt.Unavailable
            val phases = promo?.phases.orEmpty()
            val change = promo?.change?.takeIf { !owned }?.let {
                stringResource(if (it == dev.pingfront.PlanChange.Now) R.string.pf_change_now else R.string.pf_change_at_renewal)
            }
            Column {
                CardFrame(
                    icon = null,
                    title = card.title,
                    subtitle = when {
                        // An ended offer has no price to show.
                        ended -> null
                        // Every phase, as the store will charge it: "1 week free, then €29.99 a year".
                        phases.size > 1 -> phaseLine(phases)
                        else -> promo?.let { periodPrice(it.price, it.period) } ?: priceLine(card.offerId, instance)
                    },
                    regularPrice = promo?.regularPrice?.takeIf { phases.size <= 1 && !ended }?.let { periodPrice(it, promo?.period) },
                    detail = when {
                        pending -> stringResource(R.string.pf_pending_detail)
                        else -> listOfNotNull(card.body, change).joinToString("\n").ifEmpty { null }
                    },
                    button = when {
                        owned -> stringResource(R.string.pf_current_plan)
                        pending -> stringResource(R.string.pf_waiting_for_payment)
                        else -> card.button
                    },
                    filled = !owned && !pending && !ended,
                    enabled = !owned && !pending && !ended,
                    leading = { AppIcon(look, CARD_LEADING) },
                    muted = ended,
                    note = if (ended) stringResource(R.string.pf_offer_ended) else null,
                ) { conversation.open(card, message) }
                if (unreachable) {
                    Text(
                        stringResource(R.string.pf_store_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatTheme.current.muted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
        is Card.Review -> CardFrame(
            icon = null,
            title = stringResource(R.string.pf_enjoying, look.appName),
            subtitle = null,
            detail = stringResource(R.string.pf_review_helps, look.appName),
            button = card.button,
            filled = true,
            leading = { AppIcon(look, CARD_LEADING) },
            // In-app review stays in the app; only the store-page fallback leaves it.
            outbound = if (card.mode == dev.pingfront.model.ReviewMode.NativePrompt) null else stringResource(R.string.pf_opens_store),
        ) { conversation.open(card, message) }
        is Card.Link -> {
            // A screen this build registered opens in the app: no host, no "opens browser" hint.
            val inApp = card.screen?.let { instance.isScreenRegistered(it.id) } == true
            CardFrame(
                icon = R.drawable.pf_ic_menu_book,
                title = card.title,
                subtitle = null,
                detail = if (inApp) null else runCatching { java.net.URI(card.url).host }.getOrNull(),
                button = card.button,
                filled = false,
                outbound = if (inApp) null else stringResource(R.string.pf_opens_browser),
            ) { conversation.open(card, message) }
        }
        is Card.Report -> CardFrame(
            icon = R.drawable.pf_ic_bug_report,
            title = card.title,
            subtitle = null,
            detail = stringResource(R.string.pf_includes_screenshot),
            button = card.button,
            filled = false,
        ) { conversation.open(card, message) }
        is Card.StoreAction -> StoreActionCard(card, message, instance)
        is Card.Unknown -> Unit
    }
}

/**
 * A store action card: the store's own pages for what only the store can do. Restore works in place; the
 * others open Google Play and say so. A local receipt line appears under the card once something changed.
 */
@Composable
private fun StoreActionCard(card: Card.StoreAction, message: Message, instance: PingFrontInstance) {
    val strings = StoreActionStrings.of(card.action)
    val state by instance.conversation.state.collectAsState()
    Column {
        CardFrame(
            icon = strings.icon,
            // The team's words when the card has them, else the SDK's localized copy for the action.
            title = card.title ?: stringResource(strings.title),
            subtitle = null,
            detail = card.body ?: stringResource(strings.detail),
            button = card.button ?: stringResource(strings.button),
            filled = card.action == Card.StoreAction.Action.BillingIssue,
            outbound = if (card.action == Card.StoreAction.Action.RestorePurchases) null else stringResource(R.string.pf_opens_store),
        ) { instance.conversation.open(card, message) }
        // The payment-issue line pairs with the billing-issue card; manage cards show any plan status.
        when (card.action) {
            Card.StoreAction.Action.ManageSubscriptions -> PlanStatus(instance, paymentIssueOnly = false)
            Card.StoreAction.Action.BillingIssue -> PlanStatus(instance, paymentIssueOnly = true)
            else -> Unit
        }
        when (val receipt = state.storeReceipts[card.id]) {
            null -> Unit
            dev.pingfront.conversation.StoreReceipt.Unavailable -> Text(
                stringResource(R.string.pf_store_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatTheme.current.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            else -> ReceiptLine(stringResource(StoreActionStrings.receipt(receipt)))
        }
    }
}

/** One plan status line (the protocol, "Plan status"), as plain data so the rules are tested without Compose. */
internal data class PlanLine(val kind: Kind, val date: java.time.Instant?) {
    enum class Kind { Renews, Ends, Ended, PaymentIssue }

    companion object {
        /**
         * Trial or active: renews (`renewsAt`). Grace or billing retry: payment issue, update by `gracePeriodEndsAt`.
         * Cancelled: ends (`expiresAt`). Expired: ended. Nothing for none, lifetime and unknown states.
         */
        fun of(e: dev.pingfront.model.Entitlement): PlanLine? = when (e.state) {
            dev.pingfront.model.Entitlement.State.Trial, dev.pingfront.model.Entitlement.State.Active -> PlanLine(Kind.Renews, e.renewsAt)
            dev.pingfront.model.Entitlement.State.Grace, dev.pingfront.model.Entitlement.State.BillingRetry ->
                PlanLine(Kind.PaymentIssue, e.gracePeriodEndsAt)
            dev.pingfront.model.Entitlement.State.Cancelled -> PlanLine(Kind.Ends, e.expiresAt)
            dev.pingfront.model.Entitlement.State.Expired -> PlanLine(Kind.Ended, e.expiresAt)
            else -> null
        }

        /** The server's plan (built from store events, with dates) wins; the device's is the fallback. */
        fun source(server: dev.pingfront.model.Entitlement?, device: dev.pingfront.model.Entitlement?): dev.pingfront.model.Entitlement? =
            server?.takeIf { it.state != dev.pingfront.model.Entitlement.State.Unknown } ?: device
    }
}

/**
 * The person's plan in one line: "Pro renews 12 Nov", "Payment issue: update by 3 Nov". Under manage cards any
 * status shows; under a billing-issue card only the payment issue does ([paymentIssueOnly]).
 */
@Composable
private fun PlanStatus(instance: PingFrontInstance, paymentIssueOnly: Boolean) {
    val device by instance.entitlement.collectAsState()
    val person by instance.person.collectAsState()
    val slots by instance.offerSlots.collectAsState()
    val look by instance.look.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { instance.refreshPurchases() }
    val current = PlanLine.source(person?.entitlement, device) ?: return
    val line = PlanLine.of(current)?.takeIf { !paymentIssueOnly || it.kind == PlanLine.Kind.PaymentIssue } ?: return
    val name = slots.firstOrNull { slot -> slot.products.any { it.id == current.productId } }?.title ?: look.appName
    val date = line.date?.let(::shortDate)
    val text = when (line.kind) {
        PlanLine.Kind.Renews -> if (date != null) stringResource(R.string.pf_plan_renews, name, date) else stringResource(R.string.pf_plan_renews_auto, name)
        PlanLine.Kind.Ends -> if (date != null) stringResource(R.string.pf_plan_ends, name, date) else stringResource(R.string.pf_plan_wont_renew, name)
        PlanLine.Kind.Ended -> if (date != null) stringResource(R.string.pf_plan_ended, name, date) else stringResource(R.string.pf_plan_ended_undated, name)
        PlanLine.Kind.PaymentIssue -> if (date != null) stringResource(R.string.pf_plan_issue_by, date) else stringResource(R.string.pf_plan_issue)
    }.let { if (current.isTestPurchase) stringResource(R.string.pf_plan_test_purchase, it) else it }
    val problem = line.kind == PlanLine.Kind.PaymentIssue
    val theme = LocalChatTheme.current
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (problem) R.drawable.pf_ic_error else R.drawable.pf_ic_info),
            contentDescription = null,
            tint = if (problem) MaterialTheme.colorScheme.error else theme.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.labelLarge.merge(SystemLineStyle), color = theme.muted, modifier = Modifier.padding(start = 6.dp))
    }
}

/** "12 Nov" in the person's locale. */
private fun shortDate(instant: java.time.Instant): String {
    val locale = java.util.Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM")
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).withZone(java.time.ZoneId.systemDefault()).format(instant)
}

/** Icons and copy for store action cards (the card carries only the action, so the SDK localizes it). */
internal class StoreActionStrings(val icon: Int, val title: Int, val detail: Int, val button: Int) {
    companion object {
        fun of(action: Card.StoreAction.Action): StoreActionStrings = when (action) {
            // An action this SDK does not know opens the subscription page (the protocol).
            Card.StoreAction.Action.ManageSubscriptions, Card.StoreAction.Action.Unknown ->
                StoreActionStrings(R.drawable.pf_ic_settings, R.string.pf_store_manage_title, R.string.pf_store_manage_detail, R.string.pf_store_manage_button)
            Card.StoreAction.Action.BillingIssue ->
                StoreActionStrings(
                    R.drawable.pf_ic_credit_card,
                    R.string.pf_store_billing_title,
                    R.string.pf_store_billing_detail,
                    R.string.pf_store_billing_button,
                )
            Card.StoreAction.Action.RequestRefund ->
                StoreActionStrings(R.drawable.pf_ic_sell, R.string.pf_store_refund_title, R.string.pf_store_refund_detail, R.string.pf_store_refund_button)
            Card.StoreAction.Action.RedeemCode ->
                StoreActionStrings(R.drawable.pf_ic_redeem, R.string.pf_store_redeem_title, R.string.pf_store_redeem_detail, R.string.pf_store_redeem_button)
            Card.StoreAction.Action.RestorePurchases ->
                StoreActionStrings(
                    R.drawable.pf_ic_history,
                    R.string.pf_store_restore_title,
                    R.string.pf_store_restore_detail,
                    R.string.pf_store_restore_button,
                )
        }

        fun receipt(receipt: dev.pingfront.conversation.StoreReceipt): Int = when (receipt) {
            dev.pingfront.conversation.StoreReceipt.Restored -> R.string.pf_store_receipt_restored
            dev.pingfront.conversation.StoreReceipt.NothingToRestore -> R.string.pf_store_receipt_nothing
            dev.pingfront.conversation.StoreReceipt.UpToDate -> R.string.pf_store_receipt_up_to_date
            dev.pingfront.conversation.StoreReceipt.Updated -> R.string.pf_store_receipt_updated
            dev.pingfront.conversation.StoreReceipt.Unavailable -> R.string.pf_store_unavailable
        }
    }
}

/**
 * A card: a compact row (a 40 dp icon or the app icon, then title, price or subtitle and detail) and one
 * button (audit X-03: no empty tinted band). Buttons that leave the app carry an outbound mark and say where
 * they go to TalkBack (audit X-04).
 */
@Composable
internal fun CardFrame(
    icon: Int?,
    title: String,
    subtitle: String?,
    detail: String?,
    button: String,
    filled: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    outbound: String? = null,
    regularPrice: String? = null,
    muted: Boolean = false,
    note: String? = null,
    onClick: () -> Unit,
) {
    val theme = LocalChatTheme.current
    Column(
        Modifier.width(minOf(CardWidth, LocalMaxBubbleWidth.current)).clip(RoundedCornerShape(theme.cardRadius)).background(theme.teamBubble),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            when {
                leading != null -> leading()
                icon != null -> Box(
                    Modifier.size(CARD_LEADING).clip(RoundedCornerShape(12.dp)).background(theme.accentTint),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(icon), contentDescription = null, tint = theme.onAccentTint, modifier = Modifier.size(22.dp)) }
            }
            Column(Modifier.padding(start = if (leading != null || icon != null) 12.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.merge(AuthoredText),
                    fontWeight = FontWeight.SemiBold,
                    color = if (muted) theme.muted else theme.onTeamBubble,
                )
                if (subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subtitle, style = MaterialTheme.typography.labelLarge, color = theme.accentText)
                        if (regularPrice != null) {
                            Text(
                                regularPrice,
                                style = MaterialTheme.typography.labelLarge.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                                color = theme.muted,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium.merge(AuthoredText), color = theme.muted)
            }
        }
        val modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .then(
                if (outbound != null) {
                    Modifier.semantics {
                        semanticsOnClick(label = outbound) {
                            onClick()
                            true
                        }
                    }
                } else {
                    Modifier
                },
            )
        val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Text(button)
            if (outbound != null) {
                Icon(painterResource(R.drawable.pf_ic_open_in_new), contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
        if (note != null) {
            // A state, not a broken button: the line takes the button's place and says why there is nothing to tap.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50)).background(theme.background.copy(alpha = NOTE_FILL_ALPHA))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.pf_ic_info), contentDescription = null, tint = theme.muted, modifier = Modifier.size(18.dp))
                Text(note, style = MaterialTheme.typography.labelLarge, color = theme.muted, modifier = Modifier.padding(start = 8.dp))
            }
        } else if (filled) {
            Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(50), content = content)
        } else {
            FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(50), content = content)
        }
    }
}

private const val NOTE_FILL_ALPHA = 0.6f

private val CARD_LEADING = 40.dp

/** Offer card ids that a later card for the same offer replaced. */
internal val LocalSupersededOffers = androidx.compose.runtime.staticCompositionLocalOf { emptySet<String>() }

/**
 * Computes the superseded offer cards of a thread: every offer card except the newest, whatever its offer, as on iOS
 * and the web (a newer offer replaces an older one even when it is a different offer).
 */
internal fun supersededOffers(messages: List<Message>): Set<String> {
    val offers = messages.flatMap { m -> m.parts.mapNotNull { ((it as? MessagePart.CardPart)?.card as? Card.Offer)?.id } }
    return offers.dropLast(1).toHashSet()
}

/** True when the person's entitlement already covers a product in the offer ("Current plan"). */
@Composable
private fun ownsOffer(offerId: String, instance: PingFrontInstance): Boolean {
    val current by instance.entitlement.collectAsState()
    val slots by instance.offerSlots.collectAsState()
    val entitlement = current ?: return false
    if (entitlement.state !in OWNED_STATES) return false
    val products = slots.firstOrNull { it.id == offerId }?.products?.map { it.id }.orEmpty()
    return entitlement.productId != null && entitlement.productId in products
}

private val OWNED_STATES = setOf(
    dev.pingfront.model.Entitlement.State.Active,
    dev.pingfront.model.Entitlement.State.Trial,
    dev.pingfront.model.Entitlement.State.Grace,
    dev.pingfront.model.Entitlement.State.Lifetime,
)

/** "€29.99 a year, first month free" from the offer slot the app reported. */
@Composable
private fun priceLine(offerId: String, instance: PingFrontInstance): String? {
    val slots by instance.offerSlots.collectAsState()
    val product = slots.firstOrNull { it.id == offerId }?.products?.firstOrNull() ?: return null
    val price = periodPrice(product.price, product.period)
    return when (product.trial) {
        "P1M" -> stringResource(R.string.pf_first_month_free, price)
        "P1W" -> stringResource(R.string.pf_first_week_free, price)
        else -> price
    }
}

/** Pricing phases as one line: "1 week free, then €1.99 a month for 3 months, then €29.99 a year". */
@Composable
private fun phaseLine(phases: List<dev.pingfront.PricePhase>): String {
    val parts = phases.map { phase ->
        val span = phase.cycles?.let { duration(phase.period, it) }
        when {
            phase.free -> stringResource(R.string.pf_phase_free, span ?: duration(phase.period, 1) ?: phase.period)
            span != null -> stringResource(R.string.pf_phase_for, periodPrice(phase.price, phase.period), span)
            else -> periodPrice(phase.price, phase.period)
        }
    }
    return parts.drop(1).fold(parts.first()) { line, next -> stringResource(R.string.pf_phase_then, line, next) }
}

/** "3 months" for `P1M` times 3; null for a period the chat can't read. */
@Composable
private fun duration(period: String, cycles: Int): String? {
    val match = Regex("^P(\\d+)([DWMY])$").find(period) ?: return null
    val count = match.groupValues[1].toInt() * cycles
    val plural = when (match.groupValues[2]) {
        "D" -> R.plurals.pf_duration_day
        "W" -> R.plurals.pf_duration_week
        "M" -> R.plurals.pf_duration_month
        else -> R.plurals.pf_duration_year
    }
    return androidx.compose.ui.res.pluralStringResource(plural, count, count)
}

/** "€29.99 a year" for an ISO 8601 period the chat knows; the bare price otherwise. */
@Composable
private fun periodPrice(price: String, period: String?): String = when (period) {
    "P1Y" -> stringResource(R.string.pf_price_year, price)
    "P1M" -> stringResource(R.string.pf_price_month, price)
    "P1W" -> stringResource(R.string.pf_price_week, price)
    else -> price
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(card: Card.Choices, result: String?, message: Message, instance: PingFrontInstance) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val selected = result ?: chosen
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = LocalMaxBubbleWidth.current + 40.dp)) {
        card.options.forEach { option ->
            val isSelected = option.id == selected
            FilterChip(
                selected = isSelected,
                enabled = selected == null || isSelected,
                onClick = {
                    if (selected == null) {
                        chosen = option.id
                        instance.conversation.choose(option, card, message)
                    }
                },
                label = { Text(option.label, style = androidx.compose.material3.LocalTextStyle.current.merge(AuthoredText)) },
                leadingIcon = if (isSelected) {
                    (
                        {
                            Icon(
                                painterResource(R.drawable.pf_ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                        )
                } else {
                    null
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier.alpha(if (selected != null && !isSelected) DIMMED else 1f).semantics { this.selected = isSelected },
            )
        }
    }
}

private const val DIMMED = 0.6f

@Composable
private fun Rating(card: Card.Rating, result: Int?, message: Message, instance: PingFrontInstance) {
    val theme = LocalChatTheme.current
    var rated by remember { mutableStateOf<Int?>(null) }
    val value = result ?: rated
    val scale = card.scale.coerceIn(2, MAX_SCALE)
    val pluralOn = integerResource(R.integer.pf_stars_plural_on)
    Column(
        Modifier.widthIn(
            max = LocalMaxBubbleWidth.current,
        ).clip(RoundedCornerShape(theme.bubbleRadius)).background(theme.teamBubble).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            card.question,
            style = MaterialTheme.typography.titleSmall.merge(AuthoredText),
            fontWeight = FontWeight.SemiBold,
            color = theme.onTeamBubble,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row {
            for (star in 1..scale) {
                val description = pluralStringResource(R.plurals.pf_stars, starsQuantity(pluralOn, star, scale), star, scale)
                IconButton(
                    onClick = {
                        if (value == null) {
                            rated = star
                            instance.conversation.rate(star, card, message)
                        }
                    },
                    enabled = value == null,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = description
                        selected = star == value
                    },
                ) {
                    val filled = star <= (value ?: 0)
                    Icon(
                        painterResource(if (filled) R.drawable.pf_ic_star_fill else R.drawable.pf_ic_star),
                        contentDescription = null,
                        tint = if (filled) theme.star else theme.muted,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        if (value !=
            null
        ) {
            Text(
                stringResource(R.string.pf_thanks_rating),
                style = MaterialTheme.typography.bodySmall,
                color = theme.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private const val MAX_SCALE = 10

/**
 * The number that picks pf_stars' plural form: the rating when the language's noun agrees with it (`pluralOn` 1,
 * "1 étoile sur 5"), else the total ("1 of 5 stars"). `pluralOn` is the language's `pf_stars_plural_on`.
 */
internal fun starsQuantity(pluralOn: Int, star: Int, scale: Int): Int = if (pluralOn == 1) star else scale

/** The typing bubble: three dots that pulse (still when animations are off). */
@Composable
internal fun TypingDots(animate: Boolean) {
    val theme = LocalChatTheme.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val phase by if (animate) {
        transition.animateFloat(
            0f,
            3f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(TYPING_CYCLE_MS, easing = androidx.compose.animation.core.LinearEasing),
            ),
            label = "dots",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
    Row(
        Modifier.clip(RoundedCornerShape(theme.bubbleRadius)).background(theme.teamBubble).padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (dot in 0..2) {
            val active = animate && phase.toInt() == dot
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(theme.muted.copy(alpha = if (active) 1f else DOT_ALPHA)))
        }
    }
}

private const val TYPING_CYCLE_MS = 1200
private const val DOT_ALPHA = 0.45f

/** Small clickable text with a 48 dp touch target. */
@Composable
internal fun TextAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
    }
}
