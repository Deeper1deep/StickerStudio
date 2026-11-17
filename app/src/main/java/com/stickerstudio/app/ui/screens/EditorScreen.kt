package com.stickerstudio.app.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.stickerstudio.app.sticker.CropMath
import com.stickerstudio.app.ui.components.CropEditor
import com.stickerstudio.app.ui.components.CropPresetChips
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.data.PackRepository
import com.stickerstudio.app.core.WhatsAppLimits
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.stickerstudio.app.sticker.Adjust
import com.stickerstudio.app.sticker.Layer
import com.stickerstudio.app.sticker.LayerKind
import com.stickerstudio.app.sticker.MaskKind
import com.stickerstudio.app.sticker.ShapeKind
import com.stickerstudio.app.sticker.StickerRenderer
import com.stickerstudio.app.ui.components.ColorPalette
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.components.TextInputDialog
import com.stickerstudio.app.ui.components.checkerboard
import com.stickerstudio.app.ui.vmFactory
import kotlinx.coroutines.launch

private const val EMOJI_CHOICES =
    "😀😂😍😎🥳😭😡🤔👍👏🙏💪🔥✨❤️💔🎉🌟🌈🍕☕⚽🎵🐱🐶🦄🌹💯✅❌⭐🎁🚀😴🤗😱🙄"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    sourceName: String,
    packId: String,
    stickerId: String,
    draftId: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel(
        factory = vmFactory {
            EditorViewModel(context.applicationContext as Application, sourceName, packId, stickerId, draftId)
        },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showExit by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    LaunchedEffect(ui.finished) { if (ui.finished) onClose() }

    val message = ui.message
    if (message != null) {
        val text = stringResource(message.res)
        LaunchedEffect(message.nonce) {
            snackbar.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    BackHandler { if (ui.dirty) showExit = true else onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (ui.editingExisting) R.string.edit_sticker else R.string.new_sticker)) },
                navigationIcon = {
                    IconButton(onClick = { if (ui.dirty) showExit = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = ui.canUndo) {
                        Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo))
                    }
                    IconButton(onClick = vm::redo, enabled = ui.canRedo) {
                        Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.redo))
                    }
                    IconButton(onClick = { showSave = true }, enabled = !ui.loading && !ui.busy) {
                        Icon(Icons.Filled.Check, stringResource(R.string.save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.loadFailed -> Text(
                    stringResource(R.string.err_load_failed),
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> Column(Modifier.fillMaxSize()) {
                    PreviewArea(vm, ui)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ToolPanel(vm, ui)
                        }
                    }
                    ToolTabs(ui.tool, vm::selectTool)
                }
            }
            if (ui.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { showExit = false; vm.saveDraft() }) { Text(stringResource(R.string.save_draft)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExit = false; vm.discardDraftIfAny(); onClose() }) {
                        Text(stringResource(R.string.discard))
                    }
                    TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.keep_editing)) }
                }
            },
        )
    }

    if (showSave) {
        SaveDialog(
            fixedPackId = packId.ifEmpty { null },
            editing = ui.editingExisting,
            initialEmojis = vm.savedEmojis ?: "😀",
            onSave = { target, emojis -> showSave = false; vm.save(target, emojis) },
            onDismiss = { showSave = false },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Preview with gestures
// ---------------------------------------------------------------------------------------

@Composable
private fun PreviewArea(vm: EditorViewModel, ui: EditorUi) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                .checkerboard()
                .pointerInput(ui.tool) {
                    val toSticker = StickerSpace.SIZE / size.width.toFloat()
                    if (ui.tool == Tool.CROP) {
                        return@pointerInput
                    }
                    if (ui.tool == Tool.ERASE || ui.tool == Tool.DRAW) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            vm.beginStroke(down.position.x * toSticker, down.position.y * toSticker)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChanged()) {
                                    vm.extendStroke(change.position.x * toSticker, change.position.y * toSticker)
                                    change.consume()
                                }
                            }
                            vm.endStroke()
                        }
                    } else {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            vm.beginGesture()
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val rotation = event.calculateRotation()
                                if (zoom != 1f || pan != Offset.Zero || rotation != 0f) {
                                    vm.transform(pan.x * toSticker, pan.y * toSticker, zoom, rotation)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            vm.endGesture()
                        }
                    }
                },
        ) {
            val preview = ui.preview
            val cropSource = ui.source
            if (ui.tool == Tool.CROP && cropSource != null) {
                CropEditor(
                    bitmap = cropSource,
                    crop = ui.project.crop,
                    aspect = ui.cropAspect,
                    onChange = vm::setCrop,
                    onGestureStart = vm::beginGesture,
                    onGestureEnd = vm::endGesture,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = stringResource(R.string.preview),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private object StickerSpace {
    const val SIZE = 512f
}

// ---------------------------------------------------------------------------------------
// Tabs and tool panels
// ---------------------------------------------------------------------------------------

@Composable
private fun ToolTabs(selected: Tool, onSelect: (Tool) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(Tool.entries.toList()) { tool ->
                val (icon, label) = toolVisual(tool)
                val active = tool == selected
                Column(
                    Modifier
                        .widthIn(min = 68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tool) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            icon, null,
                            tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun toolVisual(tool: Tool): Pair<ImageVector, Int> = when (tool) {
    Tool.CROP -> Icons.Filled.Crop to R.string.tool_crop
    Tool.IMAGE -> Icons.Filled.Image to R.string.tool_image
    Tool.BACKGROUND -> Icons.Filled.AutoFixHigh to R.string.tool_background
    Tool.ERASE -> Icons.Filled.CleaningServices to R.string.tool_erase
    Tool.TEXT -> Icons.Filled.TextFields to R.string.tool_text
    Tool.SHAPES -> Icons.Filled.Category to R.string.tool_shapes
    Tool.DRAW -> Icons.Filled.Brush to R.string.tool_draw
    Tool.EFFECTS -> Icons.Filled.AutoAwesome to R.string.tool_effects
    Tool.LAYERS -> Icons.Filled.Layers to R.string.tool_layers
}

@Composable
private fun ToolPanel(vm: EditorViewModel, ui: EditorUi) {
    when (ui.tool) {
        Tool.CROP -> CropPanel(vm, ui)
        Tool.IMAGE -> ImagePanel(vm, ui)
        Tool.BACKGROUND -> BackgroundPanel(vm, ui)
        Tool.ERASE -> ErasePanel(vm, ui)
        Tool.TEXT -> TextPanel(vm, ui)
        Tool.SHAPES -> ShapesPanel(vm, ui)
        Tool.DRAW -> DrawPanel(vm, ui)
        Tool.EFFECTS -> EffectsPanel(vm, ui)
        Tool.LAYERS -> LayersPanel(vm, ui)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CropPanel(vm: EditorViewModel, ui: EditorUi) {
    val src = ui.source
    if (src == null) {
        Hint(stringResource(R.string.hint_no_image))
        return
    }
    Hint(stringResource(R.string.crop_hint, src.width, src.height))
    CropPresetChips(ui.cropPreset, { vm.setCropPreset(it) })
    OutlinedButton(onClick = { vm.setCropPreset(com.stickerstudio.app.ui.components.CropPreset.ORIGINAL) }) {
        Text(stringResource(R.string.crop_reset))
    }
}

@Composable
private fun ImagePanel(vm: EditorViewModel, ui: EditorUi) {
    val x = ui.project.xform
    if (ui.source == null) {
        Hint(stringResource(R.string.hint_no_image))
    } else {
        Hint(stringResource(R.string.hint_gestures))
    }
    LabeledSlider(stringResource(R.string.size), x.scale, 0.1f..4f, { v -> vm.live { it.copy(xform = it.xform.copy(scale = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.rotation), x.rotation.coerceIn(-180f, 180f), -180f..180f, { v -> vm.live { it.copy(xform = it.xform.copy(rotation = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.opacity), ui.project.opacity, 0.1f..1f, { v -> vm.live { it.copy(opacity = v) } }, vm::endGesture)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.apply { it.copy(xform = it.xform.copy(rotation = it.xform.rotation + 90f)) } }) { Text(stringResource(R.string.rotate_90)) }
        OutlinedButton(onClick = { vm.apply { it.copy(xform = it.xform.copy(flipX = !it.xform.flipX)) } }) { Text(stringResource(R.string.flip_h)) }
        OutlinedButton(onClick = { vm.apply { it.copy(xform = it.xform.copy(flipY = !it.xform.flipY)) } }) { Text(stringResource(R.string.flip_v)) }
    }
    val src = ui.source
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.apply { it.copy(xform = it.xform.copy(scale = 1f, dx = 0f, dy = 0f)) } }) { Text(stringResource(R.string.fit)) }
        if (src != null) {
            OutlinedButton(onClick = {
                vm.apply { it.copy(xform = it.xform.copy(scale = CropMath.pixels(ui.project.crop, src.width, src.height).let { px -> StickerRenderer.coverScale(px[2], px[3]) }, dx = 0f, dy = 0f)) }
            }) { Text(stringResource(R.string.fill)) }
        }
    }
    Text(stringResource(R.string.crop_shape), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MaskKind.entries.forEach { kind ->
            FilterChip(
                selected = ui.project.mask == kind,
                onClick = { vm.apply { it.copy(mask = kind) } },
                label = {
                    Text(
                        stringResource(
                            when (kind) {
                                MaskKind.NONE -> R.string.mask_none
                                MaskKind.CIRCLE -> R.string.mask_circle
                                MaskKind.ROUNDED -> R.string.mask_rounded
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun BackgroundPanel(vm: EditorViewModel, ui: EditorUi) {
    var tolerance by remember { mutableStateOf(40f) }
    if (ui.source == null) {
        Hint(stringResource(R.string.hint_no_image))
        return
    }
    Text(stringResource(R.string.bg_person_title), style = MaterialTheme.typography.titleSmall)
    Hint(stringResource(R.string.bg_person_hint))
    Button(onClick = vm::removeBackgroundPerson, enabled = !ui.busy) { Text(stringResource(R.string.bg_person_button)) }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.bg_color_title), style = MaterialTheme.typography.titleSmall)
    Hint(stringResource(R.string.bg_color_hint))
    LabeledSlider(stringResource(R.string.tolerance), tolerance, 5f..120f, { tolerance = it }, {})
    Button(onClick = { vm.removeBackgroundByColor(tolerance.toInt()) }, enabled = !ui.busy) {
        Text(stringResource(R.string.bg_color_button))
    }
    Spacer(Modifier.height(8.dp))
    Hint(stringResource(R.string.bg_manual_hint))
}

@Composable
private fun ErasePanel(vm: EditorViewModel, ui: EditorUi) {
    Hint(stringResource(R.string.hint_erase))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !ui.eraseRestore, onClick = { vm.setEraseRestore(false) }, label = { Text(stringResource(R.string.eraser)) })
        FilterChip(selected = ui.eraseRestore, onClick = { vm.setEraseRestore(true) }, label = { Text(stringResource(R.string.restore)) })
    }
    LabeledSlider(stringResource(R.string.brush_size), ui.brush, 6f..90f, vm::setBrush, {})
    OutlinedButton(onClick = vm::clearErase, enabled = ui.project.erase.isNotEmpty()) { Text(stringResource(R.string.clear_erasing)) }
}

@Composable
private fun LayerChips(layers: List<Layer>, selectedId: String?, onSelect: (String) -> Unit) {
    if (layers.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(layers, key = { it.id }) { layer ->
            FilterChip(
                selected = layer.id == selectedId,
                onClick = { onSelect(layer.id) },
                label = {
                    Text(
                        if (layer.kind == LayerKind.TEXT) layer.text.replace('\n', ' ').take(12) else shapeGlyph(layer.shape),
                    )
                },
            )
        }
    }
}

private fun shapeGlyph(shape: ShapeKind): String = when (shape) {
    ShapeKind.CIRCLE -> "●"
    ShapeKind.RECT -> "■"
    ShapeKind.STAR -> "★"
    ShapeKind.HEART -> "♥"
}

@Composable
private fun TextPanel(vm: EditorViewModel, ui: EditorUi) {
    var showAdd by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }

    val textLayers = ui.project.layers.filter { it.kind == LayerKind.TEXT }
    val selected = textLayers.firstOrNull { it.id == ui.selectedLayerId }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { showAdd = true }) { Text(stringResource(R.string.add_text)) }
        OutlinedButton(onClick = { showEmoji = true }) { Text(stringResource(R.string.add_emoji)) }
    }
    LayerChips(textLayers, ui.selectedLayerId) { vm.selectLayer(it) }

    if (selected == null) {
        Hint(stringResource(R.string.hint_text_select))
    } else {
        val id = selected.id
        Hint(stringResource(R.string.hint_gestures_layer))
        OutlinedButton(onClick = { showEdit = true }) { Text(stringResource(R.string.edit_text)) }
        LabeledSlider(stringResource(R.string.size), selected.size, 20f..260f, { v -> vm.liveLayer(id) { it.copy(size = v) } }, vm::endGesture)
        Text(stringResource(R.string.text_color), style = MaterialTheme.typography.labelLarge)
        ColorPalette(selected.color, { c -> vm.updateLayer(id) { it.copy(color = c) } })
        LabeledSlider(stringResource(R.string.outline_width), selected.strokeWidth, 0f..20f, { v -> vm.liveLayer(id) { it.copy(strokeWidth = v) } }, vm::endGesture)
        if (selected.strokeWidth > 0f) {
            Text(stringResource(R.string.outline_color), style = MaterialTheme.typography.labelLarge)
            ColorPalette(selected.strokeColor, { c -> vm.updateLayer(id) { it.copy(strokeColor = c) } })
        }
        SwitchRow(stringResource(R.string.shadow), selected.shadow) { on -> vm.updateLayer(id) { it.copy(shadow = on) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected.bold, onClick = { vm.updateLayer(id) { it.copy(bold = !it.bold) } }, label = { Text(stringResource(R.string.bold)) })
            FilterChip(selected = selected.italic, onClick = { vm.updateLayer(id) { it.copy(italic = !it.italic) } }, label = { Text(stringResource(R.string.italic)) })
        }
        LabeledSlider(stringResource(R.string.opacity), selected.opacity, 0.1f..1f, { v -> vm.liveLayer(id) { it.copy(opacity = v) } }, vm::endGesture)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val alignNames = listOf(R.string.align_left, R.string.align_center, R.string.align_right)
            alignNames.forEachIndexed { i, name ->
                FilterChip(selected = selected.align == i, onClick = { vm.updateLayer(id) { it.copy(align = i) } }, label = { Text(stringResource(name)) })
            }
        }
        LabeledSlider(stringResource(R.string.letter_spacing), selected.letterSpacing, 0f..0.5f, { v -> vm.liveLayer(id) { it.copy(letterSpacing = v) } }, vm::endGesture)
        LabeledSlider(stringResource(R.string.line_spacing), selected.lineSpacing, 0.8f..2f, { v -> vm.liveLayer(id) { it.copy(lineSpacing = v) } }, vm::endGesture)
        SwitchRow(stringResource(R.string.text_background), selected.bgEnabled) { on -> vm.updateLayer(id) { it.copy(bgEnabled = on) } }
        if (selected.bgEnabled) ColorPalette(selected.bgColor, { c -> vm.updateLayer(id) { it.copy(bgColor = c) } })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf(R.string.font_sans, R.string.font_serif, R.string.font_mono, R.string.font_script)
            items(names.indices.toList()) { i ->
                FilterChip(selected = selected.font == i, onClick = { vm.updateLayer(id) { it.copy(font = i) } }, label = { Text(stringResource(names[i])) })
            }
        }
        OutlinedButton(onClick = { vm.deleteLayer(id) }) { Text(stringResource(R.string.delete_layer)) }
    }

    if (showAdd) {
        TextInputDialog(
            title = stringResource(R.string.add_text),
            label = stringResource(R.string.text_label),
            singleLine = false,
            onConfirm = { vm.addText(it); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
    if (showEdit && selected != null) {
        TextInputDialog(
            title = stringResource(R.string.edit_text),
            label = stringResource(R.string.text_label),
            initial = selected.text,
            singleLine = false,
            onConfirm = { t -> vm.updateLayer(selected.id) { it.copy(text = t) }; showEdit = false },
            onDismiss = { showEdit = false },
        )
    }
    if (showEmoji) {
        EmojiPickerDialog(onPick = { vm.addEmoji(it); showEmoji = false }, onDismiss = { showEmoji = false })
    }
}

@Composable
private fun ShapesPanel(vm: EditorViewModel, ui: EditorUi) {
    val shapes = ui.project.layers.filter { it.kind == LayerKind.SHAPE }
    val selected = shapes.firstOrNull { it.id == ui.selectedLayerId }

    Text(stringResource(R.string.add_shape), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapeKind.entries.forEach { kind ->
            OutlinedButton(onClick = { vm.addShape(kind) }) { Text(shapeGlyph(kind)) }
        }
    }
    LayerChips(shapes, ui.selectedLayerId) { vm.selectLayer(it) }

    if (selected == null) {
        Hint(stringResource(R.string.hint_shape_select))
    } else {
        val id = selected.id
        Hint(stringResource(R.string.hint_gestures_layer))
        LabeledSlider(stringResource(R.string.size), selected.size, 40f..420f, { v -> vm.liveLayer(id) { it.copy(size = v) } }, vm::endGesture)
        Text(stringResource(R.string.fill_color), style = MaterialTheme.typography.labelLarge)
        ColorPalette(selected.color, { c -> vm.updateLayer(id) { it.copy(color = c) } })
        LabeledSlider(stringResource(R.string.outline_width), selected.strokeWidth, 0f..20f, { v -> vm.liveLayer(id) { it.copy(strokeWidth = v) } }, vm::endGesture)
        if (selected.strokeWidth > 0f) {
            ColorPalette(selected.strokeColor, { c -> vm.updateLayer(id) { it.copy(strokeColor = c) } })
        }
        SwitchRow(stringResource(R.string.shadow), selected.shadow) { on -> vm.updateLayer(id) { it.copy(shadow = on) } }
        LabeledSlider(stringResource(R.string.opacity), selected.opacity, 0.1f..1f, { v -> vm.liveLayer(id) { it.copy(opacity = v) } }, vm::endGesture)
        OutlinedButton(onClick = { vm.deleteLayer(id) }) { Text(stringResource(R.string.delete_layer)) }
    }
}

@Composable
private fun DrawPanel(vm: EditorViewModel, ui: EditorUi) {
    Hint(stringResource(R.string.hint_draw))
    Text(stringResource(R.string.pen_color), style = MaterialTheme.typography.labelLarge)
    ColorPalette(ui.penColor, vm::setPenColor)
    LabeledSlider(stringResource(R.string.brush_size), ui.penWidth, 2f..60f, vm::setPenWidth, {})
    OutlinedButton(onClick = vm::clearPen, enabled = ui.project.pen.isNotEmpty()) { Text(stringResource(R.string.clear_drawing)) }
}

@Composable
private fun EffectsPanel(vm: EditorViewModel, ui: EditorUi) {
    val o = ui.project.outline
    SwitchRow(stringResource(R.string.sticker_border), o.enabled) { on -> vm.apply { it.copy(outline = it.outline.copy(enabled = on)) } }
    if (o.enabled) {
        Text(stringResource(R.string.border_color), style = MaterialTheme.typography.labelLarge)
        ColorPalette(o.color, { c -> vm.apply { it.copy(outline = it.outline.copy(color = c)) } })
        LabeledSlider(stringResource(R.string.border_width), o.width, 2f..30f, { v -> vm.live { it.copy(outline = it.outline.copy(width = v)) } }, vm::endGesture)
    }
    SwitchRow(stringResource(R.string.shadow), ui.project.shadow) { on -> vm.apply { it.copy(shadow = on) } }

    val adj = ui.project.adjust
    Text(stringResource(R.string.adjust_title), style = MaterialTheme.typography.titleSmall)
    LabeledSlider(stringResource(R.string.adjust_brightness), adj.brightness, -1f..1f, { v -> vm.live { it.copy(adjust = it.adjust.copy(brightness = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.adjust_contrast), adj.contrast, -1f..1f, { v -> vm.live { it.copy(adjust = it.adjust.copy(contrast = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.adjust_saturation), adj.saturation, -1f..1f, { v -> vm.live { it.copy(adjust = it.adjust.copy(saturation = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.adjust_warmth), adj.warmth, -1f..1f, { v -> vm.live { it.copy(adjust = it.adjust.copy(warmth = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.adjust_exposure), adj.exposure, -1f..1f, { v -> vm.live { it.copy(adjust = it.adjust.copy(exposure = v)) } }, vm::endGesture)
    LabeledSlider(stringResource(R.string.adjust_blur), adj.blur, 0f..1f, { v -> vm.live { it.copy(adjust = it.adjust.copy(blur = v)) } }, vm::endGesture)
    SwitchRow(stringResource(R.string.adjust_grayscale), adj.grayscale) { on -> vm.apply { it.copy(adjust = it.adjust.copy(grayscale = on)) } }
    SwitchRow(stringResource(R.string.adjust_sepia), adj.sepia) { on -> vm.apply { it.copy(adjust = it.adjust.copy(sepia = on)) } }
    OutlinedButton(onClick = { vm.apply { it.copy(adjust = Adjust()) } }, enabled = !adj.isDefault) { Text(stringResource(R.string.adjust_reset)) }
}

@Composable
private fun LayersPanel(vm: EditorViewModel, ui: EditorUi) {
    val layers = ui.project.layers
    var renaming by remember { mutableStateOf<Layer?>(null) }
    if (layers.isEmpty()) {
        Hint(stringResource(R.string.layers_empty))
        return
    }
    Hint(stringResource(R.string.layers_hint))
    // Front-most layer first, like in other design apps.
    layers.asReversed().forEach { layer ->
        val selected = layer.id == ui.selectedLayerId
        var menu by remember(layer.id) { mutableStateOf(false) }
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleLayerVisible(layer.id) }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        stringResource(if (layer.visible) R.string.layer_hide else R.string.layer_show),
                    )
                }
                IconButton(onClick = { vm.toggleLayerLocked(layer.id) }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        stringResource(if (layer.locked) R.string.layer_unlock else R.string.layer_lock),
                    )
                }
                Text(
                    layerTitle(layer),
                    modifier = Modifier.weight(1f).clickable { vm.selectLayer(layer.id) }.padding(horizontal = 6.dp, vertical = 10.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { vm.moveLayer(layer.id, +1) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.layer_forward))
                }
                IconButton(onClick = { vm.moveLayer(layer.id, -1) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.layer_backward))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename_layer)) },
                            onClick = { menu = false; renaming = layer },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.duplicate_layer)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menu = false; vm.duplicateLayer(layer.id) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_layer)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; vm.deleteLayer(layer.id) },
                        )
                    }
                }
            }
        }
    }
    renaming?.let { l ->
        TextInputDialog(
            title = stringResource(R.string.rename_layer),
            label = stringResource(R.string.layer_name),
            initial = l.name,
            onConfirm = { vm.renameLayer(l.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

private fun layerTitle(l: Layer): String = when {
    l.name.isNotBlank() -> l.name
    l.kind == LayerKind.TEXT -> l.text.replace('\n', ' ').take(24).ifBlank { "T" }
    else -> shapeGlyph(l.shape)
}

// ---------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------

@Composable
private fun EmojiPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    val emojis = remember { PackRepository.splitEmojis(EMOJI_CHOICES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_emoji)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(emojis) { e -> TextButton(onClick = { onPick(e) }) { Text(e, style = MaterialTheme.typography.headlineSmall) } }
                }
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it.take(8) },
                    label = { Text(stringResource(R.string.emoji_custom)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(custom) }, enabled = custom.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Asks where to store the sticker (unless the editor was opened from a pack) and which
 * emojis WhatsApp should associate with it.
 */
@Composable
fun SaveDialog(
    fixedPackId: String?,
    editing: Boolean,
    initialEmojis: String,
    onSave: (packId: String, emojis: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val packs by container.repository.observePacks().collectAsState(initial = emptyList())
    val counts by container.repository.observeCounts().collectAsState(initial = emptyList())
    val defaultPublisher by container.settings.defaultPublisher.collectAsState(initial = "")

    var emojis by remember { mutableStateOf(initialEmojis) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var newPack by remember { mutableStateOf(false) }

    val countOf = { p: PackEntity -> counts.firstOrNull { it.packId == p.id }?.count ?: 0 }
    val usable = packs.filter { countOf(it) < WhatsAppLimits.MAX_STICKERS && (countOf(it) == 0 || !it.animated) }
    val needPack = fixedPackId == null && !editing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_sticker)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = emojis, onValueChange = { emojis = it.take(12) },
                    label = { Text(stringResource(R.string.sticker_emojis)) },
                    supportingText = { Text(stringResource(R.string.sticker_emojis_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (needPack) {
                    Text(stringResource(R.string.save_to_pack), style = MaterialTheme.typography.labelLarge)
                    usable.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = chosen == p.id, role = Role.RadioButton, onClick = { chosen = p.id }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == p.id, onClick = null)
                            Text("  ${p.name} (${countOf(p)}/${WhatsAppLimits.MAX_STICKERS})")
                        }
                    }
                    OutlinedButton(onClick = { newPack = true }) { Text(stringResource(R.string.new_pack)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = emojis.isNotBlank() && (!needPack || chosen != null),
                onClick = { onSave(fixedPackId ?: chosen ?: "", emojis) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (newPack) {
        PackInfoDialog(
            title = stringResource(R.string.new_pack),
            initialName = "",
            initialPublisher = defaultPublisher,
            onConfirm = { name, publisher ->
                newPack = false
                scope.launch { chosen = container.repository.createPack(name, publisher) }
            },
            onDismiss = { newPack = false },
        )
    }
}
