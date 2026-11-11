package dev.pingfront.testing

import java.io.File

/**
 * The protocol's language-neutral conformance fixtures (schema cases, key and link vectors, behaviour scenarios),
 * shared with the server and the other pingfront SDKs. The build passes their folder as `pingfront.fixtures`.
 */
public object ProtocolFixtures {
    public val root: File by lazy {
        val path = System.getProperty("pingfront.fixtures") ?: error("pingfront.fixtures is not set: run the tests through Gradle")
        File(path).also { check(File(it, "keys.json").isFile) { "no protocol fixtures in $it" } }
    }

    public fun file(name: String): File = File(root, name)
}
