plugins {
    alias(libs.plugins.android.library)
}

android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
    namespace = "dev.pingfront.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    explicitApi()
    compilerOptions { allWarningsAsErrors.set(true) }
}

// The umbrella: one dependency for most apps. Core, the Compose chat (registered automatically) and
// bug reports. Push (Firebase) and Billing (Play Billing) stay separate so apps only pull what they use.
dependencies {
    api(project(":pingfront-core"))
    api(project(":pingfront-ui"))
    api(project(":pingfront-reports"))
}

registerApiValidation()

pingfrontPublishing("pingfront", "The pingfront Android SDK: core, chat UI and reports in one dependency.")
