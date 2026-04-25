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

## AssemblyAI Universal-Streaming voice transcription

HeliBoard uses [AssemblyAI Universal-Streaming](https://www.assemblyai.com/docs/streaming/universal-streaming) for voice-to-text. Universal-Streaming combines a neural end-of-turn detection model with classical VAD, so finalized turns are emitted when the speaker has actually finished a thought — not on every short silence. With `format_turns=true` the final turn arrives punctuated, cased, and ITN-formatted (dates, times, currency, phone numbers).

### Why we chose Universal-Streaming

The previous Speechmatics integration used silence-based punctuation. Brief mid-sentence pauses were treated as sentence boundaries, fragmenting dictation. AssemblyAI's semantic+acoustic turn detector replaces this entire failure mode and gives us punctuation/casing/ITN as a side effect of the same pipeline.

### Customizable features

All AssemblyAI session config is assembled in:
`app/src/main/java/helium314/keyboard/latin/voice/AssemblyAITranscriptionClient.kt`

#### Speech model

AssemblyAI Universal-Streaming requires `speech_model` on every connection — there is no provider default. HeliBoard ships `universal-streaming-english`. Other supported values:

- `universal-streaming-english` — fastest, cheapest, English-only.
- `universal-streaming-multilingual` — English/Spanish/German/French/Portuguese/Italian.
- `u3-rt-pro` — Universal-3 Pro: highest accuracy, with built-in turn-detection prompt.
- `whisper-rt` — Whisper-Streaming over AssemblyAI's infrastructure (99+ languages).

**Where to change:** Settings → Transcription → Speech model (or `Defaults.PREF_ASSEMBLYAI_SPEECH_MODEL`).

#### Formatted finals (`format_turns`)

When enabled, every completed turn arrives punctuated, cased, and ITN-formatted. Default: on.

#### End-of-turn detection

Universal-Streaming uses two parallel signals:

- **Semantic detection** — a neural model rates the probability that the speaker has finished. We commit a turn when this exceeds `end_of_turn_confidence_threshold`.
- **Acoustic detection** — VAD-driven silence floor (`min_turn_silence`) and ceiling (`max_turn_silence`).

HeliBoard defaults to a higher confidence threshold (0.7 vs AssemblyAI's API default of 0.4) so dictation pauses don't fragment sentences. Setting the threshold to `0` reverts to silence-only endpointing — that's the legacy failure mode and is intentionally not surfaced as a recommendation.

**Docs:** https://www.assemblyai.com/docs/streaming/universal-streaming/turn-detection

#### Keyterm prompting

Up to 100 keyterms (each ≤ 50 chars) can be prepended to bias the model toward specific names, brands, technical jargon, or contacts. HeliBoard ships a default list (`HeliBoard`, `AssemblyAI`, `Universal-Streaming`, etc.) and merges any user-provided keyterms on top.

**Where to change:**
- Default list: `AssemblyAITranscriptionClient.defaultKeyterms()`
- User overrides: Settings → Transcription → Custom keyterms (`PREF_ASSEMBLYAI_KEYTERMS`, newline- or comma-separated)

**Docs:** https://www.assemblyai.com/docs/streaming/keyterms-prompting

#### EU streaming endpoint

Switch the WebSocket host to `streaming.eu.assemblyai.com` for lower latency in Europe.

**Where to change:** Settings → Transcription → Use EU streaming endpoint.

### User-facing settings (Settings → Transcription)

| Setting | Pref key | Default | Description |
|---------|----------|---------|-------------|
| API key | `PREF_ASSEMBLYAI_API_KEY` | `""` | AssemblyAI API key |
| Speech model | `PREF_ASSEMBLYAI_SPEECH_MODEL` | `universal-streaming-english` | AssemblyAI model name |
| Formatted final transcripts | `PREF_ASSEMBLYAI_FORMAT_TURNS` | `true` | Punctuation, casing, ITN |
| End-of-turn confidence (0–100) | `PREF_ASSEMBLYAI_END_OF_TURN_CONFIDENCE_PERCENT` | `70` | Higher = more patient turn detection |
| Min turn silence (ms) | `PREF_ASSEMBLYAI_MIN_TURN_SILENCE_MILLIS` | `600` | Floor before semantic end-of-turn check |
| Max turn silence (ms) | `PREF_ASSEMBLYAI_MAX_TURN_SILENCE_MILLIS` | `2400` | Hard ceiling that forces turn end |
| EU endpoint | `PREF_ASSEMBLYAI_USE_EU_ENDPOINT` | `false` | Switch to streaming.eu.assemblyai.com |
| Custom keyterms | `PREF_ASSEMBLYAI_KEYTERMS` | `""` | Newline-separated, max 100 entries |

Preference keys are in `Settings.java`, defaults in `Defaults.kt`, read/write logic in `TranscriptionPreferences.kt`.

Legacy Speechmatics preference keys (`speechmatics_*`, `deepgram_api_key`) are unconditionally cleared from `SharedPreferences` on the first read after upgrade.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- AssemblyAI pipeline architecture: `docs/assemblyai-transcription.md`
- Agent instructions: `AGENTS.md`
- AssemblyAI API reference: `.cursor/skills/voice-transcription/api-reference.md`

## Run in debug mode:
```
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|AssemblyAITranscription|VoiceRecorder|VOICE_'
```
or just install without logs:
```
./gradlew installDebug
```
