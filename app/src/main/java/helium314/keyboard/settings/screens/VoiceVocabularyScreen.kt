// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

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
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.GeminiTranscriptionClient
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.previewDark

/**
 * Settings screen for editing the user's custom Gemini `customVocabulary` list.
 *
 * The screen shows the built-in product/technical terms (read-only — they always
 * ship with the app) and a free-form editor where the user can add their own
 * terms, one per line. Both lists are merged, deduplicated, and sent to Gemini at
 * session start as `inputAudioTranscription.customVocabulary`, which biases
 * recognition toward the correct spelling and casing for those words.
 */
@Composable
fun VoiceVocabularyScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val b = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    var customVocabularyRaw by remember {
        mutableStateOf(TranscriptionPreferences.readGeminiCustomVocabularyRaw(prefs))
    }
    val parsedCount = remember(customVocabularyRaw) {
        TranscriptionPreferences.parseCustomVocabulary(customVocabularyRaw).size
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.voice_vocabulary_title),
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
                    text = stringResource(R.string.voice_vocabulary_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.voice_vocabulary_builtin_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    text = GeminiTranscriptionClient.defaultVocabulary()
                        .joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.voice_vocabulary_builtin_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
                )

                Text(
                    text = stringResource(R.string.voice_vocabulary_user_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.voice_vocabulary_user_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )

                OutlinedTextField(
                    value = customVocabularyRaw,
                    onValueChange = { newValue ->
                        customVocabularyRaw = newValue
                        TranscriptionPreferences.writeGeminiCustomVocabulary(prefs, newValue)
                    },
                    label = { Text(stringResource(R.string.voice_vocabulary_user_field_label)) },
                    placeholder = { Text(stringResource(R.string.voice_vocabulary_user_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 16,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.small,
                )

                Text(
                    text = stringResource(R.string.voice_vocabulary_user_count, parsedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp)
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
            VoiceVocabularyScreen { }
        }
    }
}
