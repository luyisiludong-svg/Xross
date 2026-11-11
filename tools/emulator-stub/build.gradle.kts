// Test-only stand-in for real emulators. Each flavor installs under a real emulator's package
// and activity name, logs the launch intent it receives (tag "XrossStub"), and checks that the
// ROM is readable through the granted content:// URI. Never ship this.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.xross.stub"
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
        emulator("azahar", "org.azahar_emu.azahar", "org.citra.citra_emu.activities.EmulationActivity")
        emulator("eden", "dev.eden.eden_emulator", "org.yuzu.yuzu_emu.activities.EmulationActivity")
        emulator("flycast", "com.flycast.emulator", "com.flycast.emulator.MainActivity")
        emulator("m64plusfz", "org.mupen64plusae.v3.fzurita", "paulscode.android.mupen64plusae.SplashActivity")
        emulator("melonds", "me.magnum.melonds", "me.magnum.melonds.ui.emulator.EmulatorActivity")
        emulator("scummvm", "org.scummvm.scummvm", "org.scummvm.scummvm.SplashActivity")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
