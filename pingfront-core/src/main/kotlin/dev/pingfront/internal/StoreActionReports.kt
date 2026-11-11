package dev.pingfront.internal

import dev.pingfront.PingFrontException
import dev.pingfront.conversation.Conversation
import dev.pingfront.conversation.StoreReceipt
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.wire.StoreActionResultRequest
import dev.pingfront.model.Card
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = Log.of(Log.Category.Billing)

/** Store action results for the server (split out of the engine). */
internal object StoreActionReports {
    /**
     * Sends a store action's result to the server (the protocol, Store action results). The server's receipt line
     * replaces the card's local one; without a conversation (or when a store receipt already says it) the server
     * writes none and the local line stays.
     */
    fun report(
        scope: CoroutineScope,
        session: SessionStore,
        client: AuthorizedClient,
        conversation: Conversation,
        card: Card.StoreAction,
        messageId: String?,
        receipt: StoreReceipt,
    ) {
        val result = StoreActions.wireResult(card.action, receipt) ?: return
        val request = StoreActionResultRequest(UUID.randomUUID().toString(), StoreActions.wireAction(card.action), result, card.id, messageId)
        scope.launch {
            val generation = session.generation()
            try {
                val message = client.send(Endpoints.reportStoreAction(request), generation).message ?: return@launch
                conversation.update { it.copy(storeReceipts = it.storeReceipts - card.id) }
                conversation.merge(listOf(message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                log.i("Store action report failed: ${e.logDescription}")
            }
        }
    }
}
