package com.stickerstudio.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.ui.ImportReport
import com.stickerstudio.app.ui.StickerImport
import com.stickerstudio.app.ui.components.MessageDialog
import com.stickerstudio.app.ui.components.PackInfoDialog
import com.stickerstudio.app.ui.importSourcePicture
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One entry point for everything: a picture, a GIF or a video. The app looks at the file and
 * opens the right editor (animated editor for GIF / video, picture editor otherwise).
 */
@Composable
fun CreateScreen(
    onEditor: (source: String, pack: String) -> Unit,
    onBlankEditor: (pack: String) -> Unit,
    onAnimated: (pack: String, source: String, isVideo: Boolean) -> Unit,
    onOpenPack: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = context.container.repository
    val scope = rememberCoroutineScope()
    val packs by repo.observePacks().collectAsState(initial = emptyList<PackEntity>())
    val publisher by context.container.settings.defaultPublisher.collectAsState(initial = "")

    var target by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var askNewPack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun withPack(action: (String) -> Unit) {
        val chosen = target
        if (chosen != null && packs.any { it.id == chosen }) {
            action(chosen)
        } else {
            pending = action
            askNewPack = true
        }
    }

    fun isAnimatedKind(uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri).orEmpty()
        return mime == "image/gif" || mime.startsWith("video/")
    }

    fun openPicture(uri: Uri) {
        scope.launch {
            busy = true
            val name = importSourcePicture(context, uri)
            busy = false
            if (name == null) errorRes = R.string.err_load_failed else withPack { onEditor(name, it) }
        }
    }

    fun openAnimated(uri: Uri) {
        scope.launch {
            busy = true
            val isVideo = context.contentResolver.getType(uri).orEmpty().startsWith("video/")
            val name = withContext(Dispatchers.IO) {
                try {
                    BitmapLoader.copyRaw(context, uri, repo.paths.sources, if (isVideo) "mp4" else "gif")
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (name == null) errorRes = R.string.err_load_failed else withPack { onAnimated(it, name, isVideo) }
        }
    }

    /** Decides by file type what to do with whatever the user picked. */
    fun handlePicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            val uri = uris[0]
            if (isAnimatedKind(uri)) openAnimated(uri) else openPicture(uri)
            return
        }
        val pictures = uris.filterNot(::isAnimatedKind)
        val skipped = uris.size - pictures.size
        withPack { packId ->
            scope.launch {
                busy = true
                report = StickerImport.addImages(context, pictures, packId)
                busy = false
                if (skipped > 0) errorRes = R.string.create_animated_skipped
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { handlePicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { handlePicked(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        val u = cameraUri
        if (ok && f != null && u != null) openPicture(u) else f?.delete()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.create_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.create_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The one big button: picture, GIF or video.
        Card(
            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().height(172.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(60.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.UploadFile, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
                }
                Text(stringResource(R.string.create_pick_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp))
                Text(stringResource(R.string.create_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.create_target), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(packs, key = { it.id }) { p ->
                    FilterChip(selected = target == p.id, onClick = { target = p.id }, label = { Text(p.name) })
                }
                item {
                    FilterChip(selected = false, onClick = { pending = null; askNewPack = true }, label = { Text("+ " + stringResource(R.string.new_pack)) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceTile(Icons.Filled.CameraAlt, R.string.create_camera, Modifier.weight(1f)) {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
                val u = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                cameraFile = f
                cameraUri = u
                camera.launch(u)
            }
            SourceTile(Icons.Filled.FolderOpen, R.string.create_files, Modifier.weight(1f)) { files.launch(arrayOf("image/*", "video/*")) }
            SourceTile(Icons.Filled.TextFields, R.string.create_blank, Modifier.weight(1f)) { withPack { onBlankEditor(it) } }
            SourceTile(Icons.Filled.PhotoLibrary, R.string.create_batch, Modifier.weight(1f)) { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        Text(stringResource(R.string.create_auto_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (busy) androidx.compose.ui.window.Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
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
private fun SourceTile(icon: ImageVector, label: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.height(84.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
