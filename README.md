# HeliBoard

HeliBoard is an Android keyboard app based on AOSP / OpenBoard, extended here with experimental features such as voice-to-text, smart auto-capitalization, and the standalone "full app" editing mode.

## Build an installable APK

To generate the phone-installable debug APK and place it in the canonical repository location, run:

```bash
./tools/build-dist-apk.sh
```

The build discovers the installed Android SDK/JDK, verifies the APK signature and
writes `dist/HeliBoard.apk` plus `dist/apk-metadata.json`. It preserves other files
in `dist`. Every new or updated PR includes a fresh APK, including infrastructure
and documentation changes, on desktop and cloud.

After committing and pushing the task, APK and metadata, get its immutable download link:

```bash
python3 tools/apk-handoff.py link
```

Include that link in the PR body and handoff. The PR check detects stale source or
changed APK bytes. See [agent development and testing](docs/agent-development.md)
for Android Studio, Codex actions, manual phone checks and the tooling roadmap.

## Local validation and installation

```bash
./tools/android-dev.sh doctor
./tools/android-dev.sh smoke
./tools/android-dev.sh install SERIAL  # select an authorized phone/emulator explicitly
```

Use `debugNoMinify` in Android Studio for faster debugger iteration. The canonical
PR APK uses `debug` and the shared debug key, so updates preserve app data.

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

## Muse Voice Transcribe dictation

This experimental branch uses Meta `muse-voice-transcribe-1.0` over a realtime
WebSocket. Speak first after tapping the fixed strip mic: the keyboard inserts
only completed turns attributed to that first speaker. Sequential speakers can
be filtered; overlapping voices and a radio speaking first remain limitations.
Accuracy takes priority over immediate output; provisional words never enter the
editor. Pause stops the microphone and streams generated silence to retain the
selected speaker, which can remain billable.

Configure your own Meta Model API key in Settings → Transcription. It is masked
and stored in app-private storage excluded from backup. Meta account billing and
model access are required. Existing provider credentials remain untouched for
comparison builds. No shared credential is embedded in the APK.

See [Muse transcription](docs/muse-transcription.md) for the protocol, lifecycle,
privacy, live probe, and phone acceptance matrix. Live access and transcription
quality require a user-supplied key and device testing.

## Related docs

- Fullapp architecture: `.codex/skills/full-app-mode/SKILL.md`
- Muse transcription pipeline: `docs/muse-transcription.md`
- Agent instructions: `AGENTS.md`
- Muse API reference for this app: `.codex/skills/voice-transcription/api-reference.md`
