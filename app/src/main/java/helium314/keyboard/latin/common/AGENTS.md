# latin/common

Shared low-level types used across text logic, layout code, and JNI glue.

## Direct files
- `CollectionUtils.java` - small collection helpers.
- `Colors.kt` - key/color model definitions.
- `ComposedData.kt` - structured composed-text payload.
- `Constants.java` - Java constants shared by older code paths.
- `Constants.kt` - Kotlin constants shared across newer code.
- `CoordinateUtils.java` - packed coordinate utilities.
- `FileUtils.java` - asset/file helper methods.
- `InputPointers.java` - pointer coordinate/time buffers for touch input.
- `LocaleUtils.kt` - locale helper functions.
- `NativeSuggestOptions.java` - options object passed toward native suggestion code.
- `ResizableIntArray.java` - growable primitive int array.
- `StringUtils.java` - Java string helpers.
- `StringUtils.kt` - Kotlin string helpers and extensions.
- `SuggestionSpanUtils.kt` - Android `SuggestionSpan` helpers.
- `UnicodeSurrogate.java` - UTF-16 surrogate utilities.
- `ViewOutlineProviderUtils.kt` - outline helpers for rounded/clipped views.

## Non-obvious notes
- Both Java and Kotlin `Constants`/`StringUtils` files exist because the codebase is mid-transition, not because they are duplicates by mistake.
- Changes here ripple broadly; prefer adding narrowly-scoped helpers only when multiple packages need them.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
