# latin/voice

Gemini Live realtime transcription pipeline (`gemini-3.5-transcribe-live`).

## Direct files
- `GeminiTranscriptionClient.kt` - Live API WebSocket client, tiered `setup` payload, base64 audio framing, transcript reassembly from `serverContent.inputTranscription`.
- `TranscriptSegment.kt` - finalized transcript chunk shared between the client and the IME pipeline.
- `TranscriptPostProcessor.kt` - local cleanup/formatting for finalized transcript text.
- `VoiceContextVocabulary.kt` - builds `inputAudioTranscription.customVocabulary` from user terms, built-in terms, and proper nouns harvested from editor text.
- `VoiceInputManager.kt` - record/stream/orchestrate voice sessions and deliver finalized text.
- `VoiceRecorder.kt` - microphone capture of PCM audio.
- `VoiceNetworkMonitor.kt` - default-network preflight and terminal loss observation across supported Android versions.

## Non-obvious notes
- Accuracy takes priority over latency. Only `inputTranscription` reaches the editor; interims are never promoted after EOF, timeout, failure, or replacement. `modelTurn` is ignored.
- `VoiceInputManager` serializes capture, controls, callbacks, and editor acknowledgment on the main looper. Keep a transcript at the FIFO head until the listener reports complete editor acceptance. Rejection/exception stops all later writes; never retry a possibly partial edit.
- One recording owns one connection. Transport failure, network loss, queue overflow, protocol error, or timeout is terminal. `goAway`/nine minutes stops capture and drains; require explicit restart. Only setup-schema fallback before readiness may retry, within the original 12-second deadline.
- `VoiceNetworkMonitor` requires validated default internet on API 23+, connectedness on 21–22, observes loss through drain, and invalidates queued observations when stopped. Default-network callbacks are API 24+; the older path uses a dynamic receiver.
- Audio and `audioStreamEnd` share one FIFO. Bound raw PCM to 960,000 bytes and encoded socket audio to 256 KiB. Backpressure retains the exact head; controls must never overtake it. Finalized text is bounded to 64 entries; overflow stops without dropping/coalescing data.
- Forward quiet PCM too. Local RMS VAD controls silence finalization and auto-stop, never which microphone samples are sent.
- `send(true)` is local acceptance, not server receipt. Input transcripts are independent of turn messages; `turnComplete` is not an audio watermark. Oldest queued PCM and pending-speech response/final progress have 30-second deadlines that new PCM/interims cannot extend. Pure silence does not arm speech progress. Stop reads finals for 8 seconds before close; the entire close wait is bounded to 15 seconds.
- A final cannot clear pending speech while PCM or socket frames remain queued locally. A later final after those queues drain can clear it; draining alone is not acknowledgment.
- Failure invalidates recording/connection generations before stopping capture. Preserve inserted text and mark possible loss once with `[Dictation interrupted]` if audio was captured and the stream reached readiness. Startup rejection before `setupComplete` leaves the editor untouched and reports why dictation could not start; no audio was submitted. Suppress the marker after editor rejection or cancellation.
- Classify depleted prepayment credits from the service detail before a generic quota/status message, including status-less/truncated 1011 closes and HTTP handshake bodies. A rejected project never reconnects automatically.
- Log session phase/elapsed time, captured/accepted audio counts, queue sizes, final acceptance and last-response age. Close diagnostics retain code, readiness and sanitized service detail. Do not log transcript payloads or every PCM/interim/timer event.
- Stop/join the recorder, then process already-posted tail callbacks before queuing EOF. IDLE while draining still blocks a new recording.
- A missed recorder join reports terminal failure before the EOF barrier. Retain the live thread to block capture reuse, and snapshot the callback for the entire recording loop so late native reads keep their original generation.
- `inputAudioTranscription` is beside `generationConfig`; `responseModalities: ["TEXT"]` is inside it. Audio waits for `setupComplete` and uses base64 JSON text frames. `SetupTier` negotiates only unsupported setup fields, never transport recovery.
- Finalized transcripts can be deltas or cumulative extensions. `TranscriptAccumulator` emits suffixes for prefix extensions, all unrelated text, and no identical repeat within a turn. `attachesToPrevious` handles punctuation/mid-word extensions.
- `VoiceContextVocabulary` sends only harvested words, never editor paragraphs or seeded history. Editor context is read once per explicit recording.
- Insertion and cleanup happen in `LatinIME` through `InputConnection`. `RichInputConnection` returns host acceptance and refreshes its text/cursor caches after rejected/throwing edits. Cleanup/punctuation use selection plus one replacement commit, never delete then insert; restore the caret best-effort on failure without retrying text.
- The fixed mic in `suggestions_strip.xml` starts this pipeline; the toolbar VOICE key switches to Android's shortcut voice IME.
- Preference keys live in `latin/settings`; UI lives in `settings/screens`. Protocol rationale and device acceptance checks are in `docs/gemini-transcription.md` and `.cursor/skills/voice-transcription/`.
- `streamingEndpoint` and `socketFactory` are test seams for local WebSocket and bounded-sender tests. The API key travels in a query parameter and must stay redacted in diagnostics.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
