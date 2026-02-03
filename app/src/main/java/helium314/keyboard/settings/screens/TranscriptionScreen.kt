// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.previewDark

// Labels for each prompt preset slot
private val PROMPT_LABELS = listOf(
    R.string.whisper_prompt_none,
    R.string.whisper_prompt_standard,
    R.string.whisper_prompt_paragraphs,
    R.string.whisper_prompt_casual,
    R.string.whisper_prompt_formal,
    R.string.whisper_prompt_texting,
    R.string.whisper_prompt_technical,
)

@Composable
fun TranscriptionScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val b = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_transcription),
        settings = listOf(
            Settings.PREF_WHISPER_API_KEY,
            Settings.PREF_WHISPER_PROMPT_SELECTED
        )
    )
}

fun createTranscriptionSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_WHISPER_API_KEY, R.string.whisper_api_key_title, R.string.whisper_api_key_summary) { setting ->
        TextInputPreference(setting, Defaults.PREF_WHISPER_API_KEY)
    },
    Setting(context, Settings.PREF_WHISPER_PROMPT_SELECTED, R.string.whisper_prompt_title, R.string.whisper_prompt_summary) { setting ->
        WhisperPromptPreference(setting)
    },
)

@Composable
fun WhisperPromptPreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = context.prefs()

    // Get current selection for description
    val selectedIndex = prefs.getInt(Settings.PREF_WHISPER_PROMPT_SELECTED, Defaults.PREF_WHISPER_PROMPT_SELECTED)
    val selectedLabel = stringResource(PROMPT_LABELS.getOrElse(selectedIndex) { R.string.whisper_prompt_none })

    Preference(
        name = setting.title,
        description = selectedLabel,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        WhisperPromptDialog(
            onDismissRequest = { showDialog = false }
        )
    }
}

@Composable
fun WhisperPromptDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.prefs()

    // Load initial values from SharedPreferences
    var selectedIndex by remember {
        mutableIntStateOf(prefs.getInt(Settings.PREF_WHISPER_PROMPT_SELECTED, Defaults.PREF_WHISPER_PROMPT_SELECTED))
    }

    val prompts = remember {
        mutableStateListOf<String>().apply {
            for (i in 0 until Settings.WHISPER_PROMPT_COUNT) {
                val key = Settings.PREF_WHISPER_PROMPT_PREFIX + i
                val defaultValue = Defaults.PREF_WHISPER_PROMPTS.getOrElse(i) { "" }
                add(prefs.getString(key, defaultValue) ?: defaultValue)
            }
        }
    }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = {
            // Save all prompts and selection
            prefs.edit {
                putInt(Settings.PREF_WHISPER_PROMPT_SELECTED, selectedIndex)
                for (i in 0 until Settings.WHISPER_PROMPT_COUNT) {
                    putString(Settings.PREF_WHISPER_PROMPT_PREFIX + i, prompts[i])
                }
            }
        },
        title = { Text(stringResource(R.string.whisper_prompt_presets_title)) },
        scrollContent = true,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Editable prompt presets
                for (i in 0 until Settings.WHISPER_PROMPT_COUNT) {
                    PromptPresetItem(
                        index = i,
                        label = stringResource(PROMPT_LABELS[i]),
                        prompt = prompts[i],
                        isSelected = selectedIndex == i,
                        onSelected = { selectedIndex = i },
                        onPromptChanged = { newPrompt -> prompts[i] = newPrompt }
                    )

                    if (i < Settings.WHISPER_PROMPT_COUNT - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Show currently active prompt
                val activePrompt = prompts.getOrElse(selectedIndex) { "" }
                if (activePrompt.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.whisper_prompt_current, activePrompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.whisper_prompt_none_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

@Composable
private fun PromptPresetItem(
    index: Int,
    label: String,
    prompt: String,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onPromptChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = isSelected,
                    onClick = onSelected,
                    role = Role.RadioButton
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            minLines = 2,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = {
                Text(
                    text = if (index == 0) stringResource(R.string.whisper_prompt_empty_hint) else stringResource(R.string.whisper_prompt_edit_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
