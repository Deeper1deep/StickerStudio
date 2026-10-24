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
import com.stickerstudio.app.sticker.ProjectJson
import com.stickerstudio.app.sticker.StickerRenderer
import com.stickerstudio.app.ui.StickerImport
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val settings: AnimatedSettings = AnimatedSettings(),
    val preview: Bitmap? = null,
    val converting: Boolean = false,
    val progress: Float = 0f,
    val resultBytes: ByteArray? = null,
    val resultFps: Int = 0,
    val message: Int? = null,
    val messageArg: Int = 0,
    val saved: Boolean = false,
    val loading: Boolean = false,
)

class AnimatedViewModel(app: Application, private val packId: String) : AndroidViewModel(app) {

    private val repo = app.container.repository
    private val _ui = MutableStateFlow(AnimatedUi())
    val ui: StateFlow<AnimatedUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var previewJob: Job? = null

    private fun file(name: String): File = repo.paths.source(name)

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
            val duration = withContext(Dispatchers.IO) {
                try {
                    AnimatedConverter.open(file(name), isVideo).use { it.durationMs }
                } catch (e: Exception) {
                    -1L
                }
            }
            if (duration < 0) {
                file(name).delete()
                _ui.update { it.copy(loading = false, message = R.string.err_load_failed) }
                return@launch
            }
            val limit = minOf(duration.coerceAtLeast(500L), 10_000L)
            _ui.update {
                it.copy(
                    loading = false, sourceName = name, isVideo = isVideo, durationMs = duration,
                    settings = AnimatedSettings(startMs = 0, durationMs = minOf(3000L, limit)),
                    resultBytes = null,
                )
            }
            refreshPreview()
        }
    }

    fun change(block: (AnimatedSettings) -> AnimatedSettings) {
        _ui.update { it.copy(settings = block(it.settings), resultBytes = null, message = null) }
        refreshPreview()
    }

    private fun refreshPreview() {
        val s = _ui.value
        val name = s.sourceName ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                try {
                    AnimatedConverter.open(file(name), s.isVideo).use { src ->
                        val raw = src.frameAt(s.settings.startMs) ?: return@use null
                        val out = StickerRenderer.render(AnimatedConverter.projectFor(src, s.settings), raw)
                        raw.recycle()
                        out
                    }
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            _ui.update { it.copy(preview = bmp) }
        }
    }

    fun convert() {
        val s = _ui.value
        val name = s.sourceName ?: return
        if (job?.isActive == true) return
        _ui.update { it.copy(converting = true, progress = 0f, resultBytes = null, message = null) }
        job = viewModelScope.launch {
            try {
                val out = AnimatedConverter.convert(file(name), s.isVideo, s.settings) { p ->
                    _ui.update { it.copy(progress = p) }
                }
                when (out) {
                    is ConvertOutcome.Success ->
                        _ui.update { it.copy(converting = false, resultBytes = out.bytes, resultFps = out.fps, message = R.string.anim_done, messageArg = out.bytes.size / 1024) }
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
        super.onCleared()
    }
}
