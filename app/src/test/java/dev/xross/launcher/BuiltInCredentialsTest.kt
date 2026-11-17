package dev.xross.launcher

import dev.xross.launcher.scrape.BuiltInCredentials
import dev.xross.launcher.scrape.providers.ScreenScraperCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class BuiltInCredentialsTest {

    /** The same mask app/build.gradle.kts applies. */
    private fun mask(field: String, value: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest("xross:$field".toByteArray())
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() })
    }

    @Test fun unmaskReversesTheBuildMask() {
        for (value in listOf("abc", "a-much-longer-value-than-the-thirty-two-byte-key!", "pässwörd")) {
            val masked = mask("SCREENSCRAPER_DEV_PASSWORD", value)
            assertFalse(masked.contains(value))
            assertEquals(value, BuiltInCredentials.unmask("SCREENSCRAPER_DEV_PASSWORD", masked))
        }
        assertEquals("", BuiltInCredentials.unmask("SCREENSCRAPER_DEV_ID", ""))
    }

    @Test fun credentialsNeverPrintTheirSecrets() {
        val text = ScreenScraperCredentials("devid123", "devsecret", "Xross", "retrofan", "usersecret").toString()
        for (secret in listOf("devid123", "devsecret", "usersecret")) assertFalse(text, text.contains(secret))
    }
}
