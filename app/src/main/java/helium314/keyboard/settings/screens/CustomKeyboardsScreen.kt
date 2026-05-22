// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.CustomKeyboards
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.previewDark

/**
 * Settings screen for editing custom keyboard presets.
 *
 * The user works directly with the JSON pref. There's an Enable switch, the
 * Apply button validates and saves, and the Reset button restores the seeded
 * default. Multiple presets are supported in the schema; once more than one is
 * defined, the cycle button advances `active` (this is the same operation a
 * future toolbar button will use to switch keyboards on the fly).
 */
@Composable
fun CustomKeyboardsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val b = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val initialRaw = prefs.getString(Settings.PREF_CUSTOM_KEYBOARDS_JSON, Defaults.PREF_CUSTOM_KEYBOARDS_JSON)
        ?: Defaults.PREF_CUSTOM_KEYBOARDS_JSON
    var draft by remember { mutableStateOf(initialRaw) }
    var status by remember { mutableStateOf<String?>(null) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Settings.PREF_USE_CUSTOM_KEYBOARDS, Defaults.PREF_USE_CUSTOM_KEYBOARDS))
    }

    val parsedDoc = remember(draft) { CustomKeyboards.parse(draft) }
    val activeName = remember(draft) {
        parsedDoc?.activePreset?.let { p -> p.name.ifBlank { "#${parsedDoc.active}" } }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_custom_keyboards),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_keyboards_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            prefs.edit().putBoolean(Settings.PREF_USE_CUSTOM_KEYBOARDS, value).apply()
                        }
                    )
                    Text(
                        text = stringResource(R.string.custom_keyboards_enable_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                Text(
                    text = if (activeName != null) stringResource(R.string.custom_keyboards_active_label, activeName)
                    else stringResource(R.string.custom_keyboards_active_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                if (parsedDoc != null && parsedDoc.presets.size > 1) {
                    OutlinedButton(
                        onClick = {
                            val next = (parsedDoc.active + 1) % parsedDoc.presets.size
                            val updated = parsedDoc.copy(active = next)
                            val encoded = CustomKeyboards.encode(updated)
                            draft = encoded
                            prefs.edit().putString(Settings.PREF_CUSTOM_KEYBOARDS_JSON, encoded).apply()
                            status = null
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) { Text(stringResource(R.string.custom_keyboards_cycle)) }
                }

                Text(
                    text = stringResource(R.string.custom_keyboards_editor_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12,
                    maxLines = 30,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    shape = MaterialTheme.shapes.small,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Button(onClick = {
                        val err = CustomKeyboards.validationError(draft)
                        if (err != null) {
                            status = context.getString(R.string.custom_keyboards_error_prefix, err)
                        } else {
                            prefs.edit().putString(Settings.PREF_CUSTOM_KEYBOARDS_JSON, draft).apply()
                            status = context.getString(R.string.custom_keyboards_saved)
                        }
                    }) { Text(stringResource(R.string.custom_keyboards_apply)) }
                    OutlinedButton(onClick = {
                        draft = Defaults.PREF_CUSTOM_KEYBOARDS_JSON
                        prefs.edit().putString(Settings.PREF_CUSTOM_KEYBOARDS_JSON, draft).apply()
                        status = context.getString(R.string.custom_keyboards_reset_done)
                    }) { Text(stringResource(R.string.custom_keyboards_reset)) }
                }

                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.custom_keyboards_format_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            CustomKeyboardsScreen { }
        }
    }
}
