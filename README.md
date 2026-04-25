# HeliBoard

HeliBoard is an Android keyboard app based on AOSP / OpenBoard, extended here with experimental features such as voice-to-text, smart auto-capitalization, and the standalone "full app" editing mode.

## Build an installable APK

To generate the phone-installable debug APK and place it in the canonical repository location, run:

```bash
./tools/build-dist-apk.sh
```

This script:

- sets up the Android SDK for the current shell if needed
- builds the debug APK with Gradle
- removes older files from `./dist`
- writes the latest installable artifact to:
  - `./dist/HeliBoard.apk`

Only one installable APK should exist in `./dist` at a time, and regenerating it should overwrite the previous artifact.

## Soniox voice transcription

HeliBoard uses [Soniox real-time transcription](https://soniox.com/docs/stt/api-reference/websocket-api) for voice-to-text. The session configuration is assembled in `SonioxTranscriptionClient.kt` and sent as a single JSON config text frame at the start of the WebSocket session (Soniox authenticates via the JSON `api_key` field, not HTTP headers).

### Where the integration lives

`app/src/main/java/helium314/keyboard/latin/voice/SonioxTranscriptionClient.kt`

The client:

- pins `model = "stt-rt-preview"` (TODO: revisit when `stt-rt-v4` becomes the recommended default)
- sends raw PCM (`audio_format = "pcm_s16le"`, `sample_rate = 16000`, `num_channels = 1`) to match `VoiceRecorder` output
- maps the active keyboard subtype's base language to a single-element `language_hints` array (omitting it when the subtype has no usable language so Soniox auto-detects)
- forwards the user's endpoint-detection and diarization preferences
- streams binary PCM frames immediately after queuing the start config
- ends the session by sending an empty WebSocket frame and waiting for `{"finished": true}` (with an 8 s grace timeout)

### Speaker diarization

When enabled, Soniox tags every token with a `speaker` string ID (`"1"`, `"2"`, …). The client locks onto the first non-empty `speaker` it observes and drops tokens from any other speaker. Soniox's documented IDs aren't guaranteed to correspond to the local speaker; if the locked ID drifts, the user can briefly stop and restart recording.

### What Soniox does **not** expose

The Soniox real-time API does not expose a custom-vocabulary list, post-transcription replacements, output locale, disfluency removal flag, or a punctuation-sensitivity knob. Punctuation is decided automatically by the model. None of those toggles exist in HeliBoard's settings.

### User-facing settings (Settings → Transcription)

| Setting | Pref key | Default | Description |
|---------|----------|---------|-------------|
| API key | `PREF_SONIOX_API_KEY` | `""` | Soniox API key |
| Speaker diarization | `PREF_SONIOX_DIARIZATION` | `true` | Filter to primary speaker |
| Enable endpoint detection | `PREF_SONIOX_ENABLE_ENDPOINT_DETECTION` | `true` | Finalize tokens immediately when Soniox detects the speaker has stopped talking |
| Max endpoint delay (ms) | `PREF_SONIOX_MAX_ENDPOINT_DELAY_MS` | `2000` | Soniox-documented bounds: 500–3000 |

Preference keys are in `Settings.java`, defaults in `Defaults.kt`, read/write logic in `TranscriptionPreferences.kt`. `TranscriptionPreferences.readSonioxApiKey` migrates legacy `speechmatics_api_key` and `deepgram_api_key` entries to the new pref the first time it runs.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- Soniox transcription pipeline: `docs/soniox-transcription.md`
- Agent instructions: `AGENTS.md`
- Soniox API reference: `.cursor/skills/voice-transcription/api-reference.md`

## Run in debug mode:
```
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|SonioxTranscription|VoiceRecorder|Soniox|VOICE_'
```
or just install without logs:
```
./gradlew installDebug
```

