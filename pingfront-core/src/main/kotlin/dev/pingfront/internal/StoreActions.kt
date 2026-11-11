package dev.pingfront.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.pingfront.PaymentFix
import dev.pingfront.PaymentFixer
import dev.pingfront.StorePurchases
import dev.pingfront.conversation.StoreReceipt
import dev.pingfront.model.Card
import dev.pingfront.model.Entitlement
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

/** Google Play destinations for store action cards. */
internal object StoreActionLinks {
    private const val PLAY = "https://play.google.com"

    /** The page for [action], or null when it has none ([Card.StoreAction.Action.RestorePurchases]). */
    fun url(action: Card.StoreAction.Action, productId: String?, packageName: String?): String? = when (action) {
        // The subscription center; with the product and package it opens that subscription (and its payment fix).
        // An unknown action opens it too (the protocol: the platform's subscription management page).
        Card.StoreAction.Action.ManageSubscriptions, Card.StoreAction.Action.BillingIssue, Card.StoreAction.Action.Unknown -> {
            val query = listOfNotNull(
                productId?.takeIf { it.isNotBlank() && packageName != null }?.let { "sku=${encode(it)}" },
                packageName?.takeIf { productId != null && productId.isNotBlank() }?.let { "package=${encode(it)}" },
            )
            "$PLAY/store/account/subscriptions" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
        }
        // Play has no in-app refund sheet: the order history is where a refund is requested.
        Card.StoreAction.Action.RequestRefund -> "$PLAY/store/account/orderhistory"
        Card.StoreAction.Action.RedeemCode -> "$PLAY/redeem"
        Card.StoreAction.Action.RestorePurchases -> null
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}

/** Opens a Google Play URL in the Play Store app when it is installed; returns false otherwise. */
internal object PlayStoreApp {
    private const val PACKAGE = "com.android.vending"

    fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(PACKAGE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * Store action cards. Restore re-reads purchases in place. The others open Google Play, and when the app comes
 * back to the foreground the purchases are read again: if the subscription changed, the entitlement is updated
 * and the card gets a local receipt line.
 */
internal class StoreActions(
    private val refresh: suspend () -> StorePurchases?,
    private val fixer: () -> PaymentFixer? = { null },
    private val current: () -> Entitlement?,
    private val setEntitlement: (Entitlement) -> Unit,
    private val packageName: () -> String?,
    private val open: suspend (String) -> Unit,
    private val receipt: (cardId: String, StoreReceipt) -> Unit,
    /** Reports a result to the server (`POST /store-actions`), so the team sees it in the thread. */
    private val report: (card: Card.StoreAction, messageId: String?, StoreReceipt) -> Unit = { _, _, _ -> },
) {
    private class Pending(val card: Card.StoreAction, val messageId: String?, val before: Entitlement?)

    private val pending = AtomicReference<Pending?>(null)

    private fun done(card: Card.StoreAction, messageId: String?, result: StoreReceipt) {
        receipt(card.id, result)
        report(card, messageId, result)
    }

    /** Handles a tap. Returns false when there was nothing to do. */
    suspend fun act(card: Card.StoreAction, messageId: String? = null): Boolean {
        if (card.action == Card.StoreAction.Action.RestorePurchases) {
            restore(card, messageId)
            return true
        }
        // A payment problem: Play's own message in the app first; the subscription page when it has none.
        if (card.action == Card.StoreAction.Action.BillingIssue) {
            when (fixer()?.showPaymentFix()) {
                PaymentFix.Updated -> {
                    refresh()?.let { setEntitlement(it.entitlement) }
                    done(card, messageId, StoreReceipt.Updated)
                    return true
                }
                PaymentFix.Dismissed -> return true
                PaymentFix.NothingToShow, null -> Unit
            }
        }
        val url = StoreActionLinks.url(card.action, card.productId, packageName()) ?: return false
        pending.set(Pending(card, messageId, current()))
        open(url)
        return true
    }

    private suspend fun restore(card: Card.StoreAction, messageId: String?) {
        val before = current()
        val after = refresh()?.entitlement ?: return receipt(card.id, StoreReceipt.Unavailable)
        setEntitlement(after)
        done(
            card,
            messageId,
            when {
                after.state in NOTHING -> StoreReceipt.NothingToRestore
                changed(before, after) -> StoreReceipt.Restored
                else -> StoreReceipt.UpToDate
            },
        )
    }

    /** The app is back in the foreground: re-check purchases after a trip to Google Play. */
    suspend fun onForeground() {
        val trip = pending.getAndSet(null) ?: return
        val after = refresh()?.entitlement ?: return
        if (!changed(trip.before, after)) return
        setEntitlement(after)
        done(trip.card, trip.messageId, StoreReceipt.Updated)
    }

    private fun changed(before: Entitlement?, after: Entitlement): Boolean =
        if (before == null) after.state !in NOTHING else before.state != after.state || before.productId != after.productId

    companion object {
        private val NOTHING = setOf(Entitlement.State.None, Entitlement.State.Expired)

        /** `StoreActionResult` on the wire, or null for a result that isn't reported (Play unreachable). */
        fun wireResult(action: Card.StoreAction.Action, result: StoreReceipt): String? = when (result) {
            StoreReceipt.Restored -> "purchases_restored"
            StoreReceipt.NothingToRestore -> "nothing_to_restore"
            StoreReceipt.UpToDate -> "up_to_date"
            // Back from fixing a payment, an update is the payment; anywhere else Play can't say what changed.
            StoreReceipt.Updated -> if (action == Card.StoreAction.Action.BillingIssue) "payment_updated" else "subscription_updated"
            StoreReceipt.Unavailable -> null
        }

        /** The action as reported: an unknown one opened the subscriptions page. */
        fun wireAction(action: Card.StoreAction.Action): String = when (action) {
            Card.StoreAction.Action.ManageSubscriptions, Card.StoreAction.Action.Unknown -> "manage_subscriptions"
            Card.StoreAction.Action.BillingIssue -> "billing_issue"
            Card.StoreAction.Action.RequestRefund -> "request_refund"
            Card.StoreAction.Action.RedeemCode -> "redeem_code"
            Card.StoreAction.Action.RestorePurchases -> "restore_purchases"
        }
    }
}
