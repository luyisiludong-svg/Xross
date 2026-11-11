package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.MediaGame
import dev.xross.launcher.scrape.MediaNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Scraped art in Xross/media/<kind>/<system>/<rom stem>.<ext>: names, layout and the moves into it. */
class PortableMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val u2 = "22222222-2222-4222-8222-222222222222"

    private fun game(id: Long, uuid: String, file: String, platform: String = "snes") =
        GameEntity(id = id, path = "/r/$platform/$file", platformId = platform, title = file, fileName = file, sizeBytes = 1, lastModified = 0, uuid = uuid)

    // region Names

    @Test fun stemsAreTheRomNameWithoutItsExtension() {
        assertEquals("Super Mario World (USA)", MediaNames.romStem("Super Mario World (USA).sfc"))
        assertEquals("Final Fantasy VII (Disc 1).bin", MediaNames.romStem("Final Fantasy VII (Disc 1).bin.cue"))
        assertEquals("README", MediaNames.romStem("README"))
    }

    @Test fun namesAreFatSafe() {
        assertEquals("Zelda Ocarina of Time", MediaNames.sanitize("Zelda: Ocarina of Time"))
        assertEquals("Whats New", MediaNames.sanitize("What*s <New>?"))
        assertEquals("ABC", MediaNames.sanitize("A|B\\C\u0001\"/"))
        assertEquals("Dr. Mario", MediaNames.sanitize("Dr. Mario. . "))
        assertEquals("A B", MediaNames.sanitize("A \t  B"))
        assertEquals("_CON", MediaNames.sanitize("CON"))
        assertEquals("_nul.v2", MediaNames.sanitize("nul.v2"))
        assertEquals("_", MediaNames.sanitize("???"))
        assertEquals("ポケモン (Japan)", MediaNames.sanitize("ポケモン (Japan)"))
        val long = MediaNames.sanitize("x".repeat(300))
        assertEquals(MediaNames.MAX_STEM, long.length)
        // A cut never splits a surrogate pair.
        val emoji = MediaNames.sanitize("a".repeat(MediaNames.MAX_STEM - 1) + "😀😀")
        assertFalse(emoji.last().isHighSurrogate())
    }

    @Test fun systemFoldersAreSafeToo() {
        assertEquals("snes", MediaNames.systemFolder("snes"))
        assertEquals("n3ds", MediaNames.systemFolder("N3DS"))
        assertEquals("_", MediaNames.systemFolder("..")) // can't climb out of the kind folder
        assertEquals("ab", MediaNames.systemFolder("a/b"))
    }

    @Test fun clashingNamesInOneSystemGetAUuidSuffixForAllButTheOldest() {
        val slots = MediaNames.assign(
            listOf(
                MediaGame(5, u2, "snes", "Game.7z"),
                MediaGame(3, u1, "snes", "game.zip"), // same name on FAT, which ignores case
                MediaGame(9, "33333333-3333-4333-8333-333333333333", "gba", "Game.gba"), // other system: no clash
            ),
        )
        assertEquals("game", slots.getValue(u1).name)
        assertEquals("Game [22222222]", slots.getValue(u2).name)
        assertEquals("Game", slots.getValue("33333333-3333-4333-8333-333333333333").name)
        // The owner also finds files it got under its suffixed name before it became the oldest.
        assertEquals(listOf("game", "game [11111111]"), slots.getValue(u1).names)
    }

    // endregion

    // region Layout

    @Test fun artLivesUnderKindAndSystemWithEsDeFolderNames() = runBlocking {
        val media = tmp.newFolder("Xross", "media")
        val cache = AssetCache(media)
        cache.setLibrary(listOf(game(1, u1, "Chrono Trigger (USA).sfc")))
        for (kind in listOf(ArtKind.BOX_2D, ArtKind.HERO, ArtKind.WHEEL, ArtKind.VIDEO)) {
            cache.store(u1, kind, if (kind.isVideo) "mp4" else "png") { it.writeBytes(png) }
        }
        assertEquals(File(media, "covers/snes/Chrono Trigger (USA).png"), cache.file(u1, ArtKind.BOX_2D))
        assertEquals(File(media, "heroes/snes/Chrono Trigger (USA).png"), cache.file(u1, ArtKind.HERO))
        assertEquals(File(media, "wheels/snes/Chrono Trigger (USA).png"), cache.file(u1, ArtKind.WHEEL))
        assertEquals(File(media, "snaps/snes/Chrono Trigger (USA).mp4"), cache.file(u1, ArtKind.VIDEO))
        assertTrue(File(media, ".nomedia").isFile)
    }

    @Test fun mediaCopiedInFromEsDeIsUsedAsIs() {
        val media = tmp.newFolder("media")
        File(media, "covers/snes").mkdirs()
        val esde = File(media, "covers/snes/Super Metroid (Japan, USA).jpg").apply { writeBytes(png) }
        val cache = AssetCache(media)
        cache.setLibrary(listOf(game(1, u1, "Super Metroid (Japan, USA).sfc")))
        assertEquals(esde, cache.file(u1, ArtKind.BOX_2D))
        assertTrue(cache.has(u1, ArtKind.BOX_2D))
    }

    @Test fun registeringAGameMakesItWritableBeforeTheLibraryCatchesUp() = runBlocking {
        val cache = AssetCache(tmp.newFolder("media"))
        assertNull(cache.target(u1, ArtKind.BOX_2D, "png"))
        cache.register(game(1, u1, "Golden Sun (USA).gba", platform = "gba"))
        val stored = cache.store(u1, ArtKind.BOX_2D, "png") { it.writeBytes(png) }
        assertEquals("covers/gba/Golden Sun (USA).png", stored.relativeTo(tmp.root.resolve("media")).path)
    }

    @Test fun theMediaFolderIsReadOnEveryAccess() = runBlocking {
        var root = tmp.newFolder("internal")
        val cache = AssetCache({ root })
        cache.setLibrary(listOf(game(1, u1, "A.sfc")))
        cache.store(u1, ArtKind.BOX_2D, "png") { it.writeBytes(png) }
        val old = root
        root = tmp.newFolder("sdcard")
        assertNull(cache.file(u1, ArtKind.BOX_2D))
        val report = cache.relocateFrom(old)
        assertEquals(1, report.moved)
        assertEquals(File(root, "covers/snes/A.png"), cache.file(u1, ArtKind.BOX_2D))
        assertFalse(File(old, "covers/snes/A.png").exists())
    }

    // endregion

    // region Migration from the private cache

    private fun legacyCache(): Pair<File, File> {
        val legacy = tmp.newFolder("assets")
        File(legacy, "$u1").mkdirs()
        File(legacy, "$u1/box2d.png").writeBytes(png)
        File(legacy, "$u1/video.mp4").writeBytes(png)
        File(legacy, "$u1/box2d.png.part").writeBytes(png) // stray temp file
        File(legacy, "$u2").mkdirs()
        File(legacy, "$u2/hero.jpg").writeBytes(png)
        return legacy to tmp.newFolder("media")
    }

    @Test fun oldArtStaysVisibleUntilMovedThenMovesOnce() = runBlocking {
        val (legacy, media) = legacyCache()
        val cache = AssetCache(media, legacyRoot = legacy)
        // Before the library is loaded nothing moves, and nothing is lost.
        assertEquals(2, cache.migrateLegacy().remaining)
        cache.setLibrary(listOf(game(1, u1, "Chrono Trigger (USA).sfc")))
        assertEquals(File(legacy, "$u1/box2d.png"), cache.file(u1, ArtKind.BOX_2D))

        val report = cache.migrateLegacy()
        assertEquals(2, report.moved)
        assertEquals(1, report.remaining) // u2 isn't in the library: left for prune
        assertEquals(File(media, "covers/snes/Chrono Trigger (USA).png"), cache.file(u1, ArtKind.BOX_2D))
        assertEquals(File(media, "snaps/snes/Chrono Trigger (USA).mp4"), cache.file(u1, ArtKind.VIDEO))
        assertFalse(File(legacy, u1).exists())
        // Running it again is a no-op.
        assertEquals(0, cache.migrateLegacy().moved)
        // Prune drops the leftover folder of the game that's gone.
        assertTrue(cache.prune(setOf(u1)) > 0)
        assertFalse(legacy.exists() && File(legacy, u2).exists())
    }

    @Test fun existingPortableArtWinsOverTheOldCopy() = runBlocking {
        val (legacy, media) = legacyCache()
        File(media, "covers/snes").mkdirs()
        val mine = File(media, "covers/snes/Chrono Trigger (USA).png").apply { writeText("hand-picked") }
        val cache = AssetCache(media, legacyRoot = legacy)
        cache.setLibrary(listOf(game(1, u1, "Chrono Trigger (USA).sfc")))
        val report = cache.migrateLegacy()
        assertEquals(1, report.skipped)
        assertEquals("hand-picked", mine.readText())
        assertNotEquals(0, report.moved)
    }

    // endregion
}
