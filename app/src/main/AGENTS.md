# app/src/main

This folder contains the shipping Android app: manifest, assets, Java/Kotlin sources, native code, and resources.

## Direct files
- `AndroidManifest.xml` - declares the IME service, spell checker service, settings activities, permissions, and broadcast receivers.

## Subfolders
- `java/` - app runtime code.
- `jni/` - native suggestion, proximity, and dictionary implementation.
- `res/` - Android XML resources, drawables, strings, themes, templates.
- `assets/` - keyboard layouts, popup texts, emoji data, shipped dictionaries, and Khipro mappings.

## Non-obvious notes
- The manifest points the IME service at `@xml/method_dummy`; subtype/layout metadata lives in the XML resource tree rather than in code.
- The app is direct-boot aware and uses device-protected storage, so some settings/data access must work before credential unlock.
- The spell checker is a separate Android entry point from `LatinIME` and can diverge if its config/docs are ignored.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
