# Research: Microphone Recording Strategy

## Problem Statement

After the user presses the microphone button, recording takes 1-2 seconds to start. During this time, a "Connecting..." notification is shown. Sometimes the connection fails entirely. This creates a poor user experience: the keyboard feels sluggish and unreliable.

## Root Cause Analysis

The current architecture requires a **fully established WebSocket connection** to OpenAI's Realtime API **before** the microphone even begins recording.

### Current Flow (from button press to recording)

```
User presses mic button
  → VoiceInputManager.startRecording()
  → State = CONNECTING  (UI shows "Connecting...")
  → RealtimeTranscriptionClient.connect()
    → OkHttp opens TCP connection to api.openai.com        ~300-600ms
    → TLS handshake                                        ~200-400ms
    → WebSocket upgrade (HTTP 101)                         ~100-200ms
    → OpenAI sends "transcription_session.created"         ~100-300ms
  → onSessionReady() fires
  → ONLY NOW: VoiceRecorder.startRecording()               ~20-50ms
  → AudioRecord.startRecording()
  → State = RECORDING  (UI shows red indicator)
```

**Total latency from button press to actual recording: ~700ms to 1500ms+ (best case)**

On slower networks or under load, this can easily be 2-3 seconds. And if any step fails, no audio was ever captured.

### Key Code Path

In `VoiceInputManager.kt`, the `startRecording()` method (line 125) first connects to the Realtime API, and only starts `VoiceRecorder` inside the `onSessionReady()` callback (line 163):

```kotlin
// Line 145: State goes to CONNECTING immediately
updateState(State.CONNECTING)

// Line 154: WebSocket connection begins
realtimeClient.connect(apiKey, language, prompt, callback = object : ... {
    override fun onSessionReady() {
        // Line 164: Audio recording only starts AFTER WebSocket is ready
        startAudioRecording()
    }
})
```

In `VoiceRecorder.kt`, the actual `AudioRecord.startRecording()` call (line 160) is fast (<50ms) but is gated behind the network connection.

### Failure Modes

1. **Network timeout**: 30-second `CONNECT_TIMEOUT_SECONDS` in `RealtimeTranscriptionClient.kt` (line 42)
2. **Authentication failure**: Invalid/expired API key → 401
3. **Rate limiting**: Too many requests → 429
4. **WebSocket drop**: Connection established but dropped mid-session → "Connection lost" error
5. **Server error**: OpenAI service issues → generic failure

In ALL these cases, zero audio was captured because recording never started.

## Architecture Options

### Option A: Keep Streaming, Start Recording Instantly (Buffer & Flush)

**Change:** Start `AudioRecord` immediately when button is pressed. Buffer audio in memory while WebSocket connects. Flush buffer when connected.

```
User presses mic button
  → VoiceRecorder.startRecording()           INSTANT (~20ms)
  → State = RECORDING (red indicator)
  → Audio chunks buffered in memory
  → SIMULTANEOUSLY: RealtimeTranscriptionClient.connect()
  → onSessionReady():
    → Flush buffered audio chunks to WebSocket
    → Continue streaming normally
```

**Pros:**
- Recording starts instantly — no perceived latency
- No lost audio from the first 1-2 seconds
- Minimal code changes (just reorder operations + add buffer)
- Keeps all existing Realtime API features (server VAD, delta transcription, streaming results)
- User sees immediate red recording indicator

**Cons:**
- Still depends on WebSocket for transcription
- If WebSocket fails, audio was captured but has nowhere to go (need fallback)
- Realtime API is expensive (~$0.06/min input audio for the session)
- Still need the WebSocket to work eventually for any transcription
- Additional memory usage for audio buffer (small: ~48KB/sec at 24kHz PCM16)

### Option B: Local Recording + Batch API Transcription (Recommended)

**Change:** Record audio locally using the device microphone. Detect pauses/silence. Send completed audio chunks to OpenAI's standard transcription endpoint (`POST /v1/audio/transcriptions`).

```
User presses mic button
  → VoiceRecorder.startRecording()           INSTANT (~20ms)
  → State = RECORDING (red indicator)
  → Audio chunks stored in local buffer
  → Client-side silence detection runs
  → On speech pause (e.g., 500ms silence):
    → Encode audio chunk as WAV/PCM
    → POST to /v1/audio/transcriptions
    → Response: transcribed text
    → Insert text into editor
  → Continue recording next chunk
```

**Pros:**
- **Recording starts instantly** — zero latency
- **Far more reliable** — recording never fails due to network; network only needed for transcription
- **10x cheaper** — standard transcription API: ~$0.006/min vs Realtime API session costs
- **Simpler architecture** — no WebSocket state management, no session configuration, no reconnection logic
- **Resilient to network issues** — audio is captured locally; if API call fails, can retry or queue
- **Works with any sample rate** — standard API accepts 16kHz (current `VoiceRecorder` SAMPLE_RATE can be lowered, saving memory)
- **Can use any transcription provider** — not locked into OpenAI's proprietary Realtime API protocol
- **Audio is preserved** — if transcription fails, audio exists locally and can be retried

**Cons:**
- **No real-time delta transcription** — text appears after each speech segment, not character-by-character
- **Need client-side silence/VAD detection** — must implement pause detection locally (but libraries exist, and simple energy-based detection works well)
- **Slightly higher per-segment latency** — each chunk needs upload + processing time (~500ms-1500ms per chunk depending on length)
- **No server-side noise reduction** — but the standard transcription API handles noisy audio well

### Option C: Different Streaming Provider

Providers like **Deepgram**, **AssemblyAI**, or **Google Cloud Speech-to-Text** offer streaming transcription.

**Pros:**
- May have faster connection times
- Some offer WebSocket + REST fallback
- Deepgram in particular is known for low-latency streaming

**Cons:**
- **Still has connection latency** — any streaming service requires establishing a connection first
- **Same fundamental problem** — you're still waiting for a third-party service before recording can start (unless you also implement Option A's buffering)
- **Provider lock-in** — different protocols, different SDKs
- **Additional API key** — user now needs to manage another service account
- **Pricing varies** — Deepgram ~$0.0043/min (cheaper), AssemblyAI ~$0.01/min, Google varies

### Option D: Hybrid — Local Recording + Batch API + Optional Streaming Upgrade

**Change:** Start with Option B (local recording + batch API). Optionally, if a WebSocket connection is available (pre-connected or connects fast), upgrade to streaming mode.

```
User presses mic button
  → VoiceRecorder.startRecording()           INSTANT
  → Audio buffered locally
  → Background: attempt WebSocket connection (non-blocking)
  → If WebSocket connects quickly (<500ms):
    → Flush buffer, switch to streaming mode
  → Else:
    → Continue in batch mode
    → Send chunks on silence detection via REST API
```

This is the most complex but most robust approach.

## Comparison Matrix

| Criteria | A: Buffer+Stream | B: Local+Batch | C: Alt Provider | D: Hybrid |
|---|---|---|---|---|
| Recording start latency | **~20ms** | **~20ms** | ~700-1500ms | **~20ms** |
| Reliability | Medium | **High** | Medium | **High** |
| Cost per minute | ~$0.06 | **~$0.006** | ~$0.004-0.01 | ~$0.006 |
| Real-time feedback | **Yes (deltas)** | No (per-segment) | **Yes** | Sometimes |
| Implementation complexity | Low | Medium | Medium | High |
| Network dependency | Full | Transcription only | Full | Graceful |
| Code changes needed | Small | Medium | Large (new SDK) | Large |
| Audio preserved on failure | Buffer only | **Yes, fully** | Buffer only | **Yes, fully** |

## Recommendation: Option B — Local Recording + Batch API

### Why

1. **The primary UX problem is recording start latency.** Option B eliminates it entirely.

2. **The secondary problem is reliability.** With local recording, the microphone ALWAYS works. Network is only needed to transcribe, and failures can be retried.

3. **Cost matters.** The Realtime API is ~10x more expensive than the standard transcription endpoint for the same audio. For a keyboard app that might be used frequently throughout the day, this adds up.

4. **Real-time deltas are nice but not essential for a keyboard.** The current flow already has a 1-2 second connection delay. By the time streaming starts producing deltas, Option B would have already sent the first chunk and received a complete transcription. In practice, the perceived latency of batch transcription (500ms-1500ms after a pause) is comparable or better than the current experience.

5. **Simplicity.** The WebSocket + Realtime API is a complex protocol with session management, reconnection handling, and state synchronization. A simple `POST` request with audio data is far easier to reason about, test, and maintain.

6. **Current features are preserved.** Server-side VAD can be replaced with simple client-side energy-based silence detection (threshold on RMS amplitude). The cleanup timer (3s silence → Claude cleanup) and paragraph timer (12s silence → new paragraph) work identically.

### Implementation Plan

#### Phase 1: Instant Recording Start (Quick Win)

Even before the full architecture change, we can fix the worst UX issue immediately:

**Change `VoiceInputManager.startRecording()` to start `VoiceRecorder` FIRST:**
- Start `AudioRecord` immediately on button press
- Show RECORDING state immediately (red indicator)
- Buffer audio chunks in an `ArrayList<ByteArray>` while connecting
- When WebSocket is ready, flush the buffer
- This is backward-compatible and fixes the latency problem today

#### Phase 2: Batch Transcription Client

Replace `RealtimeTranscriptionClient` with a new `BatchTranscriptionClient`:

1. **Client-side silence detection** in `VoiceRecorder`:
   - Calculate RMS energy of each audio chunk
   - Track silence duration
   - When silence exceeds threshold (e.g., 500ms), mark segment boundary
   - Emit `onSpeechSegmentComplete(audioData: ByteArray)` callback

2. **New `BatchTranscriptionClient`**:
   - `POST /v1/audio/transcriptions` with WAV-encoded audio
   - Uses `gpt-4o-transcribe` model (or `whisper-1`)
   - Returns complete transcription per segment
   - Simple OkHttp `POST`, no WebSocket

3. **Updated `VoiceInputManager`**:
   - Simplified state machine: IDLE → RECORDING → IDLE
   - No CONNECTING state needed
   - Silence timers remain the same
   - Cleanup/paragraph logic unchanged

#### Phase 3: Polish

- Tune silence detection thresholds
- Add audio format conversion (PCM → WAV encoding for API)
- Add retry logic for failed transcription requests
- Consider pre-encoding audio as opus/mp3 to reduce upload size
- Optional: show a subtle animation while each chunk is being transcribed

### Client-Side Silence Detection (Technical Notes)

Simple energy-based approach that works well for voice:

```kotlin
fun calculateRmsEnergy(audioData: ByteArray): Double {
    var sum = 0.0
    for (i in audioData.indices step 2) {
        // PCM16 little-endian
        val sample = (audioData[i].toInt() and 0xFF) or 
                     (audioData[i + 1].toInt() shl 8)
        sum += sample * sample
    }
    return sqrt(sum / (audioData.size / 2))
}

// In streaming loop:
val energy = calculateRmsEnergy(chunk)
if (energy < SILENCE_THRESHOLD) {
    silenceDurationMs += CHUNK_INTERVAL_MS
    if (silenceDurationMs >= PAUSE_DURATION_MS) {
        // Speech segment complete - send for transcription
        emitSegment()
    }
} else {
    silenceDurationMs = 0
}
```

Typical thresholds: `SILENCE_THRESHOLD` ~200-500 (for 16-bit PCM), `PAUSE_DURATION_MS` ~500-800ms.

### API Endpoint Details

The standard OpenAI transcription endpoint:

```
POST https://api.openai.com/v1/audio/transcriptions
Content-Type: multipart/form-data

file: <audio file (wav, mp3, m4a, etc.)>
model: "gpt-4o-transcribe" (or "whisper-1")
language: "en" (optional, ISO-639-1)
prompt: "..." (optional, guide transcription style)
response_format: "text" (or "json", "verbose_json")
```

- Max file size: 25MB
- Supported formats: mp3, mp4, mpeg, mpga, m4a, wav, webm
- WAV is simplest to generate from PCM data (just add a 44-byte header)

### What We Lose (and Why It's OK)

1. **Real-time delta transcription**: Currently, `onTranscriptionDelta()` is called as the Realtime API streams partial results. However, looking at the code in `LatinIME.java`, the delta callback is empty — it's not used for anything:
   ```java
   public void onTranscriptionDelta(@NonNull String text) {
       // Real-time partial transcription - could be used for live preview
   }
   ```
   So we lose nothing in practice.

2. **Server-side VAD**: The Realtime API's server-side VAD detects speech start/stop. We replace this with client-side energy-based detection, which is simpler and has zero latency (no network round-trip to detect silence).

3. **Server-side noise reduction**: The Realtime API has `input_audio_noise_reduction`. The standard transcription API handles noisy audio well on its own (Whisper was trained on noisy data).

## Cost Comparison

Assuming average usage of 5 minutes of voice input per day:

| Approach | Cost/min | Daily cost | Monthly cost |
|---|---|---|---|
| Realtime API (current) | ~$0.06 | ~$0.30 | ~$9.00 |
| Standard transcription API | ~$0.006 | ~$0.03 | ~$0.90 |

**10x cost reduction.**

## Summary

**Switch to local recording + batch API transcription.** This eliminates the 1-2 second recording start latency, dramatically improves reliability, reduces costs by 10x, and simplifies the codebase. The only tradeoff (no character-by-character delta transcription) is not currently used anyway.

For a quick win before the full refactor, start the microphone immediately on button press and buffer audio while the WebSocket connects.
