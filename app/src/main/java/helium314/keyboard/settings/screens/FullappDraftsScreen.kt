// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.edithistory.EditHistoryEntry
import helium314.keyboard.latin.edithistory.EditHistorySource
import helium314.keyboard.latin.edithistory.EditHistoryStore
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.FullappEditorResult
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.SwitchPreference
import java.text.DateFormat
import java.util.Date

@Composable
fun FullappDraftsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val liveDrafts = remember(refreshToken) { FullappEditorResult.getAllDrafts(context) }
    val historyEntries = remember(refreshToken) { EditHistoryStore.getAllEntries(context) }
    val allEntries = remember(liveDrafts, historyEntries) {
        buildList {
            addAll(liveDrafts.map { EditHistoryListEntry.Live(it) })
            addAll(historyEntries.map { EditHistoryListEntry.History(it) })
        }
    }
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        ThreeButtonAlertDialog(
            onDismissRequest = { showClearDialog = false },
            onConfirmed = {
                EditHistoryStore.clearAll(context)
                refreshToken++
                showClearDialog = false
            },
            title = { Text(stringResource(R.string.edit_history_clear_title)) },
            content = { Text(stringResource(R.string.edit_history_clear_summary)) },
            confirmButtonText = stringResource(R.string.edit_history_clear),
            cancelButtonText = stringResource(android.R.string.cancel),
        )
    }

    val regularSourceLabel = stringResource(R.string.edit_history_source_regular)
    val fullappSourceLabel = stringResource(R.string.edit_history_source_fullapp)
    val fullappLiveSourceLabel = stringResource(R.string.edit_history_source_fullapp_live)
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.settings_screen_fullapp_drafts)) },
        filteredItems = { term ->
            if (term.isBlank()) allEntries
            else allEntries.filter { entry ->
                val lowerTerm = term.lowercase()
                when (entry) {
                    is EditHistoryListEntry.Live -> {
                        val draft = entry.draft
                        draft.target.packageName.contains(lowerTerm, ignoreCase = true)
                            || resolveAppLabel(context, draft.target.packageName).contains(lowerTerm, ignoreCase = true)
                            || draft.target.fieldName.contains(lowerTerm, ignoreCase = true)
                            || draft.target.privateImeOptions.contains(lowerTerm, ignoreCase = true)
                            || draft.draftText.contains(lowerTerm, ignoreCase = true)
                            || draft.target.fieldId.toString().contains(lowerTerm)
                            || fullappLiveSourceLabel.contains(lowerTerm, ignoreCase = true)
                    }
                    is EditHistoryListEntry.History -> {
                        val history = entry.entry
                        history.target.packageName.contains(lowerTerm, ignoreCase = true)
                            || resolveAppLabel(context, history.target.packageName).contains(lowerTerm, ignoreCase = true)
                            || history.target.fieldName.contains(lowerTerm, ignoreCase = true)
                            || history.target.privateImeOptions.contains(lowerTerm, ignoreCase = true)
                            || history.text.contains(lowerTerm, ignoreCase = true)
                            || history.target.fieldId.toString().contains(lowerTerm)
                            || (history.source == EditHistorySource.REGULAR && regularSourceLabel.contains(lowerTerm, ignoreCase = true))
                            || (history.source == EditHistorySource.FULLAPP && fullappSourceLabel.contains(lowerTerm, ignoreCase = true))
                    }
                }
            }
        },
        itemContent = { entry -> EditHistoryEntryCard(entry = entry) },
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                ) {
                    SwitchPreference(
                        name = stringResource(R.string.edit_history_enabled_title),
                        description = stringResource(R.string.edit_history_enabled_summary),
                        key = Settings.PREF_EDIT_HISTORY_ENABLED,
                        default = Defaults.PREF_EDIT_HISTORY_ENABLED,
                    )
                    if (historyEntries.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showClearDialog = true }) {
                                Text(stringResource(R.string.edit_history_clear))
                            }
                        }
                    }
                    if (liveDrafts.isEmpty() && historyEntries.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.edit_history_empty_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.edit_history_empty_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        EditHistorySections(
                            liveDrafts = liveDrafts,
                            historyEntries = historyEntries,
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun FullappDraftHistorySections(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val liveDrafts = FullappEditorResult.getAllDrafts(context)
    val historyEntries = EditHistoryStore.getAllEntries(context)
    if (liveDrafts.isEmpty() && historyEntries.isEmpty()) {
        return
    }
    EditHistorySections(
        liveDrafts = liveDrafts,
        historyEntries = historyEntries,
        modifier = modifier,
    )
}

@Composable
fun EditHistorySections(
    liveDrafts: List<FullappEditorResult.DraftRecord>,
    historyEntries: List<EditHistoryEntry>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (liveDrafts.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.fullapp_live_drafts_title),
                summary = stringResource(R.string.fullapp_live_drafts_summary)
            )
            liveDrafts.forEach { draft ->
                EditHistoryEntryCard(entry = EditHistoryListEntry.Live(draft))
            }
        }
        if (historyEntries.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.edit_history_section_title),
                summary = stringResource(R.string.edit_history_section_summary)
            )
            historyEntries.forEach { entry ->
                EditHistoryEntryCard(entry = EditHistoryListEntry.History(entry))
            }
        }
    }
}

private sealed interface EditHistoryListEntry {
    data class Live(
        val draft: FullappEditorResult.DraftRecord
    ) : EditHistoryListEntry

    data class History(
        val entry: EditHistoryEntry
    ) : EditHistoryListEntry
}

@Composable
private fun SectionHeader(
    title: String,
    summary: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class EditHistoryCardModel(
    val appLabel: String,
    val savedAt: String,
    val text: String,
    val sourceLabel: String,
    val fingerprintSummary: String,
    val truncated: Boolean,
)

@Composable
private fun EditHistoryEntryCard(
    entry: EditHistoryListEntry,
) {
    val context = LocalContext.current
    var metadataExpanded by remember(entry) { mutableStateOf(false) }
    val model = when (entry) {
        is EditHistoryListEntry.Live -> {
            val draft = entry.draft
            val label = remember(draft.target.packageName) {
                resolveAppLabel(context, draft.target.packageName)
            }
            val saved = remember(draft.lastSavedAt) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(draft.lastSavedAt))
            }
            EditHistoryCardModel(
                appLabel = label,
                savedAt = saved,
                text = draft.draftText,
                sourceLabel = stringResource(R.string.edit_history_source_fullapp_live),
                fingerprintSummary = buildLiveFingerprintSummary(context, draft),
                truncated = false,
            )
        }
        is EditHistoryListEntry.History -> {
            val history = entry.entry
            val label = remember(history.target.packageName) {
                resolveAppLabel(context, history.target.packageName)
            }
            val saved = remember(history.updatedAt) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(history.updatedAt))
            }
            EditHistoryCardModel(
                appLabel = label,
                savedAt = saved,
                text = history.text,
                sourceLabel = historySourceLabel(history.source),
                fingerprintSummary = buildHistoryFingerprintSummary(context, history),
                truncated = history.truncated,
            )
        }
    }
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
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { metadataExpanded = !metadataExpanded },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(if (metadataExpanded) 90f else -90f)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = model.appLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = model.sourceLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.padding(top = 2.dp),
                    colors = IconButtonDefaults.iconButtonColors(),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(model.appLabel, model.text)
                        )
                        Toast.makeText(context, R.string.toast_msg_clipboard_copy, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.sym_keyboard_copy_rounded),
                        contentDescription = stringResource(R.string.copy_to_clipboard),
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
            if (metadataExpanded) {
                HorizontalDivider()
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.fullapp_drafts_saved_at, model.savedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = model.fingerprintSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (model.truncated) {
                Text(
                    text = stringResource(R.string.edit_history_truncated_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SelectionContainer {
                Text(
                    text = model.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun historySourceLabel(source: EditHistorySource): String = when (source) {
    EditHistorySource.FULLAPP -> stringResource(R.string.edit_history_source_fullapp)
    EditHistorySource.REGULAR -> stringResource(R.string.edit_history_source_regular)
}

private fun buildLiveFingerprintSummary(
    context: Context,
    draft: FullappEditorResult.DraftRecord,
): String = buildString {
    append(context.getString(R.string.fullapp_drafts_status_live))
    append('\n')
    appendCommonFingerprint(context, draft.target.fieldName, draft.target.fieldId, draft.draftText.length)
}

private fun buildHistoryFingerprintSummary(
    context: Context,
    entry: EditHistoryEntry,
): String = buildString {
    append(context.getString(R.string.fullapp_drafts_status_archived))
    append('\n')
    appendCommonFingerprint(context, entry.target.fieldName, entry.target.fieldId, entry.text.length)
}

private fun StringBuilder.appendCommonFingerprint(
    context: Context,
    fieldName: String,
    fieldId: Int,
    textLength: Int,
) {
    if (fieldName.isNotBlank()) {
        append(context.getString(R.string.fullapp_drafts_field_name, fieldName))
        append('\n')
    }
    if (fieldId != 0) {
        append(context.getString(R.string.fullapp_drafts_field_id, fieldId))
        append('\n')
    }
    append(context.getString(R.string.fullapp_drafts_text_length, textLength))
}

private fun resolveAppLabel(
    context: Context,
    packageName: String,
): String = runCatching {
    val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    context.packageManager.getApplicationLabel(appInfo).toString().ifBlank { packageName }
}.getOrDefault(packageName)
