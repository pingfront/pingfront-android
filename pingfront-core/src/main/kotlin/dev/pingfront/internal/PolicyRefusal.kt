package dev.pingfront.internal

import dev.pingfront.ConfigurationProblem
import dev.pingfront.ErrorCode
import dev.pingfront.PingFrontException
import dev.pingfront.model.SendPolicy

/** Refusals that mean the person can't send now: the send policy, not one failed message. */
private val PolicyCodes = setOf(ErrorCode.Blocked, ErrorCode.AppPaused, ErrorCode.VerificationRequired)

/**
 * The send policy a refused send implies (blocked, the app paused, verification required), or null. The composer's
 * notice replaces the composer at once instead of after the next thread load, which confirms or clears it
 * (`policy.canSend`), as the web widget does.
 */
internal fun policyRefusal(error: PingFrontException): SendPolicy.Closed? =
    error.code?.takeIf { it in PolicyCodes }?.let { SendPolicy.Closed(SendPolicy.Reason(it.raw)) }

/** The setup problem a refused request reveals (debug builds show it in the chat), or null. [key] names the app key. */
internal fun setupProblem(error: PingFrontException, key: String): ConfigurationProblem? = when (error.code) {
    ErrorCode.InvalidAppKey -> ConfigurationProblem(
        ConfigurationProblem.Kind.UnknownKey,
        "The server does not know this app key ($key). Copy it again from Dashboard > App > Connect, and check test vs live.",
        disablesSdk = false,
    )
    ErrorCode.AppPaused -> ConfigurationProblem(
        ConfigurationProblem.Kind.AppPaused,
        "This app is paused in the dashboard. Resume it in Dashboard > App > Settings.",
        false,
    )
    else -> null
}

/**
 * The app is paused: the server then refuses the session refresh, the live channel and read receipts too, not only
 * sends. A blocked or unverified person is refused sends, uploads and reports only, and keeps live replies and reads.
 */
internal fun isPaused(policy: SendPolicy): Boolean = (policy as? SendPolicy.Closed)?.reason == SendPolicy.Reason.AppPaused

/** Something answered that a paused app would have refused (a session, an unread count): it isn't paused any more. */
internal fun dev.pingfront.conversation.Conversation.clearPaused() {
    if (isPaused(state.value.policy)) update { it.copy(policy = SendPolicy.Open) }
}
