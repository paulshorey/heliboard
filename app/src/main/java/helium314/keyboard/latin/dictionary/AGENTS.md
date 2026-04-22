# latin/dictionary

Concrete binary dictionary implementations and aggregators.

## Direct files
- `AppsBinaryDictionary.java` - suggestions from installed app names.
- `ContactsBinaryDictionary.java` - suggestions from contact names.
- `Dictionary.java` - base dictionary abstraction.
- `DictionaryCollection.java` - aggregator over multiple dictionaries.
- `DictionaryFactory.kt` - dictionary construction helper/factory.
- `DictionaryStats.java` - dictionary size/statistics model.
- `ExpandableBinaryDictionary.java` - mutable binary dictionary base.
- `ReadOnlyBinaryDictionary.java` - immutable shipped/external binary dictionary.
- `UserBinaryDictionary.java` - user-editable dictionary storage.

## Non-obvious notes
- These classes are tightly coupled to JNI/native dictionary code and the file-format classes in `latin/makedict/`.
- When a new dictionary source is added, update the facilitator layer in the parent folder too.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
