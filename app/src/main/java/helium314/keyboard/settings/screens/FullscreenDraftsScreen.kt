// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.settings.FullscreenEditorStorage
import helium314.keyboard.settings.Theme

/**
 * Settings screen showing in-progress fullscreen drafts per app.
 * Each draft is keyed by the package name of the app that opened the keyboard.
 */
@Composable
fun FullscreenDraftsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val packages = remember { mutableStateOf(FullscreenEditorStorage.getAllPackageNames(context)) }

    fun refresh() {
        packages.value = FullscreenEditorStorage.getAllPackageNames(context)
    }

    var packageToDelete by remember { mutableStateOf<String?>(null) }

    Theme {
        Surface {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.fullscreen_drafts_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClickBack) {
                                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.spoken_description_action_previous))
                            }
                        }
                    )
                },
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (packages.value.isEmpty()) {
                        Text(
                            stringResource(R.string.fullscreen_drafts_empty),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        packages.value.forEach { pkg ->
                            val text = FullscreenEditorStorage.get(context, pkg) ?: ""
                            val appLabel = runCatching {
                                context.packageManager.getApplicationInfo(pkg, 0)
                            }.fold(
                                onSuccess = { context.packageManager.getApplicationLabel(it).toString() },
                                onFailure = { pkg }
                            )
                            val preview = text.take(80).let { if (text.length > 80) "$it…" else it }
                            FullscreenDraftItem(
                                appLabel = appLabel,
                                packageName = pkg,
                                preview = preview,
                                onDelete = { packageToDelete = pkg }
                            )
                        }
                    }
                }
            }
        }
    }

    packageToDelete?.let { pkg ->
        AlertDialog(
            onDismissRequest = { packageToDelete = null },
            title = { Text(stringResource(R.string.fullscreen_drafts_delete_title)) },
            text = { Text(stringResource(R.string.fullscreen_drafts_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        FullscreenEditorStorage.remove(context, pkg)
                        refresh()
                        packageToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { packageToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun FullscreenDraftItem(
    appLabel: String,
    packageName: String,
    preview: String,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (preview.isNotBlank()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
