pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pingfront-android"

include(
    ":pingfront-core",
    ":pingfront-ui",
    ":pingfront",
    ":pingfront-push",
    ":pingfront-billing",
    ":pingfront-reports",
    ":pingfront-revenuecat",
    ":pingfront-superwall",
    ":pingfront-adapty",
)
