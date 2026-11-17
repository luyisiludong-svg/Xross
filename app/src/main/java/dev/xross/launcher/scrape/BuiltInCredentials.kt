package dev.xross.launcher.scrape

import android.util.Base64
import dev.xross.launcher.BuildConfig
import java.security.MessageDigest

/**
 * Credentials this build ships with, unmasked on first use. app/build.gradle.kts masks them (XOR
 * with SHA-256("xross:<field>"), Base64) so the APK carries no plain-text secret. They come from
 * the developer's git-ignored `.env` at build time and never live in the repository.
 */
object BuiltInCredentials {
    val screenScraperDevId: String by lazy { unmask("SCREENSCRAPER_DEV_ID", BuildConfig.SCREENSCRAPER_DEV_ID_MASKED) }
    val screenScraperDevPassword: String by lazy { unmask("SCREENSCRAPER_DEV_PASSWORD", BuildConfig.SCREENSCRAPER_DEV_PASSWORD_MASKED) }

    /** This build can talk to ScreenScraper at all. */
    val hasScreenScraper: Boolean get() = screenScraperDevId.isNotBlank() && screenScraperDevPassword.isNotBlank()

    internal fun unmask(field: String, masked: String): String {
        if (masked.isEmpty()) return ""
        val key = MessageDigest.getInstance("SHA-256").digest("xross:$field".toByteArray())
        val bytes = Base64.decode(masked, Base64.NO_WRAP)
        return String(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }, Charsets.UTF_8)
    }
}
