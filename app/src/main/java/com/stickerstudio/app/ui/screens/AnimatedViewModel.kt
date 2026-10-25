package com.stickerstudio.app.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackRuleException
import com.stickerstudio.app.sticker.AnimatedConverter
import com.stickerstudio.app.sticker.AnimatedSettings
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.sticker.ConvertError
import com.stickerstudio.app.sticker.ConvertOutcome
import com.stickerstudio.app.sticker.CropRect
import com.stickerstudio.app.sticker.ProjectJson
import com.stickerstudio.app.sticker.StickerRenderer
import com.stickerstudio.app.ui.StickerImport
import com.stickerstudio.app.ui.components.CropPreset
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnimatedUi(
    val sourceName: String? = null,
    val isVideo: Boolean = false,
    val durationMs: Long = 0,
    val sourceW: Int = 0,
    val sourceH: Int = 0,
    val settings: AnimatedSettings = AnimatedSettings(),
    /** Frames of the live (looping) preview, already cropped / fitted / captioned. */
    val frames: List<Bitmap> = emptyList(),
    val frameMs: Long = 100,
    /** Un-cropped frame shown under the crop frame while cropping. */
    val cropFrame: Bitmap? = null,
    val cropMode: Boolean = false,
    val cropPreset: CropPreset = CropPreset.ORIGINAL,
    val cropAspect: Float? = null,
    val converting: Boolean = false,
    val progress: Float = 0f,
    val resultBytes: ByteArray? = null,
    val message: Int? = null,
    val saved: Boolean = false,
    val loading: Boolean = false,
)

class AnimatedViewModel(
    app: Application,
    private val packId: String,
    initialSource: String,
    initialIsVideo: Boolean,
) : AndroidViewModel(app) {

    private val repo = app.container.repository
    private val _ui = MutableStateFlow(AnimatedUi())
    val ui: StateFlow<AnimatedUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var previewJob: Job? = null

    private fun file(name: String): File = repo.paths.source(name)

    init {
        if (initialSource.isNotEmpty()) viewModelScope.launch { load(initialSource, initialIsVideo) }
    }

    /** Copies a picked GIF / video into app storage and opens it. */
    fun pick(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, message = null) }
            val ctx = getApplication<Application>()
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            val isVideo = mime.startsWith("video/")
            if (!isVideo && mime != "image/gif") {
                _ui.update { it.copy(loading = false, message = R.string.anim_err_type) }
                return@launch
            }
            val name = withContext(Dispatchers.IO) {
                try {
                    BitmapLoader.copyRaw(ctx, uri, repo.paths.sources, if (isVideo) "mp4" else "gif")
                } catch (e: Exception) {
                    null
                }
            }
            if (name == null) {
                _ui.update { it.copy(loading = false, message = R.string.err_load_failed) }
                return@launch
            }
            load(name, isVideo)
        }
    }

    private suspend fun load(name: String, isVideo: Boolean) {
        _ui.update { it.copy(loading = true, message = null) }
        val info = withContext(Dispatchers.IO) {
            try {
                AnimatedConverter.open(file(name), isVideo).use { Triple(it.durationMs, it.width, it.height) }
            } catch (e: Exception) {
                null
            }
        }
        if (info == null) {
            file(name).delete()
            _ui.update { it.copy(loading = false, message = R.string.err_load_failed) }
            return
        }
        val limit = minOf(info.first.coerceAtLeast(500L), 10_000L)
        _ui.update {
            it.copy(
                loading = false, sourceName = name, isVideo = isVideo, durationMs = info.first,
                sourceW = info.second, sourceH = info.third,
                settings = AnimatedSettings(startMs = 0, durationMs = minOf(3000L, limit)),
                resultBytes = null,
            )
        }
        refreshPreview()
    }

    fun change(block: (AnimatedSettings) -> AnimatedSettings) {
        _ui.update { it.copy(settings = block(it.settings), resultBytes = null, message = null) }
        refreshPreview()
    }

    // ------------------------------------------------------------------ crop

    fun setCropMode(on: Boolean) {
        _ui.update { it.copy(cropMode = on) }
        if (on) loadCropFrame()
    }

    fun setCropPreset(preset: CropPreset, rect: CropRect, aspect: Float?) {
        _ui.update { it.copy(cropPreset = preset, cropAspect = aspect, settings = it.settings.copy(crop = rect), resultBytes = null) }
        refreshPreview()
    }

    fun setCrop(rect: CropRect) {
        _ui.update { it.copy(settings = it.settings.copy(crop = rect), resultBytes = null) }
        refreshPreview()
    }

    private fun loadCropFrame() {
        val s = _ui.value
        val name = s.sourceName ?: return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                try {
                    AnimatedConverter.open(file(name), s.isVideo).use { src ->
                        val raw = src.frameAt(s.settings.startMs) ?: return@use null
                        val side = max(raw.width, raw.height)
                        if (side <= 720) raw else {
                            val k = 720f / side
                            val scaled = Bitmap.createScaledBitmap(raw, (raw.width * k).toInt().coerceAtLeast(1), (raw.height * k).toInt().coerceAtLeast(1), true)
                            raw.recycle()
                            scaled
                        }
                    }
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            _ui.update { it.copy(cropFrame = bmp) }
        }
    }

    // ------------------------------------------------------------------ live preview

    /** Re-renders a short looping preview with the current crop / fit / caption settings. */
    private fun refreshPreview() {
        val s = _ui.value
        val name = s.sourceName ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(200)   // wait until the user stops dragging a slider
            val frameDuration = (1000f / s.settings.fps).toLong().coerceAtLeast(40L)
            val total = minOf(s.settings.durationMs, max(s.durationMs - s.settings.startMs, frameDuration * 2))
            val step = max(frameDuration, total / MAX_PREVIEW_FRAMES)
            val count = (total / step).toInt().coerceIn(2, MAX_PREVIEW_FRAMES)
            val out = ArrayList<Bitmap>()
            try {
                withContext(Dispatchers.Default) {
                    AnimatedConverter.open(file(name), s.isVideo).use { src ->
                        val project = AnimatedConverter.projectFor(src, s.settings)
                        for (i in 0 until count) {
                            val raw = src.frameAt(s.settings.startMs + i * step) ?: continue
                            out += StickerRenderer.render(project, raw, PREVIEW_SIZE)
                            raw.recycle()
                            // Show the first picture right away, then the whole loop once it is ready
                            // (a half-built loop would play as a short, jerky cycle).
                            if (i == 0 || i == count - 1) {
                                val snapshot = out.toList()
                                _ui.update { it.copy(frames = snapshot, frameMs = step) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = R.string.err_load_failed) }
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(message = R.string.err_memory) }
            }
        }
    }

    // ------------------------------------------------------------------ static alternative

    /** Saves the frame at the start point as a picture and hands it to the static editor. */
    fun makeStatic(onReady: (String) -> Unit) {
        val s = _ui.value
        val name = s.sourceName ?: return
        viewModelScope.launch {
            val picture = withContext(Dispatchers.Default) {
                try {
                    AnimatedConverter.open(file(name), s.isVideo).use { src ->
                        val raw = src.frameAt(s.settings.startMs) ?: return@use null
                        val saved = BitmapLoader.savePng(repo.paths.sources, raw)
                        raw.recycle()
                        saved
                    }
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            if (picture == null) _ui.update { it.copy(message = R.string.err_load_failed) } else onReady(picture)
        }
    }

    // ------------------------------------------------------------------ convert / save

    fun convert() {
        val s = _ui.value
        val name = s.sourceName ?: return
        if (job?.isActive == true) return
        _ui.update { it.copy(converting = true, progress = 0f, resultBytes = null, message = null, cropMode = false) }
        job = viewModelScope.launch {
            try {
                val out = AnimatedConverter.convert(file(name), s.isVideo, s.settings) { p ->
                    _ui.update { it.copy(progress = p) }
                }
                when (out) {
                    is ConvertOutcome.Success ->
                        _ui.update { it.copy(converting = false, resultBytes = out.bytes, message = R.string.anim_done) }
                    is ConvertOutcome.Failure -> _ui.update {
                        it.copy(
                            converting = false,
                            message = when (out.error) {
                                ConvertError.TOO_LARGE -> R.string.err_too_big
                                ConvertError.MEMORY -> R.string.err_memory
                                ConvertError.INVALID_RESULT -> R.string.anim_err_invalid
                                ConvertError.DECODE -> R.string.err_load_failed
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(converting = false, progress = 0f, message = R.string.anim_cancelled) }
                throw e
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun saveToPack() {
        val s = _ui.value
        val bytes = s.resultBytes ?: return
        viewModelScope.launch {
            try {
                repo.addSticker(packId, bytes, true, "😀", s.sourceName, ProjectJson.encode(s.settings))
                _ui.update { it.copy(saved = true) }
            } catch (e: PackRuleException) {
                _ui.update { it.copy(message = StickerImport.messageFor(e)) }
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        previewJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_PREVIEW_FRAMES = 90
        private const val PREVIEW_SIZE = 256
    }
}
