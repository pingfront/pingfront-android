plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.roborazzi) apply false
}

/** The oldest Kotlin an app that uses the SDK may compile with (README: Requirements). */
val KOTLIN_CONSUMER_STDLIB = "2.2.0"

// detekt runs from its CLI (a plain JavaExec): the detekt Gradle plugin still calls a Gradle API that
// is deprecated, which `--warning-mode=fail` rejects.
val detektCliModule = "dev.detekt:detekt-cli:${libs.versions.detekt.get()}"
val composeRulesModule = libs.compose.rules.detekt.get().toString()

// Maven Central: `./gradlew publishToMavenCentral -Ppingfront.version=…` (buildSrc MavenCentral.kt).
registerMavenCentralUpload()

// The protocol's conformance fixtures (schemas, key and link vectors, behaviour scenarios) that the unit tests run:
// `fixtures/`, or the folder the `pingfront.fixtures` Gradle property names.
val protocolFixtures: File = file(providers.gradleProperty("pingfront.fixtures").getOrElse("fixtures"))

// Source guards (scripts/guard.sh): part of every module's `check`.
val guard = tasks.register<Exec>("guard") {
    group = "verification"
    description = "Runs scripts/guard.sh (raw requests, log privacy, storage, image caching, purchases)."
    // The rules' own tests first (scripts/guard-test.sh), then the rules on the real sources.
    commandLine(
        "bash",
        "-c",
        "bash \"\$0\" && bash \"\$1\"",
        layout.projectDirectory.file("scripts/guard-test.sh").asFile.path,
        layout.projectDirectory.file("scripts/guard.sh").asFile.path,
    )
    inputs.dir("scripts")
    inputs.files(fileTree(".") { include("pingfront*/src/main/**/*.kt", "pingfront*/api/*.api", "REFERENCE.md") })
    outputs.upToDateWhen { true }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.8.0")
        android.set(true)
    }

    val sources = listOf("src/main/kotlin").map { file(it) }.filter { it.exists() }
    val cli = configurations.create("detektCli")
    val rules = configurations.create("detektRules")
    dependencies.add(cli.name, detektCliModule)
    dependencies.add(rules.name, composeRulesModule)
    val configFile = rootProject.file("config/detekt/detekt.yml")
    val detekt = tasks.register<JavaExec>("detekt") {
        group = "verification"
        description = "Runs detekt (strict config, Compose rules, no baseline)."
        classpath = cli
        mainClass.set("dev.detekt.cli.Main")
        inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(configFile)
        val marker = layout.buildDirectory.file("reports/detekt/ok.txt")
        outputs.file(marker)
        argumentProviders += CommandLineArgumentProvider {
            listOf(
                "--input", sources.joinToString(File.pathSeparator) { it.path },
                "--config", configFile.path,
                "--build-upon-default-config",
                "--plugins", rules.files.filter { "io.nlopez.compose.rules" in it.path }.joinToString(File.pathSeparator) { it.path },
            )
        }
        doLast { marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok") }
    }

    // Published libraries are read by apps on older Kotlin compilers (a fresh React Native 0.88 or Expo 58 app declares
    // Kotlin 2.2.0 and resolves the Kotlin Gradle plugin to 2.2.10), and a compiler only reads metadata up to one minor version newer than itself
    // (kotlinlang.org/docs/kotlin-evolution-principles.html). So the SDK is built with the current compiler but
    // targets Kotlin 2.2 (language and API version) and depends on the 2.2.0 standard library, Kotlin's documented
    // approach for library authors (kotlinlang.org/docs/api-guidelines-backward-compatibility.html). An app (not
    // a library) built here stays on the current language version.
    plugins.withId("com.android.library") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            coreLibrariesVersion = KOTLIN_CONSUMER_STDLIB
            compilerOptions {
                languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
                apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            }
        }
    }

    tasks.withType<Test>().configureEach {
        inputs.dir(protocolFixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("protocolFixtures")
        systemProperty("pingfront.fixtures", protocolFixtures.absolutePath)
    }

    plugins.withId("com.android.base") {
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(guard)
            dependsOn(detekt)
        }
    }
}
