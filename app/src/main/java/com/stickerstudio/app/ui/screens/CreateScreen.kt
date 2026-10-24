package com.stickerstudio.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.ui.ImportReport
import com.stickerstudio.app.ui.StickerImport
import com.stickerstudio.app.ui.components.MessageDialog
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.importSourcePicture
import kotlinx.coroutines.launch
import java.io.File

/** Entry points for making a sticker: gallery, files, camera, batch import, blank canvas, animated. */
@Composable
fun CreateScreen(
    onEditor: (source: String, pack: String) -> Unit,
    onBlankEditor: (pack: String) -> Unit,
    onAnimated: (pack: String) -> Unit,
    onOpenPack: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = context.container.repository
    val scope = rememberCoroutineScope()
    val packs by repo.observePacks().collectAsState(initial = emptyList<PackEntity>())
    val publisher by context.container.settings.defaultPublisher.collectAsState(initial = "")

    var target by remember { mutableStateOf<String?>(null) }   // chosen pack id
    var pending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var askNewPack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    // Make sure a pack exists, then run the action with its id.
    fun withPack(action: (String) -> Unit) {
        val chosen = target
        if (chosen != null && packs.any { it.id == chosen }) action(chosen)
        else {
            pending = action
            askNewPack = true
        }
    }

    fun openEditor(uri: Uri) {
        scope.launch {
            busy = true
            val name = importSourcePicture(context, uri)
            busy = false
            if (name == null) errorRes = R.string.err_load_failed
            else withPack { onEditor(name, it) }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::openEditor) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::openEditor) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        val u = cameraUri
        if (ok && f != null && u != null) openEditor(u) else f?.delete()
    }
    val batch = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) withPack { packId ->
            scope.launch {
                busy = true
                report = StickerImport.addImages(context, uris, packId)
                busy = false
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.create_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.create_target), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs, key = { it.id }) { p ->
                FilterChip(selected = target == p.id, onClick = { target = p.id }, label = { Text(p.name) })
            }
            item {
                FilterChip(selected = false, onClick = { pending = null; askNewPack = true }, label = { Text("+ " + stringResource(R.string.new_pack)) })
            }
        }

        ActionCard(R.string.create_gallery, R.string.create_gallery_hint) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        ActionCard(R.string.create_files, R.string.create_files_hint) { files.launch(arrayOf("image/*")) }
        ActionCard(R.string.create_camera, R.string.create_camera_hint) {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val u = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            cameraFile = f
            cameraUri = u
            camera.launch(u)
        }
        ActionCard(R.string.create_batch, R.string.create_batch_hint) {
            batch.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        ActionCard(R.string.create_blank, R.string.create_blank_hint) { withPack { onBlankEditor(it) } }
        ActionCard(R.string.create_animated, R.string.create_animated_hint) { withPack { onAnimated(it) } }
    }

    if (busy) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
    }
    if (askNewPack) {
        PackInfoDialog(
            title = stringResource(R.string.new_pack),
            initialName = "",
            initialPublisher = publisher,
            onConfirm = { name, pub ->
                askNewPack = false
                scope.launch {
                    val id = repo.createPack(name.trim(), pub.trim())
                    target = id
                    pending?.invoke(id)
                    pending = null
                }
            },
            onDismiss = { askNewPack = false; pending = null },
        )
    }
    report?.let { r ->
        val text = buildString {
            append(stringResource(R.string.import_done, r.added))
            if (r.failed > 0) append("\n").append(stringResource(R.string.import_failed, r.failed))
            r.stoppedBecause?.let { append("\n").append(stringResource(it)) }
        }
        MessageDialog(stringResource(R.string.create_batch), text) {
            report = null
            target?.let(onOpenPack)
        }
    }
    errorRes?.let { MessageDialog(stringResource(R.string.create_title), stringResource(it)) { errorRes = null } }
}

@Composable
private fun ActionCard(title: Int, hint: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
