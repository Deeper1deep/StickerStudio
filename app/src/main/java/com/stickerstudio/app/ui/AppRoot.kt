package com.stickerstudio.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.ui.screens.AnimatedScreen
import com.stickerstudio.app.ui.screens.EditorScreen
import com.stickerstudio.app.ui.screens.HomeScreen
import com.stickerstudio.app.ui.screens.PackDetailScreen
import com.stickerstudio.app.ui.screens.SettingsScreen
import com.stickerstudio.app.ui.screens.UpdatesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val UPDATES = "updates"
    const val PACK = "pack/{packId}"
    const val EDITOR = "editor?source={source}&pack={pack}&sticker={sticker}&draft={draft}"
    const val ANIMATED = "animated?pack={pack}&source={source}&video={video}&sticker={sticker}"

    fun pack(id: String) = "pack/$id"
    fun editor(source: String = "", pack: String = "", sticker: String = "", draft: String = "") =
        "editor?source=${Uri.encode(source)}&pack=${Uri.encode(pack)}&sticker=${Uri.encode(sticker)}&draft=${Uri.encode(draft)}"
    fun animated(pack: String = "", source: String = "", video: Boolean = false, sticker: String = "") =
        "animated?pack=${Uri.encode(pack)}&source=${Uri.encode(source)}&video=${if (video) "1" else ""}&sticker=${Uri.encode(sticker)}"
}

/** Copies a picked picture into the app's private source folder (re-editable later). Null on failure. */
suspend fun importSourcePicture(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val bmp = BitmapLoader.decode(context, uri)
        val name = BitmapLoader.savePng(context.container.repository.paths.sources, bmp)
        bmp.recycle()
        name
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private data class Tab(val route: String, val label: Int)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = current == Routes.HOME
    com.stickerstudio.app.ui.components.CrashReportDialog()

    // Telegram-style "new version" popup: once per launch, only on the main tabs.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val updates = appContext.container.updates
    val appSettings = appContext.container.settings
    val rootScope = androidx.compose.runtime.rememberCoroutineScope()

    // One-time notification permission (Android 13+), used for update and conversion notifications.
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    fun askNotificationsOnce() {
        rootScope.launch {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !appSettings.askedNotifications.first() &&
                androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                appSettings.setAskedNotifications()
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // "What is new" message, shown once after the app was updated (never on a fresh install).
    var whatsNew by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val seen = appSettings.lastSeenVersionCode.first()
        val now = com.stickerstudio.app.BuildConfig.VERSION_CODE
        if (seen < now) {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val updatedInPlace = info.lastUpdateTime - info.firstInstallTime > 60_000L
            if (seen > 0 || updatedInPlace) whatsNew = true else { appSettings.setLastSeenVersionCode(now); askNotificationsOnce() }
        } else {
            askNotificationsOnce()
        }
    }
    if (whatsNew) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.whats_new_title, com.stickerstudio.app.BuildConfig.VERSION_NAME)) },
            text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { Text(stringResource(R.string.whats_new_body)) } },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    whatsNew = false
                    rootScope.launch {
                        appSettings.setLastSeenVersionCode(com.stickerstudio.app.BuildConfig.VERSION_CODE)
                        askNotificationsOnce()
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
    val updateState by updates.state.collectAsState()
    var updateDismissed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val available = updateState as? com.stickerstudio.app.update.UpdateUiState.Available
    if (available != null && !available.skipped && !updateDismissed && showBar && !whatsNew) {
        val m = available.manifest
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { updateDismissed = true },
            title = { Text(stringResource(R.string.update_available_title, m.versionName)) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(stringResource(R.string.update_size_mb, m.sizeBytes / 1048576.0), style = MaterialTheme.typography.labelLarge)
                    if (m.changelog.isNotBlank()) {
                        Text(m.changelog.take(600), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    updateDismissed = true
                    updates.startDownload(m)
                    nav.navigate(Routes.UPDATES)
                }) { Text(stringResource(R.string.update_now)) }
            },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = { updates.skip(m); updateDismissed = true }) { Text(stringResource(R.string.update_skip)) }
                    androidx.compose.material3.TextButton(onClick = { updateDismissed = true }) { Text(stringResource(R.string.update_later)) }
                }
            },
        )
    }

    Scaffold { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onEditor = { source, pack -> nav.navigate(Routes.editor(source = source, pack = pack)) },
                    onBlankEditor = { pack -> nav.navigate(Routes.editor(pack = pack)) },
                    onAnimated = { pack, source, video -> nav.navigate(Routes.animated(pack, source, video)) },
                    onOpenPack = { nav.navigate(Routes.pack(it)) },
                    onOpenDraft = { nav.navigate(Routes.editor(draft = it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenUpdates = { nav.navigate(Routes.UPDATES) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onOpenUpdates = { nav.navigate(Routes.UPDATES) }) }
            composable(Routes.UPDATES) { UpdatesScreen(onBack = { nav.popBackStack() }) }
            composable(
                Routes.PACK,
                arguments = listOf(navArgument("packId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("packId").orEmpty()
                PackDetailScreen(
                    packId = id,
                    onBack = { nav.popBackStack() },
                    onEditor = { source, sticker -> nav.navigate(Routes.editor(source = source, pack = id, sticker = sticker)) },
                    onEditAnimated = { source, sticker ->
                        nav.navigate(Routes.animated(pack = id, source = source, video = !source.endsWith(".gif"), sticker = sticker))
                    },
                    onAnimated = { source, video -> nav.navigate(Routes.animated(id, source, video)) },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf("source", "pack", "sticker", "draft").map {
                    navArgument(it) { type = NavType.StringType; defaultValue = "" }
                },
            ) { entry ->
                val a = entry.arguments
                // The editor is always dark so the sticker colours stand out.
                com.stickerstudio.app.ui.theme.StickerStudioTheme(darkTheme = true) {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        EditorScreen(
                            sourceName = a?.getString("source").orEmpty(),
                            packId = a?.getString("pack").orEmpty(),
                            stickerId = a?.getString("sticker").orEmpty(),
                            draftId = a?.getString("draft").orEmpty(),
                            onClose = { nav.popBackStack() },
                        )
                    }
                }
            }
            composable(
                Routes.ANIMATED,
                arguments = listOf("pack", "source", "video", "sticker").map {
                    navArgument(it) { type = NavType.StringType; defaultValue = "" }
                },
            ) { entry ->
                val pack = entry.arguments?.getString("pack").orEmpty()
                AnimatedScreen(
                    packId = pack,
                    source = entry.arguments?.getString("source").orEmpty(),
                    isVideo = entry.arguments?.getString("video").orEmpty() == "1",
                    stickerId = entry.arguments?.getString("sticker").orEmpty(),
                    onClose = { nav.popBackStack() },
                    onStatic = { name ->
                        nav.popBackStack()
                        nav.navigate(Routes.editor(source = name, pack = pack))
                    },
                )
            }
        }
    }
}
