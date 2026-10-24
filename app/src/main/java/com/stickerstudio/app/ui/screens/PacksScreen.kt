package com.stickerstudio.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.stickerstudio.app.ui.components.AppCard
import com.stickerstudio.app.ui.components.ConfirmDialog
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.components.ScreenHeader
import com.stickerstudio.app.ui.components.StatusPill
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
    var menuFor by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.nav_packs),
                subtitle = if (packs.isEmpty()) null else stringResource(R.string.packs_total, packs.size),
            )
            if (packs.isEmpty()) {
                EmptyState(stringResource(R.string.packs_empty_title), stringResource(R.string.packs_empty_body))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 104.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(packs, key = { it.id }) { pack ->
                        val n = counts.firstOrNull { it.packId == pack.id }?.count ?: 0
                        AppCard(onClick = { onOpenPack(pack.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StickerThumb(repo.trayFile(pack), Modifier.size(64.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                        Text(pack.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            StatusPill(
                                                stringResource(R.string.pack_status, n, 30),
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
                                        IconButton(onClick = { menuFor = pack.id }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                                        DropdownMenu(expanded = menuFor == pack.id, onDismissRequest = { menuFor = null }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.rename)) },
                                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                                onClick = { menuFor = null; renaming = pack },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.delete)) },
                                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                                onClick = { menuFor = null; deleting = pack },
                                            )
                                        }
                                    }
                                }
                                LinearProgressIndicator(progress = { (n / 30f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Button(
                                    onClick = { actions.addToWhatsApp(pack.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.add_to_whatsapp), style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.new_pack)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
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
