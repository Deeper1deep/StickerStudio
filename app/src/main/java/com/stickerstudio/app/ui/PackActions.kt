package com.stickerstudio.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.data.PackRepository
import com.stickerstudio.app.whatsapp.IssueCode
import com.stickerstudio.app.whatsapp.PackIssue
import com.stickerstudio.app.whatsapp.WaSyncStore
import com.stickerstudio.app.whatsapp.WhatsAppIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared logic for "Add to WhatsApp" and "Export ZIP", used by the home screen, the packs
 * list and the pack screen. The real integration path is [addToWhatsApp]; ZIP is only a backup.
 */
class PackActionsController(
    private val context: Context,
    private val repo: PackRepository,
    private val scope: CoroutineScope,
) {
    var issues by mutableStateOf<List<PackIssue>?>(null)
    var chooser by mutableStateOf<Pair<PackEntity, List<String>>?>(null)
    var info by mutableStateOf<Int?>(null)
    var infoDetail by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    var launchEnable: (Intent) -> Unit = {}
    var launchZip: (String) -> Unit = {}
    private var zipPack: PackEntity? = null
    private var sentPack: PackEntity? = null

    fun addToWhatsApp(packId: String) {
        scope.launch {
            busy = true
            val pack = repo.findPack(packId)
            if (pack == null) {
                busy = false
                return@launch
            }
            val found = WhatsAppIntegration.validate(repo, pack)
            busy = false
            if (found.isNotEmpty()) {
                issues = found
                return@launch
            }
            val targets = WhatsAppIntegration.installedTargets(context)
            when {
                targets.isEmpty() -> info = R.string.wa_not_installed
                targets.size == 1 -> start(pack, targets[0])
                else -> chooser = pack to targets
            }
        }
    }

    fun start(pack: PackEntity, target: String) {
        chooser = null
        sentPack = pack
        try {
            launchEnable(WhatsAppIntegration.enableIntent(context, pack, target))
        } catch (e: ActivityNotFoundException) {
            info = R.string.wa_not_installed
        }
    }

    fun onEnableResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            sentPack?.let { WaSyncStore.record(context, it.id, it.imageDataVersion) }
            info = R.string.wa_added
        } else {
            val error = data?.getStringExtra("validation_error")
            infoDetail = error
            info = if (error != null) R.string.wa_rejected else R.string.wa_cancelled
        }
    }

    fun exportZip(packId: String) {
        scope.launch {
            val pack = repo.findPack(packId) ?: return@launch
            zipPack = pack
            val safe = pack.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "stickers" }
            launchZip("$safe.wastickers")
        }
    }

    /** Writes the pack to cache and opens the share sheet so it can be sent to a friend as a .wastickers file. */
    fun sendFile(packId: String) {
        scope.launch {
            val pack = repo.findPack(packId) ?: return@launch
            busy = true
            try {
                val safe = pack.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "stickers" }
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    java.io.File(dir, "$safe.wastickers").also { f ->
                        f.outputStream().use { WhatsAppIntegration.writeZip(repo, pack, it) }
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                info = R.string.zip_failed
            }
            busy = false
        }
    }

    fun onZipUri(uri: Uri?) {
        val pack = zipPack
        zipPack = null
        if (uri == null || pack == null) return
        scope.launch {
            busy = true
            info = try {
                context.contentResolver.openOutputStream(uri)?.use { WhatsAppIntegration.writeZip(repo, pack, it) }
                R.string.zip_done
            } catch (e: Exception) {
                R.string.zip_failed
            }
            busy = false
        }
    }
}

@Composable
fun rememberPackActions(): PackActionsController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { PackActionsController(context, context.container.repository, scope) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.onEnableResult(it.resultCode, it.data)
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-wastickers")) {
        controller.onZipUri(it)
    }
    controller.launchEnable = { enableLauncher.launch(it) }
    controller.launchZip = { zipLauncher.launch(it) }
    return controller
}

/** Dialogs driven by [PackActionsController]. Put this once on every screen that uses it. */
@Composable
fun PackActionsDialogs(c: PackActionsController) {
    val context = LocalContext.current

    if (c.busy) {
        Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
    }

    c.issues?.let { list ->
        AlertDialog(
            onDismissRequest = { c.issues = null },
            title = { Text(stringResource(R.string.pack_not_ready)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.pack_not_ready_hint), Modifier.padding(bottom = 8.dp))
                    list.forEach { Text("• " + issueMessage(context, it), Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { c.issues = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    c.chooser?.let { (pack, targets) ->
        AlertDialog(
            onDismissRequest = { c.chooser = null },
            title = { Text(stringResource(R.string.choose_whatsapp)) },
            text = {
                Column {
                    targets.forEach { target ->
                        TextButton(onClick = { c.start(pack, target) }) {
                            Text(
                                stringResource(
                                    if (target == WhatsAppIntegration.PACKAGE_BUSINESS) R.string.whatsapp_business else R.string.whatsapp,
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { c.chooser = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    c.info?.let { res ->
        AlertDialog(
            onDismissRequest = { c.info = null; c.infoDetail = null },
            text = {
                Column {
                    Text(stringResource(res))
                    c.infoDetail?.let { Text(it, Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { c.info = null; c.infoDetail = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

fun issueMessage(context: Context, i: PackIssue): String {
    val kb = { bytes: Long -> bytes / 1024 }
    return when (i.code) {
        IssueCode.NAME_EMPTY -> context.getString(R.string.issue_name_empty)
        IssueCode.NAME_TOO_LONG -> context.getString(R.string.issue_name_long, i.limit)
        IssueCode.PUBLISHER_EMPTY -> context.getString(R.string.issue_publisher_empty)
        IssueCode.TOO_FEW_STICKERS -> context.getString(R.string.issue_too_few, i.limit, i.value)
        IssueCode.TOO_MANY_STICKERS -> context.getString(R.string.issue_too_many, i.limit, i.value)
        IssueCode.MIXED_ANIMATION -> context.getString(R.string.issue_mixed)
        IssueCode.FILE_MISSING -> context.getString(R.string.issue_file_missing, i.stickerNumber)
        IssueCode.FILE_CORRUPT -> context.getString(R.string.issue_file_corrupt, i.stickerNumber)
        IssueCode.BAD_DIMENSIONS -> context.getString(R.string.issue_dimensions, i.stickerNumber)
        IssueCode.TOO_LARGE -> context.getString(R.string.issue_too_large, i.stickerNumber, kb(i.value), kb(i.limit))
        IssueCode.DURATION_TOO_LONG -> context.getString(R.string.issue_duration, i.stickerNumber)
        IssueCode.FRAME_TOO_SHORT -> context.getString(R.string.issue_frame_short, i.stickerNumber)
        IssueCode.EMOJI_MISSING -> context.getString(R.string.issue_emoji_missing, i.stickerNumber)
        IssueCode.EMOJI_TOO_MANY -> context.getString(R.string.issue_emoji_many, i.stickerNumber)
        IssueCode.TRAY_MISSING -> context.getString(R.string.issue_tray_missing)
        IssueCode.TRAY_INVALID -> context.getString(R.string.issue_tray_invalid)
    }
}

enum class WaState { NOT_ADDED, ADDED, NEEDS_UPDATE }

/**
 * Where the pack stands in WhatsApp: not added, added, or added but edited since. It is checked
 * again each time the app comes back to the front, so removing the pack in WhatsApp brings the
 * button back.
 */
@Composable
fun rememberWaState(pack: PackEntity): WaState {
    val context = LocalContext.current
    val packId = pack.id
    var added by remember(packId) { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(packId, tick) {
        added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WhatsAppIntegration.installedTargets(context).any { WhatsAppIntegration.isPackAdded(context, it, packId) }
        }
    }
    if (!added) return WaState.NOT_ADDED
    val last = WaSyncStore.lastSent(context, packId)
    return if (last != null && last != pack.imageDataVersion) WaState.NEEDS_UPDATE else WaState.ADDED
}

/** "Add to WhatsApp", "Update in WhatsApp" or the "Added" mark, depending on [rememberWaState]. */
@Composable
fun WhatsAppActionButton(pack: PackEntity, actions: PackActionsController, modifier: Modifier = Modifier) {
    val state = rememberWaState(pack)
    if (state == WaState.ADDED) {
        androidx.compose.foundation.layout.Box(
            modifier.heightIn(min = 48.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            com.stickerstudio.app.ui.components.StatusPill(
                stringResource(R.string.pack_added_wa),
                androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    } else {
        androidx.compose.material3.Button(
            onClick = { actions.addToWhatsApp(pack.id) },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSecondary,
            ),
            modifier = modifier,
        ) {
            Text(
                stringResource(if (state == WaState.NEEDS_UPDATE) R.string.pack_update_wa else R.string.add_to_whatsapp),
                maxLines = 1,
            )
        }
    }
}
