// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.EmailsDictionary
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

/**
 * Settings screen for managing the saved emails dictionary. The user can add,
 * edit, delete and re-order (by usage count) entries. This is intentionally
 * similar in look and feel to [PersonalDictionaryScreen] so that managing
 * emails feels like a natural extension of the existing personal dictionary.
 */
@Composable
fun EmailsDictionaryScreen(
    onClickBack: () -> Unit
) {
    val ctx = LocalContext.current
    // Ensure the dictionary is loaded before we try to read from it.
    LaunchedEffect(Unit) { EmailsDictionary.init(ctx) }

    var refreshKey by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val listener = EmailsDictionary.ChangeListener { refreshKey++ }
        EmailsDictionary.addListener(listener)
        onDispose { EmailsDictionary.removeListener(listener) }
    }

    val allEntries = remember(refreshKey) { EmailsDictionary.getAll() }
    var selected: EmailsDictionary.Entry? by remember { mutableStateOf(null) }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.edit_emails_dictionary))
                Text(
                    stringResource(R.string.edit_emails_dictionary_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        filteredItems = { term ->
            if (term.isBlank()) allEntries
            else allEntries.filter { it.email.contains(term, ignoreCase = true) }
        },
        itemContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = it }
                    .padding(vertical = 6.dp, horizontal = 16.dp)
            ) {
                Column {
                    Text(it.email, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.emails_dictionary_usage_count, it.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.user_dict_settings_edit_dialog_title))
            }
        }
    )

    selected?.let {
        EditEmailDialog(entry = it) { selected = null }
    }

    ExtendedFloatingActionButton(
        onClick = { selected = EmailsDictionary.Entry("", 0) },
        text = { Text(stringResource(R.string.user_dict_add_word_button)) },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = stringResource(R.string.user_dict_add_word_button)
            )
        },
        modifier = Modifier
            .wrapContentSize(Alignment.BottomEnd)
            .padding(all = 12.dp)
            .then(Modifier.safeDrawingPadding())
    )
}

@Composable
private fun EditEmailDialog(entry: EmailsDictionary.Entry, onDismissRequest: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var newEmail by remember { mutableStateOf(entry.email) }
    var newCount by remember { mutableStateOf(entry.count.coerceAtLeast(0)) }
    val isValid = newEmail.contains('@') && newEmail.trim().length > 2

    fun save() {
        if (!isValid) return
        if (entry.email.isEmpty()) {
            EmailsDictionary.upsert(newEmail, newCount.coerceAtLeast(1))
        } else if (entry.email.equals(newEmail, ignoreCase = true)) {
            EmailsDictionary.upsert(newEmail, newCount)
        } else {
            EmailsDictionary.rename(entry.email, newEmail, newCount)
        }
    }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { save() },
        checkOk = { isValid },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = stringResource(R.string.delete),
        onNeutral = {
            if (entry.email.isNotEmpty()) EmailsDictionary.remove(entry.email)
            onDismissRequest()
        },
        title = { Text(stringResource(R.string.edit_emails_dictionary)) },
        content = {
            LaunchedEffect(entry) {
                if (entry.email.isEmpty()) focusRequester.requestFocus()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.emails_dictionary_email_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    keyboardActions = KeyboardActions {
                        if (isValid) {
                            save()
                            onDismissRequest()
                        }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.emails_dictionary_usage_count_label),
                        Modifier.fillMaxWidth(0.4f)
                    )
                    TextField(
                        value = newCount.toString(),
                        onValueChange = { value ->
                            val parsed = value.filter { it.isDigit() }.toIntOrNull()
                            if (parsed != null) newCount = parsed
                            else if (value.isEmpty()) newCount = 0
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    )
}
