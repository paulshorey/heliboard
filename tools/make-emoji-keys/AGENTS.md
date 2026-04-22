# tools/make-emoji-keys

Standalone generator for emoji category resources and compatibility metadata.

## Direct files
- `README.md` - maintenance instructions for updating Unicode emoji data and rerunning the generator.
- `build.gradle` - build config for the generator module and `makeEmoji` task.
- `convert_new_emojis.py` - helper for converting pasted emoji support info into the Android support table format.

## Key subfolders
- `src/main/kotlin/.../emoji/` - parsers, generator entry point, and helper utilities.
- `src/main/kotlin/.../emoji/model/` - typed emoji data models.
- `src/main/resources/emoji/ucd/` - versioned Unicode `emoji-test.txt` inputs.
- `src/main/resources/emoji/android-emoji-support.txt` - Android-version support mapping used during generation.

## Non-obvious notes
- The UCD version folders are data inputs, not archives; keep naming in decimal Unicode-version form.
- Generated output must stay compatible with the runtime emoji picker and `assets/emoji/minApi.txt` expectations.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
