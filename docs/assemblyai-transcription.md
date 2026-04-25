# AssemblyAI Universal-Streaming Transcription Architecture

HeliBoard uses AssemblyAI [Universal-Streaming](https://www.assemblyai.com/docs/streaming/universal-streaming)
realtime transcription for voice input. Universal-Streaming combines a neural
end-of-turn detection model with classical VAD, so the server only emits a
finalized turn when the speaker has actually finished a thought — not on every
short silence. That is the entire reason for switching off Speechmatics:
sentence boundaries are decided by speech context and grammar instead of
millisecond-level pause timing.

## Runtime flow

1. `VoiceRecorder` captures 16 kHz mono PCM16 audio immediately when the mic button is tapped.
2. `VoiceInputManager` opens an `AssemblyAITranscriptionClient` in parallel and buffers audio until the server emits a `Begin` event.
3. `AssemblyAITranscriptionClient` connects to `wss://streaming.assemblyai.com/v3/ws` (or the EU endpoint), passing all session parameters as query arguments. After `Begin`, it streams binary PCM frames directly.
4. The server emits `Turn` messages as words finalize. We only forward the message where `end_of_turn: true` AND (when `format_turns=true`) `turn_is_formatted: true`, so the editor never sees an unformatted draft of a turn before its punctuated/cased final version.
5. On graceful stop the client sends `{"type":"Terminate"}`, awaits the final formatted `Turn` plus `Termination` reply, then closes.
6. `VoiceInputManager` queues finalized transcripts in FIFO order and tracks whether each segment should attach to previous text (used for trailing punctuation like `,` and `.`).
7. `LatinIME` calls `finishInput()` and then `commitText(...)` to insert the finalized text at the caret, restoring a leading space only when the segment is a continuation rather than punctuation.

## Important files

- `app/src/main/java/helium314/keyboard/latin/voice/AssemblyAITranscriptionClient.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceRecorder.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/TranscriptPostProcessor.kt`
- `app/src/main/java/helium314/keyboard/latin/settings/TranscriptionPreferences.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/SetupAppScreen.kt`
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`

## Configuration

Settings UI (Settings → Transcription) exposes:

- **API key** (`PREF_ASSEMBLYAI_API_KEY`) — your AssemblyAI key.
- **Speech model** (`PREF_ASSEMBLYAI_SPEECH_MODEL`) — required by AssemblyAI on every connection. Defaults to `universal-streaming-english` (fastest English model). Other options: `universal-streaming-multilingual`, `u3-rt-pro` (Universal-3 Pro, highest accuracy with built-in turn-detection prompt), `whisper-rt`.
- **Formatted final transcripts** (`PREF_ASSEMBLYAI_FORMAT_TURNS`) — toggles `format_turns`. With this on, every finalized turn is delivered with punctuation, casing, and inverse text normalization (dates, times, currency, phone numbers).
- **End-of-turn confidence** (`PREF_ASSEMBLYAI_END_OF_TURN_CONFIDENCE_PERCENT`) — the threshold that drives the semantic side of turn detection. AssemblyAI's API default is 0.4; we ship 0.7 so the model holds the turn open for genuinely-incomplete sentences. Setting this to 0 disables semantic detection and reverts to silence-only endpointing — do not do that for dictation; it is the failure mode we just left.
- **Min/Max turn silence** (`PREF_ASSEMBLYAI_MIN_TURN_SILENCE_MILLIS`, `PREF_ASSEMBLYAI_MAX_TURN_SILENCE_MILLIS`) — silence floor for triggering an end-of-turn check, and the hard ceiling beyond which a turn is forced closed regardless of semantic confidence. Defaults: 600 ms / 2400 ms (more patient than the API defaults of 400 ms / 1280 ms).
- **EU endpoint** (`PREF_ASSEMBLYAI_USE_EU_ENDPOINT`) — switches to `streaming.eu.assemblyai.com`.
- **Custom keyterms** (`PREF_ASSEMBLYAI_KEYTERMS`) — newline-separated list (max 100 entries, each ≤ 50 characters) added to the [keyterm prompt](https://www.assemblyai.com/docs/streaming/keyterms-prompting). Boosts recognition of brand names, technical jargon, contacts, etc. The default list seeded by `AssemblyAITranscriptionClient.defaultKeyterms()` is always merged in.

Local silence settings (independent of AssemblyAI):

- chunk silence duration (used by the recorder for paragraph/auto-stop logic)
- silence threshold (RMS floor)
- new paragraph delay
- auto-stop delay

## Notes

- This is a hard cutover: legacy Speechmatics preference keys (`speechmatics_*`) are deleted from shared preferences on first read of `TranscriptionPreferences.readAssemblyAIApiKey`. There is no useful migration — the previous tuning targeted millisecond pause timing, which AssemblyAI replaces entirely.
- Universal-Streaming requires `speech_model` on every connection; there is no provider default.
- Universal-Streaming transcripts are immutable: once a word is marked `word_is_final: true` it never changes. The end-of-turn payload's `transcript` field already contains the final-finalized turn, so we commit it once and trust it.
- We deliberately wait for `turn_is_formatted: true` before inserting when `format_turns=true`, so the editor never receives the raw pre-formatted transcript first and then has to reconcile it with the formatted version.
- HeliBoard's local paragraph timer still drives `\n\n` insertion after the configured silence — this is independent of how AssemblyAI segments turns.
- The session connection URL carries the API key in the `Authorization` request header (no `Bearer` prefix). We support but do not yet generate temporary tokens; users with browser-side concerns can plumb a generated `token` query parameter in via `AssemblyAITranscriptionClient.buildConnectionUrl` later.
