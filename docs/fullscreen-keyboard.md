# Fullscreen Keyboard — Architecture and Lessons Learned

This document describes how the fullscreen keyboard feature works and what we learned from implementing it. Use it to avoid pitfalls and stick to the approach that works.

## Feature Overview

When the user taps the fullscreen expand button (next to the microphone):

1. The keyboard **hides** and `FullscreenEditorActivity` is launched as a standalone app.
2. The user sees a fullscreen text editor (Compose UI). They can type or use voice transcription.
3. Any exit (back press, keyboard toggle button) saves the current text and syncs it to the original app's textarea. The keyboard hides after insert.

## Why Activity-Based Fullscreen (Not Extract View)

### Extract view fails on web pages

When using Android's extract view (`setExtractView()` with `ExtractEditText`):

- The extract view is a **mirror** of the app's text field — sync flows through `InputConnection`.
- When the fullscreen extract view gains focus, the **original webpage textarea loses focus**.
- When the original textarea loses focus, the **keyboard closes**. This is expected Android behavior.
- Result: fullscreen mode works in native apps but **fails in WebViews / browser textareas**.

### Settings page insight

The user can open Settings → Transcription and focus into textareas there (API keys, prompts) while the keyboard stays open. Why? Because **the Settings page is the keyboard app itself**, running as a full standalone foreground app — not as an IME attached to another app.

So we reuse that model: **treat fullscreen as "opening the keyboard app"**, separate from the original app.

## Current Implementation (Activity-Based)

### Flow

1. **User taps fullscreen expand** → `onFullscreenExpandClicked()` → `launchFullscreenEditorActivity()`.
2. **Launch**: Commit typed text, stop voice gracefully, read current text and editor identity from `InputConnection`/`EditorInfo`, resolve text from the shared session store, call `requestHideSelf()`, start `FullscreenEditorActivity` with extras.
3. **In Activity**: User edits in Compose `OutlinedTextField` (keyboard + voice work; keyboard app is foreground). No top toolbar — the keyboard's fullscreen toggle (angle down) or back press exits.
4. **While editing**: Fullscreen text is continuously persisted as `fullscreen_in_progress` in the shared session store (debounced + lifecycle flush).
5. **On exit**: Mark session `pending_sync`, then `finish()`.
6. **When user returns**: On `onStartInputViewInternal()`, IME reconciles app field text vs the session store. If fullscreen text should win (pending sync or matching source snapshot), `replaceEntireFieldText()` inserts it and session is normalized to regular-active.

### Key files

- `LatinIME.java`: launch, reconciliation on start input view, regular-mode snapshot persistence.
- `FullscreenEditorActivity.kt`: Compose UI (text field only), lifecycle persistence and pending-sync exit.
- `FullscreenTextSessionStore.kt`: durable shared per-session text state (regular + fullscreen).
- `SuggestionStripView.kt`: Fullscreen expand button, `onFullscreenExpandClicked()`.

### Trailing newlines when opening

When entering fullscreen, the initial text can intermittently have 2 extra trailing newlines.
This points to `getExtractedText()` rather than the voice paragraph timer.

**Root cause**: Some editors (including WebView/Chrome for HTML textareas) add trailing newlines
to the text returned by `getExtractedText()` — likely for fullscreen/extract-view display.

**Fix**: We use `getOriginalFieldTextForFullscreen()`, which bypasses `getExtractedText()` and
reads only via `getTextBeforeCursor()` + `getTextAfterCursor()`. That path returns the actual
field content without the editor’s extra newlines.

### What to keep

- `replaceEntireFieldText()` — used for syncing global latest text into the app field.
- `getOriginalFieldText()`, `getOriginalFieldCursorPosition()`, `readCurrentFieldText()` — used to seed the Activity and for `replaceEntireFieldText()`.
- `FullscreenTextSessionStore` — shared bridge between Activity and IME.

## What Did NOT Work (Extract View)

### ❌ Extract view on web pages

When the extract view gains focus, the web textarea loses focus and the keyboard closes. No workaround within the extract view model.

### ❌ Writing directly to the extract EditText

The framework periodically syncs from the app to the extract view. Our direct edits were overwritten.

### ❌ Blocking `setExtractedText()`

Stopped all display updates; typing and voice transcription stopped showing.

---

## Summary: Rules of Thumb

| Do | Don't |
|----|-------|
| Launch `FullscreenEditorActivity` for fullscreen editing | Use extract view for web page textareas |
| Persist text sessions globally (regular + fullscreen), reconcile on focus | Rely on one in-memory pending variable |
| Treat fullscreen as "keyboard app as standalone app" | Assume extract view works everywhere |
| Use `replaceEntireFieldText()` when inserting pending text | Assume `InputConnection` is always ready immediately |

**Bottom line**: For web pages and apps where the extract view causes focus loss, use an Activity so the keyboard app runs as a standalone app. Sync text back when the user returns and focuses the original field again.
