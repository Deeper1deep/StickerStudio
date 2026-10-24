package com.stickerstudio.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.DraftEntity
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.ui.PackActionsDialogs
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.SectionTitle
import com.stickerstudio.app.ui.components.StickerThumb
import com.stickerstudio.app.ui.rememberPackActions
import com.stickerstudio.app.update.UpdateUiState

@Composable
fun HomeScreen(
    onCreate: () -> Unit,
    onOpenPack: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    onOpenPacks: () -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val packs by c.repository.observePacks().collectAsState(initial = emptyList<PackEntity>())
    val drafts by c.repository.observeDrafts().collectAsState(initial = emptyList<DraftEntity>())
    val counts by c.repository.observeCounts().collectAsState(initial = emptyList())
    val update by c.updates.state.collectAsState()
    val actions = rememberPackActions()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
        )

        val u = update
        if (u is UpdateUiState.Available && !u.skipped) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.update_available_title, u.manifest.versionName), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onOpenUpdates) { Text(stringResource(R.string.update_details)) }
                }
            }
        }

        if (packs.isEmpty()) {
            EmptyState(
                stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body),
                action = { Button(onClick = onCreate) { Text(stringResource(R.string.create_title)) } },
            )
        } else {
            SectionTitle(stringResource(R.string.home_my_packs))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(packs.take(10), key = { it.id }) { p ->
                    val n = counts.firstOrNull { it.packId == p.id }?.count ?: 0
                    Card(onClick = { onOpenPack(p.id) }) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            StickerThumb(c.repository.trayFile(p), Modifier.size(88.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(stringResource(R.string.pack_count, n), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { actions.addToWhatsApp(p.id) }) { Text(stringResource(R.string.add_to_whatsapp)) }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenPacks, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.home_all_packs)) }
        }

        if (drafts.isNotEmpty()) {
            SectionTitle(stringResource(R.string.home_drafts))
            drafts.forEach { d ->
                Card(onClick = { onOpenDraft(d.id) }, modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                    Row(Modifier.padding(16.dp)) { Text(stringResource(R.string.home_draft_item), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    PackActionsDialogs(actions)
}
