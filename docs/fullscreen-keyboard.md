# Fullscreen Keyboard — Architecture and Lessons Learned

This document describes how the fullscreen keyboard feature works and what we learned from implementing it. Use it to avoid pitfalls and stick to the approach that works.

## Feature Overview

When the user taps the fullscreen expand button (next to the microphone):

1. The keyboard enters fullscreen mode with a large temporary text area visible above the keyboard.
2. User can type or use voice transcription. Both appear in this text area.
3. On **Minimize**: return to normal keyboard; text syncs back to the original app field.
4. On **Submit**: close keyboard and sync text to the app field.
5. On **Cancel**: discard changes and restore original text.

## Android Extract View Architecture

The fullscreen text area uses Android’s extract view (`setExtractView()`). **Critical insight:**

### The extract view is a MIRROR, not an independent editor

- **Source of truth**: The app’s text field (via `InputConnection` / `getCurrentInputConnection()`).
- **Display**: The extract view (`ExtractEditText` with `@android:id/inputExtractEditText`) shows a copy of that content.
- **Sync direction**: App → Framework → Extract view via `setExtractedText()`.
- **Input flow**: All text input (typing, voice) goes through `InputConnection` to the app. The framework then syncs to the extract view for display.

So we must always **write to the app’s `InputConnection`**. The framework handles showing it in the fullscreen area.

---

## What Did NOT Work

### ❌ Writing directly to the extract EditText

**Attempt**: Modify `mFullscreenExtractEditText.getText()` or use `BaseInputConnection` to write to the extract view.

**Why it failed**: The framework periodically calls `setExtractedText()` to sync from the app to the extract view. Our changes were overwritten by the next sync. Text appeared briefly, then vanished.

### ❌ Blocking `setExtractedText()` (FullscreenEditText)

**Attempt**: Subclass `ExtractEditText` and override `setExtractedText()` to no-op when `setIgnoreFrameworkSync(true)`.

**Why it failed**: Blocking `setExtractedText()` stopped the framework from updating the extract view at all. Result:
- Voice transcription: no visible updates (same overwrite problem, but we also blocked the sync path).
- **Manual typing stopped working** — typed text never appeared. Typing goes: InputConnection → app → framework extracts → `setExtractedText()` → extract view. By blocking the last step, we broke the display of typed input.

### ❌ Reading context from the extract view for voice

**Attempt**: When in fullscreen, read `getTextBeforeCursorForVoiceContext()` from `mFullscreenExtractEditText` instead of `InputConnection`.

**Why it failed**: Inconsistent. The extract view can lag or be overwritten; the app’s `InputConnection` is the source of truth.

---

## What DID Work

### ✅ Always use the app’s InputConnection

- **Voice transcription**: Write via `mInputLogic.mConnection.commitText()` and `deleteTextBeforeCursor()`. Same path as regular (non-fullscreen) mode.
- **Context for voice**: Read via `mInputLogic.mConnection.getTextBeforeCursor()`. Don’t read from the extract view.
- **Paragraph breaks**: Use `mInputLogic.mConnection.commitText("\n\n", 1)`.

### ✅ Let the framework handle the extract view

- Use the standard `ExtractEditText` with `@android:id/inputExtractEditText`.
- Do **not** subclass it or override `setExtractedText()`.
- The framework syncs app content → extract view. We only need to write to the app.

### ✅ Copy text on enter, sync on exit

- **Enter fullscreen**: Copy `getOriginalFieldText()` and cursor position into the extract view (one-time init). The framework will also sync; we seed it so it’s correct from the start.
- **Exit (Minimize/Submit)**: Call `replaceEntireFieldText()` to sync our fullscreen draft back to the app via `InputConnection`, then exit. Use retries if needed for flaky connections.

### ✅ Decouple fullscreen from microphone

- Fullscreen expand button is separate from the microphone.
- Microphone starts/stops recording; fullscreen toggles layout only.
- Use `stopVoiceRecordingGracefully()` on Minimize/Submit so pending transcription can finish before sync.

---

## Summary: Rules of Thumb

| Do | Don’t |
|----|-------|
| Write to `mInputLogic.mConnection` (app) for voice and all text | Write to the extract view directly |
| Read from `mInputLogic.mConnection` for voice context | Read from the extract view for voice context |
| Use standard `ExtractEditText` | Subclass or block `setExtractedText()` |
| Let the framework sync app → extract view | Try to make the extract view independent |
| Sync fullscreen text back to app on exit | Assume the extract view is the source of truth |

**Bottom line**: The extract view is a read-only display of the app’s field. All edits go through `InputConnection` to the app; the framework updates the extract view.
