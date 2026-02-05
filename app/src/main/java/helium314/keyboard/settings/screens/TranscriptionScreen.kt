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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import helium314.keyboard.settings.preferences.TextInputPreference
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

    // Load prompt state
    var selectedIndex by remember {
        mutableIntStateOf(prefs.getInt(Settings.PREF_TRANSCRIPTION_PROMPT_SELECTED, Defaults.PREF_TRANSCRIPTION_PROMPT_SELECTED))
    }

    val prompts = remember {
        mutableStateListOf<String>().apply {
            for (i in 0 until Settings.TRANSCRIPTION_PROMPT_COUNT) {
                val key = Settings.PREF_TRANSCRIPTION_PROMPT_PREFIX + i
                val defaultValue = Defaults.PREF_TRANSCRIPTION_PROMPTS.getOrElse(i) { "" }
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
                // API Key setting
                SettingsActivity.settingsContainer[Settings.PREF_DEEPGRAM_API_KEY]?.Preference()

                // Prompt presets - inline editable
                for (i in 0 until Settings.TRANSCRIPTION_PROMPT_COUNT) {
                    PromptPresetItem(
                        prompt = prompts[i],
                        isSelected = selectedIndex == i,
                        onSelected = {
                            selectedIndex = i
                            prefs.edit { putInt(Settings.PREF_TRANSCRIPTION_PROMPT_SELECTED, i) }
                        },
                        onPromptChanged = { newPrompt ->
                            prompts[i] = newPrompt
                            val key = Settings.PREF_TRANSCRIPTION_PROMPT_PREFIX + i
                            prefs.edit { putString(key, newPrompt) }
                        }
                    )
                }
            }
        }
    }
}

fun createTranscriptionSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_DEEPGRAM_API_KEY, R.string.deepgram_api_key_title, R.string.deepgram_api_key_summary) { setting ->
        TextInputPreference(setting, Defaults.PREF_DEEPGRAM_API_KEY)
    },
)

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
