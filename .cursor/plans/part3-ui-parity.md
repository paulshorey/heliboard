# HeliBoard User-Editable Keyboard Layouts — Part 3: UI Parity & Forking

This is the third and final plan to implement user-editable keyboard layouts. The goal of this phase is to deliver the full user-facing capability: allowing users to add, edit, delete, and import custom layouts across ALL layout slots (MAIN, SYMBOLS, FUNCTIONAL, etc.) and easily create a custom copy of any built-in layout with a single tap.

---

## 1. Goal & Branching

- **Branch name:** `cursor/custom-layouts-pr3-ui-parity`
- **Objective:** Refactor the subtype settings Compose UI, implement a unified `LayoutSlotEditor` component, add a "Fork layout" (Edit a copy) icon, and provide clear user onboarding context.

---

## 2. Technical Instructions

All Compose UI work is located under `app/src/main/java/helium314/keyboard/settings/`.

### 2.1 Refactor SubtypeScreen.kt to Extract `LayoutSlotEditor`
Today, only the `MAIN` slot has full editing/addition controls via a heavily customized inline `MainLayoutRow`. All secondary slots (like `SYMBOLS` or `NUMBER_ROW`) use a basic dropdown with no creation or deletion affordances.

1. **Extract `LayoutSlotEditor` Component:**
   Pull the layout management UI out of `SubtypeScreen.kt` lines ~401–504 into a reusable `@Composable` function:
   ```kotlin
   @Composable
   fun LayoutSlotEditor(
       slotType: LayoutType,
       currentSubtype: SettingsSubtype,
       setCurrentSubtype: (SettingsSubtype) -> Unit,
       builtInLayouts: Collection<String>,
       customLayouts: Collection<String>,
   )
   ```
   *Handling Scopes:*
   - For `MAIN`: Gather files via `LayoutUtilsCustom.getLayoutFiles(MAIN, ctx, locale)` + `LayoutUtils.getAvailableLayouts(MAIN, ctx, locale)`.
   - For secondary slots: Gather files via `LayoutUtilsCustom.getLayoutFiles(slotType, ctx)` + `LayoutUtils.getAvailableLayouts(slotType, ctx)` (universal scope, as these layouts don't bind to a specific language).

2. **Wire All Layout Slots:**
   In `SubtypeScreen.kt`, replace the legacy `MainLayoutRow` rendering and the hardcoded secondary dropdown blocks with an iteration over `LayoutType.entries`:
   ```kotlin
   LayoutType.entries.forEach { slot ->
       LayoutSlotEditor(
           slotType = slot,
           currentSubtype = subtype,
           setCurrentSubtype = { updateSubtype(it) },
           builtInLayouts = ... ,
           customLayouts = ...
       )
   }
   ```
   Now, every single layout slot on the screen has full parity: Add, Edit, Delete, Import (Load-from-file), and Select.

### 2.2 Implement "Fork Layout" (Edit a Copy)
Make it extremely easy to start a custom layout from any built-in.

1. **Add Fork Icon Button:**
   Inside `LayoutSlotEditor`, if the currently selected layout is a built-in (`!LayoutUtilsCustom.isCustomLayout(name)`):
   - Render a **fork icon** (represented by a pencil-with-plus or a custom fork vector icon) next to the dropdown.
2. **On Tap Action:**
   - **Load Content:**
     - For `MAIN`: Call `LayoutUtils.getContentWithPlus(name, currentSubtype.locale, ctx)` (fetches the layout content and appends dynamic locale extra keys).
     - For secondary slots: Call `LayoutUtils.getContent(slotType, name, ctx)`.
   - **Open Edit Dialog:**
     Launch `LayoutEditDialog` pre-filled:
     - `initialLayoutName = "$name-copy"` (automatically appends `-copy` to prevent collisions)
     - `startContent = [fetched layout content]`
     - `isNameValid = { it !in customLayouts }`
3. **On Save Selection:**
   Upon successful save in `LayoutEditDialog`, immediately select this new custom layout for the current subtype slot:
   ```kotlin
   val updatedSubtype = currentSubtype.withLayout(slotType, newName)
   setCurrentSubtype(updatedSubtype)
   ```

### 2.3 LayoutEditDialog Warning for "+" Layouts
When the user forks a layout ending in `+` (e.g. `qwerty+`), the locale-specific extras get materialized into the copy's text body permanently. 

In `LayoutEditDialog.kt`, render a helpful supporting text warning:
*"Locale-specific extra keys are baked into this copy. They won't update if you later use this layout in a different language."*

### 2.4 Empty-Search Hint on LanguageScreen.kt
Help users locate custom layout editing controls. When the settings search filter results in zero matched languages, display a helpful hint in `LanguageScreen.kt`:
*"To customise a layout, tap a language above and use the + or the fork icon on any layout."*

### 2.5 Add Strings to strings.xml
Add all user-visible strings to `app/src/main/res/values/strings.xml` under descriptive keys.

---

## 3. UI Integration Tests

Create a new Compose/JVM integration test file: `app/src/test/java/helium314/keyboard/settings/LayoutSlotEditorTest.kt`

Implement testing cases for:
1. **Secondary Parity:** Verify that a secondary slot (e.g. `SYMBOLS`) successfully renders the add, edit, delete, and fork buttons when a layout is selected.
2. **Fork Launch Content:** Mock a fork click on `qwerty` inside a German subtype and assert that the dialog receives the correct pre-filled 4-row layout content.
3. **Fork Save Scopes:**
   - Saving a fork from an English (Latin) subtype correctly writes a `custom.Latn.` scoped file.
   - Saving a fork from a Russian subtype correctly writes a BCP-47 scoped `custom.ru-RU.` file.
4. **"+" Layout Alert:** Verify that the warning caption is visible when editing/forking a layout name containing `+`.

---

## 4. Progress Tracking Checklist

Tick a box (`[ ]` → `[x]`) when the work is done and committed.

- [ ] **3.1 Prep:** Create branch `cursor/custom-layouts-pr3-ui-parity` off `main`.
- [ ] **3.2 Component Extraction:** Refactor `SubtypeScreen.kt` to extract `LayoutSlotEditor`, keeping MAIN active through it. Verify Compose build.
- [ ] **3.3 Universal Parity:** Wire all other `LayoutType` slots through `LayoutSlotEditor`. Verify UI shows full controls on every slot.
- [ ] **3.4 Fork Implementation:** Implement the Fork icon and the edit dialog callback to auto-populate and auto-select.
- [ ] **3.5 Warnings:** Add the `+`-fork caption helper into `LayoutEditDialog.kt`.
- [ ] **3.6 Help Hint:** Add the search empty state help text on `LanguageScreen.kt`.
- [ ] **3.7 Strings:** Populate `res/values/strings.xml` with all user-facing strings.
- [ ] **3.8 Documentation:** Update `app/src/main/java/helium314/keyboard/settings/screens/AGENTS.md`.
- [ ] **3.9 Unit Tests:** Create and run `LayoutSlotEditorTest.kt`. Ensure `./gradlew :app:testDebugUnitTest` is green.
- [ ] **3.10 End-to-End Verification:**
  - [ ] Build and install on device (`./gradlew installDebug`).
  - [ ] Open English (US) subtype settings.
  - [ ] Tap the Fork icon on `qwerty` to create `qwerty-copy`.
  - [ ] Edit `qwerty-copy` (e.g. remove the first row to make it 3 rows, or edit popups).
  - [ ] Save and confirm the keyboard updates instantly to reflect your edits.
  - [ ] Tap Symbols slot, Fork `symbols` layout, verify and select custom symbols layout.
  - [ ] Switch active locale to Russian via Globe key, verify Russian displays its default built-in layout (no bleed of custom English layouts).
