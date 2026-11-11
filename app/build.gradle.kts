import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.xross.launcher"
    compileSdk = 36

    // ScreenScraper developer credentials are issued per application and must not be committed:
    // put screenscraper.devId / screenscraper.devPassword in local.properties (git-ignored) or
    // pass them as Gradle properties (-Pscreenscraper.devId=…) on the release machine.
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun secret(name: String): String =
        (project.findProperty(name) as String?) ?: local.getProperty(name) ?: ""
    fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    defaultConfig {
        buildConfigField("boolean", "PERF_LOG", "false")
        buildConfigField("String", "SCREENSCRAPER_DEV_ID", quoted(secret("screenscraper.devId")))
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD", quoted(secret("screenscraper.devPassword")))
        applicationId = "dev.xross.launcher"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PERF_LOG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-optimized, debug-signed build for frame-time measurements (installable side by side
        // isn't needed, so it keeps the same applicationId).
        create("profiling") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "PERF_LOG", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Exported Room schemas, readable by MigrationTestHelper in Robolectric migration tests.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // 7z header/entry access for ROM fingerprinting; xz decodes LZMA-compressed 7z headers.
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Media hub: audio/video playback, background music session, Compose video surface.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}

// Room writes the exported schema during KSP; the debug assets (used by migration tests) must be
// merged after it, or a version bump's first build can't find the new schema file.
tasks.configureEach {
    if (name == "mergeDebugAssets") dependsOn("kspDebugKotlin")
}
