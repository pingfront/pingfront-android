package dev.pingfront.internal.debug

import dev.pingfront.ErrorCode
import dev.pingfront.KeyProblem
import dev.pingfront.PingFrontException
import dev.pingfront.internal.Engine
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.wire.SendMessageRequest
import dev.pingfront.internal.wire.SessionReason
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private val log = Log.of(Log.Category.SelfCheck)

/**
 * The debug-only self-check for coding agents. `DebugGate` keeps it out of release builds: the app's
 * R8 rules assume the gate is off, so the code is removed when the app is minified.
 */
internal object DebugGate {
    @Volatile private var enabled = false

    @JvmStatic fun isEnabled(): Boolean = enabled

    fun set(debuggable: Boolean) {
        enabled = debuggable
    }
}

internal object SelfCheck {
    const val EXTRA: String = "pingfront_selfcheck"
    const val PREVIEW_EXTRA: String = "pingfront_preview"

    /** Creates a session, sends "Setup check from <app>", waits for the server and returns the one line. */
    suspend fun run(engine: Engine, appName: String, timeout: Duration = dev.pingfront.internal.runtime.Timings.selfCheckTimeout): String =
        check(engine, appName, timeout).line

    /** The typed result of the same check (the public `PingFront.selfCheck()`). */
    suspend fun check(
        engine: Engine,
        appName: String,
        timeout: Duration = dev.pingfront.internal.runtime.Timings.selfCheckTimeout,
    ): dev.pingfront.SetupCheckResult {
        val result: Result<Unit>? = withTimeoutOrNull(timeout) {
            try {
                val request = SendMessageRequest(UUID.randomUUID().toString(), "Setup check from $appName")
                engine.client.send(Endpoints.sendMessage(request, SessionReason.Prepare))
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                Result.failure(e)
            }
        }
        return result(result)
    }

    fun result(result: Result<Unit>?): dev.pingfront.SetupCheckResult {
        if (result == null) {
            return dev.pingfront.SetupCheckResult(
                false,
                "timeout",
                "The server did not answer within 20 seconds. Check baseUrl and that the API is running.",
            )
        }
        val error = result.exceptionOrNull() as? PingFrontException ?: return dev.pingfront.SetupCheckResult.Pass
        val (code, advice) = describe(error)
        return dev.pingfront.SetupCheckResult(false, code, advice)
    }

    fun line(result: Result<Unit>?): String = result(result).line

    fun line(problem: KeyProblem): String = dev.pingfront.SetupCheckResult(false, "key_invalid", problem.message).line

    fun describe(error: PingFrontException): Pair<String, String> = when (error) {
        is PingFrontException.InvalidKey -> "key_invalid" to error.problem.message
        is PingFrontException.Network ->
            "network" to "The API could not be reached. Check the network and baseUrl (the default is https://api.pingfront.sh/v1)."
        is PingFrontException.CoolingDown -> "rate_limited" to "Too many requests from this install. Wait a minute and run the check again."
        is PingFrontException.Server -> when (error.error.code) {
            ErrorCode.InvalidAppKey ->
                "key_unknown" to
                    "The server does not know this app key. Copy it again from Dashboard > App > Connect, and check test vs live."
            ErrorCode.AppPaused -> "app_paused" to "The app is paused. Resume it in Dashboard > App > Settings."
            ErrorCode.Blocked -> "blocked" to "This install is blocked in the dashboard. Unblock the person or use another device."
            ErrorCode.IdentityUserMismatch ->
                "identity_user_mismatch" to "This install is verified as another user. Call PingFront.logout() before identifying someone else."
            else ->
                error.error.code.raw to
                    "The server answered ${error.error.status}. See https://pingfront.sh/docs/errors#${error.error.code.raw}."
        }
        else -> "unexpected" to "The check failed (${error.javaClass.simpleName}). Run it again; if it keeps failing, see Dashboard > Health."
    }

    fun emit(line: String) {
        if (line.endsWith("PASS")) log.always(line) else log.e(line)
    }
}

/**
 * Loads fixture data for the `pingfront_preview` launch extra. The implementation lives in the debug
 * source set only (`DebugPreviewHooks`), so release builds carry no fixtures.
 */
internal interface PreviewHooks {
    /** Loads fixture state for [name]. Returns `true` when the chat should open (some scenarios start in the app). */
    fun load(instance: dev.pingfront.PingFrontInstance, name: String): Boolean

    companion object {
        private const val IMPLEMENTATION = "dev.pingfront.internal.debug.DebugPreviewHooks"

        fun find(): PreviewHooks? = try {
            Class.forName(IMPLEMENTATION).getField("INSTANCE").get(null) as? PreviewHooks
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}

/** Lets the SDK's other modules ask whether debug-only behavior is on (stripped by R8 in minified apps). */
@dev.pingfront.InternalPingFrontApi
public object DebugGateAccess {
    /** True in debuggable apps (always false once R8 minifies the app). */
    public fun isDebug(): Boolean = DebugGate.isEnabled()
}
