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

## Grok Voice Transcribe 2.0

This comparison branch uses Grok for streaming keyboard dictation. Install its
APK as an update, create an account/API key with credits at
[console.x.ai](https://console.x.ai), then enter the key in Settings → Transcription
or Setup this app. Use the fixed microphone in the suggestion strip.

Speak first after tapping the mic. Only the first detected speaker is inserted;
other voices are filtered using finalized word-level attribution. Speaker labels
are anonymous and may be wrong with overlapping voices. All captured audio,
including background voices, goes to xAI. There is no voice enrollment feature.

Accuracy takes priority over latency: completed utterances may take several
seconds to appear. Start with thought-completion confidence 0.7 and maximum
thinking pause 5000 ms; try 0.9 for more patient detection. Native provider wording,
casing and punctuation are preserved, with explicit spoken punctuation commands.

The personal key is masked and stored privately outside backups; it is never
embedded in the APK. Existing provider keys are preserved for comparison builds.
Network loss or a stalled service stops the recording and its pending queue.
Only temporary startup rejections retry automatically; active sessions never
reconnect or replay. A new recording requires a deliberate mic tap.

See [Grok implementation and phone acceptance](docs/grok-transcription.md) for
protocol, settings, failure limits and diagnostic instructions. Local tests cannot
establish streaming speech accuracy, speaker isolation or punctuation quality.

## Related docs

- Fullapp architecture: `.codex/skills/full-app-mode/SKILL.md`
- Active voice pipeline: `docs/grok-transcription.md`
- Agent instructions: `AGENTS.md`
- Historical Gemini comparison: `docs/gemini-transcription.md`
