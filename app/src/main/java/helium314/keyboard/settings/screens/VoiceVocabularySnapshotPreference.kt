// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.VoiceVocabularySnapshot
import helium314.keyboard.settings.NextScreenIcon
import helium314.keyboard.settings.preferences.Preference

/** Inspect the exact, most recently accepted vocabulary beside the editable custom list. */
@Composable
internal fun VoiceVocabularySnapshotPreference() {
    val snapshot by VoiceVocabularySnapshot.latest.collectAsState()
    var showWords by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val title = stringResource(R.string.voice_vocabulary_sent_title)
    val description = snapshot?.let {
        stringResource(R.string.voice_vocabulary_sent_summary,
            DateUtils.formatDateTime(context, it.connectedAtMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
            it.customTerms.size, it.editorTerms.size)
    } ?: stringResource(R.string.voice_vocabulary_sent_unavailable)

    Preference(name = title, description = description, onClick = { showWords = true }) {
        NextScreenIcon()
    }

    if (showWords) {
        AlertDialog(
            onDismissRequest = { showWords = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(description, style = MaterialTheme.typography.bodySmall)
                    val words = snapshot
                    if (words != null) {
                        if (words.customTerms.isEmpty() && words.editorTerms.isEmpty()) {
                            Text(stringResource(R.string.voice_vocabulary_sent_empty),
                                modifier = Modifier.padding(top = 12.dp))
                        } else {
                            VocabularyWords(stringResource(R.string.voice_vocabulary_user_section), words.customTerms)
                            VocabularyWords(stringResource(R.string.voice_vocabulary_sent_editor_section), words.editorTerms)
                        }
                    }
                    Text(stringResource(R.string.voice_vocabulary_sent_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { showWords = false }) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }
}

@Composable
private fun VocabularyWords(title: String, words: List<String>) {
    if (words.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    SelectionContainer { Text(words.joinToString("\n")) }
}
