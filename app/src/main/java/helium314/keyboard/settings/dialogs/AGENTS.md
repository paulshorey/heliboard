# settings/dialogs

Reusable dialogs and modal customizers for the settings UI.

## Direct files
- `ColorPickerDialog.kt` - dialog for choosing arbitrary colors.
- `ColorThemePickerDialog.kt` - dialog for choosing color themes.
- `ConfirmationDialog.kt` - reusable yes/no confirmation dialog.
- `CustomizeIconsDialog.kt` - icon customization dialog.
- `DictionaryDialog.kt` - dictionary-related dialog UI.
- `InfoDialog.kt` - informational dialog component.
- `LayoutEditDialog.kt` - layout editing dialog.
- `LayoutPickerDialog.kt` - layout selection dialog.
- `ListPickerDialog.kt` - single-list picker dialog.
- `MultiListPickerDialog.kt` - multi-select list picker dialog.
- `NewDictionaryDialog.kt` - create-new-dictionary dialog.
- `ReorderDialog.kt` - reorder dialog component.
- `SliderDialog.kt` - slider-based dialog editor.
- `TextInputDialog.kt` - text-entry dialog.
- `ThreeButtonAlertDialog.kt` - alert dialog with three actions.
- `ToolbarKeysCustomizer.kt` - toolbar-key customization dialog/sheet.

## Non-obvious notes
- Many of these dialogs are coupled to shared preference components and screen-specific state; keep their call sites in sync when changing parameters.
- Toolbar/layout customization dialogs often interact with serialized preference formats rather than simple booleans.
- `ToolbarKeysCustomizer.kt` writes per-key press/long-press overrides through `ToolbarUtils.writeCustomKeyCodes`; those values are cached by `ToolbarUtils` and invalidated from `Settings.loadSettings()` on pref changes.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
