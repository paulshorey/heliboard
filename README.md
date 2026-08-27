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
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|GeminiTranscription|VoiceRecorder|VOICE_'
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

## Gemini Live voice transcription

HeliBoard uses [Gemini Live Transcribe](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)
(`gemini-3.5-transcribe-live`) for voice-to-text, over the Live API's bidirectional
WebSocket. The session configuration is assembled in `GeminiTranscriptionClient.kt`
and sent as a single `setup` JSON text frame; the API key travels in the WebSocket
query string.

The pipeline is tuned for **accuracy over latency**. Dictated text is committed
straight into the user's editor, so a wrong word or a misplaced period costs more
than a second of waiting.

### Where the integration lives

`app/src/main/java/helium314/keyboard/latin/voice/GeminiTranscriptionClient.kt`

The client:

- connects to `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=…`
  with `model = "models/gemini-3.5-transcribe-live"` and `responseModalities = ["TEXT"]`
- sends `inputAudioTranscription` as a **sibling** of `generationConfig` (nesting it
  closes the socket with 1007) carrying `mode`, `languageCodes` and `customVocabulary`
- requests `mode: "SMART"` so Gemini removes filler words, resolves spoken
  self-corrections, and applies punctuation, casing and list formatting
- tunes server VAD for patience: `endOfSpeechSensitivity: END_SENSITIVITY_LOW` with a
  1500 ms `silenceDurationMs`, plus `START_SENSITIVITY_HIGH` and `prefixPaddingMs: 300`
  so the first syllable is never clipped
- maps the active keyboard subtype onto a documented BCP-47 `languageCodes` entry, or
  sends `[]` so Gemini auto-detects when nothing matches
- sends `customVocabulary` (≤100 terms) built from the user's list, a small built-in
  list, and proper nouns harvested from editor text near the caret
- waits for `{"setupComplete":{}}` before releasing buffered audio, then streams
  100 ms chunks of base64 PCM16 as JSON text frames (`audio/pcm;rate=16000`)
- commits only `serverContent.inputTranscription`; `interimInputTranscription` is
  discarded and `modelTurn` is ignored so no generated response can reach the editor
- sends `{"realtimeInput":{"audioStreamEnd":true}}` after local silence, on mic pause,
  and on stop — then keeps reading for 8 s so the trailing phrase still arrives
- rotates onto a fresh connection on `goAway` and before the 10-minute session cap

### Setup-tier degradation

The transcribe model's documented feature list is narrower than the shared `setup`
proto it accepts, and an unsupported field is rejected with close code 1007 — which
would leave voice input permanently dead. So `setup` is rendered at one of four tiers
and the client retries one lower on 1007:

`FULL` → `NO_SYSTEM_INSTRUCTION` → `NO_REALTIME_CONFIG` → `MINIMAL`

The working tier is cached for the rest of the process, so a schema mismatch costs one
reconnect per app run. Run `tools/gemini-live-smoke-test.py --probe-setup` with a real
key to see which tiers the server accepts today.

### Custom voice vocabulary

Settings → Transcription → **Custom voice vocabulary** (`VoiceVocabularyScreen.kt`)
lets users add `customVocabulary` terms (one per line, pref
`PREF_GEMINI_CUSTOM_VOCABULARY`). These merge with the built-in list and with
editor-harvested proper nouns at every session start.

### What HeliBoard does not send

Verbatim editor text is deliberately never sent to the model. Neither
`systemInstruction` nor a seeded `clientContent` history is a documented input for this
model, and feeding an already-typed paragraph to a generative model risks it echoing
that text back as transcription. `customVocabulary` is the documented channel for
conveying what the user has written, and local pre/post-processing in `LatinIME`
handles casing and punctuation continuity with surrounding text.

Speaker diarization and word-level timestamps are not supported by the Live API.

### Getting an API key

Create one at [aistudio.google.com/apikey](https://aistudio.google.com/apikey) and
paste it into Settings → Transcription (or Settings → Setup this app). The key stays on
the device and is sent only to Google.

### User-facing settings (Settings → Transcription)

| Setting                        | Pref key                                | Default  | Description                                                                                     |
| ------------------------------ | --------------------------------------- | -------- | ----------------------------------------------------------------------------------------------- |
| Google Gemini API Key          | `PREF_GEMINI_API_KEY`                   | `""`     | Gemini API key with Live API access                                                             |
| Smart transcription            | `PREF_GEMINI_TRANSCRIPTION_MODE`        | `SMART`  | `SMART` cleans disfluencies and formats; `VERBATIM` is literal                                  |
| Learn names from the text field| `PREF_GEMINI_USE_EDITOR_CONTEXT`        | `true`   | Harvest proper nouns near the caret into `customVocabulary`                                     |
| Custom voice vocabulary        | `PREF_GEMINI_CUSTOM_VOCABULARY`         | `""`     | User-defined `customVocabulary`, one term per line                                              |
| Detect spoken language         | `PREF_GEMINI_AUTO_DETECT_LANGUAGE`      | `false`  | Send `languageCodes: []` instead of the keyboard language                                       |
| End-of-speech pause (ms)       | `PREF_GEMINI_END_OF_SPEECH_SILENCE_MS`  | `1500`   | `silenceDurationMs`, 400–5000. Higher is more accurate; short values fragment sentences         |
| Chunk silence duration (s)     | `PREF_VOICE_CHUNK_SILENCE_SECONDS`      | `2`      | Local pause that sends `audioStreamEnd` as a backstop. Keep longer than the end-of-speech pause |

Preference keys are in `Settings.java`, defaults in `Defaults.kt`, read/write logic in
`TranscriptionPreferences.kt`. `TranscriptionPreferences.migrateLegacyProviderPrefs`
erases the preference keys of the providers used before Gemini, carrying only the
user's own vocabulary list across.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- Gemini transcription pipeline: `docs/gemini-transcription.md`
- Agent instructions: `AGENTS.md`
- Gemini Live API reference for this app: `.cursor/skills/voice-transcription/api-reference.md`
