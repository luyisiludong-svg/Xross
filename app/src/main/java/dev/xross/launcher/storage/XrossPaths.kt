package dev.xross.launcher.storage

import android.os.Environment
import java.io.File

/** Well-known folders Xross reads from shared storage (All files access covers them). */
object XrossPaths {
    /** Xross's own folder on the primary volume, next to the user's ROMs: `/storage/emulated/0/Xross`. */
    @Suppress("DEPRECATION") // Direct paths are the point: Xross runs with All files access.
    fun root(): File = File(Environment.getExternalStorageDirectory(), "Xross")

    /** Where No-Intro / Redump DATs go unless the user picks another folder. */
    fun defaultDatFolder(): File = File(root(), "dats")

    fun datFolder(setting: String): File = if (setting.isBlank()) defaultDatFolder() else File(setting)
}
