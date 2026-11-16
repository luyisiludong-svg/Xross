plugins {
    `kotlin-dsl`
}

group = "dev.xross.buildlogic"

// The plugins are only compiled against here; the root build's own classpath (the `apply false`
// aliases in the root build file) supplies them at run time, so every module uses one version.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "xross.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "xross.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
