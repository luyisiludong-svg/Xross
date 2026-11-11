package dev.xross.launcher.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi

/** Whether Xross may use shared storage directly (File paths, not the Storage Access Framework). */
object StorageAccess {
    /** Reading ROM folders: All files access on Android 11+, the legacy read permission before that. */
    fun canRead(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Writing the portable Xross folder: All files access on Android 11+, the legacy write
     * permission on Android 9 and 10 (where `requestLegacyExternalStorage` keeps File access).
     */
    fun canWrite(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Treated as not granted when the platform can't answer (no package for the calling uid). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun isExternalStorageManager(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
}
