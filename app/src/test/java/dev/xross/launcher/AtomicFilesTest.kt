package dev.xross.launcher

import dev.xross.launcher.config.AtomicFiles
import dev.xross.launcher.config.ConfigWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class AtomicFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesAndReplacesWithoutLeavingTheTempFile() {
        val target = File(tmp.root, "config/settings.json")
        AtomicFiles.writeText(target, "one")
        assertEquals("one", target.readText())
        AtomicFiles.writeText(target, "two")
        assertEquals("two", target.readText())
        assertFalse(AtomicFiles.tmpFor(target).exists())
    }

    @Test fun failedWriteKeepsTheOldFile() {
        val target = File(tmp.root, "settings.json").apply { writeText("old") }
        val broken = object : InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 10) 'x'.code else throw IOException("card removed")
        }
        try {
            AtomicFiles.copy(broken, target)
            fail("expected the copy to fail")
        } catch (_: IOException) {
        }
        assertEquals("old", target.readText())
        assertFalse(AtomicFiles.tmpFor(target).exists())
    }

    @Test fun beforeCommitCanRejectTheCopy() {
        val target = File(tmp.root, "xross.db").apply { writeText("previous") }
        try {
            AtomicFiles.copy(ByteArrayInputStream("new".toByteArray()), target) { throw IOException("invalid") }
            fail("expected rejection")
        } catch (_: IOException) {
        }
        assertEquals("previous", target.readText())
    }

    @Test fun readIgnoresAndRemovesALeftoverTempFile() {
        val target = File(tmp.root, "controllers.json")
        AtomicFiles.tmpFor(target).writeText("half-writ")
        assertNull(AtomicFiles.readText(target))
        assertFalse(AtomicFiles.tmpFor(target).exists())
        target.writeText("{}")
        assertEquals("{}", AtomicFiles.readText(target))
    }

    @Test fun configWriterKeepsOnlyTheNewestContent() {
        val target = File(tmp.root, "systems.json")
        repeat(50) { ConfigWriter.write(target, "version $it") }
        ConfigWriter.awaitIdle()
        assertEquals("version 49", target.readText())
        assertTrue(target.isFile)
    }
}
