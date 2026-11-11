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
rootProject.name = "Xross"
include(":app")

// Test-only emulator stand-ins (see tools/emulator-stub/build.gradle.kts).
include(":emulator-stub")
project(":emulator-stub").projectDir = file("tools/emulator-stub")
