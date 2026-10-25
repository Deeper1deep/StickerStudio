package com.stickerstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.stickerstudio.app.ui.WhatsAppActionButton
import com.stickerstudio.app.ui.components.StatusPill

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

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp),
        )

        val u = update
        if (u is UpdateUiState.Available && !u.skipped) {
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

        // Hero: dark card with the main call to action.
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .background(Color(0xFF17151F), RoundedCornerShape(28.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.home_hero),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Button(
                        onClick = onCreate,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B5CFF), contentColor = Color.White),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.home_hero_button), Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.size(84.dp).background(Color(0xFFFFC53D), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                    Text("🙂", style = MaterialTheme.typography.displaySmall)
                }
            }
        }

        if (packs.isEmpty()) {
            EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
        } else {
            SectionTitle(stringResource(R.string.home_my_packs))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(packs.take(10), key = { it.id }) { p ->
                    val n = counts.firstOrNull { it.packId == p.id }?.count ?: 0
                    Card(
                        onClick = { onOpenPack(p.id) },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp).widthIn(min = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            StickerThumb(c.repository.trayFile(p), Modifier.size(92.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                            Text(
                                stringResource(R.string.pack_count, n),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { (n / 30f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            WhatsAppActionButton(p, actions, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            TextButton(onClick = onOpenPacks, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.home_all_packs)) }
        }

        if (drafts.isNotEmpty()) {
            SectionTitle(stringResource(R.string.home_drafts))
            drafts.forEach { d ->
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
    PackActionsDialogs(actions)
}
