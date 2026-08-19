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
- writes the latest installable artifact into `./dist/`

Only one installable APK should exist in `./dist` at a time, and regenerating it should overwrite the previous artifact.

## Run in debug mode

```
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|SonioxTranscription|VoiceRecorder|Soniox|VOICE_'
```

or just install without logs:

```
./gradlew installDebug
```

## Remove disfluencies

app/src/main/java/helium314/keyboard/latin/voice/TranscriptPostProcessor.kt
line 14

```
object TranscriptPostProcessor {

    data class Rule(val find: String, val replace: String)

    val rules: List<Rule> = buildRules()

    private val disfluencyReplacements = listOf(
        Rule("—", ""),
        Rule(", hmm.", ""),
        Rule(" hmm.", ""),
        Rule("hmm.", ""),
        Rule(", um.", "."),
        Rule(" um.", "."),
        Rule(", uh.", "."),
        Rule(" uh.", "."),
        Rule(", and.", "."),
        Rule(" and.", "."),
    )
```

---

## Soniox voice transcription

HeliBoard uses [Soniox real-time transcription](https://soniox.com/docs/api-reference/stt/websocket-api) for voice-to-text. The session configuration is assembled in `SonioxTranscriptionClient.kt` and sent as a single JSON config text frame at the start of the WebSocket session (Soniox authenticates via the JSON `api_key` field, not HTTP headers).

### Where the integration lives

`app/src/main/java/helium314/keyboard/latin/voice/SonioxTranscriptionClient.kt`

The client:

- pins `model = "stt-rt-v5"`
- sends raw PCM (`audio_format = "pcm_s16le"`, `sample_rate = 16000`, `num_channels = 1`) to match `VoiceRecorder` output
- maps the active keyboard subtype's base language to a single-element `language_hints` array and sets `language_hints_strict` (omitting both when the subtype has no usable language so Soniox auto-detects)
- sends structured `context.general` for keyboard dictation (domain/setting/topic/product, plus language instructions and a one-speaker hint when diarization is on)
- sends `context.terms` as the union of a small built-in list (`HeliBoard`, `Soniox`, `Kubernetes`, …) and any user-defined custom terms from settings
- sends `context.text` from up to the most recent 4 000 characters of editor text before the cursor (via `LatinIME.buildVoiceContextText`) when available
- forwards the user's endpoint-detection and diarization preferences, and when endpoint detection is on sends v5 `endpoint_sensitivity = -0.3` (dictation-patient) with `max_endpoint_delay_ms`
- streams binary PCM frames immediately after queuing the start config
- sends `{"type":"keepalive"}` at least every 10 s during outbound gaps so a paused mic does not idle-timeout the session
- ends the session by sending an empty WebSocket frame and waiting for `{"finished": true}` (with an 8 s grace timeout)

### Speaker diarization

When enabled, Soniox tags every token with a `speaker` string ID (`"1"`, `"2"`, …). The client locks onto the first non-empty `speaker` it observes and drops tokens from any other speaker. Soniox's documented IDs aren't guaranteed to correspond to the local speaker; if the locked ID drifts, the user can briefly stop and restart recording.

### Custom voice vocabulary

Settings → Transcription → **Custom voice vocabulary** (`SonioxContextTermsScreen.kt`) lets users add `context.terms` (one term per line, pref `PREF_SONIOX_CUSTOM_TERMS`). These merge with the built-in list at every session start.

### What HeliBoard does not configure

HeliBoard does not configure Soniox direct replacement rules, output locale, disfluency removal, or a punctuation-sensitivity knob. Punctuation is decided automatically by the model. After commit, `TranscriptPostProcessor` handles local cleanup such as spelled-out punctuation.

### User-facing settings (Settings → Transcription)

| Setting                   | Pref key                                | Default | Description                                                                     |
| ------------------------- | --------------------------------------- | ------- | ------------------------------------------------------------------------------- |
| API key                   | `PREF_SONIOX_API_KEY`                   | `""`    | Soniox API key                                                                  |
| Speaker diarization       | `PREF_SONIOX_DIARIZATION`               | `true`  | Filter to primary speaker                                                       |
| Custom voice vocabulary   | `PREF_SONIOX_CUSTOM_TERMS`              | `""`    | User-defined `context.terms`, one term per line                                 |
| Enable endpoint detection | `PREF_SONIOX_ENABLE_ENDPOINT_DETECTION` | `true`  | Finalize tokens immediately when Soniox detects the speaker has stopped talking |
| Max endpoint delay (ms)   | `PREF_SONIOX_MAX_ENDPOINT_DELAY_MS`     | `2000`  | Soniox-documented bounds: 500–3000                                              |

Preference keys are in `Settings.java`, defaults in `Defaults.kt`, read/write logic in `TranscriptionPreferences.kt`. `TranscriptionPreferences.readSonioxApiKey` clears legacy `speechmatics_api_key` and `deepgram_api_key` entries because those provider-specific keys cannot authenticate with Soniox.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- Soniox transcription pipeline: `docs/soniox-transcription.md`
- Agent instructions: `AGENTS.md`
- Soniox API reference: `.cursor/skills/voice-transcription/api-reference.md`
