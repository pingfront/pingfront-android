package dev.pingfront

/** The result of [PingFront.selfCheck]: whether the setup message reached the server, and what to do if not. */
@ConsistentCopyVisibility
public data class SetupCheckResult internal constructor(
    /** True when the setup message reached the server. */
    public val passed: Boolean,
    /** A stable failure code, such as `key_unknown`, `network`, `timeout`, `app_paused` or `not_configured`; null when passed. */
    public val code: String? = null,
    /** What to do about the failure, in English; null when passed. */
    public val advice: String? = null,
) {
    /** The one-line form: `PingFront self-check: PASS` or `PingFront self-check: FAIL <code>: <advice>`. */
    public val line: String
        get() = if (passed) "PingFront self-check: PASS" else "PingFront self-check: FAIL ${code ?: "unexpected"}: ${advice.orEmpty()}"

    /** Known results. */
    public companion object {
        /** A passing result. */
        public val Pass: SetupCheckResult = SetupCheckResult(passed = true)
    }
}
