pluginManagement {
    // Convention plugins: xross.jvm.library, xross.android.library.
    includeBuild("build-logic")
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

// Platform-free layers (docs/design/ARCHITECTURE_ASSESSMENT.md §5): plain Kotlin/JVM.
include(":core:model")
include(":theme:engine")

// Test-only emulator stand-ins (see tools/emulator-stub/build.gradle.kts).
include(":emulator-stub")
project(":emulator-stub").projectDir = file("tools/emulator-stub")
