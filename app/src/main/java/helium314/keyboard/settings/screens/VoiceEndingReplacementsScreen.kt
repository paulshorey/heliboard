// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.TranscriptPostProcessor
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.previewDark

@Composable
fun VoiceEndingReplacementsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val b = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val rules = remember {
        val loaded = TranscriptionPreferences.readChunkEndingReplacements(prefs)
        mutableStateListOf<TranscriptPostProcessor.Rule>().apply { addAll(loaded) }
    }

    fun persist() {
        val serialized = TranscriptionPreferences.serializeChunkEndingReplacements(rules.toList())
        TranscriptionPreferences.writeChunkEndingReplacements(prefs, serialized)
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.voice_chunk_ending_replacements_title),
        settings = emptyList(),
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    rules.add(TranscriptPostProcessor.Rule("", ""))
                    persist()
                }) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.voice_chunk_ending_replacements_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.voice_chunk_ending_replacements_count, rules.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                if (rules.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.voice_chunk_ending_replacements_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }

                itemsIndexed(
                    items = rules.toList(),
                    key = { index, _ -> index }
                ) { index, rule ->
                    ReplacementRuleCard(
                        rule = rule,
                        onFindChanged = { newFind ->
                            rules[index] = rule.copy(find = newFind)
                            persist()
                        },
                        onReplaceChanged = { newReplace ->
                            rules[index] = rule.copy(replace = newReplace)
                            persist()
                        },
                        onDelete = {
                            rules.removeAt(index)
                            persist()
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.padding(bottom = 80.dp))
                }
            }
        }
    }
}

@Composable
private fun ReplacementRuleCard(
    rule: TranscriptPostProcessor.Rule,
    onFindChanged: (String) -> Unit,
    onReplaceChanged: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = displayableRule(rule),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Text(
                        "\u2715",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            OutlinedTextField(
                value = rule.find,
                onValueChange = onFindChanged,
                label = { Text(stringResource(R.string.voice_chunk_ending_replacements_find_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = rule.replace,
                onValueChange = onReplaceChanged,
                label = { Text(stringResource(R.string.voice_chunk_ending_replacements_replace_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

private fun displayableRule(rule: TranscriptPostProcessor.Rule): String {
    val find = visualizeWhitespace(rule.find)
    val replace = visualizeWhitespace(rule.replace)
    return "\u201C$find\u201D \u2192 \u201C$replace\u201D"
}

private fun visualizeWhitespace(s: String): String {
    return s.replace(" ", "\u2423")
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceEndingReplacementsScreen { }
        }
    }
}
