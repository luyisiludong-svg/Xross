package dev.xross.launcher.launch

import android.content.ComponentName
import android.content.Intent
import java.io.File

/** ARMSX3's two launch forms ([EmulatorRegistry], id `armsx3`). */
object Armsx3 {
    const val ACTIVITY = "com.armsx2.Main"

    /** Where Android keeps app data on the main volume (ES-DE's `%EXTERNALDATA%`). */
    const val EXTERNAL_DATA = "/storage/emulated/0"

    /** A `.ps3` file is a serial or two; anything bigger isn't one. */
    private const val MAX_BYTES = 4 * 1024

    /** PS3 serials: four letters and five digits (BLUS30443, NPEB00874). */
    private val SERIAL = Regex("""^[A-Za-z]{4}\d{5}$""")

    /** The serial a `.ps3` file names: its first non-blank line, trimmed; null when there is none. */
    fun serial(text: String): String? = text.lineSequence().map { it.trim().trimStart('\uFEFF') }.firstOrNull { it.isNotEmpty() }

    /** True when [serial] looks like a PS3 title id, which is what ARMSX3's game folders are named. */
    fun looksLikeSerial(serial: String): Boolean = SERIAL.matches(serial)

    /** For [EmulatorDef.check]: a `.ps3` file must name a serial. */
    fun check(file: File): String? {
        if (!file.extension.equals("ps3", ignoreCase = true)) return null
        val serial = runCatching { file.takeIf { it.length() <= MAX_BYTES }?.readText()?.let(::serial) }.getOrNull()
        return if (serial != null && looksLikeSerial(serial)) null else "${file.name} doesn't hold a PS3 serial (like BLUS30443)"
    }

    /** The folder of the game [serial] installed in ARMSX3 [packageName]. */
    fun installedGamePath(packageName: String, serial: String): String =
        "$EXTERNAL_DATA/Android/data/$packageName/files/config/dev_hdd0/game/$serial"

    /** The intent for [req]: the installed-game form for a `.ps3` file, the disc-image form for anything else. */
    fun intent(req: LaunchRequest): Intent {
        val file = File(req.romPath)
        val base = Intent().setComponent(ComponentName(req.packageName, ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!file.extension.equals("ps3", ignoreCase = true)) return base.putExtra("path", req.romPath)
        val serial = runCatching { file.takeIf { it.length() <= MAX_BYTES }?.readText()?.let(::serial) }.getOrNull()
            ?: file.nameWithoutExtension
        return base.setAction(Intent.ACTION_VIEW).putExtra("path", installedGamePath(req.packageName, serial))
    }
}
