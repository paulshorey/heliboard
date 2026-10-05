# latin/voice

Microsoft MAI-Transcribe-2-Streaming dictation directly through Azure Speech SDK 1.52.0.

## Direct files
- `MaiTranscriptionClient.kt` - main-looper lifecycle, supported device/region validation, advisory boundaries, final results, deduplication, and deadlines.
- `MaiSpeechSession.kt` - injectable SDK seam and `AzureMaiSpeechSession`, which owns native SDK objects and a serial worker for JNI calls/cleanup.
- `TranscriptSegment.kt` - completed text passed to the IME with punctuation attachment metadata.
- `TranscriptPostProcessor.kt` - local spoken punctuation, paragraph commands, and filler cleanup.
- `VoiceInputManager.kt` - microphone lifecycle, bounded startup buffer, silence/pause boundaries, EOF draining, startup backoff, and rotation.
- `VoiceRecorder.kt` - mono PCM16 at 16 kHz with adaptive local silence detection.

## SDK and lifecycle contracts
- Follow [Microsoft's MAI Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) and the pinned Java SDK API. Select the case-sensitive service identifier `mai-transcribe-2-streaming` explicitly. A live Central US check accepted this lowercase ID and rejected the title-cased display name used in Microsoft's examples; see `docs/mai-transcription.md`.
- Authenticate `SpeechConfig.fromEndpoint` with the resource key and `wss://<region>.stt.speech.microsoft.com/speech/universal/v2`. Regions are centralus, swedencentral, and southeastasia; key and region must match.
- SDK dictation supports Android API 26+ and ARM32/ARM64/x86-64. The app minimum remains API 21. Avoid creating native objects on unsupported devices.
- Start continuous recognition on the worker, then release buffered audio. Push headerless PCM16 in 100 ms chunks. Bound startup audio to 300 chunks and queued worker audio to 256 KB.
- Local silence and pause request `PushAudioInputStream.commit()` only for new unfinalized audio. Requests are advisory; final offsets and echoed commit tokens can settle boundaries. NoMatch settles without insertion. Missing confirmation triggers graceful EOF drain/replacement, never an acknowledgment error or guessed completion.
- Only final RecognizedSpeech results reach the editor. Deduplicate result IDs, allowing equal text in separate results. Intermediate results never reach the editor.
- Main-looper session tokens suppress stale SDK events. JNI operations and stop/disposal run on the serial worker.
- Stop closes push input and waits for EOF/session termination before disposal. Processing remains active through finalization. Mic restart during drain captures immediately and buffers until outgoing EOF, preserving final-text order. Pause retains the session for resume. Rotation at 55 minutes drains outgoing input while new chunks buffer.
- Use a 30-second startup deadline, 60-second boundary fallback to drain, and a separate 60-second EOF deadline. The 64-boundary limit also requests drain. Do not treat a deadline as a recognition latency guarantee.
- The SDK owns ACKs and replay/recovery of unconfirmed audio. The app's three retries apply only when no uploaded audio remains unfinalized. Failures with unfinished speech report incomplete dictation.
- Never log raw SDK cancellation details, credentials, or transcripts. Diagnostics contain lifecycle, error codes, and text lengths.
- LatinIME clears typed-word state with finishInput(), commits finals through InputConnection, and performs local paragraph cleanup. Preserve model punctuation, including em dashes. No editor context is sent upstream. The streaming guide documents no punctuation-strength or clean-style configuration; OutputFormat and ProfanityOption are ignored.
- Settings live in `latin/settings/TranscriptionPreferences.kt` and `settings/screens/TranscriptionScreen.kt`. The fixed mic invokes this pipeline; ToolbarKey.VOICE invokes Android's system voice IME shortcut.
- JVM tests inject `FakeMaiSpeechSession`; device-native libraries require Android validation. `tools/mai-streaming-smoke-test.py` checks the service through the Python SDK with environment credentials.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
