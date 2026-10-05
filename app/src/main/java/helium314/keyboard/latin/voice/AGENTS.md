# latin/voice

Microsoft MAI-Transcribe-2-Streaming dictation through Azure's Realtime WebSocket API.

## Direct files
- `MaiTranscriptionClient.kt` - authenticated WebSocket, session configuration, PCM append/commit events, ordered completed transcripts, and connection/commit timeouts.
- `TranscriptSegment.kt` - completed text passed into the IME, including punctuation attachment metadata.
- `TranscriptPostProcessor.kt` - local spoken-punctuation, paragraph-command, and filler cleanup.
- `VoiceInputManager.kt` - microphone lifecycle, bounded startup audio buffer, silence commits, pause/stop draining, reconnection, and session rotation.
- `VoiceRecorder.kt` - mono PCM16 at 16 kHz with adaptive local silence detection.

## Protocol and lifecycle contracts
- Follow the [official Realtime guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime). The model is `MAI-Transcribe-2-Streaming`; `session.audio.input.transcription.model` contains the user's Azure deployment name.
- Connect to `wss://<resource>.services.ai.azure.com/mai/v1/realtime?intent=transcription` with an `api-key` header. Never put credentials in URLs or logs.
- Wait for `session.created`, send `session.update`, and release buffered audio only after `session.updated`. Configure `audio/pcm` at 16000 Hz, with `turn_detection` and `noise_reduction` explicitly null.
- `input_audio_buffer.append` carries nonempty base64 PCM16 without a WAV header. The recorder emits 100 ms chunks, matching Microsoft's sample's latency/overhead tradeoff.
- Local silence, pause, and stop request `input_audio_buffer.commit`. No commit is sent for an empty audio buffer. Silent audio is held after a speech boundary, with a 300 ms prefix retained for the next onset.
- Only `conversation.item.input_audio_transcription.completed.transcript` reaches the editor. Intermediate hypotheses and deltas are held; never promote an intermediate after timeout or failure. `input_audio_buffer.committed` is only an acknowledgement.
- Completed results drain in commit order. Deduplicate by `item_id`, not by transcript text: repeated dictated phrases are valid. The documented minimal events can omit `item_id` and are matched in FIFO order.
- All client state, timers, and callbacks run on the main looper. Connection/session tokens suppress stale events after cancellation, new recording, or rotation.
- Stop drains every outstanding completion before closing; `hasPendingProcessing()` stays true while finalization or startup is pending. Pause keeps the socket available. Rotation at 55 minutes drains the outgoing connection while new microphone chunks buffer, then opens a replacement before the one-hour limit.
- Retry only recoverable failures without uploaded unfinalized audio, with at most three retries. Already-uploaded speech cannot safely be replayed after a broken connection; report incomplete dictation. Startup audio is bounded at 300 chunks; overflow and stalled socket uploads stop with an error instead of dropping speech.
- The fixed right-edge mic invokes this pipeline. `ToolbarKey.VOICE` invokes the system shortcut voice IME.
- `LatinIME` calls `finishInput()` then commits completed text through `InputConnection`, followed by local paragraph post-processing in the same batch edit.
- Settings live in `latin/settings/TranscriptionPreferences.kt` and `settings/screens/TranscriptionScreen.kt`. No editor context is sent to the service.
- `MaiTranscriptionClient` accepts an internal endpoint override for local MockWebServer tests; production always derives its secure URL from the resource root setting. Use `tools/mai-streaming-smoke-test.py` for credentialed service verification.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
