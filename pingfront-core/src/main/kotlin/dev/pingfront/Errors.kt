package dev.pingfront

import kotlin.time.Duration

/** A server error code (the `ErrorCode` the API returns). Unknown codes are kept verbatim. */
@JvmInline
public value class ErrorCode(
    /** The code as sent by the server. */
    public val raw: String,
) {
    override fun toString(): String = raw

    /** Known codes. */
    public companion object {
        /** 401: the server does not know this app key. */
        public val InvalidAppKey: ErrorCode = ErrorCode("invalid_app_key")

        /** 403: the app is paused in the dashboard. */
        public val AppPaused: ErrorCode = ErrorCode("app_paused")

        /** 401: the access token is missing or expired. */
        public val Unauthorized: ErrorCode = ErrorCode("unauthorized")

        /** 401: the install secret was revoked (on any route). The SDK wipes local state and starts a new guest session. */
        public val SessionRevoked: ErrorCode = ErrorCode("session_revoked")

        /** 409: the identity token belongs to a different user. */
        public val IdentityUserMismatch: ErrorCode = ErrorCode("identity_user_mismatch")

        /** 403: the person is blocked. */
        public val Blocked: ErrorCode = ErrorCode("blocked")

        /** 403: the app accepts messages only from verified people (`identify(token)` first). */
        public val VerificationRequired: ErrorCode = ErrorCode("verification_required")

        /** 403: the caller's role does not allow this (dashboard routes). */
        public val Forbidden: ErrorCode = ErrorCode("forbidden")

        /** 409: someone else changed it first (dashboard routes). */
        public val Conflict: ErrorCode = ErrorCode("conflict")

        /** 404. */
        public val NotFound: ErrorCode = ErrorCode("not_found")

        /** 409: the thread was reset; reload it. */
        public val ThreadReset: ErrorCode = ErrorCode("thread_reset")

        /** 422: the request did not match the schema. */
        public val InvalidRequest: ErrorCode = ErrorCode("invalid_request")

        /** 413. */
        public val TooLarge: ErrorCode = ErrorCode("too_large")

        /** 409: an upload was referenced before it finished. */
        public val UploadIncomplete: ErrorCode = ErrorCode("upload_incomplete")

        /** 429: see `scope` and `retryAfter`. */
        public val RateLimited: ErrorCode = ErrorCode("rate_limited")

        /** 503. */
        public val Unavailable: ErrorCode = ErrorCode("unavailable")
    }
}

/** The part of the API a rate limit applies to. Only that scope pauses. Open: unknown scopes keep their code. */
@JvmInline
public value class RateScope(
    /** The wire name. */
    public val code: String,
) {
    override fun toString(): String = code

    /** Known scopes. */
    public companion object {
        /** Sending messages. */
        public val Messages: RateScope = RateScope("messages")

        /** Photo and screenshot uploads. */
        public val Uploads: RateScope = RateScope("uploads")

        /** Creating or refreshing sessions. */
        public val Sessions: RateScope = RateScope("sessions")

        /** Reporting moments. */
        public val Moments: RateScope = RateScope("moments")

        /** The live socket. */
        public val Live: RateScope = RateScope("live")

        /** The person's data export (`exportMyData()`): 5 an hour per install. */
        public val Exports: RateScope = RateScope("exports")

        internal fun fromCode(code: String?): RateScope? = code?.takeIf { it.isNotEmpty() }?.let(::RateScope)
    }
}

/** An error returned by the pingfront API. */
public class ServerError(
    /** HTTP status. */
    public val status: Int,
    /** Machine-readable code. */
    public val code: ErrorCode,
    /** Human-readable explanation from the server. */
    public val message: String,
    /** The docs page that explains the fix. */
    public val docs: java.net.URI? = null,
    /** How long to wait before retrying, when the server says. */
    public val retryAfter: Duration? = null,
    /** For 429s: which part of the API is paused. */
    public val scope: RateScope? = null,
    /** For 422s: which fields failed, as dotted paths (`identity.email`). */
    public val fields: List<FieldError> = emptyList(),
) {
    /** A copy with the given fields replaced. */
    public fun copy(
        status: Int = this.status,
        code: ErrorCode = this.code,
        message: String = this.message,
        docs: java.net.URI? = this.docs,
        retryAfter: Duration? = this.retryAfter,
        scope: RateScope? = this.scope,
        fields: List<FieldError> = this.fields,
    ): ServerError = ServerError(status, code, message, docs, retryAfter, scope, fields)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerError) return false
        return status == other.status &&
            code == other.code &&
            message == other.message &&
            docs == other.docs &&
            retryAfter == other.retryAfter &&
            scope == other.scope &&
            fields == other.fields
    }

    override fun hashCode(): Int = java.util.Objects.hash(status, code, message, docs, retryAfter, scope, fields)

    override fun toString(): String =
        "ServerError(status=$status, code=$code, message=$message, docs=$docs, retryAfter=$retryAfter, scope=$scope, fields=$fields)"
}

/** One field a 422 rejected. */
@ConsistentCopyVisibility
public data class FieldError internal constructor(
    /** The dotted path, for example `identity.email`. */
    public val path: String,
    /** Why. */
    public val message: String,
)

/**
 * Every error the SDK surfaces. Suspending APIs throw these (cancellation stays a
 * `CancellationException`, as coroutines expect).
 */
public sealed class PingFrontException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** `PingFront.configure` was not called yet, or failed. */
    public class NotConfigured : PingFrontException("PingFront.configure was not called, or it reported a configuration problem.")

    /** The app key failed the offline check. */
    public class InvalidKey(
        /** What is wrong with the key. */
        public val problem: KeyProblem,
    ) : PingFrontException(problem.message)

    /** The server answered with an error envelope. */
    public class Server(
        /** The decoded error. */
        public val error: ServerError,
    ) : PingFrontException("${error.status} ${error.code}: ${error.message}")

    /** The request did not reach the server. */
    public class Network(
        /** True when the device appears to be offline. */
        public val offline: Boolean,
        cause: Throwable? = null,
    ) : PingFrontException(if (offline) "The device is offline." else "The request did not reach the server.", cause)

    /** A rate limit is active for [scope]; try again after [remaining]. */
    public class CoolingDown(
        /** The paused scope. */
        public val scope: RateScope,
        /** Time left. */
        public val remaining: Duration,
    ) : PingFrontException("Rate limited (${scope.code}); retry in $remaining.")

    /** The server answered with something the SDK could not read. */
    public class InvalidResponse(detail: String, cause: Throwable? = null) : PingFrontException(detail, cause)

    /** The input was rejected before sending (for example an empty message). */
    public class InvalidInput(detail: String) : PingFrontException(detail)

    /**
     * Encrypted storage is not readable right now (before the first unlock, or a Keystore failure).
     * Distinct from "nothing stored": the SDK waits instead of starting a new install.
     */
    public class StorageUnavailable(cause: Throwable? = null) : PingFrontException("Encrypted storage is not available yet.", cause)

    /**
     * The person logged out, erased their data, or the server revoked the session while this call ran.
     * Nothing was applied; call again for the new session if it still makes sense.
     */
    public class SessionChanged : PingFrontException("The session changed (logout, erase or revocation) while this call ran.")

    /** The server error code, when there is one. */
    public val code: ErrorCode? get() = (this as? Server)?.error?.code

    /** True for failures that a later retry can fix without changing the request. */
    public val isTransient: Boolean
        get() = when (this) {
            is Network, is CoolingDown, is StorageUnavailable -> true
            is Server -> error.status >= 500 || error.code == ErrorCode.RateLimited || error.code == ErrorCode.Unauthorized
            else -> false
        }

    /** True when the device appears to be offline. */
    public val isOffline: Boolean get() = this is Network && offline

    /**
     * A description safe for logs: the kind, HTTP status, code and scope only. Never the server's
     * message, URLs, tokens or anything the person wrote.
     */
    public val logDescription: String
        get() = when (this) {
            is NotConfigured -> "not_configured"
            is InvalidKey -> "invalid_key ${problem.code}"
            is Server -> buildString {
                append("server ").append(error.status).append(' ').append(error.code.raw)
                error.scope?.let { append(" scope=").append(it.code) }
                if (error.fields.isNotEmpty()) append(" fields=").append(error.fields.joinToString(",") { it.path })
            }
            is Network -> if (offline) "network offline" else "network unreachable"
            is CoolingDown -> "cooling_down ${scope.code} ${remaining.inWholeSeconds}s"
            is InvalidResponse -> "invalid_response"
            is InvalidInput -> "invalid_input"
            is StorageUnavailable -> "storage_unavailable"
            is SessionChanged -> "session_changed"
        }
}
