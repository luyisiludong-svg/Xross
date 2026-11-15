import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * `xross.android.library`: an Android library module with `:app`'s SDK levels and Java 17
 * (AGP's built-in Kotlin compiles it). For `:core:data`, `:render:gl` and the feature modules;
 * a module that needs Compose adds the Compose compiler plugin itself.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig.minSdk = MIN_SDK
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions.unitTests.isIncludeAndroidResources = true
        }
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }

    companion object {
        const val COMPILE_SDK = 36
        const val MIN_SDK = 28
    }
}
