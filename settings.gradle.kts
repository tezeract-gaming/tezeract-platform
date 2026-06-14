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

rootProject.name = "tezeract-platform"

// SDK + motion engine — the libraries every game depends on.
include(":sdk-motion")
include(":service-motion")

// Reference games + SDK validation tool. Each builds standalone via
// `./gradlew :app-flappy:assembleDebug` (etc.). They're shipped as
// examples that download cleanly and run on any Tezeract device.
include(":app-flappy")
include(":app-sample-game")
include(":app-input-tester")
include(":app-shadow-boxing")
