package com.stickerstudio.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.stickerstudio.app.ui.MediaImport
import com.stickerstudio.app.ui.WhatsAppActionButton
import com.stickerstudio.app.ui.components.BusyDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import com.stickerstudio.app.ui.components.AppCard
import com.stickerstudio.app.ui.components.IconBadge
import com.stickerstudio.app.ui.components.StatusPill
import com.stickerstudio.app.whatsapp.PackIssue
import com.stickerstudio.app.whatsapp.WhatsAppIntegration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.data.PackRepository
import com.stickerstudio.app.data.StickerEntity
import com.stickerstudio.app.ui.ImportReport
import com.stickerstudio.app.ui.PackActionsDialogs
import com.stickerstudio.app.ui.StickerImport
import com.stickerstudio.app.ui.components.ConfirmDialog
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.MessageDialog
import com.stickerstudio.app.ui.components.StickerThumb
import com.stickerstudio.app.ui.components.TextInputDialog
import com.stickerstudio.app.ui.rememberPackActions
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import com.stickerstudio.app.ui.components.ShareQrDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    packId: String,
    onBack: () -> Unit,
    onEditor: (source: String, sticker: String) -> Unit,
    onAnimated: (source: String, isVideo: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val repo = context.container.repository
    val scope = rememberCoroutineScope()
    val pack by repo.observePack(packId).collectAsState(initial = null)
    val stickers by repo.observeStickers(packId).collectAsState(initial = emptyList<StickerEntity>())
    val actions = rememberPackActions()

    val snackbar = remember { SnackbarHostState() }
    val allPacks by repo.observePacks().collectAsState(initial = emptyList<PackEntity>())
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    var order by remember(stickers) { mutableStateOf(stickers) }
    val visible = order.filter { it.id !in pendingDelete }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickTarget by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }   // ids to (move?)
    var bulkEmoji by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    val undoLabel = stringResource(R.string.undo)
    val ctx = context
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Short) } }
    fun exitSelect() { selectMode = false; selected = emptySet() }
    /** Hides the stickers right away; they are really deleted when the "Undo" bar goes away. */
    fun deleteWithUndo(list: List<StickerEntity>) {
        val ids = list.map { it.id }
        if (ids.isEmpty()) return
        pendingDelete = pendingDelete + ids
        scope.launch {
            var undone = false
            try {
                val r = snackbar.showSnackbar(
                    ctx.getString(R.string.stickers_deleted, ids.size),
                    actionLabel = undoLabel, duration = SnackbarDuration.Short,
                )
                undone = r == SnackbarResult.ActionPerformed
            } finally {
                if (undone) {
                    pendingDelete = pendingDelete - ids.toSet()
                } else {
                    withContext(NonCancellable) {
                        ids.forEach { repo.deleteSticker(it) }
                        pendingDelete = pendingDelete - ids.toSet()
                    }
                }
            }
        }
    }
    var menuFor by remember { mutableStateOf<StickerEntity?>(null) }
    var previewing by remember { mutableStateOf<StickerEntity?>(null) }
    var emojiFor by remember { mutableStateOf<StickerEntity?>(null) }
    var replaceId by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var readiness by remember { mutableStateOf<List<PackIssue>?>(null) }
    LaunchedEffect(pack, stickers) {
        val p = pack
        readiness = if (p == null) null else WhatsAppIntegration.validate(repo, p)
    }

    val addMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            if (uris.size == 1) {
                val prepared = MediaImport.prepareSingle(context, uris[0])
                busy = false
                when (prepared) {
                    is MediaImport.Prepared.Picture -> onEditor(prepared.name, "")
                    is MediaImport.Prepared.Animated -> onAnimated(prepared.name, prepared.isVideo)
                    MediaImport.Prepared.Failed -> report = ImportReport(0, 1, null)
                }
            } else {
                report = MediaImport.addMixed(context, uris, packId) { done, total -> progress = done to total }
                progress = null
                busy = false
            }
        }
    }
    val replacePicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val target = replaceId
        replaceId = null
        if (uri != null && target != null) scope.launch {
            busy = true
            report = StickerImport.addImages(context, listOf(uri), packId, replaceStickerId = target)
            busy = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectMode) stringResource(R.string.selected_count, selected.size) else (pack?.name ?: ""),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectMode) exitSelect() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (selectMode) {
                        TextButton(onClick = { selected = visible.map { it.id }.toSet() }) { Text(stringResource(R.string.select_all)) }
                    } else {
                        if (visible.isNotEmpty()) TextButton(onClick = { selectMode = true }) { Text(stringResource(R.string.select)) }
                        TextButton(onClick = { showShare = true }) { Text(stringResource(R.string.share_pack)) }
                    }
                },
            )
        },
        bottomBar = {
            if (selectMode) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val has = selected.isNotEmpty()
                        TextButton(enabled = has, onClick = {
                            val list = visible.filter { it.id in selected }
                            exitSelect(); deleteWithUndo(list)
                        }) { Text(stringResource(R.string.delete)) }
                        TextButton(enabled = has, onClick = { bulkEmoji = true }) { Text(stringResource(R.string.emoji_selected)) }
                        TextButton(enabled = has, onClick = { pickTarget = selected.toList() to false }) { Text(stringResource(R.string.copy_to)) }
                        TextButton(enabled = has, onClick = { pickTarget = selected.toList() to true }) { Text(stringResource(R.string.move_to)) }
                    }
                }
            }
        },
    ) { padding ->
        val p = pack
        val freeSlots = (30 - visible.size).coerceAtLeast(0)
        val gridState = rememberLazyGridState()
        val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
            val list = order.toMutableList()
            val a = list.indexOfFirst { it.id == from.key }
            val b = list.indexOfFirst { it.id == to.key }
            if (a >= 0 && b >= 0) {
                list.add(b, list.removeAt(a))
                order = list
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (p != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
            Row(Modifier.padding(horizontal = 0.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    stringResource(R.string.pack_status, visible.size, 30),
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(p.publisher, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val ready = readiness
            if (ready != null) {
                val ok = ready.isEmpty()
                AppCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 6.dp),
                    onClick = if (ok) null else ({ actions.issues = ready }),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            size = 44.dp,
                            tint = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.error,
                            container = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                        )
                        Column {
                            Text(
                                if (ok) stringResource(R.string.pack_ready) else stringResource(R.string.pack_issues, ready.size),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(if (ok) R.string.pack_ready_body else R.string.pack_issues_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (stickers.any { it.animated } && stickers.any { !it.animated }) {
                Text(
                    stringResource(R.string.pack_mixed_note),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (visible.size > 1 && !selectMode) {
                Text(
                    stringResource(R.string.drag_hint),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(horizontal = 0.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhatsAppActionButton(p, actions, Modifier.weight(1f))
                OutlinedButton(onClick = { actions.exportZip(packId) }) { Text(stringResource(R.string.export_zip)) }
            }
                    }
                }
            }
                items(visible, key = { it.id }) { s ->
                    ReorderableItem(reorderState, key = s.id) { _ ->
                    val isSel = s.id in selected
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                if (isSel) 3.dp else 1.dp,
                                if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(20.dp),
                            )
                            .longPressDraggableHandle(
                                enabled = !selectMode,
                                onDragStopped = { scope.launch { repo.reorder(packId, order.map { it.id }) } },
                            ),
                    ) {
                        StickerThumb(
                            repo.stickerFile(s),
                            Modifier.fillMaxSize().padding(6.dp).clickable {
                                if (selectMode) {
                                    selected = if (isSel) selected - s.id else selected + s.id
                                } else {
                                    previewing = s
                                }
                            },
                        )
                        if (s.animated) {
                            StatusPill(
                                stringResource(R.string.animated),
                                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                            )
                        }
                        Text(
                            s.emojis,
                            modifier = Modifier.align(Alignment.BottomStart).clickable(enabled = !selectMode) { emojiFor = s }.padding(6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (selectMode) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape)
                                    .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSel) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            IconButton(onClick = { menuFor = s }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                            }
                        }
                        DropdownMenu(expanded = menuFor?.id == s.id, onDismissRequest = { menuFor = null }) {
                            if (!s.animated) DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_sticker)) },
                                onClick = { menuFor = null; onEditor(s.sourceFile.orEmpty(), s.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.replace_image)) },
                                onClick = { menuFor = null; replaceId = s.id; replacePicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.sticker_emojis)) }, onClick = { menuFor = null; emojiFor = s })
                            DropdownMenuItem(text = { Text(stringResource(R.string.copy_to)) }, onClick = { menuFor = null; pickTarget = listOf(s.id) to false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_to)) }, onClick = { menuFor = null; pickTarget = listOf(s.id) to true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.set_tray)) }, onClick = { menuFor = null; scope.launch { repo.setTraySticker(packId, s.id) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuFor = null; deleteWithUndo(listOf(s)) })
                        }
                    }
                    }
                }
            items(count = freeSlots, key = { "slot_$it" }) {
                EmptySlot(onClick = { addMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
            }
        }
    }

    if (busy) BusyDialog(progress)
    previewing?.let { s ->
        AlertDialog(
            onDismissRequest = { previewing = null },
            text = { StickerThumb(repo.stickerFile(s), Modifier.fillMaxWidth().aspectRatio(1f), animate = true) },
            confirmButton = { TextButton(onClick = { previewing = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    emojiFor?.let { s ->
        TextInputDialog(
            title = stringResource(R.string.sticker_emojis),
            label = stringResource(R.string.sticker_emojis_hint),
            initial = s.emojis,
            onConfirm = { text -> emojiFor = null; scope.launch { repo.setEmojis(s.id, text) } },
            onDismiss = { emojiFor = null },
        )
    }
    pickTarget?.let { (ids, move) ->
        val others = allPacks.filter { it.id != packId }
        AlertDialog(
            onDismissRequest = { pickTarget = null },
            title = { Text(stringResource(R.string.choose_pack)) },
            text = {
                if (others.isEmpty()) Text(stringResource(R.string.no_other_packs))
                else Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    others.forEach { t ->
                        Text(
                            t.name,
                            Modifier.fillMaxWidth().clickable {
                                pickTarget = null
                                exitSelect()
                                scope.launch {
                                    val n = try {
                                        if (move) repo.moveStickersToPack(ids, t.id) else repo.copyStickersToPack(ids, t.id)
                                    } catch (e: Exception) { 0 }
                                    snackbar.showSnackbar(
                                        if (n == 0) ctx.getString(R.string.target_full)
                                        else ctx.getString(if (move) R.string.moved_n else R.string.copied_n, n),
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            }.padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (bulkEmoji) {
        TextInputDialog(
            title = stringResource(R.string.sticker_emojis),
            label = stringResource(R.string.sticker_emojis_hint),
            initial = "",
            onConfirm = { text ->
                bulkEmoji = false
                val ids = selected.toList()
                exitSelect()
                scope.launch { repo.setEmojisBulk(ids, text) }
            },
            onDismiss = { bulkEmoji = false },
        )
    }
    if (showShare) {
        ShareQrDialog(onDismiss = { showShare = false }, onSendFile = { showShare = false; actions.sendFile(packId) })
    }
    report?.let { r ->
        val text = buildString {
            append(stringResource(R.string.import_done, r.added))
            if (r.failed > 0) append("\n").append(stringResource(R.string.import_failed, r.failed))
            r.stoppedBecause?.let { append("\n").append(stringResource(it)) }
        }
        MessageDialog(stringResource(R.string.create_batch), text) { report = null }
    }
    PackActionsDialogs(actions)
}

/** An empty place in the 30-sticker grid: a dashed frame with a plus, tapping it opens the gallery. */
@Composable
private fun EmptySlot(onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = line,
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, stringResource(R.string.add_sticker), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
    }
}
