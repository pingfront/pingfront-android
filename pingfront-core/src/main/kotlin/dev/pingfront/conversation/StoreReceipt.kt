package dev.pingfront.conversation

/** What a store action card left behind, shown as a local receipt line under it (never sent). */
public enum class StoreReceipt {
    /** Restore found a purchase the app did not know about. */
    Restored,

    /** Restore found nothing to restore. */
    NothingToRestore,

    /** Restore found the same purchases the app already had. */
    UpToDate,

    /** After returning from Google Play, the subscription had changed. */
    Updated,

    /** Google Play could not be reached. */
    Unavailable,
}
