package com.stickerstudio.app.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stickerstudio.app.R
import com.stickerstudio.app.sticker.CropMath
import com.stickerstudio.app.ui.components.CropEditor
import com.stickerstudio.app.ui.components.CropPresetChips
import com.stickerstudio.app.ui.components.checkerboard
import com.stickerstudio.app.ui.components.resolve
import com.stickerstudio.app.ui.vmFactory
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Editing screen for GIF / video stickers. The picture really moves here: a looping preview
 * of the sticker as it will be exported (crop, fit and caption included).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedScreen(
    packId: String,
    source: String,
    isVideo: Boolean,
    onClose: () -> Unit,
    onStatic: (String) -> Unit,
    stickerId: String = "",
) {
    val context = LocalContext.current
    val vm: AnimatedViewModel = viewModel(
        factory = vmFactory { AnimatedViewModel(context.applicationContext as Application, packId, source, isVideo, stickerId) },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    // Progress notification while converting (Android 13+ asks once; the conversion works either way).
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotifications by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pick) }
    LaunchedEffect(ui.saved) { if (ui.saved) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_animated)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.sourceName == null) {
                Text(stringResource(R.string.anim_limits), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { picker.launch(arrayOf("image/gif", "video/*")) }, enabled = !ui.loading) {
                    Text(stringResource(R.string.anim_pick))
                }
                if (ui.loading) CircularProgressIndicator()
            } else {
                // ---- live preview or crop frame
                Box(
                    Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .align(Alignment.CenterHorizontally)
                        .clip(MaterialTheme.shapes.large)
                        .checkerboard(),
                    contentAlignment = Alignment.Center,
                ) {
                    val cropFrame = ui.cropFrame
                    if (ui.cropMode && cropFrame != null) {
                        CropEditor(
                            bitmap = cropFrame,
                            crop = ui.settings.crop,
                            aspect = ui.cropAspect,
                            onChange = vm::setCrop,
                            onGestureStart = {},
                            onGestureEnd = {},
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    } else {
                        LoopingPreview(ui.frames, ui.frameMs, ui.frameDurations)
                        if (ui.frames.isEmpty()) CircularProgressIndicator()
                    }
                }

                if (ui.cropMode) {
                    Text(stringResource(R.string.crop_hint, ui.sourceW, ui.sourceH), style = MaterialTheme.typography.bodySmall)
                    CropPresetChips(ui.cropPreset, { preset ->
                        val (rect, aspect) = preset.resolve(ui.sourceW, ui.sourceH, ui.settings.crop)
                        vm.setCropPreset(preset, rect, aspect)
                    })
                    Button(onClick = { vm.setCropMode(false) }) { Text(stringResource(R.string.crop_done)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.setCropMode(true) }, enabled = !ui.converting) {
                            Icon(Icons.Filled.Crop, null)
                            Text(stringResource(R.string.tool_crop), Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = { vm.makeStatic(onStatic) }, enabled = !ui.converting) {
                            Text(stringResource(R.string.anim_static))
                        }
                    }

                    FrameTimeline(ui, vm)

                    val maxDur = minOf(ui.durationMs.coerceAtLeast(500L), 10_000L).toFloat()
                    if (ui.isVideo) {
                        val maxStart = (ui.durationMs - 500L).coerceAtLeast(0L).toFloat()
                        if (maxStart > 0f) {
                            Text(stringResource(R.string.anim_start, ui.settings.startMs / 1000f))
                            Slider(
                                value = ui.settings.startMs.toFloat().coerceAtMost(maxStart),
                                onValueChange = { v -> vm.change { it.copy(startMs = v.toLong()) } },
                                valueRange = 0f..maxStart,
                                enabled = !ui.converting,
                            )
                        }
                    }
                    if (maxDur > 500f) {
                        Text(stringResource(R.string.anim_duration, ui.settings.durationMs / 1000f))
                        Slider(
                            value = ui.settings.durationMs.toFloat().coerceIn(500f, maxDur),
                            onValueChange = { v -> vm.change { it.copy(durationMs = v.toLong()) } },
                            valueRange = 500f..maxDur,
                            enabled = !ui.converting,
                        )
                    }
                    Text(stringResource(R.string.anim_fps, ui.settings.fps))
                    Slider(
                        value = ui.settings.fps.toFloat(),
                        onValueChange = { v -> vm.change { it.copy(fps = v.roundToInt().coerceIn(8, 20)) } },
                        valueRange = 8f..20f,
                        steps = 11,
                        enabled = !ui.converting,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.fill), Modifier.weight(1f))
                        Switch(checked = ui.settings.cover, onCheckedChange = { c -> vm.change { it.copy(cover = c) } }, enabled = !ui.converting)
                    }
                    OutlinedTextField(
                        value = ui.settings.caption,
                        onValueChange = { t -> vm.change { it.copy(caption = t.take(40)) } },
                        label = { Text(stringResource(R.string.anim_caption)) },
                        singleLine = true,
                        enabled = !ui.converting,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (ui.converting) {
                        var startedAt by remember { androidx.compose.runtime.mutableStateOf(0L) }
                        LaunchedEffect(Unit) { startedAt = android.os.SystemClock.elapsedRealtime() }
                        val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                        val etaSeconds = if (ui.progress >= 0.08f && startedAt > 0L) ((elapsed / ui.progress) * (1f - ui.progress) / 1000f).toInt() + 1 else null
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(stringResource(R.string.convert_working), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text("${(ui.progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                com.stickerstudio.app.ui.components.BrandProgress(ui.progress, height = 10.dp)
                                if (etaSeconds != null) {
                                    Text(
                                        stringResource(R.string.convert_eta, etaSeconds),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                    } else if (ui.resultBytes == null) {
                        Button(onClick = { askNotifications(); vm.convert() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text(stringResource(R.string.anim_convert), Modifier.padding(start = 8.dp))
                        }
                    }

                    ui.resultBytes?.let { bytes ->
                        Text(stringResource(R.string.anim_done, bytes.size / 1024), color = MaterialTheme.colorScheme.primary)
                        Button(onClick = vm::saveToPack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_to_pack)) }
                    }
                }
            }

            ui.message?.let { m ->
                if (ui.resultBytes == null) Text(stringResource(m), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Plays [frames] in a loop. Each frame lasts [durations] (or [frameMs] when that list is empty / does not
 * match). The frame is chosen from the display clock (not from a chain of delays), so timing stays even
 * and does not drift when a frame is slow.
 */
@Composable
private fun LoopingPreview(frames: List<android.graphics.Bitmap>, frameMs: Long, durations: List<Long> = emptyList()) {
    val images = remember(frames) { frames.map { it.asImageBitmap() } }
    var index by remember(frames) { mutableIntStateOf(0) }
    LaunchedEffect(frames, frameMs, durations) {
        if (frames.size > 1) {
            val lengths = if (durations.size == frames.size) durations.map { it.coerceAtLeast(1L) }
            else List(frames.size) { frameMs.coerceAtLeast(1L) }
            val total = lengths.sum()
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    var t = ((now - start) / 1_000_000L) % total
                    var i = 0
                    while (i < lengths.size - 1 && t >= lengths[i]) { t -= lengths[i]; i++ }
                    if (i != index) index = i
                }
            }
        }
    }
    val frame = images.getOrNull(index % maxOf(images.size, 1))
    if (frame != null) {
        Image(
            bitmap = frame,
            contentDescription = stringResource(R.string.preview),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
    }
}

/** Frame strip: select a frame, then delete / duplicate / move it, or change how long it lasts. */
@Composable
private fun FrameTimeline(ui: AnimatedUi, vm: AnimatedViewModel) {
    val frames = ui.settings.frames
    if (frames == null) {
        OutlinedButton(onClick = vm::startFrameEdit, enabled = !ui.converting) { Text(stringResource(R.string.frames_edit)) }
        return
    }
    val selected = ui.selectedFrame.coerceIn(0, frames.size - 1)
    val total = frames.sumOf { it.durationMs }
    Text(
        stringResource(R.string.frames_info, frames.size, total / 1000f),
        style = MaterialTheme.typography.titleSmall,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(frames) { i, f ->
            val thumb = ui.thumbs[f.sourceMs]
            val active = i == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(if (active) 3.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { vm.selectFrame(i) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).checkerboard(), contentAlignment = Alignment.Center) {
                    if (thumb != null) {
                        Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                Text("${i + 1} · ${f.durationMs}ms", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { vm.moveFrame(-1) }, enabled = !ui.converting && selected > 0) { Text(stringResource(R.string.frame_earlier)) }
        TextButton(onClick = { vm.moveFrame(1) }, enabled = !ui.converting && selected < frames.size - 1) { Text(stringResource(R.string.frame_later)) }
        TextButton(onClick = vm::duplicateFrame, enabled = !ui.converting) { Text(stringResource(R.string.frame_duplicate)) }
        TextButton(onClick = vm::deleteFrame, enabled = !ui.converting && frames.size > 2) { Text(stringResource(R.string.frame_delete)) }
    }
    val current = frames[selected]
    Text(stringResource(R.string.frame_length, current.durationMs), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = current.durationMs.toFloat().coerceIn(20f, 1000f),
        onValueChange = { v -> vm.setFrameDuration(v.roundToInt(), commit = false) },
        onValueChangeFinished = vm::commitFrameDuration,
        valueRange = 20f..1000f,
        enabled = !ui.converting,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(listOf(0.5f, 1.5f, 2f)) { _, factor ->
            FilterChip(
                selected = false,
                onClick = { vm.setSpeed(factor) },
                enabled = !ui.converting,
                label = { Text(stringResource(R.string.frames_speed, factor)) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = vm::undoFrames, enabled = !ui.converting && ui.canUndoFrames) { Text(stringResource(R.string.undo)) }
        TextButton(onClick = vm::redoFrames, enabled = !ui.converting && ui.canRedoFrames) { Text(stringResource(R.string.redo)) }
        TextButton(onClick = vm::resetFrames, enabled = !ui.converting) { Text(stringResource(R.string.frames_reset)) }
    }
}
