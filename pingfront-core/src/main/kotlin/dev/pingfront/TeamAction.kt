package dev.pingfront

import java.time.Instant

/**
 * Something a teammate asked the app to do for the person ("Fix it for them", the protocol, "Team actions"): a
 * built-in such as `extend_trial` (with [days] or [until]) or one of the app's custom actions. Do the work once
 * per [id], then call [complete] or [fail]. Collect them from [PingFront.teamActions].
 */
public class TeamAction internal constructor(
    /** The run's id: do the work once per id. */
    public val id: String,
    /** Which action: `extend_trial`, `grant_access` or a custom action's id. */
    public val actionId: String,
    /** The action's name, as the team sees it. */
    public val name: String,
    /** The fields the teammate filled in. */
    public val fields: Map<String, String>,
    /** Days to extend or grant, for the built-ins. */
    public val days: Int?,
    /** The new access end, for the built-ins. */
    public val until: Instant?,
    /** True when the receipt in the thread waits for [complete]. */
    public val ackRequired: Boolean,
    /** When the teammate ran it. */
    public val createdAt: Instant,
    /** After this the action expires and an ack is ignored. */
    public val expiresAt: Instant,
    private val ack: (ok: Boolean, message: String?) -> Unit,
) {
    /** The work is done: the receipt posts in the thread. Queued and retried until the server has it. */
    public fun complete(): Unit = ack(true, null)

    /**
     * The work could not be done. [message] (at most 200 characters) is shown to the team, never to the person.
     */
    public fun fail(message: String): Unit = ack(false, message)

    override fun toString(): String = "TeamAction($actionId, $id)"
}
