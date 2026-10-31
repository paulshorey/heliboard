# latin/spellcheck

Android spell checker service and session implementation.

## Direct files
- `AndroidSpellCheckerService.java` - service entry point declared in the manifest.
- `AndroidSpellCheckerSessionFactory.java` - session factory.
- `AndroidSpellCheckerSession.java` - base spell checker session.
- `AndroidWordLevelSpellCheckerSession.java` - word-level spell checking session.
- `SentenceLevelAdapter.java` - sentence/window adapter for spell checking.
- `SpellCheckerSettingsActivity.kt` - spell checker settings activity entry.

## Non-obvious notes
- Routine traces use debug/verbose levels and require explicit Android per-tag opt-in. Log counts and state instead of typed words, host text, dictionary entries or suggestion contents. Preserve warnings/errors needed to diagnose failures.
- The spell checker is a separate system integration from the IME suggestion strip.
- If dictionaries or locale behavior change in the IME, verify whether spell checker behavior should change too.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
