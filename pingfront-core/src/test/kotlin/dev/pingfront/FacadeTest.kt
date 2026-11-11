@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import androidx.test.core.app.ApplicationProvider
import dev.pingfront.internal.CardActions
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun testConfig() = PingFrontConfig().apply { baseUrl = ScriptedTransport.BASE_URL }

/** The public behavior of the facade, configured through the same entry point apps use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FacadeTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun aMistypedKeyIsReportedOfflineAndDisablesTheSdk() = runTest {
        val transport = ScriptedTransport()
        val instance = PingFrontInstance()
        instance.start(context, "pk_live_0123456789ABCDEFGHIJKLNz", testConfig(), testDependencies(transport))
        val problem = instance.configurationProblem.value
        assertEquals(ConfigurationProblem.Kind.InvalidKey(KeyProblem.ChecksumMismatch), problem?.kind)
        assertTrue(problem!!.disablesSdk)
        assertNull(instance.engine)
        assertTrue(transport.requests.isEmpty())
        instance.identify("u1", null, null)
        assertNull(instance.engine)
    }

    @Test fun identifyAndTraitsGoOutInOnePatchAfterTheSession() = runTest {
        val transport = ScriptedTransport().apply {
            json("POST sessions", body = Bodies.session())
            json("PATCH me", body = """{"person":{"id":"p_1","level":"named"}}""")
        }
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, testConfig(), testDependencies(transport))
        instance.identify("user-7", "Ada", "not-an-email")
        instance.setTraits(mapOf("plan" to "pro", "" to "dropped"))
        advanceUntilIdle()
        assertTrue(transport.requests.isEmpty(), "no network at configure or identify")
        instance.prepare()
        advanceUntilIdle()
        val session = dev.pingfront.internal.wire.WireJson.parseToJsonElement(
            transport.last("POST sessions")!!.body!!.decodeToString(),
        ).jsonObject
        val identity = session["identity"]!!.jsonObject
        assertEquals("\"user-7\"", identity["userId"].toString())
        assertNull(identity["email"], "invalid emails are not sent")
        assertEquals("{\"plan\":\"pro\"}", session["traits"].toString())
        assertTrue(instance.person.value != null)
        instance.teardown()
    }

    /**
     * Review CS-8: any app can start the exported launcher activity with forged `pf_*` extras. Before the fix
     * that opened the chat, set the badge to 99 and created a session (network at launch). Launch intents are
     * no longer read; only the SDK's non-exported PushTapActivity accepts a payload.
     */
    @Test fun aForgedLaunchIntentIsIgnored() = runTest {
        val transport = ScriptedTransport().apply { json("POST sessions", body = Bodies.session()) }
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, testConfig(), testDependencies(transport))
        var presented = 0
        instance.registry.chatPresenter = object : ChatPresenter {
            override fun presentChat(topic: dev.pingfront.look.ChatTopic?) {
                presented += 1
            }

            override fun dismissChat() = Unit

            override fun openUrl(url: String) = Unit

            override fun presentSimpleReport(prefill: String?) = Unit
        }
        val forged = android.content.Intent(context, android.app.Activity::class.java)
            .putExtra("pf_v", "1")
            .putExtra("pf_kind", "message")
            .putExtra("pf_app_id", "app_someone_else")
            .putExtra("pf_unread", "99")
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java, forged).create().start().resume()
        advanceUntilIdle()
        assertEquals(0, presented, "the chat was not opened")
        assertEquals(0, instance.unreadCount.value, "the badge was not set")
        assertEquals(0, transport.count("POST sessions"), "no session was started")
        instance.teardown()
    }

    @Test fun handlePushParsesTheV1Payload() {
        val instance = PingFrontInstance()
        assertFalse(instance.handlePush(mapOf("pingfront" to "1")))
        assertFalse(instance.handlePush(mapOf("pf_v" to "2", "pf_kind" to "message", "pf_app_id" to "a")))
        assertTrue(instance.handlePush(mapOf("pf_v" to "1", "pf_kind" to "message", "pf_app_id" to "a", "pf_unread" to "3")))
        assertEquals(3, instance.unreadCount.value)
        val parsed = PushPayload.parse(
            mapOf(
                "pf_v" to "1",
                "pf_kind" to "ping",
                "pf_app_id" to "a",
                "pf_unread" to "x",
                "pf_title" to "Deniz",
            ),
        )
        assertNull(parsed?.unread)
        assertEquals("Deniz", parsed?.title)
    }

    @Test fun momentsOnlySendWhatAPlayListensToAndReportOpenRequests() = runTest {
        val transport = ScriptedTransport().apply {
            json("POST sessions", body = Bodies.session())
            json("GET moments/listened", body = """{"names":["paywall_closed"],"storeEvents":["trial_started"]}""")
            json("POST moments", body = """{"outcome":"delivered","open":true}""")
        }
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, testConfig().apply { presentsChatForMoments = false }, testDependencies(transport))
        assertEquals(MomentResult.InvalidName, instance.moment("Paywall Closed", emptyMap()))
        assertEquals(MomentResult.NotListened, instance.moment("app_opened", emptyMap()))
        assertEquals(
            MomentResult.OpenRequested(presented = false),
            instance.moment(
                "paywall_closed",
                mapOf(
                    "seconds" to 42,
                    "plan" to "pro",
                    "bad" to listOf(1),
                ),
            ),
        )
        val body = dev.pingfront.internal.wire.WireJson.parseToJsonElement(
            transport.last("POST moments")!!.body!!.decodeToString(),
        ).jsonObject
        assertEquals("{\"seconds\":42,\"plan\":\"pro\"}", body["props"].toString())
        assertEquals(MomentResult.NotListened, instance.storeMoment("paywall_closed", StoreMomentProps("pro")))
        instance.teardown()
    }

    @Test fun propsAreCappedAtTwenty() {
        assertEquals(20, CardActions.encodeProps((1..25).associate { "k$it" to it }).size)
    }

    @Test fun reconfigureDoesNotDuplicateModules() = runTest {
        var installs = 0
        val module = object : PingFrontModule("counter") {
            override fun install(context: ModuleContext) {
                installs += 1
                context.addOfferSource(object : OfferSource {
                    override suspend fun offers() = emptyList<dev.pingfront.model.OfferSlot>()
                })
            }
        }
        val instance = PingFrontInstance()
        val config = testConfig().apply { use(module, module) }
        instance.start(context, EngineHarness.KEY, config, testDependencies())
        instance.start(context, EngineHarness.KEY, config, testDependencies())
        assertEquals(2, installs)
        assertEquals(1, instance.registry.offerSources.size)
        instance.teardown()
    }

    /** Review CQ-L18: the UI adds a wipe listener on every configure; a reconfigure must not stack them. */
    @Test fun wipeListenersDoNotStackAcrossReconfigure() = runTest {
        val instance = PingFrontInstance()
        var wipes = 0
        instance.start(context, EngineHarness.KEY, testConfig(), testDependencies())
        instance.addWipeListener { wipes += 1 }
        instance.start(context, EngineHarness.KEY, testConfig(), testDependencies())
        instance.addWipeListener { wipes += 1 }
        instance.resetForLogout()
        assertEquals(1, wipes, "one listener per configure, not one per configure so far")
        instance.teardown()
    }

    @Test fun reportBugAttachesAUtf8SafeLogTail() = runTest {
        val transport = ScriptedTransport().apply {
            json("POST sessions", body = Bodies.session())
            json("POST reports", body = Bodies.sent(3, "00000000-0000-4000-8000-000000000003"))
        }
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, testConfig(), testDependencies(transport))
        instance.registry.reportContext = object : ReportContextProvider {
            override suspend fun reportContext() = ReportContext("é".repeat(40_000), mapOf("screen" to "Settings"))
        }
        instance.reportBug("Sync stopped", null)
        val body = dev.pingfront.internal.wire.WireJson.parseToJsonElement(
            transport.last("POST reports")!!.body!!.decodeToString(),
        ).jsonObject
        val tail = body["logTail"].toString()
        assertTrue(tail.length > 1000)
        assertFalse(tail.contains('�'))
        assertEquals(0, instance.engine!!.lastSeq(), "reports never move the sequence position")
        instance.teardown()
    }

    /** N1: `PingFront.usesAppBadge` is public, off until a published config turns `features.appBadge` on. */
    @Test fun usesAppBadgeFollowsThePublishedConfig() = runTest {
        assertFalse(PingFront.usesAppBadge, "unconfigured: off")
        val instance = PingFrontInstance()
        assertFalse(instance.appBadgeEnabled, "no engine: off")
        instance.start(context, EngineHarness.KEY, testConfig(), testDependencies(ScriptedTransport()))
        assertFalse(instance.appBadgeEnabled, "no config loaded yet: off")
        val on = Bodies.config().replace("\"ai\":false}", "\"ai\":false,\"appBadge\":true}")
        instance.engine!!.remoteConfig.value = dev.pingfront.internal.wire.WireJson.decodeFromString(
            dev.pingfront.internal.wire.AppConfig.serializer(),
            on,
        )
        assertTrue(instance.appBadgeEnabled)
        instance.engine!!.remoteConfig.value = dev.pingfront.internal.wire.WireJson.decodeFromString(
            dev.pingfront.internal.wire.AppConfig.serializer(),
            Bodies.config(),
        )
        assertFalse(instance.appBadgeEnabled, "a config without the flag: off")
        instance.teardown()
    }

    @Test fun eventsAreTyped() {
        assertIs<PingFrontEvent.OfferCompleted>(PingFrontEvent.OfferCompleted(OfferRequest("o", null, "c", "m"), OfferOutcome.Pending))
    }
}
