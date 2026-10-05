# MAI voice transcription

HeliBoard sends microphone audio to a user-configured Azure deployment of **Microsoft MAI-Transcribe-2-Streaming** through its Realtime WebSocket API. The app receives full completed segments and inserts them through the active editor's `InputConnection`.

## Configure Azure and HeliBoard

Create a Microsoft Foundry resource and deploy MAI-Transcribe-2-Streaming using the [official setup and Realtime guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime). Microsoft currently labels the model a public preview; check the guide for current availability and service limits.

In **Settings → Transcription**, enter:

| Setting | Value |
| --- | --- |
| Azure resource endpoint | Your HTTPS resource root, for example `https://your-resource.services.ai.azure.com` |
| MAI deployment name | The exact deployment name from Foundry; this can differ from the model's catalog name |
| Azure API key | A key for that resource |
| Detect spoken language | Off sends the keyboard language as a hint; on requests automatic detection |

The endpoint must be a resource root without credentials, a query, fragment, or additional path. HeliBoard constructs `/mai/v1/realtime?intent=transcription` and supplies the key in an `api-key` handshake header. The key field is masked and credentials are never included in application logs. Keys are stored in the app's existing private preferences; do not embed a resource key in the APK, source, or scripts.

Onboarding links to the complete Transcription screen and marks configuration complete only when all required values pass local validation. Validation does not authenticate against Azure: resource access and deployment availability are checked when a recording connects.

## App flow

`VoiceRecorder` starts local recording immediately, while `VoiceInputManager` opens `MaiTranscriptionClient`. It records signed little-endian PCM16, mono at 16 kHz, in 100 ms chunks. The manager holds up to 300 startup chunks until the session is configured. A full buffer stops recording with an explicit error; it never drops old speech to make room.

The client waits for `session.created`, sends `session.update`, then waits for `session.updated` before declaring readiness. Its session specifies the deployment, PCM format, optional language, and null turn detection and noise reduction. Audio is sent as base64 JSON append events without a WAV header. See the [Microsoft Realtime protocol guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime) for the event schema.

The recorder's adaptive RMS detector triggers a commit at a speech boundary. After a silence commit, the manager holds silent audio and retains a 300 ms prefix for the next onset. The default boundary is two seconds; the silence threshold and auto-stop timeout remain adjustable in Transcription settings. The local detector controls when HeliBoard requests a completed segment.

The app inserts only full `completed.transcript` results. Delta and intermediate events do not enter the editor, so formatting never operates on an unfinished word. A `committed` acknowledgement leaves the result pending. Outstanding commits form a FIFO queue; identified results can arrive out of order and still reach the editor in commit order. Duplicate item IDs are ignored. Equal text in different commits is preserved, including deliberate repetitions. Minimal events without an item ID use FIFO matching.

## Pause, stop, cancellation, and rotation

Pause stops microphone capture and commits queued speech while leaving the connection available for resume. Stop allows already queued recorder callbacks to upload the final audio, sends a final nonempty commit, and keeps receiving until every outstanding completion has arrived. The keyboard's processing state stays active while a connection, queued audio, or final result is pending.

Cancel, editor-context changes, and destruction invalidate the recording and connection tokens. Queued network events cannot insert text after cancellation. New recordings wait for a stopping session to drain unless the user cancels it.

The client has a 30-second handshake timeout and a 60-second deadline for each outstanding commit. A timeout reports incomplete dictation and discards provisional text. These are application deadlines, not promises about service latency. Connections rotate after 55 minutes to stay within the service's one-hour session limit. Rotation drains the outgoing connection while newly captured audio buffers, then opens its replacement.

A recoverable connection failure before speech is accepted uses up to three retries, with 500 ms, one-second, and two-second backoff. Auth/configuration failures stop immediately. A failed stream containing uploaded unfinalized speech stops with an incomplete-segment error: the app cannot safely reconstruct what Azure processed. Buffered audio has a fixed memory bound, and the client also rejects uploads when the socket's outgoing queue falls behind.

## Editor insertion and local formatting

`LatinIME` prepares each completed segment for surrounding spaces and casing, clears the typed-word state with `finishInput()`, and commits through `InputConnection` in a batch edit. `TranscriptPostProcessor` handles local spoken punctuation, paragraph commands, and comma-attached fillers after insertion. This keeps ordinary typing's `WordComposer` and `EditorWordMirror` contracts intact. No editor text or recognition vocabulary is sent to the service.

The fixed right-edge microphone controls this pipeline. The configurable toolbar VOICE action still invokes Android's system voice IME shortcut.

## Validation

Run protocol, preference, and formatting regressions:

```bash
./gradlew :app:testDebugNoMinifyUnitTest \
  --tests 'helium314.keyboard.latin.voice.*' \
  --tests 'helium314.keyboard.latin.utils.LogVoiceDiagnosticsTest'
```

`MaiTranscriptionClientStreamTest` uses a real local WebSocket through MockWebServer. It verifies header authentication, readiness, PCM framing, explicit commits, finalization, ordered/repeated phrases, timeout errors, and cancellation. These checks do not prove recognition quality or cloud access.

For a credentialed service check, install `websockets` in a Python 3.11+ environment and set `AZURE_MAI_ENDPOINT`, `AZURE_MAI_API_KEY`, and `AZURE_MAI_DEPLOYMENT_NAME` outside the repository. Run:

```bash
python3 tools/mai-streaming-smoke-test.py --pcm /path/to/mono-16khz.pcm --language en
```

The input must be headerless mono 16 kHz signed PCM16. The tool paces audio, requests periodic commits, and drains every completed result before closing. An empty language hint enables automatic detection. It prints completed transcripts for inspection and returns failure on service error, invalid input, or finalization timeout.

On a device, also check microphone permission, a short phrase, pause/resume, stop immediately after the last word, repeated phrases, a connection failure, and editor switches. Inspect Settings → Transcription → Voice diagnostics for lengths and lifecycle messages without raw transcript payloads or keys. Build the canonical APK with `./tools/build-dist-apk.sh`.
