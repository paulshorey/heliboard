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

## Speechmatics voice transcription

HeliBoard uses [Speechmatics realtime transcription](https://docs.speechmatics.com/api-ref/realtime-transcription-websocket) for voice-to-text. The session configuration is assembled in `SpeechmaticsTranscriptionClient.kt` and sent as a `StartRecognition` message over WebSocket.

### Customizable features

All Speechmatics session config lives in:
`app/src/main/java/helium314/keyboard/latin/voice/SpeechmaticsTranscriptionClient.kt`

#### Custom dictionary (`additional_vocab`)

Improves recognition of specific words, brand names, or technical terms. Supports optional `sounds_like` pronunciations.

**Where to edit:** `SpeechmaticsTranscriptionClient.defaultAdditionalVocab()` — returns a `List<VocabEntry>`.

```kotlin
VocabEntry("HeliBoard"),                                    // just the word
VocabEntry("gnocchi", listOf("nyohki", "nokey", "nochi")),  // word + pronunciations
```

**Docs:** https://docs.speechmatics.com/speech-to-text/features/custom-dictionary

#### Word replacement

Substitutes specific words in the transcript after processing. Case-sensitive, applied post-transcription.

**Where to edit:** `SpeechmaticsTranscriptionClient.defaultReplacements()` — returns a `List<ReplacementRule>`.

```kotlin
ReplacementRule("heli board", "HeliBoard"),       // plain text replacement
ReplacementRule("speechmatics", "Speechmatics"),
```

**Docs:** https://docs.speechmatics.com/speech-to-text/formatting#word-replacement

#### Regex replacement

Uses ECMAScript regex in the `from` field (wrapped in `/` delimiters). Processed after plain-text replacements.

**Where to edit:** Same `defaultReplacements()` function.

```kotlin
ReplacementRule("/^[Oo]kay google$/", "OK Google"),  // regex with capture
ReplacementRule("/^[Hh]ey [Ss]iri$/", "Hey Siri"),
```

**Docs:** https://docs.speechmatics.com/speech-to-text/formatting#regex

#### Speaker diarization

Identifies speakers and filters to only the primary speaker, ignoring background voices. Configured with `max_speakers: 2` and `prefer_current_speaker: true`.

**Where to edit:** Enabled/disabled via `PREF_SPEECHMATICS_DIARIZATION` in user settings (defaults to `true`). The diarization config itself is in `buildStartRecognitionMessage()`. Speaker filtering logic is in `buildTranscriptSegment()` (the `primarySpeaker` parameter).

**Docs:** https://docs.speechmatics.com/speech-to-text/features/diarization

#### Punctuation

All marks permitted (`permitted_marks: ["all"]`, covering commas, periods, `?`, `!` for English and locale-specific marks for other languages). Sensitivity (0.0–1.0) controls how aggressively punctuation is inserted — higher values produce more commas at short pauses.

**Where to edit:**
- Default sensitivity: `Defaults.kt` → `PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT` (currently `55`, meaning 0.55, just above Speechmatics' own default of 0.5)
- Users can adjust via Settings → Transcription → Punctuation sensitivity

**Server end-of-utterance note:** `PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS` defaults to `0` (disabled). A non-zero value forces a final transcript at every pause past the threshold and terminates it with a sentence-end mark (a period in English) regardless of sensitivity, so enabling it causes "period-after-every-pause" output. Keep it at 0 to let Speechmatics choose punctuation from prosody; HeliBoard's local silence timers still handle paragraph breaks and auto-stop.

**Docs:** https://docs.speechmatics.com/features/punctuation-settings

#### Disfluency removal

Removes English hesitation words (um, uh, hmm) from transcripts.

**Where to edit:** `Defaults.kt` → `PREF_SPEECHMATICS_REMOVE_DISFLUENCIES` (defaults to `true`). Toggled in Settings → Transcription.

**Docs:** https://docs.speechmatics.com/speech-to-text/formatting#disfluencies

#### Output locale

Controls region-specific spelling (e.g. "colour" vs "color"). Defaults to `en-US` for English.

**Where to edit:** `SpeechmaticsTranscriptionClient.normalizeOutputLocale()` — maps the keyboard's active locale to a Speechmatics locale. Falls back to `en-US` when no specific region is detected.

**Docs:** https://docs.speechmatics.com/speech-to-text/formatting#output-locale

#### Operating point

Controls the transcription model quality. Set to `"enhanced"` for best accuracy.

**Where to edit:** `buildSessionConfig()` parameter `operatingPoint` (default `"enhanced"`).

### User-facing settings (Settings → Transcription)

These are adjustable at runtime via the Transcription settings screen:

| Setting | Pref key | Default | Description |
|---------|----------|---------|-------------|
| API key | `PREF_SPEECHMATICS_API_KEY` | `""` | Speechmatics API key |
| Final transcript delay | `PREF_SPEECHMATICS_MAX_DELAY_MILLIS` | `2500` | Latency target (ms) |
| End-of-utterance silence | `PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS` | `0` | Server-side silence trigger (ms). `0` disables it — recommended, because non-zero forces a period at every pause |
| Remove disfluencies | `PREF_SPEECHMATICS_REMOVE_DISFLUENCIES` | `true` | Remove um/uh/hmm |
| Punctuation sensitivity | `PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT` | `55` | 0–100 (maps to 0.0–1.0). Higher = more commas at short pauses |
| Speaker diarization | `PREF_SPEECHMATICS_DIARIZATION` | `true` | Filter to primary speaker |

Preference keys are in `Settings.java`, defaults in `Defaults.kt`, read/write logic in `TranscriptionPreferences.kt`.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- Agent instructions: `AGENTS.md`
- Speechmatics API: `.cursor/skills/voice-transcription/api-reference.md`

## Run in debug mode:
```
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|SpeechmaticsTranscription|VoiceRecorder|Speechmatics|VOICE_'
```
or just install without logs:
```
./gradlew installDebug
```

