// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.previewDark

@Composable
fun TranscriptionScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val b = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    var maiApiKey by remember { mutableStateOf(TranscriptionPreferences.readMaiApiKey(prefs)) }
    var maiRegion by remember { mutableStateOf(TranscriptionPreferences.readMaiRegion(prefs)) }
    var maiAutoDetectLanguage by remember {
        mutableStateOf(TranscriptionPreferences.readMaiAutoDetectLanguage(prefs))
    }
    var chunkSilenceMs by remember {
        mutableStateOf(TranscriptionPreferences.readVoiceChunkSilenceMs(prefs).toString())
    }
    var silenceThreshold by remember {
        mutableStateOf(
            prefs.getInt(
                Settings.PREF_VOICE_SILENCE_THRESHOLD,
                Defaults.PREF_VOICE_SILENCE_THRESHOLD
            ).toString()
        )
    }
    var autoStopSilenceSeconds by remember {
        mutableStateOf(
            prefs.getInt(
                Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
                Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS
            ).toString()
        )
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_transcription),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                Text(
                    text = stringResource(R.string.mai_transcription_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                InlineTextField(
                    label = stringResource(R.string.mai_region_title),
                    summary = stringResource(R.string.mai_region_summary),
                    value = maiRegion,
                    onValueChange = { maiRegion = it; TranscriptionPreferences.writeMaiRegion(prefs, it) },
                    maxLines = 1,
                )
                InlineTextField(
                    label = stringResource(R.string.mai_api_key_title),
                    summary = stringResource(R.string.mai_api_key_summary),
                    value = maiApiKey,
                    onValueChange = { maiApiKey = it; TranscriptionPreferences.writeMaiApiKey(prefs, it) },
                    maxLines = 1,
                    secret = true,
                )
                Preference(
                    name = stringResource(R.string.voice_diagnostics_title),
                    description = stringResource(R.string.voice_diagnostics_summary),
                    onClick = {
                        SettingsDestination.navigateTo(SettingsDestination.VoiceDiagnostics)
                    },
                ) { NextScreenIcon() }
                BooleanSettingRow(
                    label = stringResource(R.string.mai_auto_detect_language_title),
                    summary = stringResource(R.string.mai_auto_detect_language_summary),
                    checked = maiAutoDetectLanguage,
                    onCheckedChange = {
                        maiAutoDetectLanguage = it
                        TranscriptionPreferences.writeMaiAutoDetectLanguage(prefs, it)
                    },
                )
                InlineTextField(
                    label = stringResource(R.string.voice_chunk_silence_ms_title),
                    summary = stringResource(R.string.voice_chunk_silence_ms_summary),
                    value = chunkSilenceMs,
                    onValueChange = { newValue ->
                        chunkSilenceMs = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            TranscriptionPreferences.writeVoiceChunkSilenceMs(prefs, parsed)
                        }
                    },
                    keyboardType = KeyboardType.Number,
                    minLines = 1,
                    maxLines = 1
                )

                InlineTextField(
                    label = stringResource(R.string.voice_silence_threshold_title),
                    keyboardType = KeyboardType.Number,
                    value = silenceThreshold,
                    onValueChange = { newValue ->
                        silenceThreshold = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            prefs.edit {
                                putInt(
                                    Settings.PREF_VOICE_SILENCE_THRESHOLD,
                                    parsed.coerceIn(40, 5000)
                                )
                            }
                        }
                    },
                    minLines = 1,
                    maxLines = 1
                )

                InlineTextField(
                    label = stringResource(R.string.voice_auto_stop_silence_seconds_title),
                    keyboardType = KeyboardType.Number,
                    value = autoStopSilenceSeconds,
                    onValueChange = { newValue ->
                        autoStopSilenceSeconds = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            prefs.edit {
                                putInt(
                                    Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
                                    parsed.coerceIn(5, 300)
                                )
                            }
                        }
                    },
                    minLines = 1,
                    maxLines = 1
                )

                FullappDraftHistorySections()
            }
        }
    }
}

@Composable
private fun BooleanSettingRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

// Settings are handled inline in the screen
fun createTranscriptionSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.VOICE_DIAGNOSTICS, R.string.voice_diagnostics_title, R.string.voice_diagnostics_summary) { setting ->
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.VoiceDiagnostics) },
        ) { NextScreenIcon() }
    },
)

@Composable
private fun InlineTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    summary: String? = null,
    minLines: Int = 1,
    maxLines: Int = 3,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = if (summary != null) 2.dp else 4.dp)
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = MaterialTheme.shapes.small,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                autoCorrectEnabled = false,
            ),
        )
    }
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TranscriptionScreen { }
        }
    }
}
