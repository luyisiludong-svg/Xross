package dev.xross.launcher.theme.shader

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Between the GL thread and the UI (2b.4a): the background's [status], and [notices] the launcher
 * shows as toasts (a compile error, a shader too slow for this GPU, one that crashed Xross last
 * time). Each program's failure is announced once per process, not on every category switch.
 *
 * Construct it once per process, before any renderer runs: it runs [ShaderCanary.recover].
 */
class ShaderHealth(val canary: ShaderCanary) {
    private val _status = MutableStateFlow<ShaderStatus>(ShaderStatus.Ready(ShaderLibrary.WAVE_KEY, ShaderPreset.WAVE.label))
    val status: StateFlow<ShaderStatus> = _status.asStateFlow()

    private val _notices = Channel<String>(Channel.BUFFERED)
    /** Toast texts; one collector (the view model). */
    val notices: Flow<String> = _notices.receiveAsFlow()

    private val announced = HashSet<String>()

    /** Keys a crash left armed at the last exit; they're blocked now. */
    val recovered: List<String> = canary.recover()

    init {
        if (recovered.isNotEmpty()) {
            announced += recovered
            _notices.trySend("A theme shader stopped Xross last time · showing the wave instead")
        }
    }

    /** From the GL thread, whenever the wanted program or its state changes. */
    fun report(status: ShaderStatus) {
        _status.value = status
        if (status is ShaderStatus.Failed && markAnnounced(status.key)) _notices.trySend(status.message)
    }

    @Synchronized
    private fun markAnnounced(key: String): Boolean = announced.add(key)
}
