// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Button
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.cleanUnusedMainDicts

/**
 * Fullscreen text editor Activity.
 *
 * Launched when the user taps the fullscreen expand button while the keyboard is attached
 * to another app (e.g. a web page textarea). The keyboard app becomes the foreground app;
 * the user edits text here with full keyboard and voice support, then returns to the
 * original app with the text synced back.
 *
 * This works around the limitation where extract-view fullscreen fails on web pages:
 * when the extract view gains focus, the web textarea loses focus and the keyboard closes.
 * By using a standalone Activity (like Settings), we avoid that focus conflict.
 */
class FullscreenEditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TEXT = "initial_text"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ORIGINAL_TEXT = "original_text"

        /** True while this Activity is in the foreground. Lets the IME know we're in fullscreen editor. */
        @JvmField
        var isActive = false

        /** Called when the keyboard's fullscreen button (angle down) is tapped to minimize. */
        @JvmField
        var onMinimizeFromKeyboard: Runnable? = null
    }

    private val textState = androidx.compose.runtime.mutableStateOf("")
    private var targetPackage = ""
    private var originalText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }

        val initialText = intent?.getStringExtra(EXTRA_INITIAL_TEXT) ?: ""
        targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        originalText = intent?.getStringExtra(EXTRA_ORIGINAL_TEXT) ?: initialText
        textState.value = initialText

        onMinimizeFromKeyboard = Runnable {
            runOnUiThread {
                finishWithResult(textState.value, targetPackage, hideKeyboard = false)
            }
        }

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            helium314.keyboard.settings.Theme {
                Surface {
                    FullscreenEditorScreen(
                        textState = textState,
                        initialText = initialText,
                        targetPackage = targetPackage,
                        originalText = originalText,
                        onMinimize = { text -> finishWithResult(text, targetPackage, hideKeyboard = false) },
                        onSubmit = { text -> finishWithResult(text, targetPackage, hideKeyboard = true) },
                        onCancel = { finishWithResult(originalText, targetPackage, hideKeyboard = true) },
                        onBackPressed = { finish() }
                    )
                }
            }
        }

        enableEdgeToEdge()
    }

    override fun onResume() {
        super.onResume()
        isActive = true
    }

    override fun onPause() {
        isActive = false
        super.onPause()
    }

    override fun onDestroy() {
        onMinimizeFromKeyboard = null
        super.onDestroy()
    }

    private fun finishWithResult(text: String, targetPackage: String, hideKeyboard: Boolean) {
        FullscreenEditorResult.pendingText = text
        FullscreenEditorResult.targetPackageName = targetPackage
        FullscreenEditorResult.hideKeyboardOnInsert = hideKeyboard
        setResult(RESULT_OK, Intent().apply {
            putExtra("text", text)
            putExtra("hide_keyboard", hideKeyboard)
        })
        finish()
    }
}

/**
 * Holder for fullscreen editor result. LatinIME reads this when reconnecting to a client.
 */
object FullscreenEditorResult {
    @JvmField var pendingText: String? = null
    @JvmField var targetPackageName: String? = null
    @JvmField var hideKeyboardOnInsert: Boolean = false
}

@androidx.compose.runtime.Composable
private fun FullscreenEditorScreen(
    textState: androidx.compose.runtime.MutableState<String>,
    initialText: String,
    targetPackage: String,
    originalText: String,
    onMinimize: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onBackPressed: () -> Unit
) {
    // Use TextFieldValue with cursor at end so user can immediately continue typing
    var textFieldValue by remember {
        val len = initialText.length
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(len)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler { onCancel() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onMinimize(textFieldValue.text) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.fullscreen_minimize))
                }
                Button(
                    onClick = { onSubmit(textFieldValue.text) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.fullscreen_submit))
                }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.fullscreen_cancel))
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    textState.value = it.text
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.fullscreen_editor_hint)) },
                minLines = 10,
                maxLines = Int.MAX_VALUE,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge
            )
        }
    }
}
