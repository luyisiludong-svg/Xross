// Test-only: a long-lived accessibility reader for tools/device-tests. One `am instrument` keeps a
// UiAutomation session open and answers HTTP requests on the abstract socket "xross-uireader", so
// a screen read costs ~0.1 s instead of the ~2 s of a fresh `uiautomator dump` (a new process,
// then a 1 s idle wait). The XML matches `uiautomator dump`'s. Never ship this.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.xross.uireader"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.xross.uireader"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "uireader"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
