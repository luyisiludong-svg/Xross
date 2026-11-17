import java.security.MessageDigest
import java.util.Base64
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

    // Developer credentials have to ship inside the APK (every ScreenScraper call needs them), so
    // they are stored masked rather than as plain strings: no secret scanner, `strings` or casual
    // decompile shows them. BuiltInCredentials.kt reverses the mask. This is obfuscation, not
    // encryption; the values never appear in the repository either way.
    fun masked(field: String, value: String): String {
        if (value.isEmpty()) return "\"\""
        val key = MessageDigest.getInstance("SHA-256").digest("xross:$field".toByteArray())
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return quoted(Base64.getEncoder().encodeToString(out))
    }
    val ssDevId = secret("screenscraper.devId", "SCREENSCRAPER_DEV_ID")
    val ssDevPassword = secret("screenscraper.devPassword", "SCREENSCRAPER_DEV_PASSWORD")

    // A release without developer credentials would ship with ScreenScraper switched off for every
    // user, so refuse to build one. -PallowNoScreenScraper=true builds it anyway (forks, CI smoke builds).
    val releaseMissingScreenScraper = (ssDevId.isBlank() || ssDevPassword.isBlank()) &&
        (project.findProperty("allowNoScreenScraper") as String?) != "true"
    tasks.configureEach {
        if (name == "preReleaseBuild") doFirst {
            if (releaseMissingScreenScraper) throw GradleException(
                "Release build without ScreenScraper developer credentials: set SCREENSCRAPER_DEV_ID and " +
                    "SCREENSCRAPER_DEV_PASSWORD in .env (see .env.example), or pass -PallowNoScreenScraper=true.",
            )
        }
    }

    // Release signing, from the same places (XROSS_KEYSTORE=/path/to/keystore, XROSS_KEYSTORE_PASSWORD,
    // XROSS_KEY_ALIAS, XROSS_KEY_PASSWORD). Without them the release APK comes out unsigned.
    val keystorePath = secret("xross.keystore", "XROSS_KEYSTORE")
    val keystore = keystorePath.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    if (keystore != null && !keystore.isFile) logger.warn("XROSS_KEYSTORE points to a missing file; the release APK will be unsigned")
    if (keystore != null && keystore.isFile) {
        signingConfigs.create("release") {
            storeFile = keystore
            storePassword = secret("xross.keystorePassword", "XROSS_KEYSTORE_PASSWORD")
            keyAlias = secret("xross.keyAlias", "XROSS_KEY_ALIAS")
            keyPassword = secret("xross.keyPassword", "XROSS_KEY_PASSWORD")
        }
    }

    defaultConfig {
        buildConfigField("boolean", "PERF_LOG", "false")
        buildConfigField("String", "SCREENSCRAPER_DEV_ID_MASKED", masked("SCREENSCRAPER_DEV_ID", ssDevId))
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD_MASKED", masked("SCREENSCRAPER_DEV_PASSWORD", ssDevPassword))
        // A personal account is only ever baked into debug builds (see below), for testing.
        buildConfigField("String", "DEBUG_SCREENSCRAPER_USER", "\"\"")
        buildConfigField("String", "DEBUG_SCREENSCRAPER_PASSWORD", "\"\"")
        buildConfigField("String", "DEBUG_STEAMGRIDDB_KEY", "\"\"")
        buildConfigField("String", "DEBUG_RETROACHIEVEMENTS_USER", "\"\"")
        buildConfigField("String", "DEBUG_RETROACHIEVEMENTS_KEY", "\"\"")
        applicationId = "dev.xross.launcher"
        minSdk = 28
        targetSdk = 36
        // 0.2.0.3 → 203 (major·10000 + minor·1000 + patch·100 + build).
        versionCode = 203
        versionName = "0.2.0.3-beta"
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
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation(project(":core:model"))
    implementation(project(":theme:engine"))
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
    // StressPack and the file-backed PackPlatform, shared with :theme:engine's tests.
    testImplementation(testFixtures(project(":theme:engine")))
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

// Theme 2.0 2b.4a: every built-in background shader compiles as GLSL ES 3.00 under glslangValidator,
// composed the way the renderer composes it (prelude.glsl in front of each preset body). Part of
// `check`; needs glslangValidator on PATH (brew install glslang, apt install glslang-tools) or
// -PglslangValidator=/path/to/it.
abstract class ValidateShaders : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaderDir: DirectoryProperty

    @get:Input
    abstract val validator: Property<String>

    @get:OutputDirectory
    abstract val workDir: DirectoryProperty

    @TaskAction
    fun validate() {
        val src = shaderDir.get().asFile
        val out = workDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val prelude = File(src, "prelude.glsl").readText()
        val units = src.listFiles().orEmpty().filter { it.extension == "frag" || it.extension == "vert" }.sortedBy { it.name }
        if (units.none { it.extension == "frag" }) throw GradleException("No .frag presets in $src")
        val failures = ArrayList<String>()
        for (unit in units) {
            val body = unit.readText()
            val composed = File(out, unit.name)
            composed.writeText(if (body.trimStart().startsWith("#version")) body else prelude.trimEnd() + "\n" + body)
            val process = try {
                ProcessBuilder(validator.get(), composed.absolutePath).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                throw GradleException("glslangValidator not found (${validator.get()}): install glslang or pass -PglslangValidator=<path>", e)
            }
            val log = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) failures += "${unit.name}:\n" + log.lines().filter { it.contains("ERROR") }.joinToString("\n")
        }
        if (failures.isNotEmpty()) throw GradleException("Built-in shaders don't compile:\n" + failures.joinToString("\n"))
        logger.lifecycle("glslangValidator: ${units.size} built-in shaders OK")
    }
}

val validateShaders by tasks.registering(ValidateShaders::class) {
    group = "verification"
    description = "Compiles the built-in GLSL ES background shaders (assets/shaders) with glslangValidator."
    shaderDir.set(layout.projectDirectory.dir("src/main/assets/shaders"))
    validator.set(providers.gradleProperty("glslangValidator").orElse("glslangValidator"))
    workDir.set(layout.buildDirectory.dir("shaderValidation"))
}
tasks.named("check") { dependsOn(validateShaders) }
