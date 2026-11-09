package dev.wave.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.ui.graphics.vector.ImageVector
import dev.wave.launcher.state.GlyphKey

fun GlyphKey.vector(): ImageVector = when (this) {
    GlyphKey.SETTINGS -> Icons.Outlined.Settings
    GlyphKey.GAMES -> Icons.Outlined.SportsEsports
    GlyphKey.APPS -> Icons.Outlined.Apps
    GlyphKey.FOLDER -> Icons.Outlined.Folder
    GlyphKey.GAME -> Icons.Outlined.VideogameAsset
    GlyphKey.PLATFORM -> Icons.Outlined.Gamepad
    GlyphKey.STAR -> Icons.Outlined.Star
    GlyphKey.HISTORY -> Icons.Outlined.History
    GlyphKey.CONTROLLER -> Icons.Outlined.SportsEsports
    GlyphKey.STORAGE -> Icons.Outlined.SdStorage
    GlyphKey.REFRESH -> Icons.Outlined.Refresh
    GlyphKey.ADD -> Icons.Outlined.Add
    GlyphKey.EMULATOR -> Icons.Outlined.Memory
    GlyphKey.DISPLAY -> Icons.Outlined.Tv
    GlyphKey.INFO -> Icons.Outlined.Info
    GlyphKey.ANDROID -> Icons.Outlined.Android
    GlyphKey.TOGGLE_ON -> Icons.Outlined.ToggleOn
    GlyphKey.TOGGLE_OFF -> Icons.Outlined.ToggleOff
    GlyphKey.WAVE -> Icons.Outlined.Waves
    GlyphKey.WARNING -> Icons.Outlined.Warning
    GlyphKey.HOME -> Icons.Outlined.Home
}
