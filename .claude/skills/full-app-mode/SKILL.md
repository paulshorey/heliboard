---
name: full-app-mode
description: This android keyboard app has a unique feature not available in any other keyboard app. Full-screen mode. We call it "fullapp" or "full app" to differentiate from the system OS fullscreen mode. When entering full-app mode, the keyboard is opened as its own standalone app, in its own full screen window. Text from the source app input field is copied to the full keyboard app. When user is done editing in full mode, they can minimize it or click back button. Then the edited text syncs back to the original app input field. User is then able to continue editing using the regular keyboard mode.
---

# Fullapp Keyboard — Architecture and Lessons Learned

This document describes how the fullapp keyboard feature works and what we learned from implementing it. Use it to avoid pitfalls and stick to the approach that works.

## Feature Overview

When the user taps the fullapp toolbar button (configured through the normal toolbar / pinned-toolbar settings):

1. The keyboard **hides** and `FullappEditorActivity` is launched as a standalone app.
2. The user sees a fullapp text editor (Compose UI). They can type or use voice transcription.
3. The fullapp editor autosaves while editing and when backgrounded, so Home/app-switcher/process recreation does not drop the draft.
4. Any explicit exit (back press, keyboard toggle button) keeps the latest draft ready to sync to the original app's textarea.
5. When the IME reconnects to the matching editor, it retries the replay. After sync succeeds (or the target field already matches the draft), the draft is removed from the live-sync list and archived into read-only history instead of being deleted.
6. Users can review and copy both live drafts and archived fullapp history from Settings. Archived entries never sync automatically again.

## Why Activity-Based Fullapp (Not Extract View)

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

### Key files

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
returns the actual field content (including active selections) without the editor’s extra
newlines.

### What to keep

- `replaceEntireFieldText()` — used for inserting pending text when the IME reconnects.
- `getOriginalFieldText()`, `getOriginalFieldCursorPosition()`, `readCurrentFieldText()` — used to seed the Activity and for `replaceEntireFieldText()`.
- `FullappEditorResult` — live draft store, archived history store, and editor-target matching between the Activity and IME.

## What Did NOT Work (Extract View)

### ❌ Extract view on web pages

When the extract view gains focus, the web textarea loses focus and the keyboard closes. No workaround within the extract view model.

### ❌ Writing directly to the extract EditText

The framework periodically syncs from the app to the extract view. Our direct edits were overwritten.

### ❌ Blocking `setExtractedText()`

Stopped all display updates; typing and voice transcription stopped showing.

---

## Summary: Rules of Thumb

| Do                                                             | Don't                                                  |
| -------------------------------------------------------------- | ------------------------------------------------------ |
| Launch `FullappEditorActivity` for fullapp editing             | Use extract view for web page textareas                |
| Store result in `FullappEditorResult`, insert on IME reconnect | Try to keep IME attached while switching to fullapp UI |
| Treat fullapp as "keyboard app as standalone app"              | Assume extract view works everywhere                   |
| Use `replaceEntireFieldText()` when inserting pending text     | Assume `InputConnection` is always ready immediately   |

**Bottom line**: For web pages and apps where the extract view causes focus loss, use an Activity so the keyboard app runs as a standalone app. Sync text back when the user returns and focuses the original field again.

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality is different than described, update and fix the documentation. Always keep this skill file up to date (.claude/skills/full-app-mode/SKILL.md) after any change or after you discover any difference in implementation or functionality.
