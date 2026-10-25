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

    var menuFor by remember { mutableStateOf<StickerEntity?>(null) }
    var previewing by remember { mutableStateOf<StickerEntity?>(null) }
    var emojiFor by remember { mutableStateOf<StickerEntity?>(null) }
    var deleteFor by remember { mutableStateOf<StickerEntity?>(null) }
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
        topBar = {
            TopAppBar(
                title = { Text(pack?.name ?: "", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val p = pack
        val freeSlots = (30 - stickers.size).coerceAtLeast(0)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (p != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
            Row(Modifier.padding(horizontal = 0.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    stringResource(R.string.pack_status, stickers.size, 30),
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
            Row(Modifier.padding(horizontal = 0.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhatsAppActionButton(p, actions, Modifier.weight(1f))
                OutlinedButton(onClick = { actions.exportZip(packId) }) { Text(stringResource(R.string.export_zip)) }
            }
                    }
                }
            }
                items(stickers, key = { it.id }) { s ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
                    ) {
                        StickerThumb(
                            repo.stickerFile(s),
                            Modifier.fillMaxSize().padding(6.dp).clickable { previewing = s },
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
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        IconButton(onClick = { menuFor = s }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_earlier)) }, onClick = { menuFor = null; scope.launch { repo.moveSticker(s.id, -1) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_later)) }, onClick = { menuFor = null; scope.launch { repo.moveSticker(s.id, 1) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.set_tray)) }, onClick = { menuFor = null; scope.launch { repo.setTraySticker(packId, s.id) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuFor = null; deleteFor = s })
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
    deleteFor?.let { s ->
        ConfirmDialog(
            stringResource(R.string.delete_sticker_title), stringResource(R.string.delete_sticker_message), stringResource(R.string.delete),
            onConfirm = { deleteFor = null; scope.launch { repo.deleteSticker(s.id) } },
            onDismiss = { deleteFor = null },
        )
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
