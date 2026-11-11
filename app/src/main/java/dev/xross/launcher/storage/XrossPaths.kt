package dev.xross.launcher.storage

import android.os.Environment
import java.io.File

/** Well-known folders Xross reads from shared storage (All files access covers them). */
object XrossPaths {
    const val ROOT_NAME = "Xross"

    /**
     * Xross's own folder on the primary volume, next to the user's ROMs: `/storage/emulated/0/Xross`.
     * The folder actually in use (which may be on an SD card, or app-private before storage access
     * is granted) comes from [XrossEnvironment].
     */
    @Suppress("DEPRECATION") // Direct paths are the point: Xross runs with All files access.
    fun root(): File = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
}
