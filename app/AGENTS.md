# app module

This is the Android application module. Most product work stays inside this tree.

## Direct files
- `build.gradle.kts` - app module build, variants, signing, native build, dependencies, and test setup.
- `proguard-rules.pro` - shrinker rules used by minified builds.

## Subfolders
- `src/main/` - manifest, production code, resources, assets, JNI.
- `src/test/` - JVM and Robolectric tests.

## Non-obvious notes
- `debug` is intentionally minified to keep the distributable APK small enough; use `debugNoMinify` for faster local iteration.
- The app module enables both Compose and classic Views.
- Native code is built from `src/main/jni/Android.mk`, so Java/Kotlin changes in dictionary/JNI glue sometimes require native awareness too.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
