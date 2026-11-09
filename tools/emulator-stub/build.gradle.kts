// Test-only stand-in for real emulators. Each flavor installs under a real emulator's package
// and activity name, logs the launch intent it receives (tag "WaveStub"), and checks that the
// ROM is readable through the granted content:// URI. Never ship this.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.wave.stub"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "stub"
    }

    flavorDimensions += "emulator"
    productFlavors {
        fun emulator(name: String, appId: String, activity: String) = create(name) {
            dimension = "emulator"
            applicationId = appId
            manifestPlaceholders["entryActivity"] = activity
        }
        emulator("retroarch", "com.retroarch.aarch64", "com.retroarch.browser.retroactivity.RetroActivityFuture")
        emulator("duckstation", "com.github.stenzek.duckstation", "com.github.stenzek.duckstation.EmulationActivity")
        emulator("nethersx2", "xyz.aethersx2.android", "xyz.aethersx2.android.EmulationActivity")
        emulator("dolphin", "org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinemu.ui.main.MainActivity")
        emulator("ppsspp", "org.ppsspp.ppsspp", "org.ppsspp.ppsspp.PpssppActivity")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
