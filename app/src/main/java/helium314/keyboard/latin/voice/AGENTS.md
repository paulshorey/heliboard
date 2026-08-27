# latin/voice

Gemini Live realtime transcription pipeline (`gemini-3.5-transcribe-live`).

## Direct files
- `GeminiTranscriptionClient.kt` - Live API WebSocket client, tiered `setup` payload, base64 audio framing, transcript reassembly from `serverContent.inputTranscription`.
- `TranscriptSegment.kt` - finalized transcript chunk shared between the client and the IME pipeline.
- `TranscriptPostProcessor.kt` - local cleanup/formatting for finalized transcript text.
- `VoiceContextVocabulary.kt` - builds `inputAudioTranscription.customVocabulary` from user terms, built-in terms, and proper nouns harvested from editor text.
- `VoiceInputManager.kt` - record/stream/orchestrate voice sessions and deliver finalized text.
- `VoiceRecorder.kt` - microphone capture of PCM audio.

## Non-obvious notes
- This pipeline is tuned for **accuracy over latency** on purpose. Dictated text lands directly in the user's editor, so do not trade transcript quality for responsiveness.
- Voice recording starts from the fixed right-edge mic in `suggestions_strip.xml` (`R.id.voice_input_key`), not from `ToolbarKey.VOICE`; the toolbar VOICE key switches to Android's shortcut/voice IME.
- Final insertion happens in `LatinIME`, not in this folder: `prepareVoiceTranscriptionText()` handles leading spaces, mid-sentence casing, and trailing punctuation before `commitVoiceTranscriptionText()` clears typed-word state with `finishInput()` and commits directly through `InputConnection`.
- Paragraph-level cleanup runs after commit through `runTranscriptPostProcessing()` / `TranscriptPostProcessor.processCurrentParagraph()`, covering spoken punctuation, paragraph commands, and leftover comma-attached fillers such as "um," / "uh,".
- Preference keys/defaults live in `latin/settings/`, while the settings screens live in `settings/screens/TranscriptionScreen.kt`, `settings/screens/VoiceVocabularyScreen.kt`, and `settings/screens/VoiceDiagnosticsScreen.kt`.
- **`inputAudioTranscription` is a sibling of `generationConfig` in `setup`, never a child.** Nesting it closes the socket with 1007, and Google's own Live Translate guide documents the broken shape. `responseModalities` must be `["TEXT"]` and *does* belong inside `generationConfig`.
- The transcribe model's documented feature list is narrower than the `setup` proto it accepts, and an unsupported field is a hard 1007. `SetupTier` (`FULL` → `NO_SYSTEM_INSTRUCTION` → `NO_REALTIME_CONFIG` → `MINIMAL`) plus the retry in `retrySetupWithLowerTier` keep that from killing voice input; the working tier is cached in `negotiatedSetupTier`. Put any new `setup` field in the tier matching how well-documented it is for this model.
- Audio must wait for `{"setupComplete":{}}`. `VoiceInputManager` buffers chunks until `onStreamReady` fires, unlike the previous provider where the stream was usable as soon as the config frame was queued.
- Audio goes out as **JSON text frames** with unwrapped base64 (`okio ByteString.base64()`), never as binary frames.
- Only `serverContent.inputTranscription` is committed. `interimInputTranscription` is speculative and discarded; `modelTurn` is ignored so a generated response cannot leak into the editor. A single server event can carry several of these fields, so each is checked independently.
- Finalized transcripts have arrived both as per-utterance deltas and as text that grows per message, and the semantics changed between model generations. `TranscriptAccumulator` compares each transcript against the previous one, emitting only the suffix for a prefix-extension, everything for unrelated text, and nothing for an identical repeat. `turnComplete` resets it.
- `attachesToPrevious` covers leading attaching punctuation and mid-word resumption (`head` then `heading` yields `ing`, not `head ing`).
- Turn finalization is Hybrid VAD: server VAD stays enabled for accurate onset with prefix padding, but is configured patient about ending speech (`END_SENSITIVITY_LOW`, `silenceDurationMs` default 1500). `audioStreamEnd` is the client's backstop, sent after local silence, on mic pause, and on stop. The session stays open and the next audio chunk reopens the stream.
- Sending `audioStreamEnd` on **mic pause** is not optional: a turn left open with no incoming audio is the dominant cause of the Live API dropping the connection with 1011. There is no application-level keepalive in this protocol.
- Sessions are capped at 10 minutes. `goAway.timeLeft` arrives as a protobuf-Duration **string** (`"30s"`). `VoiceInputManager.scheduleSessionRotate` opens a fresh connection before the deadline and after 9 minutes; rotation does not consume a reconnect attempt.
- `VoiceContextVocabulary` sends **only harvested words**, never the editor text itself. Neither `systemInstruction` nor a seeded `clientContent` history is a documented input for this model, and feeding an already-typed paragraph to a generative model risks it echoing that text back as transcription.
- `VoiceInputManager` preserves FIFO transcript delivery, coalesces the oldest transcript entries if its queue reaches 64, and drops the oldest buffered audio chunks if the stream is not ready after 300 chunks.
- `GeminiTranscriptionClient.streamingEndpoint` exists so `GeminiTranscriptionClientStreamTest` can point the client at a local WebSocket server. Nothing in production changes it.
- The API key travels in the WebSocket query string, so `Log.redactVoiceDiagnosticMessage` strips `key=` from URLs as well as `api_key=` from JSON.
- `tools/gemini-live-smoke-test.py` sends these exact payloads to the real endpoint; use it with a working `GEMINI_API_KEY` to confirm anything the unit tests cannot.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
