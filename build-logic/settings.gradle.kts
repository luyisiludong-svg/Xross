// Convention plugins for Xross's modules (docs/design/ARCHITECTURE_ASSESSMENT.md §6 step 2).
// An included build, so the root build sees its plugins by id: `id("xross.jvm.library")`.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "build-logic"
include(":convention")
