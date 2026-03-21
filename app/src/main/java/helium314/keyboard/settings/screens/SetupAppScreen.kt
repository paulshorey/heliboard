// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.previewDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SetupAppScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.getActivity()
    val prefs = context.prefs()
    val appName = stringResource(context.applicationInfo.labelRes)
    val prefChanged = (activity as? SettingsActivity)?.prefChanged?.collectAsState()
    val imm = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    val scope = rememberCoroutineScope()

    var isImeEnabled by remember { mutableStateOf(false) }
    var isImeCurrent by remember { mutableStateOf(false) }
    var microphoneGranted by remember { mutableStateOf(false) }
    var deepgramApiKey by remember { mutableStateOf("") }

    fun refreshStatus() {
        isImeEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(context, imm)
        isImeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)
        microphoneGranted = PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.RECORD_AUDIO)
        deepgramApiKey = prefs.getString(Settings.PREF_DEEPGRAM_API_KEY, Defaults.PREF_DEEPGRAM_API_KEY) ?: ""
    }

    LaunchedEffect(prefChanged?.value) {
        refreshStatus()
    }

    val systemSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshStatus()
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphoneGranted = it
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_setup_app),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.setup_app_breadcrumb),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.setup_app_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.setup_app_checklist_title),
                    style = MaterialTheme.typography.titleMedium
                )

                SetupRequirementItem(
                    title = stringResource(R.string.setup_step1_title, appName),
                    summary = stringResource(R.string.setup_app_enable_keyboard_summary, appName),
                    isComplete = isImeEnabled,
                    actionLabel = stringResource(R.string.setup_app_open_system_settings),
                    onAction = {
                        val intent = Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS).apply {
                            addCategory(Intent.CATEGORY_DEFAULT)
                        }
                        systemSettingsLauncher.launch(intent)
                    }
                )
                SetupRequirementItem(
                    title = stringResource(R.string.setup_step2_title, appName),
                    summary = stringResource(R.string.setup_app_select_keyboard_summary, appName),
                    isComplete = isImeCurrent,
                    actionLabel = stringResource(R.string.setup_app_choose_keyboard),
                    onAction = {
                        imm.showInputMethodPicker()
                        scope.launch {
                            repeat(40) {
                                delay(250)
                                refreshStatus()
                                if (isImeCurrent) return@launch
                            }
                        }
                    }
                )
                SetupRequirementItem(
                    title = stringResource(R.string.deepgram_api_key_title),
                    summary = stringResource(R.string.deepgram_api_key_summary),
                    isComplete = deepgramApiKey.isNotBlank(),
                ) {
                    SetupKeyField(
                        value = deepgramApiKey,
                        onValueChange = { newValue ->
                            deepgramApiKey = newValue
                            prefs.edit { putString(Settings.PREF_DEEPGRAM_API_KEY, newValue) }
                        }
                    )
                }
                SetupRequirementItem(
                    title = stringResource(R.string.setup_app_microphone_title),
                    summary = stringResource(R.string.setup_app_microphone_summary),
                    isComplete = microphoneGranted,
                    actionLabel = stringResource(R.string.setup_app_grant_permission),
                    onAction = {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                Text(
                    text = stringResource(R.string.setup_app_return_later),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(
                    onClick = onClickBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setup_app_dismiss))
                }
            }
        }
    }
}

@Composable
private fun SetupRequirementItem(
    title: String,
    summary: String,
    isComplete: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = isComplete,
                onCheckedChange = null
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(if (isComplete) R.string.setup_app_configured else R.string.setup_app_missing),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (content != null) {
            Column(
                modifier = Modifier.padding(start = 48.dp)
            ) {
                content()
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(start = 48.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun SetupKeyField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 2,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = MaterialTheme.shapes.small,
    )
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            SetupAppScreen { }
        }
    }
}
