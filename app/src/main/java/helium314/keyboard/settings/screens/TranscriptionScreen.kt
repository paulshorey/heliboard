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

    var sonioxApiKey by remember {
        mutableStateOf(TranscriptionPreferences.readSonioxApiKey(prefs))
    }
    var sonioxDiarization by remember {
        mutableStateOf(TranscriptionPreferences.readSonioxDiarization(prefs))
    }
    var sonioxRemoveCommas by remember {
        mutableStateOf(TranscriptionPreferences.readSonioxRemoveCommas(prefs))
    }
    var sonioxEnableEndpointDetection by remember {
        mutableStateOf(TranscriptionPreferences.readSonioxEnableEndpointDetection(prefs))
    }
    var sonioxMaxEndpointDelayMs by remember {
        mutableStateOf(
            TranscriptionPreferences.readSonioxMaxEndpointDelayMs(prefs).toString()
        )
    }
    var chunkSilenceSeconds by remember {
        mutableStateOf(
            prefs.getInt(
                Settings.PREF_VOICE_CHUNK_SILENCE_SECONDS,
                Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS
            ).toString()
        )
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
                InlineTextField(
                    label = stringResource(R.string.soniox_api_key_title),
                    value = sonioxApiKey,
                    onValueChange = { newValue ->
                        sonioxApiKey = newValue.trim()
                        TranscriptionPreferences.writeSonioxApiKey(prefs, newValue)
                    },
                    minLines = 1,
                    maxLines = 2
                )
                BooleanSettingRow(
                    label = stringResource(R.string.soniox_diarization_title),
                    summary = stringResource(R.string.soniox_diarization_summary),
                    checked = sonioxDiarization,
                    onCheckedChange = { checked ->
                        sonioxDiarization = checked
                        TranscriptionPreferences.writeSonioxDiarization(prefs, checked)
                    }
                )
                BooleanSettingRow(
                    label = stringResource(R.string.soniox_remove_commas_title),
                    summary = stringResource(R.string.soniox_remove_commas_summary),
                    checked = sonioxRemoveCommas,
                    onCheckedChange = { checked ->
                        sonioxRemoveCommas = checked
                        TranscriptionPreferences.writeSonioxRemoveCommas(prefs, checked)
                    }
                )
                Preference(
                    name = stringResource(R.string.soniox_context_terms_title),
                    description = stringResource(R.string.soniox_context_terms_summary),
                    onClick = {
                        SettingsDestination.navigateTo(SettingsDestination.SonioxContextTerms)
                    },
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.voice_diagnostics_title),
                    description = stringResource(R.string.voice_diagnostics_summary),
                    onClick = {
                        SettingsDestination.navigateTo(SettingsDestination.VoiceDiagnostics)
                    },
                ) { NextScreenIcon() }
                BooleanSettingRow(
                    label = stringResource(R.string.soniox_enable_endpoint_detection_title),
                    summary = stringResource(R.string.soniox_enable_endpoint_detection_summary),
                    checked = sonioxEnableEndpointDetection,
                    onCheckedChange = { checked ->
                        sonioxEnableEndpointDetection = checked
                        TranscriptionPreferences.writeSonioxEnableEndpointDetection(prefs, checked)
                    }
                )
                InlineTextField(
                    label = stringResource(R.string.soniox_max_endpoint_delay_ms_title),
                    summary = stringResource(R.string.soniox_max_endpoint_delay_ms_summary),
                    value = sonioxMaxEndpointDelayMs,
                    onValueChange = { newValue ->
                        sonioxMaxEndpointDelayMs = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            TranscriptionPreferences.writeSonioxMaxEndpointDelayMs(prefs, parsed)
                        }
                    },
                    minLines = 1,
                    maxLines = 1
                )
                InlineTextField(
                    label = stringResource(R.string.voice_chunk_silence_seconds_title),
                    value = chunkSilenceSeconds,
                    onValueChange = { newValue ->
                        chunkSilenceSeconds = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            prefs.edit {
                                putInt(
                                    Settings.PREF_VOICE_CHUNK_SILENCE_SECONDS,
                                    parsed.coerceIn(1, 30)
                                )
                            }
                        }
                    },
                    minLines = 1,
                    maxLines = 1
                )

                InlineTextField(
                    label = stringResource(R.string.voice_silence_threshold_title),
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
    maxLines: Int = 3
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
