@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.messaging.Outbox
import dev.pingfront.internal.messaging.OutboxItem
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.model.Author
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.TestTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class RecordStoreTest {
    @Test fun versionEnvelopeAndRoundTrip() = runTest {
        val p = MemoryPersistence()
        RecordStore(p).write(StoredRecords.credentials, InstallCredentials("i", "s"))
        assertTrue(p.raw("install")!!.startsWith("{\"v\":1,\"data\":"))
        assertEquals("i", RecordStore(p).read(StoredRecords.credentials)?.installId)
    }

    @Test fun migrationsRunStepByStep() = runTest {
        val p = MemoryPersistence()
        p.putRaw("install", """{"v":1,"data":{"id":"old","secret":"s"}}""")
        val v2 = RecordStore.Record(
            "install",
            InstallCredentials.serializer(),
            version = 2,
            migrations = mapOf(
                1 to
                    { old ->
                        val o = old.jsonObject
                        JsonObject(
                            mapOf(
                                "installId" to o.getValue("id"),
                                "installSecret" to o.getValue("secret"),
                            ),
                        )
                    },
            ),
        )
        assertEquals(InstallCredentials("old", "s"), RecordStore(p).read(v2))
    }

    @Test fun unreadableRecordsAreMovedAsideNotOverwritten() = runTest {
        val p = MemoryPersistence()
        p.putRaw("outbox", "{garbage")
        assertNull(RecordStore(p).read(StoredRecords.outbox))
        assertEquals("{garbage", p.raw("outbox.unreadable"))
        p.putRaw("install", """{"v":9,"data":{}}""")
        assertNull(RecordStore(p).read(StoredRecords.credentials), "written by a newer SDK")
        assertTrue("install.unreadable" in p.names())
    }

    @Test fun unavailableIsNotNotFound() = runTest {
        val p = MemoryPersistence().apply { unavailable = true }
        assertFailsWith<PingFrontException.StorageUnavailable> { RecordStore(p).read(StoredRecords.credentials) }
    }
}

class OutboxTest {
    private fun item(id: String, text: String? = "t", photo: Boolean = false) = OutboxItem(
        PendingMessage(id, text, if (photo) listOf(PendingMessage.Photo("photo-$id-0", 10, 10, 3, "ab")) else emptyList(), Instant.EPOCH),
    )

    private fun ack(id: String, seq: Int) = Message("m$seq", seq, id, Author(Author.Kind.Person), listOf(MessagePart.Text("t")), Instant.EPOCH)

    @Test fun survivesRestartAndSendingGoesBackToQueued() = runTest {
        val p = MemoryPersistence()
        val first = Outbox(p)
        first.enqueue(item("a"))
        p.putRaw("outbox", p.raw("outbox")!!.replace("\"state\":\"Queued\"", "\"state\":\"Sending\""))
        val second = Outbox(p)
        assertEquals(listOf(PendingMessage.State.Queued), second.load().map { it.state })
    }

    @Test fun idempotentRetryKeepsTheClientId() = runTest {
        val outbox = Outbox(MemoryPersistence())
        outbox.enqueue(item("a"))
        val seen = mutableListOf<String>()
        val first = outbox.drain(false) {
            seen += it.message.clientId
            throw PingFrontException.Network(true)
        }
        assertIs<Outbox.DrainResult.RetryLater>(first)
        outbox.drain(false) {
            seen += it.message.clientId
            ack(it.message.clientId, 1)
        }
        assertEquals(listOf("a", "a"), seen)
        assertTrue(outbox.load().isEmpty())
    }

    @Test fun aRejectedMessageFailsAloneAndLaterOnesSend() = runTest {
        val outbox = Outbox(MemoryPersistence())
        outbox.enqueue(item("bad"))
        outbox.enqueue(item("good"))
        outbox.drain(false) {
            if (it.message.clientId == "bad") throw PingFrontException.Server(ServerError(422, ErrorCode.InvalidRequest, "x"))
            ack(it.message.clientId, 2)
        }
        val left = outbox.load()
        assertEquals(listOf("bad"), left.map { it.clientId })
        assertEquals(PendingMessage.State.Failed, left.single().state)
        assertEquals("invalid_request", left.single().failure)
    }

    /** Audit A-06: a send that keeps failing (not offline) turns failed with Retry and Delete after 5 attempts; Retry starts over. */
    @Test fun repeatedFailuresTurnFailedAndRetryStartsOver() = runTest {
        val outbox = Outbox(MemoryPersistence())
        outbox.enqueue(item("slow"))
        val serverError = PingFrontException.Server(ServerError(503, ErrorCode("unavailable"), "x"))
        repeat(Outbox.MAX_ATTEMPTS - 1) {
            assertTrue(outbox.drain(false) { throw serverError } is Outbox.DrainResult.RetryLater, "attempt ${it + 1} retries")
        }
        // The fifth attempt fails for good.
        outbox.drain(false) { throw serverError }
        assertEquals(PendingMessage.State.Failed, outbox.load().single().state)
        outbox.retry("slow")
        assertEquals(0, outbox.load().single().attempts)
        // Offline never gives up: it waits for the connection.
        repeat(Outbox.MAX_ATTEMPTS + 2) { outbox.drain(false) { throw PingFrontException.Network(offline = true) } }
        assertEquals(PendingMessage.State.Queued, outbox.load().single().state)
    }

    @Test fun textKeepsSendingWhileUploadsCoolDownAndOrderIsKept() = runTest {
        val outbox = Outbox(MemoryPersistence())
        outbox.enqueue(item("photo", photo = true), listOf(byteArrayOf(1, 2, 3)))
        outbox.enqueue(item("text1"))
        outbox.enqueue(item("text2"))
        val sent = mutableListOf<String>()
        outbox.drain(uploadsPaused = true) {
            sent += it.message.clientId
            ack(it.message.clientId, sent.size)
        }
        assertEquals(listOf("text1", "text2"), sent)
        outbox.drain(uploadsPaused = false) {
            sent += it.message.clientId
            ack(it.message.clientId, sent.size)
        }
        assertEquals(listOf("text1", "text2", "photo"), sent)
    }

    @Test fun uploadsAreRecordedSoARetryNeverUploadsTwice() = runTest {
        val outbox = Outbox(MemoryPersistence())
        outbox.enqueue(item("p", photo = true), listOf(byteArrayOf(9)))
        outbox.recordUpload("p", "photo-p-0", "att_1")
        assertEquals("att_1", outbox.all().single().message.photos.single().attachmentId)
    }
}

class StoredTimeTest {
    @Test fun testTimeIsVirtual() = runTest {
        val t = TestTime(this)
        kotlinx.coroutines.delay(1_000)
        assertEquals(1_000, t.now().inWholeMilliseconds)
        assertEquals(TestTime.BASE.plusSeconds(1), t.wall())
        assertEquals(JsonPrimitive(1), JsonPrimitive(1))
    }
}
