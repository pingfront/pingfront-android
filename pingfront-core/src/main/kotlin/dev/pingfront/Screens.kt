package dev.pingfront

/**
 * A screen of your app that the team's saved links can open (the protocol, Saved links). Register them with
 * [PingFront.registerScreens]; a link card that names one goes to [PingFront.onScreen].
 */
public class AppScreen(
    /**
     * Your own route id for the screen, such as `"settings.sound"` or `"plans/yearly"`:
     * `^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$`.
     */
    public val id: String,
    /** What the team sees in the dashboard ("Sound settings"), at most 60 characters. Never shown to the person. */
    public val title: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is AppScreen && other.id == id && other.title == title

    override fun hashCode(): Int = java.util.Objects.hash(id, title)

    override fun toString(): String = "AppScreen(id=$id, title=$title)"
}

/** Checks and keeps the screens the app registered: valid ids only, each once, at most [MAX]. */
internal object ScreenRules {
    const val MAX: Int = 200
    private const val TITLE_CHARS = 60
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$")

    fun isValid(id: String): Boolean = ID.matches(id)

    /** The list as the SDK sends it, and what it dropped (bad ids, repeats, beyond [MAX]). */
    class Result(val screens: List<AppScreen>, val invalid: List<String>, val repeated: List<String>, val overflow: Int)

    fun clean(screens: List<AppScreen>): Result {
        val kept = LinkedHashMap<String, AppScreen>()
        val invalid = mutableListOf<String>()
        val repeated = mutableListOf<String>()
        var overflow = 0
        for (screen in screens) {
            when {
                !isValid(screen.id) -> invalid += screen.id
                screen.id in kept -> repeated += screen.id
                kept.size >= MAX -> overflow += 1
                else -> kept[screen.id] = AppScreen(screen.id, title(screen.title))
            }
        }
        return Result(kept.values.toList(), invalid, repeated, overflow)
    }

    /** Blank titles are dropped; long ones are cut at 60 code points. */
    private fun title(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return dev.pingfront.internal.wire.Limits.truncate(value, TITLE_CHARS)
    }
}
