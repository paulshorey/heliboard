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
import helium314.keyboard.latin.voice.VoiceTranscriptionSettings
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
    var deepgramApiKey by remember {
        mutableStateOf(prefs.getString(Settings.PREF_DEEPGRAM_API_KEY, Defaults.PREF_DEEPGRAM_API_KEY) ?: "")
    }
    var chunkSilenceMs by remember {
        mutableStateOf(
            VoiceTranscriptionSettings.readDeepgramEndpointingMs(prefs).toString()
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
                // Deepgram API Key
                InlineTextField(
                    label = stringResource(R.string.deepgram_api_key_title),
                    value = deepgramApiKey,
                    onValueChange = { newValue ->
                        val trimmedValue = newValue.trim()
                        deepgramApiKey = trimmedValue
                        prefs.edit { putString(Settings.PREF_DEEPGRAM_API_KEY, trimmedValue) }
                    },
                    minLines = 1,
                    maxLines = 2
                )
                InlineTextField(
                    label = stringResource(R.string.voice_chunk_silence_ms_title),
                    value = chunkSilenceMs,
                    onValueChange = { newValue ->
                        chunkSilenceMs = newValue
                        newValue.toIntOrNull()?.let { parsed ->
                            prefs.edit {
                                putInt(
                                    Settings.PREF_VOICE_CHUNK_SILENCE_MS,
                                    parsed.coerceIn(
                                        VoiceTranscriptionSettings.MIN_ENDPOINTING_MS,
                                        VoiceTranscriptionSettings.MAX_ENDPOINTING_MS
                                    )
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
