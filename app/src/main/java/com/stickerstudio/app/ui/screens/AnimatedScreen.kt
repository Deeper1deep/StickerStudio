package com.stickerstudio.app.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stickerstudio.app.R
import com.stickerstudio.app.ui.components.checkerboard
import com.stickerstudio.app.ui.vmFactory
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedScreen(packId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val vm: AnimatedViewModel = viewModel(factory = vmFactory { AnimatedViewModel(context.applicationContext as Application, packId) })
    val ui by vm.ui.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pick) }
    LaunchedEffect(ui.saved) { if (ui.saved) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_animated)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.anim_limits), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { picker.launch(arrayOf("image/gif", "video/*")) }, enabled = !ui.converting && !ui.loading) {
                Text(stringResource(R.string.anim_pick))
            }

            if (ui.sourceName != null) {
                ui.preview?.let {
                    Image(
                        it.asImageBitmap(), null,
                        Modifier.fillMaxWidth().aspectRatio(1f).checkerboard(),
                    )
                }
                val maxDur = minOf(ui.durationMs.coerceAtLeast(500L), 10_000L).toFloat()
                if (ui.isVideo) {
                    val maxStart = (ui.durationMs - 500L).coerceAtLeast(0L).toFloat()
                    if (maxStart > 0f) {
                        Text(stringResource(R.string.anim_start, ui.settings.startMs / 1000f))
                        Slider(
                            value = ui.settings.startMs.toFloat().coerceAtMost(maxStart),
                            onValueChange = { v -> vm.change { it.copy(startMs = v.toLong()) } },
                            valueRange = 0f..maxStart,
                            enabled = !ui.converting,
                        )
                    }
                }
                Text(stringResource(R.string.anim_duration, ui.settings.durationMs / 1000f))
                Slider(
                    value = ui.settings.durationMs.toFloat().coerceIn(500f, maxDur),
                    onValueChange = { v -> vm.change { it.copy(durationMs = v.toLong()) } },
                    valueRange = 500f..maxDur,
                    enabled = !ui.converting,
                )
                Text(stringResource(R.string.anim_fps, ui.settings.fps))
                Slider(
                    value = ui.settings.fps.toFloat(),
                    onValueChange = { v -> vm.change { it.copy(fps = v.roundToInt().coerceIn(8, 20)) } },
                    valueRange = 8f..20f,
                    steps = 11,
                    enabled = !ui.converting,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fill), Modifier.weight(1f))
                    Switch(checked = ui.settings.cover, onCheckedChange = { c -> vm.change { it.copy(cover = c) } }, enabled = !ui.converting)
                }
                OutlinedTextField(
                    value = ui.settings.caption,
                    onValueChange = { t -> vm.change { it.copy(caption = t.take(40)) } },
                    label = { Text(stringResource(R.string.anim_caption)) },
                    singleLine = true,
                    enabled = !ui.converting,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (ui.converting) {
                    LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(ui.progress * 100).toInt()}%")
                    OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
                } else {
                    Button(onClick = vm::convert) { Text(stringResource(R.string.anim_convert)) }
                }

                ui.resultBytes?.let { bytes ->
                    Text(stringResource(R.string.anim_done, bytes.size / 1024), color = MaterialTheme.colorScheme.primary)
                    AsyncImage(
                        model = bytes, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).checkerboard(),
                    )
                    Button(onClick = vm::saveToPack) { Text(stringResource(R.string.save_to_pack)) }
                }
            }

            ui.message?.let { m ->
                if (ui.resultBytes == null) Text(stringResource(m), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
