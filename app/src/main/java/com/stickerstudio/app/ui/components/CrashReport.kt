package com.stickerstudio.app.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stickerstudio.app.R
import com.stickerstudio.app.StickerStudioApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** If the app crashed last time, shows the error so the user can send it (only when they tap send). */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            val f = File(context.filesDir, StickerStudioApp.CRASH_FILE)
            if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        }
    }
    val text = report ?: return
    fun clear() {
        File(context.filesDir, StickerStudioApp.CRASH_FILE).delete()
        report = null
    }
    AlertDialog(
        onDismissRequest = { clear() },
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                Text(text.take(2500), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
                clear()
            }) { Text(stringResource(R.string.crash_send)) }
        },
        dismissButton = { TextButton(onClick = { clear() }) { Text(stringResource(R.string.close)) } },
    )
}
