package com.stickerstudio.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.stickerstudio.app.ui.components.ConfirmDialog
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.components.StickerThumb
import com.stickerstudio.app.ui.rememberPackActions
import kotlinx.coroutines.launch

@Composable
fun PacksScreen(onOpenPack: (String) -> Unit) {
    val context = LocalContext.current
    val repo = context.container.repository
    val scope = rememberCoroutineScope()
    val packs by repo.observePacks().collectAsState(initial = emptyList<PackEntity>())
    val counts by repo.observeCounts().collectAsState(initial = emptyList())
    val publisher by context.container.settings.defaultPublisher.collectAsState(initial = "")
    val actions = rememberPackActions()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PackEntity?>(null) }
    var deleting by remember { mutableStateOf<PackEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (packs.isEmpty()) {
            EmptyState(stringResource(R.string.packs_empty_title), stringResource(R.string.packs_empty_body), Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(packs, key = { it.id }) { pack ->
                    val n = counts.firstOrNull { it.packId == pack.id }?.count ?: 0
                    Card(onClick = { onOpenPack(pack.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StickerThumb(repo.trayFile(pack), Modifier.size(64.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                                Text(pack.publisher, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    stringResource(R.string.pack_count, n) + if (pack.animated) " · " + stringResource(R.string.animated) else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { renaming = pack }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                            IconButton(onClick = { deleting = pack }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.new_pack))
        }
    }

    if (creating) {
        PackInfoDialog(
            stringResource(R.string.new_pack), "", publisher,
            onConfirm = { n, p -> creating = false; scope.launch { onOpenPack(repo.createPack(n.trim(), p.trim())) } },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { p ->
        PackInfoDialog(
            stringResource(R.string.rename), p.name, p.publisher,
            onConfirm = { n, pub -> renaming = null; scope.launch { repo.updatePackInfo(p.id, n.trim(), pub.trim()) } },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            stringResource(R.string.delete_pack_title), stringResource(R.string.delete_pack_message, p.name),
            stringResource(R.string.delete),
            onConfirm = { deleting = null; scope.launch { repo.deletePack(p.id) } },
            onDismiss = { deleting = null },
        )
    }
    PackActionsDialogs(actions)
}
