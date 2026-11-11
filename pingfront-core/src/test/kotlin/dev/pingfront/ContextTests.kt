@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.Engine
import dev.pingfront.internal.context.ContextBuckets
import dev.pingfront.internal.context.ContextSource
import dev.pingfront.internal.context.NetworkSample
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.BuildContext
import dev.pingfront.internal.wire.ConnectionContext
import dev.pingfront.internal.wire.ContextCollection
import dev.pingfront.internal.wire.DeviceContext
import dev.pingfront.internal.wire.DisplayContext
import dev.pingfront.internal.wire.FreeStorage
import dev.pingfront.internal.wire.HealthContext
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.InstallSource
import dev.pingfront.internal.wire.MemoryPressure
import dev.pingfront.internal.wire.NetworkKind
import dev.pingfront.internal.wire.NotificationAuthorization
import dev.pingfront.internal.wire.NotificationContext
import dev.pingfront.internal.wire.ScreenClass
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.TextSize
import dev.pingfront.internal.wire.Thermal
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.WireRules
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A scripted platform: fixed values per group, counted reads, and a connection callback the test drives. */
internal class FakeContextSource : ContextSource {
    @Volatile var reads: Int = 0

    @Volatile var build: BuildContext = BuildContext(InstallSource.PlayStore, debuggable = false)

    @Volatile var display: DisplayContext =
        DisplayContext(ScreenClass.Compact, 3.0, darkMode = false, TextSize.Default, false, false, false, listOf("en-US", "de"))

    @Volatile var connection: ConnectionContext = WIFI

    @Volatile var health: HealthContext = HealthContext(FreeStorage.Over20Gb, 80, charging = false, Thermal.Nominal, MemoryPressure.Normal)

    @Volatile var notifications: NotificationContext = NotificationContext(NotificationAuthorization.Authorized, channelEnabled = true)

    val changes = MutableSharedFlow<ConnectionContext>(extraBufferCapacity = 8)

    override fun build(): BuildContext = build.also { reads += 1 }

    override fun display(): DisplayContext = display.also { reads += 1 }

    override fun connection(): ConnectionContext = connection.also { reads += 1 }

    override fun health(): HealthContext = health.also { reads += 1 }

    override fun notifications(): NotificationContext = notifications.also { reads += 1 }

    /** Times the connection was read from a network change (Data Saver and Battery Saver included). */
    @Volatile var connectionReads: Int = 0

    override fun networkChanges(): Flow<NetworkSample> = changes.map { change ->
        NetworkSample(online = true) { change.also { connectionReads += 1 } }
    }

    /** The network changed: the platform reads it and the callback reports it. */
    fun change(to: ConnectionContext) {
        connection = to
        changes.tryEmit(to)
    }

    companion object {
        val WIFI = ConnectionContext(NetworkKind.Wifi, constrained = false, expensive = false, lowPower = false)
        val CELLULAR = ConnectionContext(NetworkKind.Cellular, constrained = true, expensive = true, lowPower = false)
    }
}

private const val NO_HEALTH =
    """"collect":{"build":true,"display":true,"connection":true,"health":false,"notifications":true}"""

private fun withCollect(body: String, collect: String) = body.trimEnd().removeSuffix("}") + ",$collect}"

private fun ScriptedTransport.body(route: String): JsonObject? = last(route)?.body?.decodeToString()?.let { WireJson.parseToJsonElement(it).jsonObject }

private fun JsonObject.context(): JsonObject? = this["device"]?.jsonObject?.get("context")?.jsonObject

private fun TestScope.patch(h: EngineHarness, trait: String) {
    h.engine.submit(Engine.Command.Stage(ProfileChange.Traits(mapOf("t" to trait))))
    advanceTimeBy(300)
    runCurrent()
}

class ContextReportingTest {
    @Test fun nothingIsReadOrSentAtLaunch() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, context = source)
        advanceUntilIdle()
        assertTrue(h.transport.requests.isEmpty(), "no request at launch")
        assertEquals(0, source.reads, "no context read at launch")
        assertEquals(0, source.changes.subscriptionCount.value, "no network callback before a session")
        h.stop()
    }

    /** An install whose switches arrived with an earlier answer (a new one sends nothing first: ContextBeforeSwitchesTest). */
    private suspend fun known(): MemoryPersistence = MemoryPersistence().also {
        dev.pingfront.internal.storage.RecordStore(it).write(StoredRecords.contextCollection, ContextCollection.All)
    }

    @Test fun aSessionCreateCarriesEveryAllowedGroup() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, persistence = known(), context = source)
        h.engine.session.token(SessionReason.Prepare)
        val body = h.transport.body("POST sessions")!!
        val context = assertNotNull(body.context())
        assertEquals(setOf("build", "display", "connection", "health", "notifications"), context.keys)
        assertEquals("\"play_store\"", context["build"]!!.jsonObject["installSource"].toString())
        assertEquals("\"from_1_to_5gb\"", WireJson.encodeToJsonElement(FreeStorage.serializer(), FreeStorage.From1To5Gb).toString())
        assertEquals("80", context["health"]!!.jsonObject["battery"].toString())
        assertEquals("[\"en-US\",\"de\"]", context["display"]!!.jsonObject["languages"].toString())
        assertEquals(emptyList(), WireRules.problems("CreateSessionRequest", body))
        advanceUntilIdle()
        assertEquals(1, source.changes.subscriptionCount.value, "the network callback is registered once a session exists")
        h.stop()
    }

    @Test fun healthOffStopsHealthOnTheNextSession() = runTest {
        val persistence = MemoryPersistence()
        val source = FakeContextSource()
        val first = EngineHarness(this, persistence = persistence, context = source)
        first.transport.json("POST sessions", body = withCollect(Bodies.session(), NO_HEALTH))
        first.engine.session.token(SessionReason.Prepare)
        assertNull(first.transport.body("POST sessions")!!.context(), "no group before the first answer (review AN-24)")
        assertEquals(false, dev.pingfront.internal.storage.RecordStore(persistence).read(StoredRecords.contextCollection)?.health, "kept with the install")
        advanceUntilIdle()
        first.stop()

        // A new process: its first refresh is a session start, but the app no longer collects health.
        val second = EngineHarness(this, persistence = persistence, context = source)
        second.transport.json("POST sessions/refresh", body = Bodies.refresh())
        second.engine.session.token(SessionReason.Prepare)
        val context = assertNotNull(second.transport.body("POST sessions/refresh")!!.context())
        assertNull(context["health"])
        assertNotNull(context["build"])
        assertNotNull(context["connection"])
        advanceUntilIdle()
        second.stop()
    }

    @Test fun anAbsentCollectMeansEveryGroup() = runTest {
        val persistence = MemoryPersistence()
        dev.pingfront.internal.storage.RecordStore(persistence).apply {
            write(StoredRecords.credentials, InstallCredentials("8a1f6f2e-1111-4222-8333-944455556666", "s"))
            write(StoredRecords.contextCollection, ContextCollection(health = false))
        }
        val h = EngineHarness(this, persistence = persistence, context = FakeContextSource())
        h.transport.json("POST sessions/refresh", body = Bodies.refresh())
        h.engine.session.token(SessionReason.Prepare)
        assertNull(h.transport.body("POST sessions/refresh")!!.context()!!["health"], "the stored switches apply")
        assertEquals(
            ContextCollection.All,
            dev.pingfront.internal.storage.RecordStore(persistence).read(StoredRecords.contextCollection),
            "an older server: every group",
        )
        h.stop()
    }

    @Test fun aConnectionChangeRidesOnTheNextPatchAtMostEveryFiveMinutes() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, persistence = known(), context = source)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val start = testScheduler.currentTime

        // A change alone never sends anything, however long it waits.
        advanceTimeBy(1.minutes)
        source.change(FakeContextSource.CELLULAR)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(0, h.transport.count("PATCH me"))

        // A PATCH within 5 minutes of the last delivery goes without the connection.
        patch(h, "1")
        assertEquals(1, h.transport.count("PATCH me"))
        assertNull(h.transport.body("PATCH me")!!.context(), "within 5 minutes: not yet")

        advanceTimeBy(20.minutes)
        runCurrent()
        assertEquals(1, h.transport.count("PATCH me"), "never a PATCH of its own")
        assertEquals(0, h.transport.count("POST sessions/refresh"), "never a refresh of its own")

        patch(h, "2")
        assertTrue(testScheduler.currentTime - start >= 5.minutes.inWholeMilliseconds)
        val context = assertNotNull(h.transport.body("PATCH me")!!.context())
        assertEquals(setOf("connection"), context.keys, "only what changed; never health")
        assertEquals("\"cellular\"", context["connection"]!!.jsonObject["network"].toString())

        // Flip back and forth: coalesced, and limited again.
        source.change(FakeContextSource.WIFI)
        source.change(FakeContextSource.CELLULAR)
        advanceTimeBy(6.minutes)
        patch(h, "3")
        assertNull(h.transport.body("PATCH me")!!.context(), "back to what was delivered: nothing to send")
        h.stop()
    }

    @Test fun displayChangesRideOnTheNextPatch() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, persistence = known(), context = source)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        source.display = source.display.copy(darkMode = true)
        patch(h, "1")
        val context = assertNotNull(h.transport.body("PATCH me")!!.context())
        assertEquals(setOf("display"), context.keys)
        assertEquals("true", context["display"]!!.jsonObject["darkMode"].toString())
        patch(h, "2")
        assertNull(h.transport.body("PATCH me")!!.context(), "delivered once")
        h.stop()
    }

    @Test fun logoutUnregistersTheNetworkCallback() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, context = source)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        assertEquals(1, source.changes.subscriptionCount.value)
        h.engine.submit(Engine.Command.Logout)
        advanceUntilIdle()
        assertEquals(0, source.changes.subscriptionCount.value)
        h.stop()
    }
}

class ContextWireTest {
    @Test fun unknownEnumValuesDecodeToUnknown() {
        val json = """{"build":{"installSource":"carrier_store"},"display":{"screenClass":"huge","textSize":"tiny"},
            "connection":{"network":"satellite"},"health":{"freeStorage":"plenty","thermal":"melting","memoryPressure":"odd"},
            "notifications":{"authorization":"maybe"},"future":{"x":1}}"""
        val context = WireJson.decodeFromString(DeviceContext.serializer(), json)
        assertEquals(InstallSource.Unknown, context.build?.installSource)
        assertEquals(ScreenClass.Unknown, context.display?.screenClass)
        assertEquals(TextSize.Unknown, context.display?.textSize)
        assertEquals(NetworkKind.Unknown, context.connection?.network)
        assertEquals(FreeStorage.Unknown, context.health?.freeStorage)
        assertEquals(Thermal.Unknown, context.health?.thermal)
        assertEquals(MemoryPressure.Unknown, context.health?.memoryPressure)
        assertEquals(NotificationAuthorization.Unknown, context.notifications?.authorization)
        // An Unknown is never sent: the rules refuse it.
        val encoded = WireJson.encodeToJsonElement(DeviceContext.serializer(), context)
        assertTrue(WireRules.problems("DeviceContext", encoded)!!.contains("connection.network"))
    }

    @Test fun collectDecodesFromBothSessionResponsesAndMissingFlagsMeanCollected() {
        val create = WireJson.decodeFromString(
            dev.pingfront.internal.wire.CreateSessionResponse.serializer(),
            withCollect(Bodies.session(), NO_HEALTH),
        )
        assertEquals(false, create.collect?.health)
        val refresh = WireJson.decodeFromString(dev.pingfront.internal.wire.RefreshSessionResponse.serializer(), withCollect(Bodies.refresh(), NO_HEALTH))
        assertEquals(false, refresh.collect?.health)
        assertNull(WireJson.decodeFromString(dev.pingfront.internal.wire.RefreshSessionResponse.serializer(), Bodies.refresh()).collect)
        assertEquals(ContextCollection.All, WireJson.decodeFromString(ContextCollection.serializer(), "{}"))
    }
}

class ContextBucketsTest {
    @Test fun installSource() {
        assertEquals(InstallSource.PlayStore to null, ContextBuckets.installSource("com.android.vending"))
        assertEquals(
            InstallSource.OtherStore to "com.sec.android.app.samsungapps",
            ContextBuckets.installSource("com.sec.android.app.samsungapps"),
        )
        assertEquals(InstallSource.Sideloaded to null, ContextBuckets.installSource(null))
        assertEquals(InstallSource.Sideloaded to null, ContextBuckets.installSource("com.google.android.packageinstaller"))
        assertEquals(InstallSource.OtherStore to null, ContextBuckets.installSource("not a package"))
    }

    @Test fun display() {
        assertEquals(ScreenClass.Compact, ContextBuckets.screenClass(599))
        assertEquals(ScreenClass.Regular, ContextBuckets.screenClass(600))
        assertEquals(1.0, ContextBuckets.scale(0.75f))
        assertEquals(2.5, ContextBuckets.scale(2.625f))
        assertEquals(3.0, ContextBuckets.scale(2.75f))
        assertEquals(4.0, ContextBuckets.scale(5f))
        assertEquals(TextSize.Default, ContextBuckets.textSize(0.85f))
        assertEquals(TextSize.Default, ContextBuckets.textSize(1.0f))
        assertEquals(TextSize.Large, ContextBuckets.textSize(1.3f))
        assertEquals(TextSize.Accessibility, ContextBuckets.textSize(1.5f))
        assertEquals(
            listOf("en-GB", "de", "zh-Hans-CN"),
            ContextBuckets.languages(listOf("en-GB", "und", "de", "en-GB", "zh-Hans-CN", "tr")),
        )
        assertEquals(emptyList(), ContextBuckets.languages(listOf("x", "en-GB-abcdefghijklmnop")))
    }

    @Test fun health() {
        val gb = 1_000_000_000L
        assertEquals(FreeStorage.Under1Gb, ContextBuckets.freeStorage(gb - 1))
        assertEquals(FreeStorage.From1To5Gb, ContextBuckets.freeStorage(gb))
        assertEquals(FreeStorage.From5To20Gb, ContextBuckets.freeStorage(5 * gb))
        assertEquals(FreeStorage.Over20Gb, ContextBuckets.freeStorage(20 * gb))
        assertEquals(40, ContextBuckets.battery(47, 100))
        assertEquals(100, ContextBuckets.battery(100, 100))
        assertEquals(0, ContextBuckets.battery(9, 100))
        assertEquals(50, ContextBuckets.battery(128, 255))
        assertNull(ContextBuckets.battery(-1, 100))
        assertEquals(Thermal.Nominal, ContextBuckets.thermal(0))
        assertEquals(Thermal.Fair, ContextBuckets.thermal(1))
        assertEquals(Thermal.Fair, ContextBuckets.thermal(2))
        assertEquals(Thermal.Serious, ContextBuckets.thermal(3))
        assertEquals(Thermal.Critical, ContextBuckets.thermal(6))
        assertEquals(MemoryPressure.Critical, ContextBuckets.memoryPressure(true, 10, 100))
        assertEquals(MemoryPressure.Warning, ContextBuckets.memoryPressure(false, 150, 100))
        assertEquals(MemoryPressure.Normal, ContextBuckets.memoryPressure(false, 1000, 100))
    }

    @Test fun network() {
        assertEquals(NetworkKind.None, ContextBuckets.network(connected = false, wifi = true, cellular = false, ethernet = false))
        assertEquals(NetworkKind.Wifi, ContextBuckets.network(connected = true, wifi = true, cellular = true, ethernet = false))
        assertEquals(NetworkKind.Cellular, ContextBuckets.network(connected = true, wifi = false, cellular = true, ethernet = false))
        assertEquals(NetworkKind.Wired, ContextBuckets.network(connected = true, wifi = false, cellular = false, ethernet = true))
        assertEquals(NetworkKind.Other, ContextBuckets.network(connected = true, wifi = false, cellular = false, ethernet = false))
    }
}
