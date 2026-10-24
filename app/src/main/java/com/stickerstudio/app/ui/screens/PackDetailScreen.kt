package com.stickerstudio.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    packId: String,
    onBack: () -> Unit,
    onEditor: (source: String, sticker: String) -> Unit,
    onAnimated: () -> Unit,
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
    var addMenu by remember { mutableStateOf(false) }

    val addPictures = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            report = StickerImport.addImages(context, uris, packId)
            busy = false
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
                title = { Text(pack?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.add_sticker)) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.create_batch)) },
                                onClick = { addMenu = false; addPictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.create_blank)) },
                                onClick = { addMenu = false; onEditor("", "") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.create_animated)) },
                                onClick = { addMenu = false; onAnimated() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val p = pack
            if (p != null) {
                Text(
                    stringResource(R.string.pack_status, stickers.size, 30) + " · " + p.publisher,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.addToWhatsApp(packId) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_to_whatsapp))
                    }
                    OutlinedButton(onClick = { actions.exportZip(packId) }) { Text(stringResource(R.string.export_zip)) }
                }
            }
            if (stickers.isEmpty()) {
                EmptyState(stringResource(R.string.pack_empty_title), stringResource(R.string.pack_empty_body))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(stickers, key = { it.id }) { s ->
                        Box {
                            StickerThumb(
                                repo.stickerFile(s),
                                Modifier.aspectRatio(1f).clickable { previewing = s },
                            )
                            Text(
                                s.emojis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            IconButton(onClick = { menuFor = s }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                                Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, stringResource(R.string.more))
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
                }
            }
        }
    }

    if (busy) androidx.compose.ui.window.Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
    previewing?.let { s ->
        AlertDialog(
            onDismissRequest = { previewing = null },
            text = { StickerThumb(repo.stickerFile(s), Modifier.fillMaxWidth().aspectRatio(1f)) },
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
