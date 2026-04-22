# latin/makedict

In-memory types describing the compiled dictionary binary format.

## Direct files
- `DictionaryHeader.kt` - parsed dictionary header model.
- `FormatSpec.java` - dictionary format/version constants.
- `NgramProperty.java` - n-gram metadata model.
- `ProbabilityInfo.java` - probability/frequency wrapper.
- `UnsupportedFormatException.java` - format mismatch exception.
- `WeightedString.java` - string plus weight/probability.
- `WordProperty.java` - word entry metadata model.

## Non-obvious notes
- This folder is about the on-device binary format, not UI or user dictionary editing.
- Format changes need to stay compatible with native dictionary code and any offline dictionary-generation tooling.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
