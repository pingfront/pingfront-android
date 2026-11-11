package dev.pingfront

/**
 * Marks declarations that are public only so the SDK's own modules (UI, push, billing, reports) can
 * use them. They are not part of the stable API, are excluded from the API dump, and can change in
 * any release.
 */
@RequiresOptIn(
    message = "Internal pingfront API, used between the SDK's modules. It can change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalPingFrontApi
