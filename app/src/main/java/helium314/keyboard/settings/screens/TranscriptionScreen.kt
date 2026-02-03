// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.previewDark

// Predefined prompts for common use cases
private val PROMPT_PRESETS = listOf(
    "" to "None (no prompt)",
    "Use proper capitalization and punctuation." to "Standard (capitalize & punctuate)",
    "Use proper capitalization, punctuation, and paragraph breaks." to "With paragraphs",
    "Transcribe in all lowercase with minimal punctuation." to "Casual/lowercase",
    "Use formal business English with proper grammar and punctuation." to "Formal/business",
    "This is a text message. Use casual, conversational language." to "Text messaging",
    "Include technical terms and programming syntax." to "Technical/coding",
)

@Composable
fun TranscriptionScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val items = listOf(
        Settings.PREF_WHISPER_API_KEY,
        Settings.PREF_WHISPER_PROMPT,
    )

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_transcription),
        settings = items
    )
}

fun createTranscriptionSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_WHISPER_API_KEY, R.string.whisper_api_key_title, R.string.whisper_api_key_summary) { setting ->
        TextInputPreference(setting, Defaults.PREF_WHISPER_API_KEY)
    },
    Setting(context, Settings.PREF_WHISPER_PROMPT, R.string.whisper_prompt_title, R.string.whisper_prompt_summary) { setting ->
        WhisperPromptPreference(setting.key)
    },
)

@Composable
fun WhisperPromptPreference(key: String) {
    val context = LocalContext.current
    val prefs = context.prefs()
    var currentPrompt by remember {
        mutableStateOf(prefs.getString(key, Defaults.PREF_WHISPER_PROMPT) ?: "")
    }
    var customPrompt by remember {
        mutableStateOf(
            if (PROMPT_PRESETS.none { it.first == currentPrompt }) currentPrompt else ""
        )
    }

    val isCustomSelected = PROMPT_PRESETS.none { it.first == currentPrompt } && currentPrompt.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.whisper_prompt_presets_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Preset options
        PROMPT_PRESETS.forEach { (prompt, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = currentPrompt == prompt && !isCustomSelected,
                        onClick = {
                            currentPrompt = prompt
                            prefs.edit { putString(key, prompt) }
                        },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentPrompt == prompt && !isCustomSelected,
                    onClick = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (prompt.isNotEmpty()) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Custom option
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = isCustomSelected,
                    onClick = {
                        if (customPrompt.isNotEmpty()) {
                            currentPrompt = customPrompt
                            prefs.edit { putString(key, customPrompt) }
                        }
                    },
                    role = Role.RadioButton
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isCustomSelected,
                onClick = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.whisper_prompt_custom),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Custom prompt text field
        OutlinedTextField(
            value = customPrompt,
            onValueChange = { newValue ->
                customPrompt = newValue
                if (newValue.isNotEmpty()) {
                    currentPrompt = newValue
                    prefs.edit { putString(key, newValue) }
                }
            },
            label = { Text(stringResource(R.string.whisper_prompt_custom_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, top = 4.dp),
            minLines = 2,
            maxLines = 4,
            enabled = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Current prompt display
        if (currentPrompt.isNotEmpty()) {
            Text(
                text = stringResource(R.string.whisper_prompt_current, currentPrompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
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
