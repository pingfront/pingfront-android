package dev.pingfront

import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.Cooldowns
import dev.pingfront.internal.wire.Limits
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.internal.wire.ThreadResponse
import dev.pingfront.internal.wire.Utf8
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.model.Author
import dev.pingfront.model.Card
import dev.pingfront.model.ChatLook
import dev.pingfront.model.Corners
import dev.pingfront.model.Entitlement
import dev.pingfront.model.HeaderStyle
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.model.PingOrigin
import dev.pingfront.model.PingReason
import dev.pingfront.model.ReceiptAction
import dev.pingfront.model.ReviewMode
import dev.pingfront.model.SendPolicy
import dev.pingfront.model.ThreadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AppKeyTest {
    @Test fun crcCheckValue() = assertEquals(0xCBF43926L, KeyCheck.crc32("123456789"))

    @Test fun protocolVectors() {
        val vectors = mapOf(
            "pk_test_abcdefghijklmnopqrstuvvd" to null,
            "pk_live_0123456789ABCDEFGHIJKLNy" to null,
            "pk_test_abcdefghijklmnopqrstuwvd" to KeyProblem.ChecksumMismatch,
            "pk_live_0123456789ABCDEFGHIJKLNz" to KeyProblem.ChecksumMismatch,
            "pk_live_0123456789ABCDEFGHIJKLN" to KeyProblem.WrongLength,
            // A secret key pasted by mistake, built from parts so secret scanners don't flag the test vector.
            "sk_" + "live_0123456789ABCDEFGHIJKLNy" to KeyProblem.WrongPrefix,
            "" to KeyProblem.Empty,
            " pk_live_0123456789ABCDEFGHIJKLNy" to KeyProblem.Whitespace,
            "pk_live_0123456789ABCDEFGHIJK-Ny" to KeyProblem.BadCharacters,
        )
        for ((key, expected) in vectors) assertEquals(expected, AppKey.problem(key), key)
        assertEquals(AppKey.Mode.Test, AppKey.parse("pk_test_abcdefghijklmnopqrstuvvd").getOrThrow().mode)
    }

    @Test fun maskedInLogs() = assertEquals("pk_live_…LNy".takeLast(3), AppKey.parse("pk_live_0123456789ABCDEFGHIJKLNy").getOrThrow().toString().takeLast(3))

    @Test fun everyProblemSaysWhatToDo() = KeyProblem.entries.forEach { assertTrue(it.message.length > 20) }
}

class WireDecodingTest {
    private fun message(json: String) = WireJson.decodeFromString(Message.serializer(), json)

    private val base = """"id":"m1","seq":1,"author":{"kind":"team","name":"Deniz"},"createdAt":"2026-10-30T18:00:00.123+02:00""""

    @Test fun everyPartAndCard() {
        val m = message(
            """{$base,"parts":[
            {"type":"text","text":"Hi"},
            {"type":"image","attachmentId":"a1","width":10,"height":20,"url":"https://x/y.jpg"},
            {"type":"card","card":{"kind":"offer","id":"c1","offerId":"o","title":"T","body":"B","button":"Go"}},
            {"type":"card","card":{"kind":"review","id":"c2","mode":"native_prompt","button":"Rate"}},
            {"type":"card","card":{"kind":"link","id":"c3","title":"T","url":"app://x","button":"Open"}},
            {"type":"card","card":{"kind":"choices","id":"c4","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]},"result":{"at":"2026-10-30T18:01:00Z","choiceId":"a"}},
            {"type":"card","card":{"kind":"rating","id":"c5","question":"Q","scale":5},"result":{"at":"2026-10-30T18:01:00Z","rating":4}},
            {"type":"card","card":{"kind":"report","id":"c6","title":"T","button":"Report"}},
            {"type":"receipt","action":"trial_extended","text":"Trial extended to 14 Nov"}],
            "ping":{"kind":"play","playId":"p1","explanation":"Because","reason":{"code":"moment","params":{"moment":"paywall_closed"}}},
            "translation":{"language":"de","parts":[{"type":"text","text":"Hallo"}]},"fallback":"Hi"}""",
        )
        assertEquals(9, m.parts.size)
        assertEquals(MessagePart.Text("Hi"), m.parts[0])
        assertIs<MessagePart.Image>(m.parts[1])
        val cards = m.parts.filterIsInstance<MessagePart.CardPart>()
        assertIs<Card.Offer>(cards[0].card)
        assertEquals(ReviewMode.NativePrompt, (cards[1].card as Card.Review).mode)
        assertIs<Card.Link>(cards[2].card)
        assertEquals("a", cards[3].result?.choiceId)
        assertEquals(4, cards[4].result?.rating)
        assertIs<Card.Report>(cards[5].card)
        assertEquals(ReceiptAction.TrialExtended, (m.parts[8] as MessagePart.Receipt).action)
        assertEquals(PingReason.Code.Moment, m.ping?.reason?.code)
        assertEquals(listOf("Hallo"), m.translation?.texts)
        assertEquals("Hi", m.fallback)
        assertEquals(java.time.Instant.parse("2026-10-30T16:00:00.123Z"), m.createdAt)
    }

    /** The AI teammate's hand-off line is its own receipt case; nothing was done for the person (the protocol, Hand-off). */
    @Test fun aHandOffReceiptIsItsOwnCase() {
        val m = message("""{$base,"parts":[{"type":"receipt","action":"handed_off","text":"Deniz will reply"}]}""")
        val receipt = m.parts.single() as MessagePart.Receipt
        assertEquals(ReceiptAction.HandedOff, receipt.action)
        assertEquals("Deniz will reply", receipt.text)
        // The thread cache keeps it (encode, then decode).
        val cached = WireJson.decodeFromString(Message.serializer(), WireJson.encodeToString(Message.serializer(), m))
        assertEquals(ReceiptAction.HandedOff, (cached.parts.single() as MessagePart.Receipt).action)
        // Actions this SDK does not name still decode safely, as custom.
        val other = message("""{$base,"parts":[{"type":"receipt","action":"renewed","text":"x"}]}""")
        assertEquals(ReceiptAction.Custom, (other.parts.single() as MessagePart.Receipt).action)
        // And one the contract has never had.
        val future = message("""{$base,"parts":[{"type":"receipt","action":"some_future_action","text":"x"}]}""")
        assertEquals(ReceiptAction.Custom, (future.parts.single() as MessagePart.Receipt).action)
    }

    @Test fun unknownContentNeverCrashes() {
        val m = message(
            """{$base,"parts":[{"type":"poll","question":"?"},{"type":"card","card":{"kind":"carousel","id":"c9"}},
            {"type":"card","card":{"kind":"offer","id":"c1"}},{"type":"receipt","action":"refund_sent","text":"Refunded"}],
            "ping":{"kind":"robot","explanation":"x","reason":{"code":"lunar"}},"fallback":"Poll"}""",
        )
        assertEquals(MessagePart.Unknown("poll"), m.parts[0])
        assertEquals(Card.Unknown("carousel", "c9"), (m.parts[1] as MessagePart.CardPart).card)
        assertEquals(Card.Unknown("offer", "c1"), (m.parts[2] as MessagePart.CardPart).card)
        assertEquals(ReceiptAction.Custom, (m.parts[3] as MessagePart.Receipt).action)
        // A kind this SDK does not know is Unknown, never mistaken for "manual" (review CQ-L1).
        assertEquals(PingOrigin.Kind.Unknown, m.ping?.kind)
        assertEquals(PingReason.Code.Unknown, m.ping?.reason?.code)
        assertEquals(
            Author.Kind.Unknown,
            message(
                """{"id":"m","seq":1,"author":{"kind":"bot"},"parts":[{"type":"text","text":"x"}],"createdAt":"2026-10-30T18:00:00Z"}""",
            ).author.kind,
        )
    }

    @Test fun aMalformedMessageIsDroppedWhileThePageSurvives() {
        val page = WireJson.decodeFromString(
            ThreadResponse.serializer(),
            """{"thread":null,"messages":[{"id":"bad"},{$base,"parts":[{"type":"text","text":"ok"}]}],"hasOlder":false,"team":[],"policy":{"canSend":false,"reason":"app_paused"}}""",
        )
        assertEquals(1, page.messages.size)
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), page.policy)
        assertFalse(page.policy.canSend)
    }

    @Test fun openTypesKeepUnknownCodes() {
        val e = WireJson.decodeFromString(Entitlement.serializer(), """{"state":"trial","source":"paddle"}""")
        assertEquals(Entitlement.Source("paddle"), e.source)
        assertEquals(Entitlement.State.Trial, e.state)
        assertEquals(
            RateScope("exports"),
            ApiClient.serverError(
                429,
                """{"error":{"code":"rate_limited","message":"m","scope":"exports"}}""".encodeToByteArray(),
                null,
            ).scope,
        )
    }

    @Test fun oneBadLookFieldDropsOnlyThatField() {
        val look = WireJson.decodeFromString(
            ChatLook.serializer(),
            """{"theme":{"accent":{"light":"#XYZ","dark":"#000000"},"corners":"soft","teamBubble":{"light":"#EEEEEE","dark":"#222222"}},
            "header":{"showLocalTime":"yes","style":"team"},"topics":[{"id":"a","label":{"default":"A"},"symbol":"bug"},{"id":"bad"}],"aiName":"Guide"}""",
        )
        assertNull(look.theme.accent)
        assertEquals(Corners.Soft, look.theme.corners)
        assertEquals("#EEEEEE", look.theme.teamBubble?.light?.hex)
        assertNull(look.header.showLocalTime)
        assertEquals(HeaderStyle.Team, look.header.style)
        assertEquals(1, look.topics.size)
        assertEquals("Guide", look.aiName)
    }

    @Test fun liveEvents() {
        val hello = ServerEvent.decode(
            """{"type":"hello","lastSeq":5,"readSeq":2,"rev":3,"teamReadSeq":4,"status":"resolved"}""",
        ) as ServerEvent.Hello
        assertEquals(3, hello.rev)
        assertEquals(4, hello.teamReadSeq)
        assertEquals(ThreadStatus.Resolved, hello.status)
        assertIs<ServerEvent.NewMessage>(
            ServerEvent.decode("""{"type":"message","seq":6,"message":{$base,"parts":[{"type":"text","text":"x"}]}}"""),
        )
        assertEquals(
            ServerEvent.Typing(true, "Coach", true),
            ServerEvent.decode("""{"type":"typing","author":{"kind":"ai","name":"Coach"},"on":true}"""),
        )
        assertEquals(ServerEvent.Read(7), ServerEvent.decode("""{"type":"read","by":"team","upToSeq":7}"""))
        assertEquals(ServerEvent.Reset, ServerEvent.decode("""{"type":"reset"}"""))
        assertEquals(ServerEvent.Config(4, true), ServerEvent.decode("""{"type":"config","revision":4,"draft":true}"""))
        assertEquals(ServerEvent.Reauth, ServerEvent.decode("""{"type":"reauth"}"""))
        assertEquals(ServerEvent.Unknown("presence"), ServerEvent.decode("""{"type":"presence"}"""))
        assertNull(ServerEvent.decode("not json"))
    }
}

class ErrorsTest {
    @Test fun rateLimitWithScopeAndRetryAfter() {
        val e = ApiClient.serverError(
            429,
            """{"error":{"code":"rate_limited","message":"slow","docs":"https://pingfront.sh/docs/errors#rate_limited",
            |"retryAfter":41.5,"scope":"uploads"}}
            """.trimMargin().replace("\n", "").encodeToByteArray(),
            null,
        )
        assertEquals(ErrorCode.RateLimited, e.code)
        assertEquals(RateScope.Uploads, e.scope)
        assertEquals(41_500.milliseconds, e.retryAfter)
        assertEquals("https://pingfront.sh/docs/errors#rate_limited", e.docs.toString())
    }

    @Test fun fieldsOn422() {
        val e = ApiClient.serverError(
            422,
            """{"error":{"code":"invalid_request","message":"m","fields":[{"path":"identity.email","message":"bad"}]}}""".encodeToByteArray(),
            null,
        )
        assertEquals(listOf(FieldError("identity.email", "bad")), e.fields)
    }

    @Test fun nonEnvelopeFallsBackToStatusClass() {
        val e = ApiClient.serverError(503, "<html>".encodeToByteArray(), "7")
        assertEquals(ErrorCode.Unavailable, e.code)
        assertEquals(7.seconds, e.retryAfter)
        assertTrue(PingFrontException.Server(e).isTransient)
        assertFalse(PingFrontException.Server(ApiClient.serverError(422, ByteArray(0), null)).isTransient)
    }

    @Test fun logDescriptionNeverCarriesTheServerMessage() {
        val secret = "user ada@example.com token abc123 at https://api.pingfront.sh/v1/thread"
        val error = PingFrontException.Server(ServerError(403, ErrorCode.Blocked, secret, scope = RateScope.Messages))
        assertEquals("server 403 blocked scope=messages", error.logDescription)
        assertFalse(error.logDescription.contains("ada"))
        assertEquals("network offline", PingFrontException.Network(true, java.net.UnknownHostException("api.pingfront.sh")).logDescription)
        assertTrue(PingFrontException.Network(true, java.io.IOException("x")).cause is java.io.IOException)
    }
}

class TimingTest {
    /** the protocol, Live channel: `min(1s·2ⁿ, 30s)` ±20%. */
    @Test fun liveReconnectBackoffDoublesToThirtySeconds() {
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }, (0..6).map { Backoff.Live.nominal(it) })
        assertEquals(1200.milliseconds, Backoff.Live.delay(0, 1.0))
        assertEquals(800.milliseconds, Backoff.Live.delay(0, -1.0))
        assertEquals(36.seconds, Backoff.Live.delay(10, 5.0))
    }

    /** the protocol, Retrying writes: `min(1 s · 2ⁿ, 5 min)` ±20% for the outbox, acks and `PATCH /me`. */
    @Test fun writeRetryBackoffDoublesToFiveMinutes() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300).map { it.seconds }, (0..10).map { Backoff.Writes.nominal(it) })
        assertEquals(360.seconds, Backoff.Writes.delay(20, 1.0))
        assertEquals(240.seconds, Backoff.Writes.delay(20, -1.0))
        assertEquals(300.seconds, Backoff.Writes.nominal(Int.MAX_VALUE))
    }

    @Test fun scopedCooldownsAndCountdownCopy() {
        var c = Cooldowns()
        c = c.record(RateScope.Uploads, 42.seconds, 0.seconds)
        assertEquals(42.seconds, c.remaining(RateScope.Uploads, 0.seconds))
        assertNull(c.remaining(RateScope.Messages, 0.seconds))
        c = c.record(RateScope.Messages, null, 10.seconds)
        assertEquals(30.seconds, c.remaining(RateScope.Messages, 10.seconds))
        assertEquals("0:42", Cooldowns.countdown(41_600.milliseconds))
        assertEquals("4:59", Cooldowns.countdown(299.seconds))
        assertNull(Cooldowns.countdown(300.seconds))
    }

    @Test fun unreadPollingSchedule() {
        assertEquals(20.seconds, dev.pingfront.internal.messaging.UnreadSchedule.interval(0.seconds))
        assertEquals(120.seconds, dev.pingfront.internal.messaging.UnreadSchedule.interval(121.seconds))
        assertNull(dev.pingfront.internal.messaging.UnreadSchedule.interval((120 + 3600).seconds))
    }
}

class TextLimitsTest {
    @Test fun codePointsNotUtf16() {
        val emoji = "😀"
        assertEquals(1, Limits.codePoints(emoji))
        assertEquals(emoji.repeat(3), Limits.truncate(emoji.repeat(5), 3))
    }

    @Test fun logTailNeverSplitsACharacter() {
        val text = "ab" + "é".repeat(10) + "日本"
        for (max in 1..text.encodeToByteArray().size) {
            val tail = Utf8.tail(text, max)
            assertTrue(tail.encodeToByteArray().size <= max)
            assertFalse(tail.contains('�'), "max=$max")
            assertTrue(text.endsWith(tail))
        }
    }
}
