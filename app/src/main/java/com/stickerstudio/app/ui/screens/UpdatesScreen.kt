package com.stickerstudio.app.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.update.DownloadFailure
import com.stickerstudio.app.update.DownloadUiState
import com.stickerstudio.app.update.FailureKind
import com.stickerstudio.app.update.UpdateUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val updates = context.container.updates
    val state by updates.state.collectAsState()
    val download by updates.download.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_updates)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.update_installed, updates.installedVersionName, updates.installedVersionCode))

            when (val s = state) {
                UpdateUiState.Idle -> Unit
                UpdateUiState.Checking -> Row { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text(stringResource(R.string.update_checking)) }
                UpdateUiState.NotConfigured -> Text(stringResource(R.string.update_not_configured))
                UpdateUiState.NoRelease -> Text(stringResource(R.string.update_no_release))
                is UpdateUiState.UpToDate -> Text(stringResource(R.string.update_up_to_date))
                is UpdateUiState.Failed -> Text(
                    stringResource(
                        when (s.kind) {
                            FailureKind.NETWORK -> R.string.update_err_network
                            FailureKind.RATE_LIMITED -> R.string.update_err_rate
                            FailureKind.SERVER -> R.string.update_err_server
                            FailureKind.INVALID_DATA -> R.string.update_err_invalid
                            FailureKind.NO_MANIFEST -> R.string.update_err_manifest
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                is UpdateUiState.Available -> {
                    Text(stringResource(R.string.update_available_title, s.manifest.versionName), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.update_size_mb, s.manifest.sizeBytes / 1048576.0))
                    if (s.manifest.changelog.isNotBlank()) {
                        Text(stringResource(R.string.update_changelog), style = MaterialTheme.typography.titleSmall)
                        Text(s.manifest.changelog)
                    }
                    DownloadSection(updates, download, s)
                }
            }

            OutlinedButton(onClick = { updates.check() }, enabled = state != UpdateUiState.Checking) {
                Text(stringResource(R.string.update_check_now))
            }
        }
    }
}

@Composable
private fun DownloadSection(
    updates: com.stickerstudio.app.update.UpdateManager,
    download: DownloadUiState,
    available: UpdateUiState.Available,
) {
    val context = LocalContext.current
    when (download) {
        DownloadUiState.None -> {
            Button(onClick = { updates.startDownload(available.manifest) }) { Text(stringResource(R.string.update_download)) }
            if (available.skipped) {
                TextButton(onClick = { updates.unskip(available.manifest) }) { Text(stringResource(R.string.update_unskip)) }
            } else {
                TextButton(onClick = { updates.skip(available.manifest) }) { Text(stringResource(R.string.update_skip)) }
            }
        }
        is DownloadUiState.Downloading -> {
            val fraction = if (download.total > 0) (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("${(fraction * 100).toInt()}%")
            OutlinedButton(onClick = { updates.cancelDownload() }) { Text(stringResource(R.string.cancel)) }
        }
        is DownloadUiState.Verifying -> Row { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text(stringResource(R.string.update_verifying)) }
        is DownloadUiState.Ready -> {
            Text(stringResource(R.string.update_verified))
            Button(onClick = {
                if (!updates.canInstall(context)) {
                    context.startActivity(updates.unknownSourcesIntent(context))
                } else {
                    try {
                        context.startActivity(updates.installIntent(context, download))
                    } catch (e: ActivityNotFoundException) {
                        updates.resetDownload()
                    }
                }
            }) { Text(stringResource(R.string.update_install)) }
            Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall)
        }
        is DownloadUiState.Failed -> {
            Text(
                stringResource(
                    when (download.reason) {
                        DownloadFailure.NETWORK -> R.string.update_err_download
                        DownloadFailure.VERIFY_SIZE -> R.string.update_err_size
                        DownloadFailure.VERIFY_HASH -> R.string.update_err_hash
                        DownloadFailure.VERIFY_NOT_APK -> R.string.update_err_not_apk
                        DownloadFailure.VERIFY_PACKAGE -> R.string.update_err_package
                        DownloadFailure.VERIFY_NOT_NEWER -> R.string.update_err_not_newer
                        DownloadFailure.VERIFY_SIGNATURE -> R.string.update_err_signature
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { updates.resetDownload() }) { Text(stringResource(R.string.update_retry)) }
        }
    }
}
