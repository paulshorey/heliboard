// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        const val EXTRA_SESSION_KEY = "session_key"
        const val EXTRA_SOURCE_TEXT = "source_text"

        /** True while this Activity is in the foreground. Lets the IME know we're in fullscreen editor. */
        @JvmField
        var isActive = false

        /** Called when the keyboard's fullscreen toggle (angle down) is tapped to exit and save. */
        @JvmField
        var onExitFromKeyboard: Runnable? = null
    }

    private val textFieldState = mutableStateOf(TextFieldValue(""))
    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistInProgress() }

    private var targetPackage = ""
    private var sessionKey = ""
    private var sourceText = ""
    private var hasExited = false
    private var hasPendingPersist = false
    private var lastPersistedAt = 0L
    private var lastPersistedText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }

        initializeFromIntent(intent)
        onExitFromKeyboard = Runnable { runOnUiThread { saveAndExit() } }

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            helium314.keyboard.settings.Theme {
                Surface {
                    FullscreenEditorScreen(
                        textFieldState = textFieldState,
                        onTextChanged = ::onEditorTextChanged,
                        onExit = ::saveAndExit,
                    )
                }
            }
        }

        enableEdgeToEdge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initializeFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        refreshFromGlobalSession()
    }

    override fun onPause() {
        if (!hasExited) {
            persistInProgress()
        }
        isActive = false
        super.onPause()
    }

    override fun onStop() {
        if (!hasExited) {
            persistInProgress()
        }
        super.onStop()
    }

    override fun onDestroy() {
        persistHandler.removeCallbacksAndMessages(null)
        onExitFromKeyboard = null
        super.onDestroy()
    }

    private fun initializeFromIntent(intent: Intent?) {
        val initialText = intent?.getStringExtra(EXTRA_INITIAL_TEXT) ?: ""
        targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        sourceText = intent?.getStringExtra(EXTRA_SOURCE_TEXT) ?: initialText
        sessionKey = intent?.getStringExtra(EXTRA_SESSION_KEY)
            ?: FullscreenTextSessionStore.buildSessionKey(null, targetPackage)
        if (sessionKey.isEmpty()) {
            sessionKey = targetPackage
        }
        val launchText = FullscreenTextSessionStore.resolveLaunchText(
            context = this,
            sessionKey = sessionKey,
            packageName = targetPackage,
            fallbackLiveText = initialText,
        )
        textFieldState.value = TextFieldValue(launchText, selection = TextRange(launchText.length))
        hasExited = false
        hasPendingPersist = false
        val snapshot = FullscreenTextSessionStore.getSessionForKey(
            context = this,
            sessionKey = sessionKey,
            packageName = targetPackage,
        )
        lastPersistedAt = snapshot?.updatedAt ?: 0L
        lastPersistedText = snapshot?.text ?: launchText
        persistInProgress()
    }

    private fun refreshFromGlobalSession() {
        if (sessionKey.isEmpty() || targetPackage.isEmpty()) return
        val snapshot = FullscreenTextSessionStore.getSessionForKey(
            context = this,
            sessionKey = sessionKey,
            packageName = targetPackage,
        ) ?: return
        if (hasPendingPersist) return
        if (snapshot.updatedAt <= lastPersistedAt) return
        if (snapshot.text == textFieldState.value.text) return
        textFieldState.value = TextFieldValue(snapshot.text, selection = TextRange(snapshot.text.length))
        lastPersistedAt = snapshot.updatedAt
        lastPersistedText = snapshot.text
    }

    private fun onEditorTextChanged(newText: String) {
        if (newText == lastPersistedText) {
            hasPendingPersist = false
            persistHandler.removeCallbacks(persistRunnable)
            return
        }
        hasPendingPersist = true
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, 220L)
    }

    private fun persistInProgress() {
        if (sessionKey.isEmpty() || targetPackage.isEmpty()) return
        if (!hasPendingPersist && textFieldState.value.text == lastPersistedText) {
            return
        }
        val snapshot = FullscreenTextSessionStore.upsertSession(
            context = this,
            sessionKey = sessionKey,
            packageName = targetPackage,
            text = textFieldState.value.text,
            state = FullscreenTextSessionStore.STATE_FULLSCREEN_IN_PROGRESS,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = sourceText,
        )
        hasPendingPersist = false
        lastPersistedAt = snapshot.updatedAt
        lastPersistedText = snapshot.text
    }

    private fun saveAndExit() {
        if (hasExited) return
        hasExited = true
        hasPendingPersist = false
        persistHandler.removeCallbacks(persistRunnable)
        val finalText = textFieldState.value.text
        val snapshot = FullscreenTextSessionStore.upsertSession(
            context = this,
            sessionKey = sessionKey,
            packageName = targetPackage,
            text = finalText,
            state = FullscreenTextSessionStore.STATE_PENDING_SYNC,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = sourceText,
        )
        lastPersistedAt = snapshot.updatedAt
        lastPersistedText = snapshot.text
        setResult(RESULT_OK, Intent().putExtra("text", finalText))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun FullscreenEditorScreen(
    textFieldState: androidx.compose.runtime.MutableState<TextFieldValue>,
    onTextChanged: (String) -> Unit,
    onExit: () -> Unit,
) {
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
            value = textFieldState.value,
            onValueChange = {
                textFieldState.value = it
                onTextChanged(it.text)
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
