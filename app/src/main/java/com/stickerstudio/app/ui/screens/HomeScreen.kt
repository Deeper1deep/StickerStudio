package com.stickerstudio.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import com.stickerstudio.app.ui.components.BrandProgress
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.DraftEntity
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.ui.PackActionsController
import com.stickerstudio.app.ui.PackActionsDialogs
import com.stickerstudio.app.ui.WhatsAppActionButton
import com.stickerstudio.app.ui.components.AppCard
import com.stickerstudio.app.ui.IncomingShare
import com.stickerstudio.app.ui.PackFileImport
import com.stickerstudio.app.ui.PackImportResult
import com.stickerstudio.app.ui.components.BusyDialog
import com.stickerstudio.app.ui.components.ConfirmDialog
import com.stickerstudio.app.ui.components.MessageDialog
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.components.SectionTitle
import com.stickerstudio.app.ui.components.StatusPill
import com.stickerstudio.app.ui.components.StickerThumb
import com.stickerstudio.app.ui.rememberPackActions
import com.stickerstudio.app.update.UpdateUiState
import kotlinx.coroutines.launch

/**
 * The only main screen: settings button on top, then "create" (pick a picture, GIF or video), then
 * the packs that were already made. There is no bottom navigation bar.
 */
@Composable
fun HomeScreen(
    onEditor: (source: String, pack: String) -> Unit,
    onBlankEditor: (pack: String) -> Unit,
    onAnimated: (pack: String, source: String, isVideo: Boolean) -> Unit,
    onOpenPack: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val packs by c.repository.observePacks().collectAsState(initial = emptyList<PackEntity>())
    val drafts by c.repository.observeDrafts().collectAsState(initial = emptyList<DraftEntity>())
    val counts by c.repository.observeCounts().collectAsState(initial = emptyList())
    val update by c.updates.state.collectAsState()
    val publisher by c.settings.defaultPublisher.collectAsState(initial = "")
    val actions = rememberPackActions()

    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var pendingPacks by remember { mutableStateOf(setOf<String>()) }
    val visiblePacks = packs.filter { it.id !in pendingPacks }
    val undoLabel = stringResource(R.string.undo)
    val copySuffix = stringResource(R.string.copy_suffix)
    /** Hides the pack right away; it is really deleted when the Undo bar goes away. */
    fun deleteWithUndo(p: PackEntity) {
        pendingPacks = pendingPacks + p.id
        scope.launch {
            var undone = false
            try {
                val r = snackbar.showSnackbar(
                    context.getString(R.string.pack_deleted, p.name),
                    actionLabel = undoLabel, duration = androidx.compose.material3.SnackbarDuration.Short,
                )
                undone = r == androidx.compose.material3.SnackbarResult.ActionPerformed
            } finally {
                if (undone) {
                    pendingPacks = pendingPacks - p.id
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        c.repository.deletePack(p.id)
                        pendingPacks = pendingPacks - p.id
                    }
                }
            }
        }
    }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PackEntity?>(null) }
    var deleting by remember { mutableStateOf<PackEntity?>(null) }
    var importProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var importResults by remember { mutableStateOf<List<PackImportResult>?>(null) }
    // .wastickers has no registered MIME type, so the picker must accept any file; several can be picked.
    fun runImport(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val results = ArrayList<PackImportResult>()
            for ((i, uri) in uris.withIndex()) {
                importProgress = (i + 1) to uris.size
                results += PackFileImport.import(context, uri)
            }
            importProgress = null
            importResults = results
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> runImport(uris) }

    // Files shared / opened from other apps: packs are imported here, pictures go to the create section.
    val incoming by IncomingShare.pending.collectAsState()
    var sharedMedia by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    LaunchedEffect(incoming) {
        val item = incoming ?: return@LaunchedEffect
        IncomingShare.consume()
        if (item.media.isNotEmpty()) sharedMedia = item.media
        runImport(item.packs)
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        val u = update
        if (u is UpdateUiState.Available && !u.skipped) {
            item(key = "update") {
                Card(
                    Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.update_available_title, u.manifest.versionName),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenUpdates) { Text(stringResource(R.string.update_details)) }
                    }
                }
            }
        }

        item(key = "create") {
            CreateScreen(
                onEditor = onEditor,
                onBlankEditor = onBlankEditor,
                onAnimated = onAnimated,
                onOpenPack = onOpenPack,
                incoming = sharedMedia,
                onIncomingHandled = { sharedMedia = null },
            )
        }

        item(key = "packs_title") {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_my_packs), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.import_pack), Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.new_pack), Modifier.padding(start = 6.dp))
                }
            }
        }

        if (visiblePacks.isEmpty()) {
            item(key = "packs_empty") {
                EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
            }
        } else {
            items(visiblePacks, key = { it.id }) { pack ->
                val n = counts.firstOrNull { it.packId == pack.id }?.count ?: 0
                PackCard(
                    pack = pack,
                    count = n,
                    actions = actions,
                    onOpen = { onOpenPack(pack.id) },
                    onRename = { renaming = pack },
                    onDelete = { deleting = pack },
                    onDuplicate = { scope.launch { c.repository.duplicatePack(pack.id, copySuffix) } },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }

        if (drafts.isNotEmpty()) {
            item(key = "drafts_title") { SectionTitle(stringResource(R.string.home_drafts)) }
            items(drafts, key = { "draft_" + it.id }) { d ->
                Card(
                    onClick = { onOpenDraft(d.id) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(20.dp, 4.dp),
                ) {
                    Row(Modifier.padding(16.dp)) { Text(stringResource(R.string.home_draft_item), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (creating) {
        PackInfoDialog(
            stringResource(R.string.new_pack), "", publisher,
            onConfirm = { n, p -> creating = false; scope.launch { onOpenPack(c.repository.createPack(n.trim(), p.trim())) } },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { p ->
        PackInfoDialog(
            stringResource(R.string.rename), p.name, p.publisher,
            onConfirm = { n, pub -> renaming = null; scope.launch { c.repository.updatePackInfo(p.id, n.trim(), pub.trim()) } },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            stringResource(R.string.delete_pack_title), stringResource(R.string.delete_pack_message, p.name),
            stringResource(R.string.delete),
            onConfirm = { deleting = null; deleteWithUndo(p) },
            onDismiss = { deleting = null },
        )
    }
    importProgress?.let { BusyDialog(it) }
    importResults?.let { results ->
        val ok = results.filter { it.packId != null }
        val first = results.firstOrNull()
        val message = when {
            results.size == 1 && first?.error != null -> stringResource(first.error)
            results.size == 1 && first != null ->
                stringResource(R.string.import_pack_done, first.added) +
                    if (first.skipped > 0) " " + stringResource(R.string.import_pack_skipped, first.skipped) else ""
            ok.isEmpty() -> stringResource(R.string.import_pack_invalid)
            else -> stringResource(R.string.import_packs_done, ok.size, ok.sumOf { it.added }) +
                if (results.size > ok.size) "\n" + stringResource(R.string.import_packs_failed, results.size - ok.size) else ""
        }
        MessageDialog(
            stringResource(R.string.import_pack_title), message,
            onDismiss = {
                importResults = null
                // a single imported pack opens right away
                if (ok.size == 1) ok[0].packId?.let(onOpenPack)
            },
        )
    }
    PackActionsDialogs(actions)
}

@Composable
private fun PackCard(
    pack: PackEntity,
    count: Int,
    actions: PackActionsController,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalContext.current.container.repository
    val stickers by repo.observeStickers(pack.id).collectAsState(initial = emptyList())
    var menu by remember { mutableStateOf(false) }
    AppCard(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // a peek at the first four stickers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until 4) {
                    val sticker = stickers.getOrNull(i)
                    if (sticker != null) {
                        StickerThumb(repo.stickerFile(sticker), Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(pack.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                StatusPill(
                    stringResource(R.string.pack_status, count, 30),
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (pack.animated) {
                    StatusPill(
                        stringResource(R.string.animated),
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                        Modifier.padding(start = 6.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.duplicate_pack)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            BrandProgress(count / 30f)
            WhatsAppActionButton(pack, actions, Modifier.fillMaxWidth())
        }
    }
}
