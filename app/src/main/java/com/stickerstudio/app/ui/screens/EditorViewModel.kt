package com.stickerstudio.app.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.DraftEntity
import com.stickerstudio.app.data.PackRuleException
import com.stickerstudio.app.sticker.BackgroundRemover
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.sticker.Colors
import com.stickerstudio.app.sticker.EraseStroke
import com.stickerstudio.app.sticker.Layer
import com.stickerstudio.app.sticker.LayerKind
import com.stickerstudio.app.sticker.PenStroke
import com.stickerstudio.app.sticker.ProjectJson
import com.stickerstudio.app.sticker.ShapeKind
import com.stickerstudio.app.sticker.StickerEncoder
import com.stickerstudio.app.sticker.StickerProject
import com.stickerstudio.app.sticker.StickerRenderer
import com.stickerstudio.app.sticker.CropRect
import com.stickerstudio.app.ui.StickerImport
import com.stickerstudio.app.ui.components.CropPreset
import com.stickerstudio.app.ui.components.resolve
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tool { CROP, IMAGE, BACKGROUND, ERASE, TEXT, SHAPES, DRAW, EFFECTS }

/** One-shot messages shown as a snackbar / dialog. [res] is a string resource id. */
data class EditorMessage(val res: Int, val nonce: Long = System.nanoTime())

data class EditorUi(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val project: StickerProject = StickerProject(),
    val source: Bitmap? = null,
    val preview: Bitmap? = null,
    val tool: Tool = Tool.IMAGE,
    val selectedLayerId: String? = null,   // null = the base image
    val eraseRestore: Boolean = false,
    val brush: Float = 28f,
    val penColor: Int = -65536,
    val penWidth: Float = 10f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val busy: Boolean = false,
    val message: EditorMessage? = null,
    val finished: Boolean = false,
    val dirty: Boolean = false,
    val cropPreset: CropPreset = CropPreset.ORIGINAL,
    val cropAspect: Float? = null,
    val editingExisting: Boolean = false,
)

private class Snapshot(val project: StickerProject, val source: Bitmap?)

class EditorViewModel(
    app: Application,
    private val sourceName: String,
    private val initialPackId: String,
    private val stickerId: String,
    private val draftId: String,
) : AndroidViewModel(app) {

    private val container = app.container
    private val repo = container.repository

    private val _ui = MutableStateFlow(EditorUi(editingExisting = stickerId.isNotEmpty()))
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var gestureStart: Snapshot? = null
    private var renderJob: Job? = null
    private var renderVersion = 0

    /** File name of the working image on disk (null = text/drawing only sticker or not yet saved). */
    private var currentSourceFile: String? = null
    private var loadedBitmap: Bitmap? = null
    private var activeDraftId: String? = draftId.ifEmpty { null }

    val targetPackId: String get() = initialPackId

    /** Emojis of the sticker being edited (null for a new sticker). */
    var savedEmojis: String? = null
        private set

    init {
        viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------------ loading

    private suspend fun load() {
        var project = StickerProject()
        var fileName: String? = sourceName.ifEmpty { null }
        var emojisFromSticker: String? = null

        if (stickerId.isNotEmpty()) {
            val sticker = repo.findSticker(stickerId)
            if (sticker == null) {
                fail()
                return
            }
            fileName = sticker.sourceFile
            project = ProjectJson.decodeProject(sticker.projectJson) ?: StickerProject()
            emojisFromSticker = sticker.emojis
        } else if (draftId.isNotEmpty()) {
            val draft = repo.findDraft(draftId)
            if (draft == null) {
                fail()
                return
            }
            fileName = draft.sourceFile
            project = ProjectJson.decodeProject(draft.projectJson) ?: StickerProject()
        }

        var bitmap: Bitmap? = null
        if (fileName != null) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapLoader.decodeFile(repo.paths.source(fileName))
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                fail()
                return
            }
        }
        currentSourceFile = fileName
        loadedBitmap = bitmap
        savedEmojis = emojisFromSticker
        _ui.update { it.copy(loading = false, project = project, source = bitmap) }
        render()
    }

    private fun fail() {
        _ui.update { it.copy(loading = false, loadFailed = true) }
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val state = _ui.value
        val version = ++renderVersion
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                StickerRenderer.render(state.project, state.source, PREVIEW_SIZE)
            }
            if (version == renderVersion) {
                _ui.update { it.copy(preview = bitmap) }
            }
        }
    }

    // ------------------------------------------------------------------ history

    private fun snapshot() = Snapshot(_ui.value.project, _ui.value.source)

    private fun pushUndo(s: Snapshot) {
        undoStack.addLast(s)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        _ui.update { it.copy(canUndo = true, canRedo = false, dirty = true) }
    }

    /** Applies a single, undoable change. */
    fun apply(change: (StickerProject) -> StickerProject) {
        pushUndo(snapshot())
        _ui.update { it.copy(project = change(it.project)) }
        render()
    }

    /** Starts a continuous change (drag, slider). Pair with [endGesture]. */
    fun beginGesture() {
        if (gestureStart == null) gestureStart = snapshot()
    }

    /** Applies part of a continuous change without creating a history entry. */
    fun live(change: (StickerProject) -> StickerProject) {
        beginGesture()
        _ui.update { it.copy(project = change(it.project), dirty = true) }
        render()
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start.project != _ui.value.project || start.source !== _ui.value.source) pushUndo(start)
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
    }

    private fun restore(s: Snapshot) {
        _ui.update {
            it.copy(
                project = s.project, source = s.source,
                canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(), dirty = true,
                selectedLayerId = it.selectedLayerId?.takeIf { id -> s.project.layers.any { l -> l.id == id } },
            )
        }
        render()
    }

    // ------------------------------------------------------------------ tool state

    fun selectTool(tool: Tool) = _ui.update { it.copy(tool = tool) }
    fun selectLayer(id: String?) = _ui.update { it.copy(selectedLayerId = id) }
    fun setEraseRestore(restore: Boolean) = _ui.update { it.copy(eraseRestore = restore) }
    fun setBrush(size: Float) = _ui.update { it.copy(brush = size) }
    fun setPenColor(color: Int) = _ui.update { it.copy(penColor = color) }
    fun setPenWidth(width: Float) = _ui.update { it.copy(penWidth = width) }
    fun consumeMessage() = _ui.update { it.copy(message = null) }

    // ------------------------------------------------------------------ crop

    /** Picks an aspect preset: resets the frame to the largest centred frame of that shape. */
    fun setCropPreset(preset: CropPreset) {
        val src = _ui.value.source ?: return
        val (rect, aspect) = preset.resolve(src.width, src.height, _ui.value.project.crop)
        apply { it.copy(crop = rect) }
        _ui.update { it.copy(cropPreset = preset, cropAspect = aspect) }
    }

    /** Live drag of the crop frame (pair with beginGesture / endGesture). */
    fun setCrop(rect: CropRect) = live { it.copy(crop = rect) }

    // ------------------------------------------------------------------ base image / layers transform

    /** Pinch / drag / rotate on the selected layer (or on the base image). Units: sticker space. */
    fun transform(panX: Float, panY: Float, zoom: Float, rotationDeg: Float) {
        val s = _ui.value
        // Layers are moved only while the text / shapes tab is open; elsewhere the image moves.
        val selected = if (s.tool == Tool.TEXT || s.tool == Tool.SHAPES) s.selectedLayerId else null
        live { p ->
            if (selected == null) {
                val x = p.xform
                p.copy(
                    xform = x.copy(
                        scale = (x.scale * zoom).coerceIn(0.1f, 8f),
                        dx = x.dx + panX, dy = x.dy + panY,
                        rotation = x.rotation + rotationDeg,
                    )
                )
            } else {
                p.copy(
                    layers = p.layers.map { l ->
                        if (l.id != selected) l else l.copy(
                            x = l.x + panX, y = l.y + panY,
                            scale = (l.scale * zoom).coerceIn(0.1f, 8f),
                            rotation = l.rotation + rotationDeg,
                        )
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------ eraser / pen strokes

    fun beginStroke(x: Float, y: Float) {
        val s = _ui.value
        beginGesture()
        if (s.tool == Tool.ERASE) {
            live { p -> p.copy(erase = p.erase + EraseStroke(s.eraseRestore, s.brush, listOf(x, y))) }
        } else {
            live { p -> p.copy(pen = p.pen + PenStroke(s.penColor, s.penWidth, listOf(x, y))) }
        }
    }

    fun extendStroke(x: Float, y: Float) {
        val tool = _ui.value.tool
        live { p ->
            if (tool == Tool.ERASE) {
                val last = p.erase.lastOrNull() ?: return@live p
                p.copy(erase = p.erase.dropLast(1) + last.copy(points = last.points + listOf(x, y)))
            } else {
                val last = p.pen.lastOrNull() ?: return@live p
                p.copy(pen = p.pen.dropLast(1) + last.copy(points = last.points + listOf(x, y)))
            }
        }
    }

    fun endStroke() = endGesture()

    fun clearErase() = apply { it.copy(erase = emptyList()) }
    fun clearPen() = apply { it.copy(pen = emptyList()) }

    // ------------------------------------------------------------------ layers

    private fun newId() = UUID.randomUUID().toString()

    fun addText(text: String) {
        if (text.isBlank()) return
        val layer = Layer(id = newId(), kind = LayerKind.TEXT, text = text, strokeWidth = 5f, strokeColor = Colors.BLACK)
        apply { it.copy(layers = it.layers + layer) }
        selectLayer(layer.id)
        selectTool(Tool.TEXT)
    }

    fun addEmoji(emoji: String) {
        if (emoji.isBlank()) return
        val layer = Layer(id = newId(), kind = LayerKind.TEXT, text = emoji, size = 150f, bold = false)
        apply { it.copy(layers = it.layers + layer) }
        selectLayer(layer.id)
    }

    fun addShape(kind: ShapeKind) {
        val layer = Layer(id = newId(), kind = LayerKind.SHAPE, shape = kind, size = 180f, color = -1754827)
        apply { it.copy(layers = it.layers + layer) }
        selectLayer(layer.id)
    }

    /** Discrete edit of one layer (colour, text, font...). */
    fun updateLayer(id: String, change: (Layer) -> Layer) =
        apply { p -> p.copy(layers = p.layers.map { if (it.id == id) change(it) else it }) }

    /** Continuous edit of one layer (sliders). */
    fun liveLayer(id: String, change: (Layer) -> Layer) =
        live { p -> p.copy(layers = p.layers.map { if (it.id == id) change(it) else it }) }

    fun deleteLayer(id: String) {
        apply { p -> p.copy(layers = p.layers.filterNot { it.id == id }) }
        if (_ui.value.selectedLayerId == id) selectLayer(null)
    }

    // ------------------------------------------------------------------ background removal

    fun removeBackgroundByColor(tolerance: Int) = runOnSource(R.string.bg_done_color) { BackgroundRemover.removeBorderColor(it, tolerance) }

    fun removeBackgroundPerson() = runOnSource(R.string.bg_done_person) { BackgroundRemover.removePerson(it) }

    private fun runOnSource(doneMessage: Int, work: suspend (Bitmap) -> Bitmap) {
        val src = _ui.value.source ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { work(src) }
                pushUndo(snapshot())
                _ui.update { it.copy(source = result, busy = false, message = EditorMessage(doneMessage)) }
                render()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, message = EditorMessage(R.string.bg_failed)) }
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(busy = false, message = EditorMessage(R.string.err_memory)) }
            }
        }
    }

    // ------------------------------------------------------------------ saving

    /** Renders at 512x512, encodes and stores the sticker. Calls [onDone] when finished. */
    fun save(packId: String, emojis: String) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val state = _ui.value
                val webp = withContext(Dispatchers.Default) {
                    val bitmap = StickerRenderer.render(state.project, state.source)
                    val bytes = StickerEncoder.encodeStatic(bitmap)
                    bitmap.recycle()
                    bytes
                }
                if (webp == null) {
                    _ui.update { it.copy(busy = false, message = EditorMessage(R.string.err_too_big)) }
                    return@launch
                }
                val sourceFile = withContext(Dispatchers.IO) { persistSource(state.source) }
                val json = ProjectJson.encode(state.project)
                if (stickerId.isNotEmpty()) {
                    repo.replaceSticker(stickerId, webp, false, emojis, sourceFile, json)
                } else {
                    repo.addSticker(packId, webp, false, emojis, sourceFile, json)
                }
                activeDraftId?.let { repo.deleteDraft(it, deleteSource = false) }
                _ui.update { it.copy(busy = false, finished = true) }
            } catch (e: PackRuleException) {
                _ui.update { it.copy(busy = false, message = EditorMessage(StickerImport.messageFor(e))) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, message = EditorMessage(R.string.err_save_failed)) }
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(busy = false, message = EditorMessage(R.string.err_memory)) }
            }
        }
    }

    /** Keeps the current work so the user can continue later. */
    fun saveDraft() {
        val state = _ui.value
        viewModelScope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                val file = withContext(Dispatchers.IO) { persistSource(state.source) }
                val id = activeDraftId ?: UUID.randomUUID().toString().also { activeDraftId = it }
                repo.saveDraft(
                    DraftEntity(
                        id = id,
                        targetPackId = initialPackId.ifEmpty { null },
                        sourceFile = file,
                        projectJson = ProjectJson.encode(state.project),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            _ui.update { it.copy(finished = true) }
        }
    }

    fun discardDraftIfAny() {
        val id = activeDraftId ?: return
        viewModelScope.launch { repo.deleteDraft(id, deleteSource = true) }
    }

    /** Writes the working image to app storage unless it is still the file we loaded. */
    private fun persistSource(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val existing = currentSourceFile
        if (existing != null && bitmap === loadedBitmap) return existing
        val name = BitmapLoader.savePng(repo.paths.sources, bitmap)
        currentSourceFile = name
        loadedBitmap = bitmap
        return name
    }

    override fun onCleared() {
        renderJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val PREVIEW_SIZE = 384
        private const val MAX_HISTORY = 20
    }
}
