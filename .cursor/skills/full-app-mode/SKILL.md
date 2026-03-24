---
name: full-app-mode
description: Fullapp keyboard opens the keyboard as a standalone app in its own full-screen window. Text syncs between the source app field and the fullapp editor. Use when working on FullappEditorActivity, fullapp drafts, text sync, extract view, or the fullapp toolbar button.
---

# Fullapp Keyboard — Architecture and Lessons Learned

## Feature Overview

When the user taps the fullapp toolbar button:

1. The keyboard **hides** and `FullappEditorActivity` is launched as a standalone app.
2. The user sees a fullapp text editor (Compose UI). They can type or use voice transcription.
3. The fullapp editor autosaves while editing and when backgrounded, so Home/app-switcher/process recreation does not drop the draft.
4. Any explicit exit (back press, keyboard toggle button) keeps the latest draft ready to sync to the original app's textarea.
5. When the IME reconnects to the matching editor, it retries the replay. After sync succeeds (or the target field already matches the draft), the draft is removed from the live-sync list and archived into read-only history instead of being deleted.
6. Users can review and copy both live drafts and archived fullapp history from Settings. Archived entries never sync automatically again.

## Why Activity-Based (Not Extract View)

### Extract view fails on web pages

When using Android's extract view (`setExtractView()` with `ExtractEditText`):

- The extract view is a **mirror** of the app's text field — sync flows through `InputConnection`.
- When the system fullscreen extract view gains focus, the **original webpage textarea loses focus**.
- When the original textarea loses focus, the **keyboard closes**. This is expected Android behavior.
- Result: system fullscreen extract mode works in native apps but **fails in WebViews / browser textareas**.

### Settings page insight

The user can open Settings → Transcription and focus into textareas there (API keys, prompts) while the keyboard stays open. Why? Because **the Settings page is the keyboard app itself**, running as a full standalone foreground app — not as an IME attached to another app.

So we reuse that model: **treat fullapp as "opening the keyboard app"**, separate from the original app.

## Current Implementation (Activity-Based)

### Flow

1. **User taps the fullapp toolbar key** → `KeyCode.FULLAPP` → `onFullappExpandClicked()` / `onFullappMinimizeClicked()` → `launchFullappEditorActivity()` or exit runnable.
2. **Launch**: Commit typed text, stop voice gracefully, read current text and `packageName` from `InputConnection`/`EditorInfo`, call `requestHideSelf()`, start `FullappEditorActivity` with extras.
3. **In Activity**: User edits in Compose `OutlinedTextField` (keyboard + voice work; keyboard app is foreground). No top toolbar — the keyboard's fullapp toggle (angle down) or back press exits.
4. **While editing**: Persist a live draft in credential-protected storage, keyed by an editor fingerprint (package + field metadata). This also lets multiple apps/fields keep separate unsynced drafts.
5. **On exit/background**: Keep the latest live draft persisted instead of relying on a one-shot in-memory handoff.
6. **When user returns**: They go back to the original app. When they focus the matching text area, `onStartInputViewInternal()` looks up the live draft, retries `replaceEntireFieldText()` if needed, restores selection, and then archives the finished draft into read-only history instead of deleting it.
7. **Settings history**: The settings UI shows two sections: live in-progress fullapp edits (still eligible for sync) and archived fullapp edit history (reference only, copyable, never auto-synced).

### Key Files

- `LatinIME.java`: `launchFullappEditorActivity()`, pending-text handling in `onStartInputViewInternal()`.
- `FullappEditorActivity.kt`: Compose UI, autosave lifecycle, `FullappEditorResult` live-draft + archive store.
- `SuggestionStripView.kt`: Runtime fullapp toolbar button state (expand/minimize icon swap).
- `ToolbarUtils.kt`: Fullapp toolbar key registration, defaults, and settings integration.

### Trailing newlines when opening

When entering fullapp mode, the initial text can intermittently have 2 extra trailing newlines.
This points to `getExtractedText()` rather than the voice paragraph timer.

**Root cause**: Some editors (including WebView/Chrome for HTML textareas) add trailing newlines
to the text returned by `getExtractedText()` — likely for system fullscreen/extract-view display.

**Fix**: We use `getOriginalFieldTextForFullapp()`, which bypasses `getExtractedText()` and
reads via `getTextBeforeCursor()` + `getSelectedText()` + `getTextAfterCursor()`. That path
returns the actual field content (including active selections) without the editor's extra
newlines.

### What to keep

- `replaceEntireFieldText()` — used for inserting pending text when the IME reconnects.
- `getOriginalFieldText()`, `getOriginalFieldCursorPosition()`, `readCurrentFieldText()` — used to seed the Activity and for `replaceEntireFieldText()`.
- `FullappEditorResult` — live draft store, archived history store, and editor-target matching between the Activity and IME.

## What Did NOT Work (Extract View)

| Attempt | Result |
|---------|--------|
| Extract view on web pages | Web textarea loses focus → keyboard closes |
| Writing directly to extract EditText | Framework syncs from app → overwrites our edits |
| Blocking `setExtractedText()` | Stops all display updates; typing/voice stop showing |

## Rules of Thumb

| Do | Don't |
|----|-------|
| Launch `FullappEditorActivity` for fullapp editing | Use extract view for web page textareas |
| Store result in `FullappEditorResult`, insert on IME reconnect | Try to keep IME attached while switching to fullapp UI |
| Treat fullapp as "keyboard app as standalone app" | Assume extract view works everywhere |
| Use `replaceEntireFieldText()` when inserting pending text | Assume `InputConnection` is always ready immediately |

**Key rule**: The extract view is a mirror of the app's field. All text input (typing, voice) must go through `InputConnection` to the app — never write to the extract view directly. The framework syncs app → extract view via `setExtractedText()`.

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality differs from what's described, update this skill file immediately.
