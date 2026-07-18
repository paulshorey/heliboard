// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.cleanUnusedMainDicts
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.edithistory.EditHistorySource
import helium314.keyboard.latin.edithistory.EditHistoryStore
import helium314.keyboard.latin.edithistory.EditorTargetSnapshot
import org.json.JSONObject

/**
 * Fullapp text editor Activity.
 *
 * Launched when the user taps the fullapp toolbar button while the keyboard is attached
 * to another app (e.g. a web page textarea). The keyboard app becomes the foreground app;
 * the user edits text here with full keyboard and voice support, then returns to the
 * original app with the text synced back.
 *
 * Any exit (back press, keyboard toggle button) saves the current text and syncs it to
 * the original app's textarea.
 */
class FullappEditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TEXT = "initial_text"
        const val EXTRA_SESSION_TOKEN = "session_token"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_INITIAL_SELECTION_START = "initial_selection_start"
        const val EXTRA_INITIAL_SELECTION_END = "initial_selection_end"
        const val EXTRA_TARGET_FIELD_ID = "target_field_id"
        const val EXTRA_TARGET_FIELD_NAME = "target_field_name"
        const val EXTRA_TARGET_INPUT_TYPE = "target_input_type"
        const val EXTRA_TARGET_IME_OPTIONS = "target_ime_options"
        const val EXTRA_TARGET_PRIVATE_IME_OPTIONS = "target_private_ime_options"

        /** True while this Activity is in the foreground. Lets the IME know we're in fullapp editor. */
        @JvmField
        var isActive = false

        /** Called when the keyboard's fullapp toggle (angle down) is tapped to exit and save. */
        @JvmField
        var onExitFromKeyboard: Runnable? = null

        @Volatile
        private var pendingReturnTargetKey: String? = null

        private const val AUTOSAVE_DELAY_MS = 750L

        @JvmStatic
        fun markPendingReturn(target: EditorTargetSnapshot) {
            pendingReturnTargetKey = target.storageKey
        }

        @JvmStatic
        fun consumePendingReturn(target: EditorTargetSnapshot?): Boolean {
            val storageKey = target?.storageKey ?: return false
            if (pendingReturnTargetKey != storageKey) {
                return false
            }
            pendingReturnTargetKey = null
            return true
        }

        @JvmStatic
        fun clearPendingReturn() {
            pendingReturnTargetKey = null
        }
    }

    private val textState = mutableStateOf(TextFieldValue())
    private val autosaveHandler = Handler(Looper.getMainLooper())
    private val autosaveRunnable = Runnable { persistDraft() }
    private var targetSnapshot: EditorTargetSnapshot? = null
    private var launchSessionToken = ""
    private var originalText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }
        onExitFromKeyboard = Runnable { saveAndExit() }
        loadEditorState(intent)

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            helium314.keyboard.settings.Theme {
                Surface {
                    FullappEditorScreen(
                        textState = textState,
                        onTextChanged = ::updateTextState,
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

    override fun onNewIntent(intent: Intent) {
        persistDraft()
        super.onNewIntent(intent)
        setIntent(intent)
        loadEditorState(intent)
    }

    override fun onPause() {
        isActive = false
        autosaveHandler.removeCallbacks(autosaveRunnable)
        persistDraft()
        super.onPause()
    }

    override fun onDestroy() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        onExitFromKeyboard = null
        super.onDestroy()
    }

    private fun saveAndExit() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        val savedDraft = persistDraft()
        val target = targetSnapshot
        if (savedDraft && target != null) {
            markPendingReturn(target)
        } else {
            clearPendingReturn()
        }
        setResult(RESULT_OK, Intent().putExtra("text", textState.value.text))
        finish()
    }

    private fun loadEditorState(sourceIntent: Intent?) {
        val initialText = sourceIntent?.getStringExtra(EXTRA_INITIAL_TEXT) ?: ""
        val defaultSelection = initialText.length
        val initialSelectionStart = sourceIntent?.getIntExtra(EXTRA_INITIAL_SELECTION_START, defaultSelection)
            ?: defaultSelection
        val initialSelectionEnd = sourceIntent?.getIntExtra(EXTRA_INITIAL_SELECTION_END, initialSelectionStart)
            ?: initialSelectionStart
        launchSessionToken = sourceIntent?.getStringExtra(EXTRA_SESSION_TOKEN).orEmpty()

        targetSnapshot = FullappEditorResult.getTargetFromIntent(sourceIntent)
        val savedDraft = targetSnapshot?.let { FullappEditorResult.loadDraft(this, it) }
        val matchingDraft = savedDraft?.takeIf {
            FullappEditorResult.belongsToLaunchSession(it, launchSessionToken)
        }
        if (savedDraft != null && matchingDraft == null && launchSessionToken.isNotBlank()) {
            FullappEditorResult.archiveAndClearDraft(this, savedDraft)
        }
        originalText = matchingDraft?.originalText ?: initialText

        val restoredText = matchingDraft?.draftText ?: initialText
        val selectionStart = (matchingDraft?.selectionStart ?: initialSelectionStart).coerceIn(0, restoredText.length)
        val selectionEnd = (matchingDraft?.selectionEnd ?: initialSelectionEnd).coerceIn(0, restoredText.length)
        textState.value = TextFieldValue(
            text = restoredText,
            selection = TextRange(selectionStart, selectionEnd)
        )
    }

    private fun updateTextState(value: TextFieldValue) {
        textState.value = value
        autosaveHandler.removeCallbacks(autosaveRunnable)
        autosaveHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS)
    }

    private fun persistDraft(): Boolean {
        val target = targetSnapshot ?: return false
        val currentValue = textState.value
        if (currentValue.text == originalText) {
            FullappEditorResult.clearDraft(this, target)
            return false
        }
        val textLength = currentValue.text.length
        val draft = FullappEditorResult.DraftRecord(
            target = target,
            originalText = originalText,
            draftText = currentValue.text,
            selectionStart = currentValue.selection.start.coerceIn(0, textLength),
            selectionEnd = currentValue.selection.end.coerceIn(0, textLength),
            launchSessionToken = launchSessionToken,
            lastSavedAt = System.currentTimeMillis()
        )
        FullappEditorResult.saveDraft(this, draft)
        return true
    }
}

/**
 * Holder for fullapp editor result. LatinIME reads this when reconnecting to a client.
 */
object FullappEditorResult {
    private const val TAG = "FullappDrafts"
    private const val PREF_FULLAPP_DRAFT_KEYS = "fullapp_draft_keys"
    private const val PREF_FULLAPP_DRAFT_PREFIX = "fullapp_draft_"
    private const val FULLAPP_SYNC_RECENCY_WINDOW_MS = 120_000L
    private const val MAX_LIVE_DRAFTS = 50

    private const val JSON_ORIGINAL_TEXT = "original_text"
    private const val JSON_DRAFT_TEXT = "draft_text"
    private const val JSON_SELECTION_START = "selection_start"
    private const val JSON_SELECTION_END = "selection_end"
    private const val JSON_SESSION_TOKEN = "session_token"
    private const val JSON_LAST_SAVED_AT = "last_saved_at"

    data class DraftRecord(
        val target: EditorTargetSnapshot,
        val originalText: String,
        val draftText: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val launchSessionToken: String,
        val lastSavedAt: Long
    )

    @JvmStatic
    fun createTargetSnapshot(editorInfo: EditorInfo?): EditorTargetSnapshot? {
        return EditorTargetSnapshot.createFrom(editorInfo)
    }

    @JvmStatic
    fun putTargetExtras(intent: Intent, target: EditorTargetSnapshot) {
        intent.putExtra(FullappEditorActivity.EXTRA_PACKAGE_NAME, target.packageName)
        intent.putExtra(FullappEditorActivity.EXTRA_TARGET_FIELD_ID, target.fieldId)
        intent.putExtra(FullappEditorActivity.EXTRA_TARGET_FIELD_NAME, target.fieldName)
        intent.putExtra(FullappEditorActivity.EXTRA_TARGET_INPUT_TYPE, target.inputType)
        intent.putExtra(FullappEditorActivity.EXTRA_TARGET_IME_OPTIONS, target.imeOptions)
        intent.putExtra(FullappEditorActivity.EXTRA_TARGET_PRIVATE_IME_OPTIONS, target.privateImeOptions)
    }

    @JvmStatic
    fun getTargetFromIntent(intent: Intent?): EditorTargetSnapshot? {
        intent ?: return null
        val packageName = intent.getStringExtra(FullappEditorActivity.EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() }
            ?: return null
        return EditorTargetSnapshot(
            packageName = packageName,
            fieldId = intent.getIntExtra(FullappEditorActivity.EXTRA_TARGET_FIELD_ID, 0),
            fieldName = intent.getStringExtra(FullappEditorActivity.EXTRA_TARGET_FIELD_NAME).orEmpty(),
            inputType = intent.getIntExtra(FullappEditorActivity.EXTRA_TARGET_INPUT_TYPE, 0),
            imeOptions = intent.getIntExtra(FullappEditorActivity.EXTRA_TARGET_IME_OPTIONS, 0),
            privateImeOptions = intent.getStringExtra(FullappEditorActivity.EXTRA_TARGET_PRIVATE_IME_OPTIONS).orEmpty()
        )
    }

    @JvmStatic
    fun loadDraft(context: Context, target: EditorTargetSnapshot): DraftRecord? {
        val rawDraft = draftPrefs(context)?.getString(prefKey(target.storageKey), null) ?: return null
        return draftFromJson(rawDraft)
    }

    @JvmStatic
    fun getAllDrafts(context: Context): List<DraftRecord> {
        val prefs = draftPrefs(context) ?: return emptyList()
        enforceAgeRetention(context)
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet()).orEmpty()
        val drafts = mutableListOf<DraftRecord>()
        val staleKeys = mutableListOf<String>()
        for (draftKey in draftKeys) {
            val rawDraft = prefs.getString(prefKey(draftKey), null)
            if (rawDraft == null) {
                staleKeys.add(draftKey)
                continue
            }
            val draft = draftFromJson(rawDraft)
            if (draft == null) {
                staleKeys.add(draftKey)
                continue
            }
            drafts.add(draft)
        }
        if (staleKeys.isNotEmpty()) {
            val updatedKeys = draftKeys.toMutableSet().apply { removeAll(staleKeys.toSet()) }
            prefs.edit {
                putStringSet(PREF_FULLAPP_DRAFT_KEYS, updatedKeys)
                staleKeys.forEach { remove(prefKey(it)) }
            }
        }
        return drafts.sortedByDescending { it.lastSavedAt }
    }

    @JvmStatic
    fun saveDraft(context: Context, draft: DraftRecord) {
        val prefs = draftPrefs(context) ?: return
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        draftKeys.add(draft.target.storageKey)
        prefs.edit {
            putStringSet(PREF_FULLAPP_DRAFT_KEYS, draftKeys)
            putString(prefKey(draft.target.storageKey), draft.toJson().toString())
        }
        enforceAgeRetention(context)
        evictOldestLiveDraftsIfNeeded(context)
        Log.i(TAG, "Saved fullapp draft for ${draft.target.debugSummary()}, chars=${draft.draftText.length}")
    }

    @JvmStatic
    fun archiveAndClearDraft(context: Context, draft: DraftRecord) {
        val prefs = draftPrefs(context) ?: return
        val archivedAt = System.currentTimeMillis()
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        draftKeys.remove(draft.target.storageKey)
        prefs.edit {
            putStringSet(PREF_FULLAPP_DRAFT_KEYS, draftKeys)
            remove(prefKey(draft.target.storageKey))
        }
        EditHistoryStore.addEntry(
            context = context,
            source = EditHistorySource.FULLAPP,
            target = draft.target,
            text = draft.draftText,
            selectionStart = draft.selectionStart,
            selectionEnd = draft.selectionEnd,
            updatedAt = archivedAt,
        )
        Log.i(
            TAG,
            "Archived fullapp draft for ${draft.target.debugSummary()}, chars=${draft.draftText.length}"
        )
    }

    @JvmStatic
    fun clearDraft(context: Context, target: EditorTargetSnapshot) {
        val prefs = draftPrefs(context) ?: return
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        draftKeys.remove(target.storageKey)
        prefs.edit {
            putStringSet(PREF_FULLAPP_DRAFT_KEYS, draftKeys)
            remove(prefKey(target.storageKey))
        }
        Log.i(TAG, "Cleared fullapp draft for ${target.debugSummary()}")
    }

    @JvmStatic
    fun belongsToLaunchSession(draft: DraftRecord, launchSessionToken: String): Boolean {
        return launchSessionToken.isNotBlank() && draft.launchSessionToken == launchSessionToken
    }

    @JvmStatic
    fun matchesCurrentFieldContents(draft: DraftRecord, currentFieldText: String): Boolean {
        return currentFieldText == draft.originalText || currentFieldText == draft.draftText
    }

    @JvmStatic
    fun wasSupersededByRegularEditing(draft: DraftRecord, currentFieldText: String): Boolean {
        return !matchesCurrentFieldContents(draft, currentFieldText)
    }

    @JvmStatic
    fun isRecentEnoughToSync(draft: DraftRecord): Boolean {
        return isRecentEnoughToSync(draft, System.currentTimeMillis())
    }

    @JvmStatic
    fun isRecentEnoughToSync(draft: DraftRecord, nowMs: Long): Boolean {
        if (draft.lastSavedAt <= 0L) {
            return false
        }
        return nowMs - draft.lastSavedAt <= FULLAPP_SYNC_RECENCY_WINDOW_MS
    }

    @JvmStatic
    fun shouldSyncToCurrentField(draft: DraftRecord, currentFieldText: String): Boolean {
        return shouldSyncToCurrentField(draft, currentFieldText, false, System.currentTimeMillis())
    }

    @JvmStatic
    fun shouldSyncToCurrentField(draft: DraftRecord, currentFieldText: String, forceSync: Boolean): Boolean {
        return shouldSyncToCurrentField(draft, currentFieldText, forceSync, System.currentTimeMillis())
    }

    @JvmStatic
    fun shouldSyncToCurrentField(
        draft: DraftRecord,
        currentFieldText: String,
        forceSync: Boolean,
        nowMs: Long
    ): Boolean {
        if (forceSync) {
            return true
        }
        return currentFieldText == draft.originalText && isRecentEnoughToSync(draft, nowMs)
    }

    @JvmStatic
    fun findDraftForEditor(context: Context, editorInfo: EditorInfo): DraftRecord? {
        val prefs = draftPrefs(context) ?: return null
        enforceAgeRetention(context)
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet()).orEmpty()
        val staleKeys = mutableListOf<String>()
        var bestDraft: DraftRecord? = null
        var bestScore = Int.MIN_VALUE
        var bestSavedAt = Long.MIN_VALUE
        for (draftKey in draftKeys) {
            val rawDraft = prefs.getString(prefKey(draftKey), null)
            if (rawDraft == null) {
                staleKeys.add(draftKey)
                continue
            }
            val draft = draftFromJson(rawDraft)
            if (draft == null) {
                staleKeys.add(draftKey)
                continue
            }
            val score = draft.target.matchScore(editorInfo)
            if (score == Int.MIN_VALUE) {
                continue
            }
            if (score > bestScore || (score == bestScore && draft.lastSavedAt > bestSavedAt)) {
                bestDraft = draft
                bestScore = score
                bestSavedAt = draft.lastSavedAt
            }
        }
        if (staleKeys.isNotEmpty()) {
            val updatedKeys = draftKeys.toMutableSet().apply { removeAll(staleKeys.toSet()) }
            prefs.edit {
                putStringSet(PREF_FULLAPP_DRAFT_KEYS, updatedKeys)
                staleKeys.forEach { remove(prefKey(it)) }
            }
        }
        if (bestDraft != null) {
            Log.i(TAG, "Matched fullapp draft for ${bestDraft.target.debugSummary()}")
        }
        return bestDraft
    }

    private fun DraftRecord.toJson() = JSONObject().apply {
        put("target", target.toJson())
        put(JSON_ORIGINAL_TEXT, originalText)
        put(JSON_DRAFT_TEXT, draftText)
        put(JSON_SELECTION_START, selectionStart)
        put(JSON_SELECTION_END, selectionEnd)
        put(JSON_SESSION_TOKEN, launchSessionToken)
        put(JSON_LAST_SAVED_AT, lastSavedAt)
    }

    private fun draftFromJson(rawDraft: String): DraftRecord? = runCatching {
        val json = JSONObject(rawDraft)
        jsonToDraftRecord(json)
    }.getOrNull()

    private fun prefKey(storageKey: String) = PREF_FULLAPP_DRAFT_PREFIX + storageKey

    @JvmStatic
    fun enforceAgeRetention(context: Context) {
        val prefs = draftPrefs(context) ?: return
        val retentionAgeMs = EditHistoryStore.getRetentionAgeMs(context) ?: return
        val now = System.currentTimeMillis()
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet()).orEmpty()
        if (draftKeys.isEmpty()) {
            return
        }
        val expired = draftKeys.mapNotNull { key ->
            val rawDraft = prefs.getString(prefKey(key), null) ?: return@mapNotNull key
            val draft = draftFromJson(rawDraft) ?: return@mapNotNull key
            if (now - draft.lastSavedAt > retentionAgeMs) key else null
        }
        if (expired.isEmpty()) {
            return
        }
        val updatedKeys = draftKeys.toMutableSet().apply { removeAll(expired.toSet()) }
        prefs.edit {
            putStringSet(PREF_FULLAPP_DRAFT_KEYS, updatedKeys)
            expired.forEach { remove(prefKey(it)) }
        }
        Log.i(TAG, "Evicted ${expired.size} live fullapp drafts older than retention window")
    }

    private fun evictOldestLiveDraftsIfNeeded(context: Context) {
        val prefs = draftPrefs(context) ?: return
        val draftKeys = prefs.getStringSet(PREF_FULLAPP_DRAFT_KEYS, emptySet()).orEmpty()
        if (draftKeys.size <= MAX_LIVE_DRAFTS) {
            return
        }
        val drafts = draftKeys.mapNotNull { key ->
            val rawDraft = prefs.getString(prefKey(key), null) ?: return@mapNotNull null
            draftFromJson(rawDraft)
        }.sortedBy { it.lastSavedAt }
        val toEvict = drafts.take(drafts.size - MAX_LIVE_DRAFTS)
        if (toEvict.isEmpty()) {
            return
        }
        val updatedKeys = draftKeys.toMutableSet()
        prefs.edit {
            toEvict.forEach { draft ->
                updatedKeys.remove(draft.target.storageKey)
                remove(prefKey(draft.target.storageKey))
            }
            putStringSet(PREF_FULLAPP_DRAFT_KEYS, updatedKeys)
        }
        Log.i(TAG, "Evicted ${toEvict.size} stale live fullapp drafts")
    }

    private fun jsonToDraftRecord(json: JSONObject) = DraftRecord(
        target = json.optJSONObject("target")?.let { EditorTargetSnapshot.fromJson(it) }
            ?: EditorTargetSnapshot.fromJson(json),
        originalText = json.optString(JSON_ORIGINAL_TEXT, ""),
        draftText = json.optString(JSON_DRAFT_TEXT, ""),
        selectionStart = json.optInt(JSON_SELECTION_START, 0),
        selectionEnd = json.optInt(JSON_SELECTION_END, 0),
        launchSessionToken = json.optString(JSON_SESSION_TOKEN, ""),
        lastSavedAt = json.optLong(JSON_LAST_SAVED_AT, 0L)
    )

    private fun draftPrefs(context: Context): SharedPreferences? = runCatching {
        context.protectedPrefs()
    }.getOrNull()
}

@androidx.compose.runtime.Composable
private fun FullappEditorScreen(
    textState: MutableState<TextFieldValue>,
    onTextChanged: (TextFieldValue) -> Unit,
    onExit: () -> Unit
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
            value = textState.value,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.fullapp_editor_hint)) },
            minLines = 10,
            maxLines = Int.MAX_VALUE,
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge
        )
    }
}
