package com.stickerstudio.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PackEntity?>(null) }
    var deleting by remember { mutableStateOf<PackEntity?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<PackImportResult?>(null) }
    // .wastickers has no registered MIME type, so the picker must accept any file.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                importResult = PackFileImport.import(context, uri)
                importing = false
            }
        }
    }

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

        if (packs.isEmpty()) {
            item(key = "packs_empty") {
                EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
            }
        } else {
            items(packs, key = { it.id }) { pack ->
                val n = counts.firstOrNull { it.packId == pack.id }?.count ?: 0
                PackCard(
                    pack = pack,
                    count = n,
                    actions = actions,
                    onOpen = { onOpenPack(pack.id) },
                    onRename = { renaming = pack },
                    onDelete = { deleting = pack },
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
            onConfirm = { deleting = null; scope.launch { c.repository.deletePack(p.id) } },
            onDismiss = { deleting = null },
        )
    }
    if (importing) BusyDialog()
    importResult?.let { r ->
        val message = if (r.error != null) {
            stringResource(r.error)
        } else {
            stringResource(R.string.import_pack_done, r.added) +
                if (r.skipped > 0) " " + stringResource(R.string.import_pack_skipped, r.skipped) else ""
        }
        MessageDialog(
            stringResource(R.string.import_pack_title), message,
            onDismiss = {
                importResult = null
                r.packId?.let(onOpenPack)
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
    modifier: Modifier = Modifier,
) {
    val repo = LocalContext.current.container.repository
    var menu by remember { mutableStateOf(false) }
    AppCard(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StickerThumb(repo.trayFile(pack), Modifier.size(64.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(pack.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            stringResource(R.string.pack_status, count, 30),
                            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (pack.animated) {
                            StatusPill(
                                stringResource(R.string.animated),
                                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
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
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            LinearProgressIndicator(progress = { (count / 30f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            WhatsAppActionButton(pack, actions, Modifier.fillMaxWidth())
        }
    }
}
