package dev.pingfront

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review AN-23 (3 Nov 2026): before 1.0.0 freezes the API, every public class must be able to gain a field without
 * breaking apps compiled against it. A public data class cannot: adding a property changes its public constructor
 * and `copy`. So a data class in the API dumps keeps its constructor and `copy` out of the API
 * (`@ConsistentCopyVisibility` with an internal constructor, for what the SDK produces), and what apps construct is a
 * regular class whose constructors can be overloaded as it grows. `componentN` functions are additive and allowed.
 */
class EvolvableApiTest {
    @Test fun noPublicDataClassExposesItsConstructorOrCopy() {
        val dumps = File("..").listFiles { f -> f.isDirectory && f.name.startsWith("pingfront") }.orEmpty()
            .mapNotNull { module -> File(module, "api/${module.name}.api").takeIf { it.exists() } }
        val offenders = dumps.flatMap { dump ->
            Regex("""public (?:final |abstract )?class (\S+) [^\n]*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
                .findAll(dump.readText())
                .filter { "component1" in it.groupValues[2] }
                .filter { m ->
                    // The JVM's no-argument constructor (emitted when every parameter has a default, whatever the
                    // constructor's visibility) survives an added field with a default, so it stays allowed.
                    m.groupValues[2].lines().any {
                        ("public fun <init> (" in it && "public fun <init> ()V" !in it) || "public final fun copy (" in it ||
                            "public static synthetic fun copy\$default" in it
                    }
                }
                .map { "${dump.name.removeSuffix(".api")}: ${it.groupValues[1]}" }
                .toList()
        }
        assertEquals(emptyList(), offenders, "${offenders.size} public data classes expose their constructor or copy")
    }
}
