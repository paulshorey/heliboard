# app/src/main/jni

Native code for dictionary lookup, suggestion generation, and keyboard proximity data.

## Direct files
- `Android.bp` - Soong build metadata.
- `Android.mk` - ndk-build entry point for app native code.
- `Application.mk` - NDK build configuration.
- `CleanupNativeFileList.mk` - helper make fragment for native file lists.
- `com_android_inputmethod_keyboard_ProximityInfo.cpp` - JNI bridge for keyboard proximity info.
- `com_android_inputmethod_keyboard_ProximityInfo.h` - JNI header for proximity info.
- `com_android_inputmethod_latin_BinaryDictionary.cpp` - JNI bridge for binary dictionary operations.
- `com_android_inputmethod_latin_BinaryDictionary.h` - JNI header for binary dictionary operations.
- `com_android_inputmethod_latin_BinaryDictionaryUtils.cpp` - JNI bridge for dictionary utility operations.
- `com_android_inputmethod_latin_BinaryDictionaryUtils.h` - JNI header for dictionary utility operations.
- `com_android_inputmethod_latin_DicTraverseSession.cpp` - JNI bridge for dictionary traversal sessions.
- `com_android_inputmethod_latin_DicTraverseSession.h` - JNI header for traversal sessions.
- `HostUnitTests.mk` - native host-unit-test make config.
- `jni_common.cpp` - shared JNI helper code.
- `jni_common.h` - shared JNI helper declarations.
- `NativeFileList.mk` - native source file list include.
- `run-tests.sh` - native test runner helper.
- `TargetUnitTests.mk` - target/native unit test make config.

## Subfolders
- `src/` - native implementation split by `dictionary/`, `suggest/`, and `utils/`.
- `tests/` - native unit tests mirroring major native areas.

## Non-obvious notes
- Java/Kotlin classes such as `JniUtils.java`, `Dictionary.java`, and `DecoderSpecificConstants.kt` depend on this folder's contracts.
- Avoid renaming JNI symbols casually; their names are part of the Java/native binding surface.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
