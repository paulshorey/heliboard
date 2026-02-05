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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    // Load API keys and cleanup prompt state
    var openaiApiKey by remember {
        mutableStateOf(prefs.getString(Settings.PREF_WHISPER_API_KEY, Defaults.PREF_WHISPER_API_KEY) ?: "")
    }
    var anthropicApiKey by remember {
        mutableStateOf(prefs.getString(Settings.PREF_ANTHROPIC_API_KEY, Defaults.PREF_ANTHROPIC_API_KEY) ?: "")
    }
    var cleanupPrompt by remember {
        mutableStateOf(prefs.getString(Settings.PREF_CLEANUP_PROMPT, Defaults.PREF_CLEANUP_PROMPT) ?: Defaults.PREF_CLEANUP_PROMPT)
    }

    // Load prompt state
    var selectedIndex by remember {
        mutableIntStateOf(prefs.getInt(Settings.PREF_WHISPER_PROMPT_SELECTED, Defaults.PREF_WHISPER_PROMPT_SELECTED))
    }

    val prompts = remember {
        mutableStateListOf<String>().apply {
            for (i in 0 until Settings.WHISPER_PROMPT_COUNT) {
                val key = Settings.PREF_WHISPER_PROMPT_PREFIX + i
                val defaultValue = Defaults.PREF_TRANSCRIBE_PROMPTS.getOrElse(i) { "" }
                add(prefs.getString(key, defaultValue) ?: defaultValue)
            }
        }
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
                // OpenAI API Key - inline editable
                InlineTextField(
                    label = stringResource(R.string.whisper_api_key_title),
                    value = openaiApiKey,
                    onValueChange = { newValue ->
                        openaiApiKey = newValue
                        prefs.edit { putString(Settings.PREF_WHISPER_API_KEY, newValue) }
                    },
                    minLines = 1,
                    maxLines = 2
                )

                // Anthropic API Key - inline editable
                InlineTextField(
                    label = stringResource(R.string.anthropic_api_key_title),
                    value = anthropicApiKey,
                    onValueChange = { newValue ->
                        anthropicApiKey = newValue
                        prefs.edit { putString(Settings.PREF_ANTHROPIC_API_KEY, newValue) }
                    },
                    minLines = 1,
                    maxLines = 2
                )

                // Cleanup prompt - inline editable
                InlineTextField(
                    label = stringResource(R.string.cleanup_prompt_title),
                    value = cleanupPrompt,
                    onValueChange = { newValue ->
                        cleanupPrompt = newValue
                        prefs.edit { putString(Settings.PREF_CLEANUP_PROMPT, newValue) }
                    },
                    minLines = 4,
                    maxLines = 10
                )

                // Prompt presets - inline editable
                for (i in 0 until Settings.WHISPER_PROMPT_COUNT) {
                    PromptPresetItem(
                        prompt = prompts[i],
                        isSelected = selectedIndex == i,
                        onSelected = {
                            selectedIndex = i
                            prefs.edit { putInt(Settings.PREF_WHISPER_PROMPT_SELECTED, i) }
                        },
                        onPromptChanged = { newPrompt ->
                            prompts[i] = newPrompt
                            val key = Settings.PREF_WHISPER_PROMPT_PREFIX + i
                            prefs.edit { putString(key, newPrompt) }
                        }
                    )
                }
            }
        }
    }
}

// Settings are now handled inline in the screen, so this returns empty list
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

@Composable
private fun PromptPresetItem(
    prompt: String,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onPromptChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Radio button on its own line
        RadioButton(
            selected = isSelected,
            onClick = onSelected,
            modifier = Modifier
                .selectable(
                    selected = isSelected,
                    onClick = onSelected,
                    role = Role.RadioButton
                )
                .padding(start = 4.dp, top = 8.dp, bottom = 0.dp)
        )

        // Text area immediately below, edge to edge
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 12.dp),
            minLines = 3,
            maxLines = 6,
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
