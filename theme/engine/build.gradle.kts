// :theme:engine: the pack format and everything that resolves it (manifest, cascade, rules,
// variants, surfaces, components, cosmetics, colour pipeline, look resolver, export and
// archives), including the pack loader behind PackPlatform. Kotlin/JVM: no Android, no Compose
// UI; :app supplies the Android platform (assets, SVG and bitmap icons, typefaces).
plugins {
    id("xross.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.junit)
}

// The engine's tests load the real Default pack from the app's assets through a file-backed
// PackPlatform (testFixtures), so they run as plain JUnit with no Robolectric.
val defaultPackAssets = rootProject.layout.projectDirectory.dir("app/src/main/assets")
tasks.withType<Test>().configureEach {
    inputs.dir(defaultPackAssets.dir("themes")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("defaultPack")
    systemProperty("xross.assets", defaultPackAssets.asFile.absolutePath)
}
