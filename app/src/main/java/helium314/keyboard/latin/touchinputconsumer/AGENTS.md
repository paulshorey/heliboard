# latin/touchinputconsumer

Gesture-typing output consumer hook.

## Direct files
- `GestureConsumer.java` - applies gesture typing results to the text pipeline.

## Non-obvious notes
- This package is tiny but sits on a sensitive seam between pointer tracking and text insertion ordering.
- Validate gesture behavior if you change surrounding input-logic sequencing.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
