// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.settings.FullappEditorResult
import helium314.keyboard.settings.SearchScreen
import java.text.DateFormat
import java.util.Date

@Composable
fun FullappDraftsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val drafts = FullappEditorResult.getAllDrafts(context)
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.settings_screen_fullapp_drafts)) },
        filteredItems = { term ->
            if (term.isBlank()) drafts
            else drafts.filter { draft ->
                val lowerTerm = term.lowercase()
                draft.target.packageName.contains(lowerTerm, ignoreCase = true)
                    || resolveAppLabel(context, draft.target.packageName).contains(lowerTerm, ignoreCase = true)
                    || draft.target.fieldName.contains(lowerTerm, ignoreCase = true)
                    || draft.target.privateImeOptions.contains(lowerTerm, ignoreCase = true)
                    || draft.draftText.contains(lowerTerm, ignoreCase = true)
                    || draft.target.fieldId.toString().contains(lowerTerm)
            }
        },
        itemContent = { draft -> FullappDraftEntry(draft = draft) },
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                if (drafts.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fullapp_drafts_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.fullapp_drafts_empty_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(innerPadding)
                    ) {
                        drafts.forEach { draft ->
                            FullappDraftEntry(draft = draft)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun FullappDraftEntry(
    draft: FullappEditorResult.DraftRecord,
) {
    val context = LocalContext.current
    val appLabel = remember(draft.target.packageName) {
        resolveAppLabel(context, draft.target.packageName)
    }
    val savedAt = remember(draft.lastSavedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(draft.lastSavedAt))
    }
    val fingerprintSummary = remember(draft) { buildFingerprintSummary(context, draft) }
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = appLabel,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (appLabel != draft.target.packageName) {
                        Text(
                            text = draft.target.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.fullapp_drafts_saved_at, savedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(appLabel, draft.draftText)
                        )
                        Toast.makeText(context, R.string.toast_msg_clipboard_copy, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.sym_keyboard_copy_rounded),
                        contentDescription = stringResource(R.string.copy_to_clipboard)
                    )
                }
            }
            Text(
                text = fingerprintSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(
                    text = draft.draftText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun buildFingerprintSummary(
    context: Context,
    draft: FullappEditorResult.DraftRecord,
): String = buildString {
    if (draft.target.fieldName.isNotBlank()) {
        append(context.getString(R.string.fullapp_drafts_field_name, draft.target.fieldName))
        append('\n')
    }
    if (draft.target.fieldId != 0) {
        append(context.getString(R.string.fullapp_drafts_field_id, draft.target.fieldId))
        append('\n')
    }
    append(context.getString(R.string.fullapp_drafts_text_length, draft.draftText.length))
    append('\n')
    append(
        context.getString(
            R.string.fullapp_drafts_input_type,
            draft.target.inputType.toUInt().toString(16)
        )
    )
    append('\n')
    append(
        context.getString(
            R.string.fullapp_drafts_ime_options,
            draft.target.imeOptions.toUInt().toString(16)
        )
    )
    if (draft.target.privateImeOptions.isNotBlank()) {
        append('\n')
        append(
            context.getString(
                R.string.fullapp_drafts_private_ime_options,
                draft.target.privateImeOptions
            )
        )
    }
}

private fun resolveAppLabel(
    context: Context,
    packageName: String,
): String = runCatching {
    val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    context.packageManager.getApplicationLabel(appInfo).toString().ifBlank { packageName }
}.getOrDefault(packageName)
