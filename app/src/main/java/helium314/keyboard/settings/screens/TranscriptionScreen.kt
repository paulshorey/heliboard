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
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.Theme
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

    // API keys
    var speechmaticsApiKey by remember {
        mutableStateOf(TranscriptionPreferences.readSpeechmaticsApiKey(prefs))
    }
    var speechmaticsMaxDelay by remember {
        mutableStateOf(
            TranscriptionPreferences.readSpeechmaticsMaxDelaySeconds(prefs).toString()
        )
    }
    var speechmaticsEndOfUtterance by remember {
        mutableStateOf(
            TranscriptionPreferences.readSpeechmaticsEndOfUtteranceSeconds(prefs).toString()
        )
    }
    var speechmaticsRemoveDisfluencies by remember {
        mutableStateOf(TranscriptionPreferences.readSpeechmaticsRemoveDisfluencies(prefs))
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
    var newParagraphSilenceSeconds by remember {
        mutableStateOf(
            prefs.getInt(
                Settings.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS,
                Defaults.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS
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
                // Speechmatics API Key
                InlineTextField(
                    label = stringResource(R.string.speechmatics_api_key_title),
                    value = speechmaticsApiKey,
                    onValueChange = { newValue ->
                        speechmaticsApiKey = newValue.trim()
                        TranscriptionPreferences.writeSpeechmaticsApiKey(prefs, newValue)
                    },
                    minLines = 1,
                    maxLines = 2
                )
                InlineTextField(
                    label = stringResource(R.string.speechmatics_max_delay_title),
                    value = speechmaticsMaxDelay,
                    onValueChange = { newValue ->
                        speechmaticsMaxDelay = newValue
                        newValue.toDoubleOrNull()?.let { parsed ->
                            val sanitized = TranscriptionPreferences.sanitizeSpeechmaticsMaxDelaySeconds(parsed)
                            TranscriptionPreferences.writeSpeechmaticsMaxDelayTenths(
                                prefs,
                                (sanitized * 10).toInt()
                            )
                            val currentEndOfUtterance =
                                TranscriptionPreferences.readSpeechmaticsEndOfUtteranceSeconds(prefs)
                            val adjustedEndOfUtterance = minOf(
                                currentEndOfUtterance,
                                TranscriptionPreferences.maxEndOfUtteranceForMaxDelay(sanitized)
                            )
                            if (adjustedEndOfUtterance != currentEndOfUtterance) {
                                TranscriptionPreferences.writeSpeechmaticsEndOfUtteranceMs(
                                    prefs,
                                    (adjustedEndOfUtterance * 1000).toInt()
                                )
                                speechmaticsEndOfUtterance = adjustedEndOfUtterance.toString()
                            }
                        }
                    },
                    minLines = 1,
                    maxLines = 1
                )
                InlineTextField(
                    label = stringResource(R.string.speechmatics_end_of_utterance_title),
                    value = speechmaticsEndOfUtterance,
                    onValueChange = { newValue ->
                        speechmaticsEndOfUtterance = newValue
                        newValue.toDoubleOrNull()?.let { parsed ->
                            val maxDelay = TranscriptionPreferences.readSpeechmaticsMaxDelaySeconds(prefs)
                            val sanitized = TranscriptionPreferences.sanitizeSpeechmaticsEndOfUtteranceSeconds(
                                value = parsed,
                                maxDelaySeconds = maxDelay
                            )
                            TranscriptionPreferences.writeSpeechmaticsEndOfUtteranceMs(
                                prefs,
                                (sanitized * 1000).toInt()
                            )
                        }
                    },
                    minLines = 1,
                    maxLines = 1
                )
                BooleanSettingRow(
                    label = stringResource(R.string.speechmatics_remove_disfluencies_title),
                    summary = stringResource(R.string.speechmatics_remove_disfluencies_summary),
                    checked = speechmaticsRemoveDisfluencies,
                    onCheckedChange = { checked ->
                        speechmaticsRemoveDisfluencies = checked
                        TranscriptionPreferences.writeSpeechmaticsRemoveDisfluencies(prefs, checked)
                    }
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
                    label = stringResource(R.string.voice_new_paragraph_silence_seconds_title),
                    value = newParagraphSilenceSeconds,
                    onValueChange = { newValue ->
                        newParagraphSilenceSeconds = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            prefs.edit {
                                putInt(
                                    Settings.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS,
                                    parsed.coerceIn(3, 120)
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
fun createTranscriptionSettings(context: Context) = emptyList<Setting>()

@Composable
private fun InlineTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
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
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
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
