// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.OutlinedTextField
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
 * Any exit (back press, keyboard toggle button) saves the current text and syncs it to
 * the original app's textarea.
 */
class FullscreenEditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TEXT = "initial_text"
        const val EXTRA_PACKAGE_NAME = "package_name"

        /** True while this Activity is in the foreground. Lets the IME know we're in fullscreen editor. */
        @JvmField
        var isActive = false

        /** Called when the keyboard's fullscreen toggle (angle down) is tapped to exit and save. */
        @JvmField
        var onExitFromKeyboard: Runnable? = null
    }

    private val textState = androidx.compose.runtime.mutableStateOf("")
    private var targetPackage = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }

        val initialText = intent?.getStringExtra(EXTRA_INITIAL_TEXT) ?: ""
        targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        textState.value = initialText

        onExitFromKeyboard = Runnable { saveAndExit() }

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            helium314.keyboard.settings.Theme {
                Surface {
                    FullscreenEditorScreen(
                        textState = textState,
                        initialText = initialText,
                        onExit = ::saveAndExit
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
        // Persist current text when user switches away (e.g. to another app).
        // Survives process death so we can sync when they return.
        if (targetPackage.isNotBlank()) {
            FullscreenEditorStorage.put(this, targetPackage, textState.value)
        }
        super.onPause()
    }

    override fun onDestroy() {
        onExitFromKeyboard = null
        super.onDestroy()
    }

    private fun saveAndExit() {
        val text = textState.value
        FullscreenEditorResult.pendingText = text
        FullscreenEditorResult.targetPackageName = targetPackage
        // Also persist so we don't lose if FullscreenEditorResult gets overwritten
        // when user opens fullscreen from another app before returning here
        if (targetPackage.isNotBlank()) {
            FullscreenEditorStorage.put(this, targetPackage, text)
        }
        setResult(RESULT_OK, Intent().putExtra("text", text))
        finish()
    }
}

/**
 * Holder for fullscreen editor result. LatinIME reads this when reconnecting to a client.
 */
object FullscreenEditorResult {
    @JvmField var pendingText: String? = null
    @JvmField var targetPackageName: String? = null
}

@androidx.compose.runtime.Composable
private fun FullscreenEditorScreen(
    textState: androidx.compose.runtime.MutableState<String>,
    initialText: String,
    onExit: () -> Unit
) {
    var textFieldValue by remember {
        val len = initialText.length
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(len)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onExit)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
