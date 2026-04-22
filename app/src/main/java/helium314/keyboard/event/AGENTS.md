# event

Normalized key event model, combiners, and batched input transactions.

## Direct files
- `BnKhiproCombiner.kt` - Khipro-specific input combiner.
- `Combiner.kt` - combiner contract.
- `CombinerChain.kt` - ordered combiner pipeline.
- `DeadKeyCombiner.kt` - dead-key composition combiner.
- `Event.kt` - canonical input event model.
- `EventDecoder.kt` - decodes generic events into IME actions.
- `HapticEvent.kt` - haptic feedback event model.
- `HardwareEventDecoder.kt` - hardware key event decoder.
- `HardwareKeyboardEventDecoder.kt` - physical keyboard specific decoder.
- `InputTransaction.kt` - batched input transaction model.

## Non-obvious notes
- Combiner ordering matters; changing it can subtly break dead keys or Khipro entry.
- `InputTransaction.kt` is the main unit that links decoded events to the text pipeline.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
