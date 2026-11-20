package com.stickerstudio.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import com.stickerstudio.app.ui.PackActionsDialogs
import com.stickerstudio.app.ui.PackFileImport
import com.stickerstudio.app.ui.PackImportResult
import com.stickerstudio.app.ui.WhatsAppActionButton
import com.stickerstudio.app.ui.components.AppCard
import com.stickerstudio.app.ui.components.BusyDialog
import com.stickerstudio.app.ui.components.ConfirmDialog
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.MessageDialog
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.components.ScreenHeader
import com.stickerstudio.app.ui.components.StatusPill
import com.stickerstudio.app.ui.components.StickerThumb
import com.stickerstudio.app.ui.rememberPackActions
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Imported packs" section: only packs that came from external files (.wastickers / .zip).
 * Packs made inside the app never appear here, and imported packs never appear in "My packs".
 * The section has its own import button, list, state and per-pack management.
 */
@Composable
fun ImportedPacksScreen(onOpenPack: (String) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val packs by c.repository.observeImportedPacks().collectAsState(initial = emptyList<PackEntity>())
    val counts by c.repository.observeCounts().collectAsState(initial = emptyList())
    val actions = rememberPackActions()

    val snackbar = remember { SnackbarHostState() }
    var pendingPacks by remember { mutableStateOf(setOf<String>()) }
    val visiblePacks = packs.filter { it.id !in pendingPacks }
    val undoLabel = stringResource(R.string.undo)

    /** Hides the pack right away; it is really deleted when the Undo bar goes away. Only this pack is touched. */
    fun deleteWithUndo(p: PackEntity) {
        pendingPacks = pendingPacks + p.id
        scope.launch {
            var undone = false
            try {
                val r = snackbar.showSnackbar(
                    context.getString(R.string.pack_deleted, p.name),
                    actionLabel = undoLabel, duration = SnackbarDuration.Short,
                )
                undone = r == SnackbarResult.ActionPerformed
            } finally {
                if (undone) {
                    pendingPacks = pendingPacks - p.id
                } else {
                    withContext(NonCancellable) {
                        c.repository.deletePack(p.id)
                        pendingPacks = pendingPacks - p.id
                    }
                }
            }
        }
    }

    var renaming by remember { mutableStateOf<PackEntity?>(null) }
    var deleting by remember { mutableStateOf<PackEntity?>(null) }
    var importProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var importResults by remember { mutableStateOf<List<PackImportResult>?>(null) }
    var reimportTarget by remember { mutableStateOf<PackEntity?>(null) }

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
    val reimportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = reimportTarget
        reimportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                importProgress = 1 to 1
                importResults = listOf(PackFileImport.import(context, uri, replacePackId = target.id))
                importProgress = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                ScreenHeader(
                    stringResource(R.string.imported_title),
                    trailing = {
                        FilledTonalButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.import_pack), Modifier.padding(start = 6.dp))
                        }
                    },
                )
            }

            if (visiblePacks.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        stringResource(R.string.imported_empty_title),
                        stringResource(R.string.imported_empty_body),
                    ) {
                        FilledTonalButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.import_pack), Modifier.padding(start = 6.dp))
                        }
                    }
                }
            } else {
                items(visiblePacks, key = { it.id }) { pack ->
                    ImportedPackCard(
                        pack = pack,
                        count = counts.firstOrNull { it.packId == pack.id }?.count ?: 0,
                        onOpen = { onOpenPack(pack.id) },
                        onRename = { renaming = pack },
                        onReimport = { reimportTarget = pack; reimportPicker.launch(arrayOf("*/*")) },
                        onDelete = { deleting = pack },
                        actions = actions,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
            results.size == 1 && first != null && first.duplicate -> stringResource(R.string.import_pack_duplicate)
            results.size == 1 && first != null && first.updated ->
                stringResource(R.string.reimport_done, first.added) +
                    if (first.skipped > 0) " " + stringResource(R.string.import_pack_skipped, first.skipped) else ""
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
                // a single imported (or already present) pack opens right away
                if (ok.size == 1) ok[0].packId?.let(onOpenPack)
            },
        )
    }
    PackActionsDialogs(actions)
}

@Composable
private fun ImportedPackCard(
    pack: PackEntity,
    count: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onReimport: () -> Unit,
    onDelete: () -> Unit,
    actions: com.stickerstudio.app.ui.PackActionsController,
    modifier: Modifier = Modifier,
) {
    val repo = LocalContext.current.container.repository
    val stickers by repo.observeStickers(pack.id).collectAsState(initial = emptyList())
    var menu by remember { mutableStateOf(false) }
    val thumb = repo.trayFile(pack) ?: stickers.firstOrNull()?.let { repo.stickerFile(it) }
    val importedOn = remember(pack.createdAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pack.createdAt))
    }
    AppCard(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StickerThumb(
                thumb,
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).aspectRatio(1f),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(pack.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        stringResource(R.string.pack_count, count),
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    StatusPill(
                        stringResource(if (pack.animated) R.string.animated else R.string.static_badge),
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                        Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    stringResource(R.string.imported_on, importedOn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_pack)) },
                        leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                        onClick = { menu = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reimport_pack)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { menu = false; onReimport() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        WhatsAppActionButton(pack, actions, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
    }
}
