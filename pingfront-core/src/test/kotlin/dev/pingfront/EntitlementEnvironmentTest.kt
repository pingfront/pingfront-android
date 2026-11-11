package dev.pingfront

import dev.pingfront.internal.wire.WireJson
import dev.pingfront.model.Entitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Entitlement.environment (optional): decoded as sent, and anything but production is a test purchase. */
class EntitlementEnvironmentTest {
    @Test fun theEnvironmentDecodesAndMarksTestPurchases() {
        val sandbox = WireJson.decodeFromString(Entitlement.serializer(), """{"state":"active","environment":"sandbox"}""")
        assertEquals("sandbox", sandbox.environment)
        assertTrue(sandbox.isTestPurchase)
        val absent = WireJson.decodeFromString(Entitlement.serializer(), """{"state":"active"}""")
        assertFalse(absent.isTestPurchase)
    }
}
