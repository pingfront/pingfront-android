package dev.pingfront

import dev.pingfront.internal.debug.SelfCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Review AN-22: the self-check line is logged (and the React Native and Flutter bridges return it), so it carries
 * codes and our own advice, never the server's text or an exception's message.
 */
class SelfCheckLineTest {
    @Test fun aCheckLineNeverCarriesServerText() {
        val server = PingFrontException.Server(ServerError(409, ErrorCode("conflict"), "person ada@example.com has thread t_1"))
        val (code, advice) = SelfCheck.describe(server)
        assertEquals("conflict", code)
        assertFalse(advice.contains("ada@example.com"), "server text in the advice: $advice")
        val other = SelfCheck.describe(PingFrontException.InvalidResponse("body was {\"email\":\"ada@example.com\"}"))
        assertEquals("unexpected", other.first)
        assertFalse(other.second.contains("ada@example.com"), "exception text in the advice: ${other.second}")
    }
}
