package com.stickerstudio.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.stickerstudio.app.BuildConfig
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.ThemeMode
import com.stickerstudio.app.ui.components.AppCard
import com.stickerstudio.app.ui.components.IconBadge
import com.stickerstudio.app.ui.components.ScreenHeader
import kotlinx.coroutines.launch

private const val CONTACT_URL = "https://wa.me/qr/BTFMWNAXQXM3L1"

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenUpdates: () -> Unit) {
    var showShareQr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showShareQr) com.stickerstudio.app.ui.components.ShareQrDialog(onDismiss = { showShareQr = false })
    val context = LocalContext.current
    val settings = context.container.settings
    val scope = rememberCoroutineScope()
    val theme by settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
    val auto by settings.autoUpdateCheck.collectAsState(initial = true)
    val savedPublisher by settings.defaultPublisher.collectAsState(initial = "")
    var publisher by remember { mutableStateOf("") }
    LaunchedEffect(savedPublisher) { if (publisher.isEmpty()) publisher = savedPublisher }
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(stringResource(R.string.nav_settings), onBack = onBack)

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Group(Icons.Filled.DarkMode, R.string.settings_theme) {
                ChipRow(
                    listOf(
                        Triple(ThemeMode.SYSTEM, R.string.theme_system, theme == ThemeMode.SYSTEM),
                        Triple(ThemeMode.LIGHT, R.string.theme_light, theme == ThemeMode.LIGHT),
                        Triple(ThemeMode.DARK, R.string.theme_dark, theme == ThemeMode.DARK),
                    ),
                ) { mode -> scope.launch { settings.setTheme(mode) } }
            }

            Group(Icons.Filled.Language, R.string.settings_language) {
                ChipRow(
                    listOf(
                        Triple("", R.string.lang_system, currentLang == ""),
                        Triple("ar", R.string.lang_ar, currentLang == "ar"),
                        Triple("en", R.string.lang_en, currentLang == "en"),
                    ),
                ) { tag -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
            }

            Group(Icons.Filled.Person, R.string.settings_publisher) {
                OutlinedTextField(
                    value = publisher,
                    onValueChange = { publisher = it.take(60); scope.launch { settings.setDefaultPublisher(it.take(60)) } },
                    label = { Text(stringResource(R.string.pack_publisher)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Group(Icons.Filled.SystemUpdate, R.string.settings_updates) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_auto_update), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = auto, onCheckedChange = { scope.launch { settings.setAutoUpdateCheck(it) } })
                }
                OutlinedButton(onClick = onOpenUpdates, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_open_updates)) }
                OutlinedButton(onClick = { showShareQr = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_app)) }
            }

            AppCard(
                onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(CONTACT_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: android.content.ActivityNotFoundException) {
                        // no app can open the link: nothing to do
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Filled.Favorite, size = 40.dp)
                        Column {
                            Text(stringResource(R.string.credits_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.credits_made_by) + " @Deeper", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(stringResource(R.string.credits_contact), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.credits_rights), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                stringResource(R.string.settings_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Group(icon: ImageVector, title: Int, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(icon, size = 40.dp)
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun <T> ChipRow(items: List<Triple<T, Int, Boolean>>, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (value, label, selected) ->
            FilterChip(selected = selected, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
        }
    }
}
