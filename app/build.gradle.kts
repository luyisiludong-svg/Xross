import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.xross.launcher"
    compileSdk = 36

    // ScreenScraper credentials never live in the repository. Looked up, in order, as a Gradle
    // property (-Pscreenscraper.devId=…), in local.properties, or in the git-ignored .env file
    // (SCREENSCRAPER_DEV_ID=…, see .env.example).
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    val dotenv = Properties().apply {
        rootProject.file(".env").takeIf { it.isFile }?.readLines()?.forEach { raw ->
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) return@forEach
            setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim().removeSurrounding("\""))
        }
    }
    fun secret(name: String, vararg envNames: String): String =
        (project.findProperty(name) as String?) ?: local.getProperty(name)
            ?: envNames.firstNotNullOfOrNull { dotenv.getProperty(it)?.takeIf { v -> v.isNotBlank() } } ?: ""
    fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    defaultConfig {
        buildConfigField("boolean", "PERF_LOG", "false")
        buildConfigField("String", "SCREENSCRAPER_DEV_ID", quoted(secret("screenscraper.devId", "SCREENSCRAPER_DEV_ID")))
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD", quoted(secret("screenscraper.devPassword", "SCREENSCRAPER_DEV_PASSWORD")))
        // A personal account is only ever baked into debug builds (see below), for testing.
        buildConfigField("String", "DEBUG_SCREENSCRAPER_USER", "\"\"")
        buildConfigField("String", "DEBUG_SCREENSCRAPER_PASSWORD", "\"\"")
        buildConfigField("String", "DEBUG_STEAMGRIDDB_KEY", "\"\"")
        buildConfigField("String", "DEBUG_RETROACHIEVEMENTS_USER", "\"\"")
        buildConfigField("String", "DEBUG_RETROACHIEVEMENTS_KEY", "\"\"")
        applicationId = "dev.xross.launcher"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PERF_LOG", "true")
            // Pre-fills Settings ▸ Metadata ▸ ScreenScraper account on a fresh install, from .env.
            buildConfigField("String", "DEBUG_SCREENSCRAPER_USER", quoted(secret("screenscraper.user", "SCREENSCRAPER_USER")))
            buildConfigField("String", "DEBUG_SCREENSCRAPER_PASSWORD", quoted(secret("screenscraper.password", "SCREENSCRAPER_PASSWORD", "SCREENSCRAPER_USER_PASSWORD")))
            buildConfigField("String", "DEBUG_STEAMGRIDDB_KEY", quoted(secret("steamgriddb.apiKey", "STEAMGRIDDB_API_KEY", "SteamGridDB_API")))
            // Links RetroAchievements on a fresh debug install (user name + Web API key, never the password).
            buildConfigField("String", "DEBUG_RETROACHIEVEMENTS_USER", quoted(secret("retroachievements.user", "RETROACHIEVEMENTS_USER", "RETROACHIEVEMENT_USER")))
            buildConfigField("String", "DEBUG_RETROACHIEVEMENTS_KEY", quoted(secret("retroachievements.apiKey", "RETROACHIEVEMENTS_WEB_API_KEY", "RETROACHIEVEMENT_USER_API")))
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
    // Portable config files in Xross/config/ (settings.json, controllers.json, systems.json).
    implementation(libs.kotlinx.serialization.json)
    // Theme 2.0 contextual palette: swatches from the focused game's hero or wheel art.
    implementation(libs.androidx.palette.ktx)

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
    // Compose under Robolectric: animation tests (E-Ink still motion).
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// ScreenScraperLiveTest makes real API calls; it only runs with -PliveScrape=true.
tasks.withType<Test>().configureEach {
    systemProperty("xross.liveScrape", (project.findProperty("liveScrape") as String?) ?: "false")
}

// Room writes the exported schema during KSP; the debug assets (used by migration tests) must be
// merged after it, or a version bump's first build can't find the new schema file.
tasks.configureEach {
    if (name == "mergeDebugAssets") dependsOn("kspDebugKotlin")
}
