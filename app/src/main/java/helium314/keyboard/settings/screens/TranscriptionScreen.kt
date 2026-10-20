// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.GrokPreferences
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

    var apiKey by remember { mutableStateOf(GrokPreferences.readApiKey(context)) }
    var keySaveError by remember { mutableStateOf(false) }
    val initial = remember { GrokPreferences.readConfig(prefs) }
    var smartTurn by remember { mutableStateOf(initial.smartTurn.toString()) }
    var turnTimeout by remember { mutableStateOf(initial.turnTimeoutMs.toString()) }
    var useEditorContext by remember { mutableStateOf(initial.useEditorContext) }
    var autoDetectLanguage by remember { mutableStateOf(initial.autoDetectLanguage) }
    var formatNumbers by remember { mutableStateOf(initial.format) }
    var semanticCompletion by remember { mutableStateOf(initial.smartTurnEnabled) }
    var forceTurnTimeout by remember { mutableStateOf(initial.forceTurnTimeout) }
    var endpointing by remember { mutableStateOf(initial.endpointingMs.toString()) }
    var nativePunctuation by remember { mutableStateOf(initial.nativePunctuation) }
    var vadThreshold by remember { mutableStateOf(initial.vadThreshold.toString()) }
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
                Text(stringResource(R.string.grok_speaker_summary),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                InlineTextField(
                    label = stringResource(R.string.grok_api_key_title),
                    summary = stringResource(R.string.grok_api_key_summary),
                    value = apiKey,
                    onValueChange = { value ->
                        apiKey = value.filterNot { it.isISOControl() }.trim()
                        keySaveError = runCatching { GrokPreferences.writeApiKey(context, apiKey) }.isFailure
                    },
                    maxLines = 1, secret = true
                )
                if (keySaveError) Text(stringResource(R.string.grok_key_save_error), color = MaterialTheme.colorScheme.error)
                BooleanSettingRow(
                    label = stringResource(R.string.grok_use_editor_context_title),
                    summary = stringResource(R.string.grok_use_editor_context_summary),
                    checked = useEditorContext,
                    onCheckedChange = { useEditorContext = it; prefs.edit { putBoolean(GrokPreferences.EDITOR_CONTEXT, it) } }
                )
                Preference(
                    name = stringResource(R.string.voice_vocabulary_title),
                    description = stringResource(R.string.voice_vocabulary_summary),
                    onClick = {
                        SettingsDestination.navigateTo(SettingsDestination.VoiceVocabulary)
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
                    label = stringResource(R.string.grok_auto_detect_language_title),
                    summary = stringResource(R.string.grok_auto_detect_language_summary),
                    checked = autoDetectLanguage,
                    onCheckedChange = { autoDetectLanguage = it; prefs.edit { putBoolean(GrokPreferences.AUTO_LANGUAGE, it) } }
                )
                BooleanSettingRow(
                    label = stringResource(R.string.grok_format_title),
                    summary = stringResource(R.string.grok_format_summary),
                    checked = formatNumbers,
                    onCheckedChange = { formatNumbers = it; prefs.edit { putBoolean(GrokPreferences.FORMAT, it) } }
                )
                Preference(
                    name = stringResource(R.string.grok_patient_preset_title),
                    description = stringResource(R.string.grok_patient_preset_summary),
                    onClick = {
                        semanticCompletion = true; forceTurnTimeout = true
                        smartTurn = "0.9"; turnTimeout = "5000"; nativePunctuation = true
                        prefs.edit {
                            putBoolean(GrokPreferences.SMART_TURN_ENABLED, true)
                            putBoolean(GrokPreferences.FORCE_TURN_TIMEOUT, true)
                            putString(GrokPreferences.SMART_TURN, "0.9")
                            putInt(GrokPreferences.TURN_TIMEOUT, 5000)
                            putBoolean(GrokPreferences.NATIVE_PUNCTUATION, true)
                        }
                    },
                )
                BooleanSettingRow(
                    label = stringResource(R.string.grok_native_punctuation_title),
                    summary = stringResource(R.string.grok_native_punctuation_summary),
                    checked = nativePunctuation,
                    onCheckedChange = { nativePunctuation = it; prefs.edit { putBoolean(GrokPreferences.NATIVE_PUNCTUATION, it) } }
                )
                BooleanSettingRow(
                    label = stringResource(R.string.grok_semantic_completion_title),
                    summary = stringResource(R.string.grok_semantic_completion_summary),
                    checked = semanticCompletion,
                    onCheckedChange = { semanticCompletion = it; prefs.edit { putBoolean(GrokPreferences.SMART_TURN_ENABLED, it) } }
                )
                if (semanticCompletion) {
                    InlineTextField(
                        label = stringResource(R.string.grok_smart_turn_title),
                        summary = stringResource(R.string.grok_smart_turn_summary),
                        value = smartTurn,
                        onValueChange = { value ->
                            smartTurn = value
                            value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { parsed ->
                                prefs.edit { putString(GrokPreferences.SMART_TURN, parsed.coerceIn(0.0, 1.0).toString()) }
                            }
                        }, maxLines = 1
                    )
                    BooleanSettingRow(
                        label = stringResource(R.string.grok_force_timeout_title),
                        summary = stringResource(R.string.grok_force_timeout_summary),
                        checked = forceTurnTimeout,
                        onCheckedChange = { forceTurnTimeout = it; prefs.edit { putBoolean(GrokPreferences.FORCE_TURN_TIMEOUT, it) } }
                    )
                    if (forceTurnTimeout) {
                        InlineTextField(
                            label = stringResource(R.string.grok_turn_timeout_title),
                            summary = stringResource(R.string.grok_turn_timeout_summary),
                            value = turnTimeout,
                            onValueChange = { value ->
                                turnTimeout = value
                                value.toIntOrNull()?.let { parsed -> prefs.edit { putInt(GrokPreferences.TURN_TIMEOUT, parsed.coerceIn(1, 5000)) } }
                            }, maxLines = 1
                        )
                    }
                } else {
                    InlineTextField(
                        label = stringResource(R.string.grok_endpointing_title),
                        summary = stringResource(R.string.grok_endpointing_summary),
                        value = endpointing,
                        onValueChange = { value ->
                            endpointing = value
                            value.toIntOrNull()?.let { prefs.edit { putInt(GrokPreferences.ENDPOINTING, it.coerceIn(0, 5000)) } }
                        }, maxLines = 1
                    )
                }
                InlineTextField(
                    label = stringResource(R.string.grok_vad_title),
                    summary = stringResource(R.string.grok_vad_summary),
                    value = vadThreshold,
                    onValueChange = { value ->
                        vadThreshold = value
                        value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { parsed ->
                            prefs.edit { putString(GrokPreferences.VAD_THRESHOLD, parsed.coerceIn(0.0, 1.0).toString()) }
                        }
                    }, maxLines = 1
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
    maxLines: Int = 3,
    secret: Boolean = false
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
            singleLine = secret,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
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
