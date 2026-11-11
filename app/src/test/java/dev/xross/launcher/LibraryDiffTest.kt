package dev.xross.launcher

import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.storage.ScannedRom
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDiffTest {
    private fun game(id: Long, path: String, size: Long = 1) =
        GameEntity(id, path, "snes", "t", path.substringAfterLast('/'), size, 0)

    private fun rom(path: String, size: Long = 1) =
        ScannedRom(path, "snes", "t", path.substringAfterLast('/'), size, 0)

    @Test fun insertsUpdatesAndDeletes() {
        val existing = listOf(game(1, "/sd/ROMs/snes/a.sfc"), game(2, "/sd/ROMs/snes/b.sfc"))
        val scanned = listOf(rom("/sd/ROMs/snes/a.sfc", size = 9), rom("/sd/ROMs/snes/c.sfc"))
        val diff = GameRepository.computeDiff(existing, scanned, listOf("/sd/ROMs"), listOf("/sd/ROMs"))
        assertEquals(listOf("/sd/ROMs/snes/c.sfc"), diff.toInsert.map { it.path })
        assertEquals(listOf(1L), diff.toUpdate.map { it.first })
        assertEquals(listOf(2L), diff.toDelete)
    }

    @Test fun keepsGamesOnUnmountedVolume() {
        val existing = listOf(game(1, "/storage/AAAA-BBBB/ROMs/snes/a.sfc"))
        val diff = GameRepository.computeDiff(
            existing,
            scanned = emptyList(),
            configuredRoots = listOf("/storage/AAAA-BBBB/ROMs"),
            scannedRoots = emptyList(),
        )
        assertEquals(emptyList<Long>(), diff.toDelete)
    }

    @Test fun deletesGamesFromRemovedFolders() {
        val existing = listOf(game(1, "/old/snes/a.sfc"))
        val diff = GameRepository.computeDiff(existing, emptyList(), listOf("/new"), listOf("/new"))
        assertEquals(listOf(1L), diff.toDelete)
    }

    @Test fun siblingPrefixIsNotTreatedAsInsideRoot() {
        val existing = listOf(game(1, "/sd/ROMs2/snes/a.sfc"))
        val diff = GameRepository.computeDiff(existing, emptyList(), listOf("/sd/ROMs", "/sd/ROMs2"), listOf("/sd/ROMs"))
        assertEquals(emptyList<Long>(), diff.toDelete)
    }
}
