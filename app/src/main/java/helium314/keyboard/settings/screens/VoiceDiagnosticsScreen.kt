// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.LogLine
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun VoiceDiagnosticsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(Log.getVoiceDiagnosticsLog()) }
    var autoRefresh by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    fun refresh() {
        snapshot = Log.getVoiceDiagnosticsLog()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val lines = Log.getVoiceDiagnosticsLog()
        scope.launch(Dispatchers.IO) {
            context.getActivity()?.contentResolver?.openOutputStream(uri)?.use { os ->
                os.writer().use { writer ->
                    writer.write(Log.formatVoiceDiagnosticsExport(lines, BuildConfig.VERSION_NAME))
                }
            }
        }
    }

    fun launchExport() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Calendar.getInstance().time)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(
                Intent.EXTRA_TITLE,
                context.getString(R.string.english_ime_name).replace(" ", "_") + "_voice_log_$date.txt"
            )
            .setType("text/plain")
        exportLauncher.launch(intent)
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    LaunchedEffect(autoRefresh) {
        if (!autoRefresh) return@LaunchedEffect
        while (true) {
            delay(2000)
            val next = Log.getVoiceDiagnosticsLog()
            if (next.size != snapshot.size || next.lastOrNull() != snapshot.lastOrNull()) {
                snapshot = next
            }
        }
    }

    LaunchedEffect(snapshot.size) {
        if (snapshot.isNotEmpty()) {
            listState.animateScrollToItem(snapshot.lastIndex)
        }
    }

    val refreshLabel = stringResource(R.string.voice_diagnostics_refresh)
    val exportLabel = stringResource(R.string.voice_diagnostics_export)

    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.voice_diagnostics_title)) },
        filteredItems = { emptyList<LogLine>() },
        itemContent = {},
        icon = {},
        menu = listOf(
            refreshLabel to ::refresh,
            exportLabel to ::launchExport,
        ),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.voice_diagnostics_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.voice_diagnostics_auto_refresh),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.voice_diagnostics_line_count, snapshot.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
                    }
                    if (snapshot.isEmpty()) {
                        Text(
                            text = stringResource(R.string.voice_diagnostics_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(snapshot, key = { index, line -> "$index:${line.tag}:${line.message}" }) { _, line ->
                                VoiceDiagnosticLogLine(line)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun VoiceDiagnosticLogLine(line: LogLine) {
    val color = when (line.level) {
        'E', 'F' -> MaterialTheme.colorScheme.error
        'W' -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = line.formatLine(redact = true),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}
