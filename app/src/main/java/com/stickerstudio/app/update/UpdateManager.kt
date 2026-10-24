package com.stickerstudio.app.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.stickerstudio.app.BuildConfig
import com.stickerstudio.app.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the updates screen shows. Only states backed by real release data say "available". */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NotConfigured : UpdateUiState
    data object NoRelease : UpdateUiState
    data class UpToDate(val latest: UpdateManifest) : UpdateUiState
    data class Available(val manifest: UpdateManifest, val skipped: Boolean) : UpdateUiState
    data class Failed(val kind: FailureKind, val detail: String?) : UpdateUiState
}

sealed interface DownloadUiState {
    data object None : DownloadUiState
    data class Downloading(val downloaded: Long, val total: Long) : DownloadUiState
    data class Verifying(val dummy: Int = 0) : DownloadUiState
    data class Ready(val file: File, val manifest: UpdateManifest) : DownloadUiState
    data class Failed(val reason: DownloadFailure) : DownloadUiState
}

enum class DownloadFailure { NETWORK, VERIFY_SIZE, VERIFY_HASH, VERIFY_NOT_APK, VERIFY_PACKAGE, VERIFY_NOT_NEWER, VERIFY_SIGNATURE }

/** Application-wide updater: one instance, so the state survives screen changes. */
class UpdateManager(private val app: Application, private val settings: SettingsStore) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val fetcher = HttpsFetcher()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _download = MutableStateFlow<DownloadUiState>(DownloadUiState.None)
    val download: StateFlow<DownloadUiState> = _download.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    val installedVersionName: String get() = BuildConfig.VERSION_NAME
    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** Checks the configured GitHub repository. Never throws. */
    fun check() {
        if (checkJob?.isActive == true) return
        _state.value = UpdateUiState.Checking
        checkJob = scope.launch {
            val checker = UpdateChecker(
                fetcher, BuildConfig.UPDATE_REPO_OWNER, BuildConfig.UPDATE_REPO_NAME,
                BuildConfig.VERSION_CODE, app.packageName,
            )
            val result = withContext(Dispatchers.IO) { checker.check() }
            val skipped = settings.skippedVersionCode.first()
            _state.value = when (result) {
                UpdateCheckResult.NotConfigured -> UpdateUiState.NotConfigured
                UpdateCheckResult.NoRelease -> UpdateUiState.NoRelease
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.latest)
                is UpdateCheckResult.Available ->
                    UpdateUiState.Available(result.manifest, skipped == result.manifest.versionCode)
                is UpdateCheckResult.Failure -> UpdateUiState.Failed(result.kind, result.detail)
            }
        }
    }

    /** Silent check at app start: only when enabled in settings and the repo is configured. */
    fun checkOnStartup() {
        scope.launch {
            if (BuildConfig.UPDATE_REPO_OWNER.isNotBlank() && settings.autoUpdateCheck.first()) check()
        }
    }

    fun skip(manifest: UpdateManifest) {
        scope.launch {
            settings.setSkippedVersionCode(manifest.versionCode)
            _state.update { s -> if (s is UpdateUiState.Available) s.copy(skipped = true) else s }
        }
    }

    fun unskip(manifest: UpdateManifest) {
        scope.launch {
            settings.setSkippedVersionCode(0)
            _state.update { s -> if (s is UpdateUiState.Available) s.copy(skipped = false) else s }
        }
    }

    fun startDownload(manifest: UpdateManifest) {
        if (downloadJob?.isActive == true) return
        _download.value = DownloadUiState.Downloading(0, manifest.sizeBytes)
        downloadJob = scope.launch {
            val dir = File(app.cacheDir, "updates")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }   // never keep stale APKs around
            val target = File(dir, "StickerStudio-${manifest.versionName}.apk")
            try {
                ApkDownloader.download(manifest.apkUrl, target) { done, total ->
                    _download.value = DownloadUiState.Downloading(done, if (total > 0) total else manifest.sizeBytes)
                }
                _download.value = DownloadUiState.Verifying()
                val verdict = withContext(Dispatchers.IO) { ApkVerifier.verify(app, target, manifest) }
                if (verdict == VerifyResult.OK) {
                    _download.value = DownloadUiState.Ready(target, manifest)
                } else {
                    target.delete()
                    _download.value = DownloadUiState.Failed(verdict.toFailure())
                }
            } catch (e: CancellationException) {
                File(dir, target.name + ".part").delete()
                _download.value = DownloadUiState.None
                throw e
            } catch (e: Exception) {
                _download.value = DownloadUiState.Failed(DownloadFailure.NETWORK)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun resetDownload() {
        _download.value = DownloadUiState.None
    }

    private fun VerifyResult.toFailure(): DownloadFailure = when (this) {
        VerifyResult.SIZE_MISMATCH -> DownloadFailure.VERIFY_SIZE
        VerifyResult.HASH_MISMATCH -> DownloadFailure.VERIFY_HASH
        VerifyResult.NOT_AN_APK -> DownloadFailure.VERIFY_NOT_APK
        VerifyResult.WRONG_PACKAGE -> DownloadFailure.VERIFY_PACKAGE
        VerifyResult.NOT_NEWER -> DownloadFailure.VERIFY_NOT_NEWER
        VerifyResult.SIGNATURE_MISMATCH -> DownloadFailure.VERIFY_SIGNATURE
        VerifyResult.OK -> DownloadFailure.NETWORK
    }

    // ------------------------------------------------------------------ installation

    /** True if Android lets this app launch the package installer. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows installs from this app. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Opens Android's own package installer for an APK that already passed every check.
     * The user still has to confirm: nothing is installed silently.
     */
    fun installIntent(context: Context, ready: DownloadUiState.Ready): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
