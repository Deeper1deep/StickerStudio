package com.stickerstudio.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.export.ExportItem
import com.stickerstudio.app.export.ExportPackRow
import com.stickerstudio.app.export.ExportSession
import com.stickerstudio.app.export.ExportViewModel
import com.stickerstudio.app.export.FailReason
import com.stickerstudio.app.export.ItemStatus
import com.stickerstudio.app.export.RunState
import com.stickerstudio.app.ui.components.AppCard
import com.stickerstudio.app.ui.components.BrandProgress
import com.stickerstudio.app.ui.components.EmptyState
import com.stickerstudio.app.ui.components.ScreenHeader
import com.stickerstudio.app.ui.components.SectionTitle
import com.stickerstudio.app.ui.components.StatusPill
import com.stickerstudio.app.ui.components.StickerThumb
import com.stickerstudio.app.whatsapp.WhatsAppIntegration
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A separate section: pick packs, order them, and send them to WhatsApp one after another. */
@Composable
fun ExportScreen(onGoPacks: () -> Unit) {
    val vm: ExportViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val launchRequest by vm.launch.collectAsState()
    val repo = LocalContext.current.container.repository
    val snackbar = remember { SnackbarHostState() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onResult(it.resultCode, it.data)
    }
    LaunchedEffect(launchRequest?.nonce) {
        val request = launchRequest ?: return@LaunchedEffect
        try {
            launcher.launch(request.intent)
            vm.onLaunched()
        } catch (e: Exception) {
            vm.onLaunchFailed()
        }
    }

    val notice = ui.notice
    val noticeText = if (notice == null) null else if (notice.arg > 0) stringResource(notice.res, notice.arg) else stringResource(notice.res)
    LaunchedEffect(notice?.nonce) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            vm.consumeNotice()
        }
    }

    val session = ui.session
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = (from.key as? String)?.takeIf { it.startsWith("o:") }?.removePrefix("o:")
        val b = (to.key as? String)?.takeIf { it.startsWith("o:") }?.removePrefix("o:")
        if (a != null && b != null) vm.move(a, b)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (session.isActive || session.state == RunState.FINISHED) {
                RunControls(session, vm)
            } else if (ui.rows.isNotEmpty()) {
                StartBar(ui.order.ids.size, onStart = vm::start)
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                Column {
                    ScreenHeader(stringResource(R.string.export_title))
                    Text(
                        stringResource(R.string.export_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            if (session.isActive || session.state == RunState.FINISHED) {
                item(key = "run_card") { RunCard(session) }
                items(session.items.size, key = { "r:$it:" + session.items[it].packId }) { i ->
                    val row = ui.rows.firstOrNull { it.pack.id == session.items[i].packId }
                    RunItemRow(
                        index = i, item = session.items[i], thumb = row?.let { repo.trayFile(it.pack) },
                        onRetry = { vm.retry(i) }, onSkip = { vm.skip(i) },
                    )
                }
            } else if (ui.loaded && ui.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        stringResource(R.string.export_empty_title), stringResource(R.string.export_empty_body),
                        action = { Button(onClick = onGoPacks) { Text(stringResource(R.string.export_go_packs)) } },
                    )
                }
            } else {
                item(key = "packs_title") {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle(stringResource(R.string.export_packs_title))
                            Text(
                                stringResource(R.string.export_selected_count, ui.order.ids.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        TextButton(onClick = vm::selectAll) { Text(stringResource(R.string.export_select_all)) }
                        TextButton(onClick = vm::clearSelection, enabled = ui.order.ids.isNotEmpty()) {
                            Text(stringResource(R.string.export_clear))
                        }
                    }
                }
                items(ui.rows, key = { "p:" + it.pack.id }) { row ->
                    PackSelectRow(
                        row = row, thumb = repo.trayFile(row.pack),
                        position = ui.order.position(row.pack.id),
                        onToggle = { vm.toggle(row.pack.id) },
                    )
                }
                item(key = "order_title") {
                    Column(Modifier.padding(top = 12.dp)) {
                        SectionTitle(stringResource(R.string.export_order_title))
                        Text(
                            stringResource(R.string.export_order_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }
                items(ui.order.ids, key = { "o:$it" }) { id ->
                    val row = ui.rows.firstOrNull { it.pack.id == id }
                    if (row != null) {
                        ReorderableItem(reorderState, key = "o:$id") { dragging ->
                            OrderRow(
                                row = row, thumb = repo.trayFile(row.pack), position = ui.order.position(id),
                                dragging = dragging,
                                handle = Modifier.draggableHandle(),
                                onRemove = { vm.remove(id) },
                            )
                        }
                    }
                }
            }
        }
    }

    ui.chooseTarget?.let { targets ->
        AlertDialog(
            onDismissRequest = vm::dismissTargetChoice,
            title = { Text(stringResource(R.string.choose_whatsapp)) },
            text = {
                Column {
                    targets.forEach { t ->
                        TextButton(onClick = { vm.chooseTarget(t) }) {
                            Text(stringResource(if (t == WhatsAppIntegration.PACKAGE_BUSINESS) R.string.whatsapp_business else R.string.whatsapp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissTargetChoice) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------------------

@Composable
private fun OrderBadge(position: Int) {
    Box(
        Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(position.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun PackSelectRow(row: ExportPackRow, thumb: java.io.File?, position: Int, onToggle: () -> Unit) {
    AppCard(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked = position > 0, onCheckedChange = null)
            StickerThumb(thumb, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(row.pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(R.string.export_sticker_count, row.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (position > 0) OrderBadge(position)
        }
    }
}

@Composable
private fun OrderRow(
    row: ExportPackRow,
    thumb: java.io.File?,
    position: Int,
    dragging: Boolean,
    handle: Modifier,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = if (dragging) 6.dp else 0.dp,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrderBadge(position)
            StickerThumb(thumb, Modifier.size(44.dp))
            Text(row.pack.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.export_remove)) }
            Icon(Icons.Filled.DragHandle, stringResource(R.string.export_drag), handle.padding(12.dp))
        }
    }
}

@Composable
private fun StartBar(count: Int, onStart: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onStart,
            enabled = count > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                when (count) {
                    0 -> stringResource(R.string.export_button_none)
                    1 -> stringResource(R.string.export_button_one)
                    else -> stringResource(R.string.export_button, count)
                },
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Running an export
// ---------------------------------------------------------------------------------------

@Composable
private fun RunCard(s: ExportSession) {
    val current = s.items.getOrNull(s.inProgressIndex ?: s.nextPendingIndex ?: -1)
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (s.state) {
                    RunState.FINISHED -> stringResource(R.string.export_state_finished)
                    RunState.CANCELLED -> stringResource(R.string.export_state_cancelled)
                    RunState.PAUSED -> stringResource(R.string.export_state_paused)
                    else -> stringResource(R.string.export_progress_title, (s.doneCount + 1).coerceAtMost(s.total), s.total)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            BrandProgress(if (s.total == 0) 0f else s.doneCount / s.total.toFloat())
            if (s.state == RunState.FINISHED) {
                Text(stringResource(R.string.export_done_summary, s.addedCount, s.failedCount, s.skippedCount), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.export_added_count, s.addedCount), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.export_remaining, s.remainingCount), style = MaterialTheme.typography.bodySmall)
                }
                if (current != null) {
                    Text(stringResource(R.string.export_current, current.name.ifBlank { "—" }), style = MaterialTheme.typography.bodyMedium)
                }
                if (s.inProgressIndex != null) {
                    Text(stringResource(R.string.export_waiting_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RunItemRow(index: Int, item: ExportItem, thumb: java.io.File?, onRetry: () -> Unit, onSkip: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (item.status) {
        ItemStatus.PENDING -> Triple(stringResource(R.string.st_pending), scheme.surfaceVariant, scheme.onSurfaceVariant)
        ItemStatus.IN_PROGRESS -> Triple(stringResource(R.string.st_progress), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        ItemStatus.ADDED -> Triple(stringResource(R.string.st_added), scheme.secondaryContainer, scheme.onSecondaryContainer)
        ItemStatus.FAILED -> Triple(stringResource(R.string.st_failed), scheme.errorContainer, scheme.onErrorContainer)
        ItemStatus.SKIPPED -> Triple(stringResource(R.string.st_skipped), scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    val reason = when (item.reason) {
        FailReason.NONE -> null
        FailReason.INVALID -> stringResource(R.string.fr_invalid, item.detail)
        FailReason.MISSING -> stringResource(R.string.fr_missing)
        FailReason.NOT_INSTALLED -> stringResource(R.string.fr_not_installed)
        FailReason.NOT_CONFIRMED -> stringResource(R.string.fr_not_confirmed)
        FailReason.REJECTED -> stringResource(R.string.fr_rejected, item.detail)
        FailReason.LAUNCH_FAILED -> stringResource(R.string.fr_launch)
    }
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrderBadge(index + 1)
                StickerThumb(thumb, Modifier.size(44.dp))
                Text(item.name.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                StatusPill(label, container, content)
            }
            if (reason != null) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            if (item.status == ItemStatus.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.reason != FailReason.MISSING && item.reason != FailReason.INVALID) {
                        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.export_retry)) }
                    }
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.export_skip)) }
                }
            }
        }
    }
}

@Composable
private fun RunControls(s: ExportSession, vm: ExportViewModel) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (s.state) {
                RunState.RUNNING -> {
                    OutlinedButton(onClick = vm::pause, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_pause)) }
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_cancel)) }
                }
                RunState.PAUSED, RunState.CANCELLED -> {
                    if (s.remainingCount > 0 || s.state == RunState.PAUSED) {
                        Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_resume)) }
                    }
                    if (s.state == RunState.PAUSED) {
                        OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_cancel)) }
                    } else {
                        OutlinedButton(onClick = vm::finishRun, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_back_to_list)) }
                    }
                }
                else -> Button(onClick = vm::finishRun, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_new)) }
            }
        }
    }
}
