package dev.xross.launcher.state

import android.net.Uri
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.scrape.GameMediaEditor
import dev.xross.launcher.scrape.MediaOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * The Metadata & Media editor (Options ▸ Metadata & Media) and its media picker. Pure layout and
 * cursor logic live in [MetadataEditor] and [MediaPickerState]; this class turns input into
 * database and media changes, and keeps the open page in step with the database (a rescrape, a
 * new title or a picked image shows at once).
 *
 * Offers are asked for only when a picker opens: opening the editor and paging with L1/R1 cost
 * no quota.
 */
class MetadataEditorController(
    private val scope: CoroutineScope,
    private val db: XrossDatabase,
    private val media: GameMediaEditor,
    private val sounds: UiSounds,
    private val artVersion: StateFlow<Long>,
    /** Settings ▸ Scrape videos: the Video snap slot shows only then. */
    private val videos: () -> Boolean,
    /** Opens a picked document (Use a file…). */
    private val open: (Uri) -> InputStream?,
    /** Where a picked document is copied before it is checked and moved into place. */
    private val scratch: () -> File,
    private val host: Host,
) {
    /** What the editor needs from the menu around it. */
    interface Host {
        fun toast(message: String)
        fun fail(message: String)
        fun refuse()
        fun emit(effect: UiEffect)
        /** Runs a game command the menu already has (Edit title…, Find match…, Scrape again…). */
        fun run(command: Command)
        /** Game ids of the list the editor pages through. */
        fun siblings(): List<Long>
        /** The list cursor follows paging, so Back lands on the game that was on screen. */
        fun follow(gameId: Long)
        /** Back: the game's Options menu, where the editor was opened from. */
        fun reopenOptions(gameId: Long)
    }

    private val _state = MutableStateFlow<MetadataEditorState?>(null)
    val state: StateFlow<MetadataEditorState?> = _state.asStateFlow()

    private var watchJob: Job? = null
    private var offersJob: Job? = null
    private var pickJob: Job? = null

    /** Opens the editor on [gameId]; [direction] is ±1 when paging from a neighbour. */
    fun open(gameId: Long, direction: Int = 0) {
        val ids = host.siblings()
        watchJob?.cancel()
        watchJob = scope.launch {
            // Files copied into the media folders by hand show up as soon as the editor opens.
            runCatching { media.refreshStatus(gameId) }
            combine(db.games().observe(gameId), db.metadata().observe(gameId), artVersion) { game, meta, art -> Triple(game, meta, art) }
                .collectLatest { (game, meta, art) ->
                    if (game == null) return@collectLatest close(sound = false)
                    val previous = _state.value
                    val next = MetadataEditor.build(
                        game, PlatformRegistry.byId(game.platformId), meta, art, ids, videos(),
                        previous = previous, pageDirection = direction,
                    )
                    if (previous == null || previous.gameId != gameId) sounds.play(if (direction == 0) UiSound.ENTER else UiSound.MOVE)
                    _state.value = next
                }
        }
    }

    /** Input while the editor is open; false when it isn't. */
    fun onAction(action: NavAction): Boolean {
        val editor = _state.value ?: return false
        editor.picker?.let {
            pickerAction(editor, it, action)
            return true
        }
        when (action) {
            NavAction.UP, NavAction.DOWN -> move(editor, editor.move(if (action == NavAction.UP) -1 else 1))
            NavAction.CONFIRM, NavAction.START, NavAction.RIGHT -> activate(editor)
            NavAction.BACK, NavAction.LEFT -> {
                close()
                host.reopenOptions(editor.gameId)
            }
            NavAction.OPTIONS -> close()
            NavAction.PREV_CATEGORY -> page(editor, -1)
            NavAction.NEXT_CATEGORY -> page(editor, +1)
            NavAction.MEDIA -> Unit
        }
        return true
    }

    private fun move(before: MetadataEditorState, after: MetadataEditorState) {
        if (after == before) return
        sounds.play(UiSound.MOVE)
        _state.value = after
    }

    private fun page(editor: MetadataEditorState, delta: Int) {
        val next = MetadataEditor.neighbor(editor, delta) ?: return host.refuse()
        host.follow(next)
        _state.update { it?.copy(pageDirection = delta) }
        open(next, direction = delta)
    }

    private fun activate(editor: MetadataEditorState) {
        when (val row = editor.focused ?: return host.refuse()) {
            is EditorRow.Media -> openPicker(editor, row)
            is EditorRow.Action -> {
                sounds.play(UiSound.ENTER)
                when (row.action) {
                    EditorAction.EDIT_TITLE -> host.run(Command.EditTitle(editor.gameId))
                    EditorAction.EDIT_DESCRIPTION -> scope.launch {
                        val meta = withContext(Dispatchers.IO) { db.metadata().get(editor.gameId) }
                        host.emit(UiEffect.PromptText(TextPrompt.GameDescription(editor.gameId, meta?.shownSynopsis.orEmpty())))
                    }
                    EditorAction.FIND_MATCH -> host.run(Command.FindMatch(editor.gameId))
                    EditorAction.SCRAPE_AGAIN -> host.run(Command.Rescrape(editor.gameId))
                    EditorAction.RESET_METADATA -> host.run(Command.ResetMetadata(editor.gameId))
                    EditorAction.RESET_MEDIA -> scope.launch {
                        media.resetMedia(editor.gameId)
                        host.toast("Scraping chooses this game's media again")
                    }
                }
            }
        }
    }

    /** The description typed in the prompt; empty goes back to the scraped one. */
    fun onDescriptionEntered(gameId: Long, text: String) {
        val value = text.trim()
        scope.launch {
            withContext(Dispatchers.IO) { db.metadata().edit(gameId) { it.copy(synopsisOverride = value.ifEmpty { null }) } }
            host.toast(if (value.isEmpty()) "Scraped description restored" else "Description changed")
        }
    }

    // region Media picker

    private fun openPicker(editor: MetadataEditorState, row: EditorRow.Media) {
        sounds.play(UiSound.OPTION)
        val gameId = editor.gameId
        val kind = row.kind
        _state.update { it?.copy(picker = MediaPickerState(kind, row.label, hasFile = row.hasFile)) }
        offersJob?.cancel()
        offersJob = scope.launch {
            val result = media.offers(gameId, kind)
            _state.update { s ->
                val picker = s?.picker?.takeIf { s.gameId == gameId && it.kind == kind && it.loading } ?: return@update s
                s.copy(picker = picker.withOffers(result))
            }
        }
    }

    private fun pickerAction(editor: MetadataEditorState, picker: MediaPickerState, action: NavAction) {
        if (picker.busy != null) return
        when (action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> {
                val moved = picker.move(action)
                if (moved != picker) {
                    sounds.play(UiSound.MOVE)
                    _state.update { it?.copy(picker = moved) }
                }
            }
            NavAction.CONFIRM, NavAction.START -> choose(editor, picker)
            NavAction.BACK, NavAction.OPTIONS -> closePicker()
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY -> host.refuse()
            NavAction.MEDIA -> Unit
        }
    }

    private fun choose(editor: MetadataEditorState, picker: MediaPickerState) {
        when (val choice = picker.focused ?: return host.refuse()) {
            is PickerChoice.Offer -> pick(editor.gameId, picker, choice.offer)
            PickerChoice.UseFile -> {
                sounds.play(UiSound.ENTER)
                host.emit(UiEffect.PickMediaFile(video = picker.kind.isVideo))
            }
            PickerChoice.Remove -> {
                sounds.play(UiSound.ENTER)
                scope.launch {
                    media.remove(editor.gameId, picker.kind)
                    closePicker(sound = false)
                    host.toast("${picker.title} removed · scraping won't bring it back")
                }
            }
        }
    }

    private fun pick(gameId: Long, picker: MediaPickerState, offer: MediaOffer) {
        sounds.play(UiSound.ENTER)
        _state.update { it?.copy(picker = picker.copy(busy = "Downloading…")) }
        pickJob = scope.launch { finish(picker, media.pick(gameId, offer)) }
    }

    /** The document picked for "Use a file…"; null when the picker was cancelled. */
    fun onFilePicked(uri: Uri?) {
        uri ?: return
        val editor = _state.value ?: return
        val picker = editor.picker ?: return
        _state.update { it?.copy(picker = picker.copy(busy = "Copying…")) }
        pickJob = scope.launch {
            val copy = try {
                withContext(Dispatchers.IO) { copyIn(uri, picker.kind) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch finish(picker, "Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
            }
            finish(picker, copy?.let { media.useFile(editor.gameId, picker.kind, it) } ?: "That file is over the ${picker.kind.maxBytes shr 20} MB limit for this slot")
        }
    }

    /** Copies [uri] to a scratch file, or null when it is larger than the slot allows. */
    private fun copyIn(uri: Uri, kind: ArtKind): File? {
        val dir = scratch().apply { mkdirs() }
        val out = File.createTempFile("pick-", ".part", dir)
        val input = open(uri) ?: run {
            out.delete()
            throw java.io.IOException("it can't be opened")
        }
        var total = 0L
        input.use { src ->
            out.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > kind.maxBytes) {
                        out.delete()
                        return null
                    }
                    dst.write(buffer, 0, n)
                }
            }
        }
        return out
    }

    private fun finish(picker: MediaPickerState, problem: String?) {
        if (problem == null) {
            sounds.play(UiSound.OK)
            closePicker(sound = false)
            host.toast("${picker.title} chosen")
        } else {
            _state.update { s -> s?.copy(picker = s.picker?.copy(busy = null)) }
            host.fail(problem)
        }
    }

    private fun closePicker(sound: Boolean = true) {
        if (_state.value?.picker == null) return
        offersJob?.cancel()
        if (sound) sounds.play(UiSound.BACK)
        _state.update { it?.copy(picker = null) }
    }

    // endregion

    /** A touch on the editor or its picker. */
    fun onEvent(event: XmbEvent.MetadataEditor) {
        val editor = _state.value ?: return
        when (event) {
            is XmbEvent.MetadataEditor.RowTap -> if (editor.picker == null) {
                if (event.index == editor.index) activate(editor) else move(editor, editor.moveTo(event.index))
            }
            is XmbEvent.MetadataEditor.PickerTap -> editor.picker?.let { picker ->
                if (picker.busy != null) return
                if (event.index == picker.index) choose(editor, picker) else {
                    sounds.play(UiSound.MOVE)
                    _state.update { it?.copy(picker = picker.moveTo(event.index)) }
                }
            }
            XmbEvent.MetadataEditor.ClosePicker -> closePicker()
            XmbEvent.MetadataEditor.Close -> close()
        }
    }

    /** The preview of [offer], downloaded once per session. */
    suspend fun thumbnail(offer: MediaOffer): File? = media.thumbnail(offer)

    fun close(sound: Boolean = true) {
        watchJob?.cancel()
        offersJob?.cancel()
        if (_state.value == null) return
        if (sound) sounds.play(UiSound.BACK)
        _state.value = null
        scope.launch { media.clearSession() }
    }
}
