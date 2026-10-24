package com.stickerstudio.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.stickerstudio.app.ui.screens.CreateScreen
import com.stickerstudio.app.ui.screens.EditorScreen
import com.stickerstudio.app.ui.screens.HomeScreen
import com.stickerstudio.app.ui.screens.PackDetailScreen
import com.stickerstudio.app.ui.screens.PacksScreen
import com.stickerstudio.app.ui.screens.SettingsScreen
import com.stickerstudio.app.ui.screens.UpdatesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val PACKS = "packs"
    const val SETTINGS = "settings"
    const val UPDATES = "updates"
    const val PACK = "pack/{packId}"
    const val EDITOR = "editor?source={source}&pack={pack}&sticker={sticker}&draft={draft}"
    const val ANIMATED = "animated?pack={pack}"

    fun pack(id: String) = "pack/$id"
    fun editor(source: String = "", pack: String = "", sticker: String = "", draft: String = "") =
        "editor?source=${Uri.encode(source)}&pack=${Uri.encode(pack)}&sticker=${Uri.encode(sticker)}&draft=${Uri.encode(draft)}"
    fun animated(pack: String = "") = "animated?pack=${Uri.encode(pack)}"
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
    val tabs = listOf(
        Tab(Routes.HOME, R.string.nav_home),
        Tab(Routes.CREATE, R.string.nav_create),
        Tab(Routes.PACKS, R.string.nav_packs),
        Tab(Routes.SETTINGS, R.string.nav_settings),
    )
    val showBar = tabs.any { it.route == current }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { goTab(nav, tab.route) },
                            icon = {
                                when (tab.route) {
                                    Routes.HOME -> Icon(Icons.Filled.Home, null)
                                    Routes.CREATE -> Icon(Icons.Filled.Add, null)
                                    Routes.PACKS -> Icon(painterResource(R.drawable.ic_packs), null)
                                    else -> Icon(Icons.Filled.Settings, null)
                                }
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onCreate = { goTab(nav, Routes.CREATE) },
                    onOpenPack = { nav.navigate(Routes.pack(it)) },
                    onOpenDraft = { nav.navigate(Routes.editor(draft = it)) },
                    onOpenPacks = { goTab(nav, Routes.PACKS) },
                    onOpenUpdates = { nav.navigate(Routes.UPDATES) },
                )
            }
            composable(Routes.CREATE) {
                CreateScreen(
                    onEditor = { source, pack -> nav.navigate(Routes.editor(source = source, pack = pack)) },
                    onBlankEditor = { pack -> nav.navigate(Routes.editor(pack = pack)) },
                    onAnimated = { pack -> nav.navigate(Routes.animated(pack)) },
                    onOpenPack = { nav.navigate(Routes.pack(it)) },
                )
            }
            composable(Routes.PACKS) { PacksScreen(onOpenPack = { nav.navigate(Routes.pack(it)) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onOpenUpdates = { nav.navigate(Routes.UPDATES) }) }
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
                    onAnimated = { nav.navigate(Routes.animated(id)) },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf("source", "pack", "sticker", "draft").map {
                    navArgument(it) { type = NavType.StringType; defaultValue = "" }
                },
            ) { entry ->
                val a = entry.arguments
                EditorScreen(
                    sourceName = a?.getString("source").orEmpty(),
                    packId = a?.getString("pack").orEmpty(),
                    stickerId = a?.getString("sticker").orEmpty(),
                    draftId = a?.getString("draft").orEmpty(),
                    onClose = { nav.popBackStack() },
                )
            }
            composable(
                Routes.ANIMATED,
                arguments = listOf(navArgument("pack") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                AnimatedScreen(
                    packId = entry.arguments?.getString("pack").orEmpty(),
                    onClose = { nav.popBackStack() },
                )
            }
        }
    }
}

private fun goTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
