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
- `KeyboardState.kt` - high-level keyboard state machine.
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
- `KeyboardParser` rescales per-row relative heights when the layout has more than four rows; it also scales `KeyboardParams.mVerticalGap` with the same factor so inter-row spacing stays consistent (important for the optional number row).
- `KeyboardBuilder` adds a small gap above the first row when the optional number row is on (`R.dimen.config_number_row_top_extra_gap`, via extra `KeyboardParams.mTopPadding` and matching `mBaseHeight` shrink). For alphabet/symbol layouts it may set `Key.KeyParams.mSpaceVisualInsetTop` on wide bottom-row space keys when `R.dimen.config_spacebar_visual_inset_top` is positive so drawing and hit-testing skip a strip at the top of the slot; the default is zero so the space key uses the full row slot.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
