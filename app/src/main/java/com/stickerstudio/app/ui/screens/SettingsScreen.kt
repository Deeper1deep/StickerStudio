package com.stickerstudio.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.stickerstudio.app.BuildConfig
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenUpdates: () -> Unit) {
    val context = LocalContext.current
    val settings = context.container.settings
    val scope = rememberCoroutineScope()
    val theme by settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
    val auto by settings.autoUpdateCheck.collectAsState(initial = true)
    val savedPublisher by settings.defaultPublisher.collectAsState(initial = "")
    var publisher by remember { mutableStateOf("") }
    LaunchedEffect(savedPublisher) { if (publisher.isEmpty()) publisher = savedPublisher }

    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall)

        Header(R.string.settings_theme)
        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        ).forEach { (mode, label) ->
            Choice(stringResource(label), theme == mode) { scope.launch { settings.setTheme(mode) } }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Header(R.string.settings_language)
        listOf("" to R.string.lang_system, "ar" to R.string.lang_ar, "en" to R.string.lang_en).forEach { (tag, label) ->
            Choice(stringResource(label), currentLang == tag) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Header(R.string.settings_publisher)
        OutlinedTextField(
            value = publisher,
            onValueChange = { publisher = it.take(60); scope.launch { settings.setDefaultPublisher(it.take(60)) } },
            label = { Text(stringResource(R.string.pack_publisher)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Header(R.string.settings_updates)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_auto_update), Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { scope.launch { settings.setAutoUpdateCheck(it) } })
        }
        OutlinedButton(onClick = onOpenUpdates, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.settings_open_updates)) }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Header(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp))
    }
}
