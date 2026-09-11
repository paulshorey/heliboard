# keyboard/internal

Internal mechanics behind keyboard rendering, previews, gesture trails, parser prep, and keyboard state transitions.

## Direct files
- `AbstractDrawingPreview.java` - base class for drawing previews above the keyboard.
- `AlphabetShiftState.java` - alphabet shift/caps state tracking.
- `BatchInputArbiter.java` - coordinates batch/gesture input state.
- `BogusMoveEventDetector.java` - filters invalid pointer-move events.
- `DrawingPreviewPlacerView.java` - view that places/draws preview overlays.
- `DrawingProxy.java` - drawing abstraction used by preview components.
- `GestureEnabler.java` - enables/disables gesture typing paths.
- `GestureFloatingTextDrawingPreview.java` - floating preview text for gesture input.
- `GestureStrokeDrawingParams.java` - stroke-drawing parameter holder.
- `GestureStrokeDrawingPoints.java` - stroke points for drawing.
- `GestureStrokeRecognitionParams.java` - gesture-recognition parameter holder.
- `GestureStrokeRecognitionPoints.java` - gesture-recognition point buffer.
- `GestureTrailDrawingParams.java` - trail-drawing parameter holder.
- `GestureTrailDrawingPoints.java` - gesture-trail point buffer.
- `GestureTrailsDrawingPreview.java` - rendered gesture-trail preview.
- `HermiteInterpolator.java` - smoothing/interpolation for gesture trails.
- `KeyboardBuilder.kt` - constructs keyboard models from parsed layout data.
- `KeyboardCodesSet.java` - canonical key code constants/set logic.
- `KeyboardIconsSet.kt` - icon resolution for keys.
- `KeyboardParams.java` - assembled keyboard parameter bundle.
- `KeyboardState.kt` - high-level keyboard state machine. `KeyCode.EMOJI` toggles from the visible palette (`SwitchActions.isShowingEmojiKeyboard`), not `mode`. Physical `onToggleKeyboard` can change the UI without this state machine, so returning to alphabet restores leftover or saved caps lock the same way symbols/numpad do.
- `KeyDrawParams.java` - key drawing parameter holder.
- `KeyPreviewChoreographer.java` - schedules key preview display.
- `KeyPreviewDrawParams.java` - key preview drawing parameters.
- `KeyPreviewView.java` - visual key preview view.
- `KeySpecParser.java` - parses key spec strings.
- `KeyVisualAttributes.java` - resolved visual attributes for a key.
- `ModifierKeyState.java` - modifier/sticky-key state holder.
- `NonDistinctMultitouchHelper.java` - multitouch compatibility helper.
- `PointerTrackerQueue.java` - ordered pointer tracker collection.
- `PopupKeySpec.java` - popup-key spec model.
- `RoundedLine.java` - rounded line/path drawing helper.
- `ShiftKeyState.java` - shift key specific state holder.
- `SlidingKeyInputDrawingPreview.java` - sliding input preview rendering.
- `TimerHandler.java` - delayed/timer-driven keyboard events.
- `TimerProxy.java` - timer abstraction wrapper.
- `TouchPositionCorrection.java` - touch bias/correction model.
- `TypingTimeRecorder.java` - typing timing recorder.
- `UniqueKeysCache.java` - cache of deduplicated key objects.

## Subfolders
- `keyboard_parser/` - XML/text layout parsing into typed keyboard models.

## Non-obvious notes
- This folder is mostly plumbing beneath `MainKeyboardView`; many classes are hot-path and allocation-sensitive.
- Parser output, icon resolution, and draw params must stay consistent with resource and asset naming conventions.
- `KeyboardParser` rescales row heights and `KeyboardParams.mVerticalGap` when `keysInRows.size != 4`, so baked number-row and other multi-row layouts keep a uniform inter-row gap. Emoji/clipboard bottom rows use `applyEmojiClipBottomRowGeometry`, sizing one functional slot from the active MAIN layout’s rendered row count.
- Alphabet/symbol keyboards share one slot height and vertical gap. `KeyboardBuilder` adds `config_keyboard_toolbar_gap` (4dp in `values/dimens.xml`) above the first row so keys are not flush with the suggestion/pinned toolbar; overlay bottom-row math includes the same gap. `applyNumberRowLabelInset()` nudges number-row labels down 2dp (`config_number_row_label_inset_top`) inside the same key rectangle on 5+ row layouts. Optional spacebar visual inset: `config_spacebar_visual_inset_top` (default 0).

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
