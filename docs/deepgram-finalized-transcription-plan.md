# Deepgram Finalized Transcription — Technical Implementation Plan

## Goal

Replace the current "final-only" transcription pipeline with Deepgram's full
interim → finalized → utterance-complete flow. This gives the user instant
visual feedback ("flash" of text) while Deepgram refines punctuation,
capitalization, and word accuracy in the background. When the speaker pauses,
the final corrected text replaces the provisional version — the same behavior
as Gboard's voice input.

---

## Current State (after post-processing removal)

| Layer | What it does today |
|---|---|
| **DeepgramTranscriptionClient** | Opens WebSocket. Drops all `is_final=false` messages. Surfaces only finalized transcripts via `onTranscriptionResult(text)`. |
| **VoiceInputManager** | Receives finalized text, enqueues in FIFO, dispatches to `VoiceInputListener.onTranscriptionResult`. |
| **LatinIME** | Receives text, trims it, calls `commitText(text, 1)` — permanent, append-only insertion. |

**Key limitation**: No interim results are shown. The user sees nothing until
Deepgram finalizes a segment (~300–1500ms after speaking). No composing region,
no replaceable text, no utterance assembly.

---

## The Three Deepgram Signals

Every WebSocket message with `"type": "Results"` carries two booleans:

| `is_final` | `speech_final` | Meaning | Action |
|---|---|---|---|
| `false` | `false` | **Interim** — preliminary guess, will be revised | Show immediately, treat as replaceable |
| `true` | `false` | **Finalized segment** — locked in, but speaker continues | Buffer segment, update display |
| `true` | `true` | **Utterance complete** — speaker paused | Commit permanently, reset |

The "flash then replace" behavior comes from the fact that interim results
cover the same audio range. Each new message replaces the entire in-progress
region, and when `speech_final=true` arrives, that region becomes permanent.

---

## WebSocket URL Parameters

```
wss://api.deepgram.com/v1/listen?
    model=nova-3
    &encoding=linear16
    &sample_rate=16000
    &channels=1
    &interim_results=true      ← NEW: enables fast interim feedback
    &dictation=true             ← NEW: "comma" → ","  "period" → "."
    &smart_format=true          ← KEEP: numbers, dates, capitalization
    &punctuate=true             ← KEEP: contextual punctuation
    &endpointing=300            ← KEEP: 300ms silence = utterance boundary
    &vad_events=true            ← KEEP: SpeechStarted / UtteranceEnd events
    &language={locale}          ← KEEP: optional locale
```

### What `dictation=true` gives us

Deepgram converts spoken punctuation commands into symbols server-side:
- "comma" → `,`
- "period" / "full stop" → `.`
- "question mark" → `?`
- "exclamation point" → `!`
- "colon" → `:`
- "semicolon" → `;`
- "new line" → `\n`
- "new paragraph" → `\n\n`

This replaces our deleted `VoicePostTranscriptionFilter` spoken-alias logic.
The conversion appears in the `punctuated_word` field on each word object in
the response, and also in the top-level `transcript` string.

### What `smart_format=true` gives us

- Auto-capitalization (sentence starts, proper nouns)
- Number formatting ("twenty one" → "21")
- Currency ("five dollars" → "$5")
- Date/time formatting
- Address formatting
- Email/URL detection

This replaces our deleted number-word conversion and capitalization logic.

### Interaction between `smart_format` and interim results

Deepgram's smart formatting is **utterance-aware** in streaming mode. For
entities that look incomplete (like a partial phone number), it waits for the
speaker to continue or finalizes after ~3 seconds of silence. This means:
- Interim results may show partially-formatted text
- Finalized results will have the complete formatting applied
- This is the intended behavior — the user sees partial text quickly and the
  final version is polished

---

## Implementation Plan — Layer by Layer

### Layer 1: DeepgramTranscriptionClient

**File**: `DeepgramTranscriptionClient.kt`

#### 1a. Update WebSocket URL

In `buildStreamingUrl()`, add two new parameters:

```kotlin
append("&interim_results=true")
append("&dictation=true")
```

#### 1b. Expand the StreamingCallback interface

Replace the single `onTranscriptionResult` with three signal-specific callbacks:

```kotlin
interface StreamingCallback {
    fun onStreamReady()

    /** Interim transcript — show immediately, will be revised. */
    fun onInterimTranscript(text: String)

    /** Finalized segment — locked in, but utterance continues. */
    fun onFinalizedSegment(text: String)

    /** Utterance complete — speaker paused. Commit this text permanently. */
    fun onUtteranceComplete(text: String)

    fun onStreamError(error: String)
    fun onStreamClosed()
}
```

#### 1c. Rewrite `handleMessage()` to dispatch all three signals

```kotlin
"Results" -> {
    val transcript = /* extract as before */
    val isFinal = json.optBoolean("is_final", false)
    val speechFinal = json.optBoolean("speech_final", false)

    when {
        !isFinal -> {
            // Interim — no deduplication needed (they always change)
            if (transcript.isNotBlank()) {
                postIfCurrent(connectionToken) {
                    callback?.onInterimTranscript(transcript)
                }
            }
        }
        speechFinal -> {
            // Utterance complete — deduplicate, then commit
            if (deduplicate(transcript, start, duration)) {
                postIfCurrent(connectionToken) {
                    callback?.onUtteranceComplete(transcript)
                }
            }
            if (isClosing) {
                clearFinalizeCloseTimer()
                webSocket?.close(1000, "client_stop")
            }
        }
        else -> {
            // Finalized segment, speaker continues
            if (transcript.isNotBlank() && deduplicate(transcript, start, duration)) {
                postIfCurrent(connectionToken) {
                    callback?.onFinalizedSegment(transcript)
                }
            }
        }
    }
}
```

**Deduplication**: Only apply fingerprint dedup to finalized results (`is_final=true`),
not to interims. Interims naturally supersede each other.

---

### Layer 2: TranscriptAssembler (new class)

**File**: `voice/TranscriptAssembler.kt` (new)

This class holds the state for one in-progress utterance and knows how to
combine finalized segments with the latest interim text.

```kotlin
class TranscriptAssembler {
    private val finalizedSegments = mutableListOf<String>()
    private var currentInterim: String = ""

    /** What should be displayed right now in the composing region. */
    fun getDisplayText(): String {
        val finalized = finalizedSegments.joinToString(" ")
        return when {
            finalized.isNotEmpty() && currentInterim.isNotEmpty() -> "$finalized $currentInterim"
            finalized.isNotEmpty() -> finalized
            else -> currentInterim
        }
    }

    fun onInterim(text: String): String {
        currentInterim = text
        return getDisplayText()
    }

    fun onFinalizedSegment(text: String): String {
        if (text.isNotBlank()) finalizedSegments.add(text)
        currentInterim = ""
        return getDisplayText()
    }

    fun onUtteranceComplete(text: String): String {
        if (text.isNotBlank()) finalizedSegments.add(text)
        val full = finalizedSegments.joinToString(" ")
        reset()
        return full
    }

    fun reset() {
        finalizedSegments.clear()
        currentInterim = ""
    }

    fun hasContent(): Boolean =
        finalizedSegments.isNotEmpty() || currentInterim.isNotEmpty()
}
```

**Why a separate class**: The assembler is pure state + logic with no
Android dependencies. Easy to unit test.

---

### Layer 3: VoiceInputManager

**File**: `VoiceInputManager.kt`

#### 3a. Add an assembler instance

```kotlin
private val transcriptAssembler = TranscriptAssembler()
```

Reset it in `invalidateActiveSession()` and `beginNewSession()`.

#### 3b. Expand VoiceInputListener

Add one new callback for interim display updates:

```kotlin
interface VoiceInputListener {
    // ... existing callbacks ...

    /** Interim/in-progress text to display. Replaces any previous interim text. */
    fun onInterimDisplayUpdate(text: String)

    /** Finalized utterance — commit permanently. (Renamed from onTranscriptionResult.) */
    fun onTranscriptionResult(text: String)
}
```

#### 3c. Wire the three DeepgramTranscriptionClient callbacks

In `startStreamingSession()`, the `StreamingCallback` implementation becomes:

```kotlin
override fun onInterimTranscript(text: String) {
    if (sessionId != activeSessionId) return
    val display = transcriptAssembler.onInterim(text)
    mainHandler.post {
        if (sessionId != activeSessionId) return@post
        listener?.onInterimDisplayUpdate(display)
    }
}

override fun onFinalizedSegment(text: String) {
    if (sessionId != activeSessionId) return
    val display = transcriptAssembler.onFinalizedSegment(text)
    mainHandler.post {
        if (sessionId != activeSessionId) return@post
        listener?.onInterimDisplayUpdate(display)
    }
}

override fun onUtteranceComplete(text: String) {
    if (sessionId != activeSessionId) return
    val committed = transcriptAssembler.onUtteranceComplete(text)
    enqueueTranscript(committed, sessionId)
}
```

**Key design decisions**:
- **Interim updates bypass the FIFO queue** — they go directly to the UI for
  minimum latency. They're transient and will be replaced.
- **Only utterance-complete results go through `enqueueTranscript`** — these are
  permanent commits that need ordering guarantees.
- The assembler lives in VoiceInputManager (not in DeepgramTranscriptionClient)
  so that reconnection can reset it without losing the client's connection state.

#### 3d. Handle session stop with pending interim text

When `stopRecording()` is called while the assembler has buffered content,
we need to handle the case where Deepgram's final `speech_final=true` arrives
during the finalize grace period. The existing `finishStreaming()` →
`FINALIZE_CLOSE_GRACE_MS` flow already handles this. But we should also handle
the edge case where the grace period expires with unflushed assembler content:

```kotlin
// In the finalize-close timer, if assembler has content, commit what we have
if (transcriptAssembler.hasContent()) {
    val partial = transcriptAssembler.getDisplayText()
    transcriptAssembler.reset()
    if (partial.isNotBlank()) {
        enqueueTranscript(partial, sessionId)
    }
}
```

---

### Layer 4: LatinIME — The Composing Region

**File**: `LatinIME.java`

This is the most critical layer. We need to show provisional text that can be
replaced, then lock it in permanently.

#### 4a. The `setComposingText` approach (recommended)

Android's `InputConnection` provides `setComposingText(text, cursorPosition)`
specifically for this use case. It creates an underlined "composing region"
that the IME owns and can freely replace. When done, `commitText()` or
`finishComposingText()` makes it permanent.

**This is what Gboard uses for its voice "flash then replace" behavior.**

#### 4b. New state tracking

```java
/** Whether voice input currently owns the composing region. */
private boolean mVoiceComposingActive = false;
```

#### 4c. Implement `onInterimDisplayUpdate`

```java
@Override
public void onInterimDisplayUpdate(@NonNull String text) {
    try {
        if (text.isEmpty()) return;

        // First interim of this utterance: clear any keyboard composing state
        if (!mVoiceComposingActive) {
            mInputLogic.mConnection.beginBatchEdit();
            mInputLogic.finishInput();
            mInputLogic.mConnection.endBatchEdit();
            mVoiceComposingActive = true;
        }

        // Replace the entire composing region with the new interim text.
        // setComposingText replaces any existing composing text atomically.
        mInputLogic.mConnection.setComposingText(text, 1);
    } catch (Exception e) {
        Log.e(TAG, "Error updating interim voice text: " + e.getMessage(), e);
    }
}
```

#### 4d. Modify `onTranscriptionResult` for final commit

```java
@Override
public void onTranscriptionResult(@NonNull String text) {
    try {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            // Clear composing region if empty
            if (mVoiceComposingActive) {
                mInputLogic.mConnection.finishComposingText();
                mVoiceComposingActive = false;
            }
            return;
        }

        mInputLogic.mConnection.beginBatchEdit();
        if (mVoiceComposingActive) {
            // Replace composing region with final text and commit
            mInputLogic.mConnection.commitText(trimmed, 1);
            mVoiceComposingActive = false;
        } else {
            // No composing region active (edge case) — direct commit
            mInputLogic.finishInput();
            mInputLogic.mConnection.commitText(trimmed, 1);
        }
        mInputLogic.mConnection.endBatchEdit();
    } catch (Exception e) {
        Log.e(TAG, "Error committing voice transcription: " + e.getMessage(), e);
    }
}
```

**Why `commitText` instead of `finishComposingText`**: `commitText` replaces
the composing region AND inserts the new text atomically. If the finalized text
differs from the last interim (which it usually will — better punctuation,
capitalization), `commitText` handles this in one call.

#### 4e. Guard: cursor movement during voice composing

The existing `onUpdateSelection` guard in LatinIME already cancels voice input
if the user moves the cursor. We need to extend this to also clean up the
composing state:

```java
// In the existing cursor-movement guard:
if (mVoiceComposingActive) {
    mInputLogic.mConnection.finishComposingText();
    mVoiceComposingActive = false;
}
```

#### 4f. Guard: voice session ends without utterance completion

In `resetVoiceInputState()`:

```java
private void resetVoiceInputState() {
    if (mVoiceComposingActive) {
        mInputLogic.mConnection.finishComposingText();
        mVoiceComposingActive = false;
    }
    mPendingNewParagraph = false;
    mKeyboardSwitcher.hideProcessingIndicator();
}
```

---

### Layer 5: Paragraph Break Handling

The current `onNewParagraphRequested()` inserts Enter keys after a configurable
silence duration. With the new flow:

- **Deepgram `dictation=true` handles "new line" and "new paragraph"** as spoken
  commands, converting them server-side. This means the `\n` will appear in the
  transcript text itself.
- **The silence-based paragraph timer** (VoiceRecorder → VoiceInputManager →
  LatinIME) should only fire after an utterance is committed, not while interim
  text is being displayed. The existing `onProcessingIdle` gate already ensures
  this.

No changes needed to paragraph break logic, but we should verify that Deepgram's
dictation output for newline commands produces literal `\n` characters that
`commitText` will insert correctly.

---

## Handling Edge Cases

### 1. Reconnection mid-utterance

If the WebSocket drops and reconnects while the assembler has buffered segments:
- **Option A**: Commit whatever the assembler has, reset, start fresh.
  Simplest and safest — avoids confusion between old and new audio ranges.
- **Option B**: Keep buffered segments, let new connection add to them.
  Risky because audio timestamps reset on reconnection.

**Recommendation**: Option A. On reconnect, flush the assembler as a committed
utterance, then start clean.

### 2. Very long utterances

If the user speaks continuously for a long time, multiple `is_final=true,
speech_final=false` segments arrive. The assembler concatenates them. The
composing region grows. Test with 30+ seconds of continuous speech to ensure
`setComposingText` handles long strings without performance issues.

### 3. Rapid utterances

If the user speaks in short bursts, `speech_final=true` arrives quickly after
each burst. Each one triggers a commit. The assembler resets between utterances.
This should work cleanly since each utterance is independent.

### 4. Empty interim followed by final

Deepgram occasionally sends empty interim transcripts during silence. The
assembler's `getDisplayText()` returns empty string → no UI update. When the
final arrives, it's handled normally.

### 5. User types on keyboard during voice

If the user starts typing while voice composing is active, `onUpdateSelection`
fires, the guard detects cursor movement, and we `finishComposingText()` to
lock in whatever text is showing. The keyboard's WordComposer takes over.

---

## Testing Strategy

### Unit tests (TranscriptAssembler)
- Sequence: interim → interim → final+speech_final → verify utterance
- Sequence: interim → final(no speech) → interim → final+speech_final
- Empty transcripts at various stages
- Reset behavior
- `hasContent()` accuracy

### Integration tests (manual on device)
1. **Basic dictation**: Speak a sentence, verify text appears progressively
   and finalizes with correct punctuation.
2. **Spoken punctuation**: Say "hello comma how are you question mark" →
   verify "Hello, how are you?"
3. **Long continuous speech**: 30+ seconds without pausing.
4. **Short bursts**: Quick separate utterances.
5. **Cancel mid-utterance**: Start speaking, cancel voice → verify composing
   text either commits or clears cleanly.
6. **Cursor movement**: Start speaking, tap elsewhere → verify text locks in.
7. **Pause/resume**: Pause recording, resume, continue speaking.
8. **Network drop**: Kill connection mid-utterance → verify partial text
   commits and recording can continue.

---

## Implementation Order

1. **TranscriptAssembler** — new class, unit tests. Zero risk, no existing code touched.
2. **DeepgramTranscriptionClient** — URL params + split callbacks. Isolated change.
3. **VoiceInputManager** — wire assembler + new listener callback. Builds on steps 1–2.
4. **LatinIME** — composing region for interim, commitText for final. Builds on step 3.
5. **End-to-end testing** on device.

Each step is independently testable. If any step introduces regressions, it
can be reverted without affecting the others (except step 4 depends on step 3's
listener change).

---

## Summary of Files Changed

| File | Change |
|---|---|
| `voice/TranscriptAssembler.kt` | **NEW** — utterance assembly logic |
| `voice/DeepgramTranscriptionClient.kt` | Add `interim_results=true`, `dictation=true`; split callback into 3 signals; rewrite `handleMessage` |
| `voice/VoiceInputManager.kt` | Add assembler instance; add `onInterimDisplayUpdate` to listener; wire 3 Deepgram callbacks through assembler |
| `LatinIME.java` | Add `mVoiceComposingActive` flag; implement `onInterimDisplayUpdate` with `setComposingText`; update `onTranscriptionResult` to `commitText`; clean up composing state in guards |

No changes needed to: `VoiceRecorder.kt` (audio capture is unchanged),
`InputLogic.java`, `RichInputConnection.java`, `WordComposer.java`.
