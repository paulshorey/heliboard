# General-Purpose Edit History — Technical Implementation Plan

## 1. Summary

Today HeliBoard keeps an "edit history" only for the **fullapp** editor
(`FullappEditorResult` in `app/src/main/java/helium314/keyboard/settings/FullappEditorActivity.kt`,
surfaced by the **Fullapp edit history** settings page in
`app/src/main/java/helium314/keyboard/settings/screens/FullappDraftsScreen.kt`).

This plan turns that into a **general-purpose edit history** that also records
what the user types in **regular keyboard mode**, for every app and every text
field, so that content lost to a glitchy/confusing host app can be recovered
from Settings (and, later, from an in-keyboard panel).

The work has three hard requirements pulled directly from the task:

1. **Capture from both interfaces** — save the latest version of user input from
   both the regular keyboard and the fullapp editor.
2. **Bounded storage** — old content must be deleted; the list must be truncated
   so it can never grow into a memory/storage problem.
3. **Do not break fullapp sync** — the original reason the fullapp store exists is
   an edge case where the user leaves fullapp without syncing and the keyboard
   later replays the newest fullapp draft into the original field when they
   return. General history must be **additive** and must not interfere with that
   replay, including the "regular → fullapp → back" ordering where the fullapp
   text is still the newest version and must win.

The core design decision that satisfies requirement 3 is a strict separation
between two concerns that currently live in one object:

- **Sync-eligible drafts** (fullapp only): the small set of unsynced fullapp
  drafts that may still be *written back* into a host field on reconnect. This
  behavior is unchanged.
- **Read-only history** (fullapp + regular): a bounded, append-only log that is
  never auto-synced and is only shown/copied in the UI.

Regular-keyboard capture feeds **only** the read-only history. It never creates
sync-eligible drafts and never writes to a host field, so it is structurally
incapable of breaking the fullapp replay path.

---

## 2. Current architecture (what exists today)

### 2.1 Storage model (`FullappEditorResult`)

All data lives in **device-protected** `SharedPreferences`
(`context.protectedPrefs()`), so it is available before credential unlock. There
are two independent stores:

- **Live drafts** — one entry per editor target.
  - Index: `PREF_FULLAPP_DRAFT_KEYS` (a `Set<String>` of storage keys).
  - Per-entry: `PREF_FULLAPP_DRAFT_PREFIX + storageKey` → JSON `DraftRecord`.
  - `storageKey = sha256(package|fieldId|fieldName|inputType|imeOptions|privateImeOptions)`
    (so re-editing the same field overwrites its live draft).
- **Archived drafts** — read-only history.
  - Index: `PREF_FULLAPP_ARCHIVE_KEYS` (a `Set<String>`).
  - Per-entry: `PREF_FULLAPP_ARCHIVE_PREFIX + archiveKey` → JSON `ArchivedDraftRecord`.
  - `archiveKey = sha256(storageKey|lastSavedAt|archivedAt|draftText)` (content-addressed,
    so history keeps multiple versions rather than overwriting).

`DraftRecord` holds `target` (a `TargetSnapshot`), `originalText` (field contents
at fullapp launch), `draftText` (current fullapp text), selection, the
`launchSessionToken`, and `lastSavedAt`. `ArchivedDraftRecord` wraps a
`DraftRecord` plus `archivedAt`.

`TargetSnapshot.matchScore(EditorInfo)` is the fuzzy matcher used to re-associate
a saved draft with a field on reconnect (package must match; fieldId/fieldName
are strong signals; inputType/imeOptions/privateImeOptions are weaker signals).

### 2.2 Fullapp draft lifecycle

1. **Launch** — `LatinIME.launchFullappEditorActivity()`
   (`app/src/main/java/helium314/keyboard/latin/LatinIME.java`) commits typed
   text, reads the field via `getOriginalFieldTextForFullapp()`
   (`getTextBeforeCursor` + `getSelectedText` + `getTextAfterCursor`, bypassing
   `getExtractedText()` to avoid trailing-newline bugs), mints a
   `launchSessionToken`, and starts `FullappEditorActivity`.
2. **Editing** — `FullappEditorActivity` autosaves a `DraftRecord`
   (debounced ~750 ms via `updateTextState`, and on `onPause`/`onNewIntent`)
   through `FullappEditorResult.saveDraft`. If the text equals `originalText`,
   the draft is cleared instead.
3. **Exit** — `saveAndExit()` persists and calls
   `markPendingReturn(target)` so the IME knows a specific field has a pending
   draft.
4. **Reconnect** — `LatinIME.onStartInputViewInternal()` calls
   `maybeSyncPendingFullappDraft(editorInfo)` which:
   - Resolves the target (`consumePendingReturn`, else exact `loadDraft`, else
     fuzzy `findDraftForEditor`).
   - If the field already equals the draft → `archiveAndClearDraft` (done).
   - If **not** returning-from-fullapp and the field no longer matches
     `originalText`/`draftText` → `wasSupersededByRegularEditing` → keep but do
     **not** sync.
   - Else, if within the 120 s recency window (or forced by returning), call
     `attemptPendingFullappSync` → `replaceEntireFieldText(draftText, true)` →
     restore selection → `archiveAndClearDraft`.
5. **Settings** — `FullappDraftsScreen` shows two sections: live drafts and
   archived history, each copyable.

### 2.3 Gaps relative to the new feature

- **No regular-keyboard capture** at all.
- **No truncation** anywhere: `saveDraft` and `archiveAndClearDraft` never cap
  the number of entries or total characters. Archived history is content-addressed
  and grows without bound. This is the requirement‑2 defect and it exists *today*.
- Naming (`FullappEditorResult`, `fullapp_*` prefixes/strings) is fullapp-specific
  and will read as misleading once regular history is added.

---

## 3. Proposed architecture

### 3.1 Conceptual split

Introduce two clearly separated responsibilities. The simplest, lowest-risk
approach is to **keep `FullappEditorResult` as-is for the sync-eligible live
drafts** and **generalize only the read-only history** into a shared store used
by both fullapp archival and regular-keyboard capture.

```
                     ┌─────────────────────────────┐
 fullapp editing ───▶│ live drafts (sync-eligible)  │──sync──▶ host field
                     │  FullappEditorResult (drafts)│
                     └──────────────┬──────────────┘
                                    │ archiveAndClearDraft (on sync/supersede)
                                    ▼
                     ┌─────────────────────────────┐
 regular typing ────▶│ EditHistoryStore (read-only) │──copy──▶ Settings UI
                     │  bounded, never auto-synced  │        (future: kbd panel)
                     └─────────────────────────────┘
```

- **Live drafts stay in `FullappEditorResult`** and remain fullapp-only and
  sync-eligible. No behavior change to the replay path (requirement 3 is
  protected by construction — regular capture never touches this store).
- **A new `EditHistoryStore`** owns the bounded read-only log. Fullapp archival
  writes into it (replacing the current `PREF_FULLAPP_ARCHIVE_*` store), and
  regular-keyboard capture also writes into it. Entries carry a `source`
  (`FULLAPP` | `REGULAR`) for display/filtering.

This is deliberately conservative: it isolates the risky part (never break sync)
from the new part (record regular typing).

### 3.2 New data types

Add to a new file
`app/src/main/java/helium314/keyboard/latin/edithistory/EditHistoryStore.kt`
(package `helium314.keyboard.latin.edithistory`; a runtime concern, so it lives
under `latin/`, not the Compose `settings/` package):

```kotlin
enum class EditHistorySource { FULLAPP, REGULAR }

data class EditHistoryEntry(
    val source: EditHistorySource,
    val target: FullappEditorResult.TargetSnapshot, // reuse existing snapshot type
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val updatedAt: Long,
)
```

`TargetSnapshot` (with `storageKey`, `matchScore`, `debugSummary`) is reused as-is
so the fingerprint UI and matching logic are shared. To avoid a `settings ←
latin` dependency inversion, `TargetSnapshot` and the small JSON helpers should be
**moved** from `FullappEditorActivity.kt` into the new `latin/edithistory`
package (e.g. `EditorTargetSnapshot.kt`), with `FullappEditorResult` importing it.
This keeps the runtime store free of any Compose/settings imports.

### 3.3 Keying and dedup strategy for regular history

Regular typing must record the **latest** text per field, not one entry per
keystroke. Use a two-tier scheme:

- **Per-field "latest" slot**: keyed by `target.storageKey`, holding the most
  recent text for the field currently being edited. This is updated in place
  (debounced) while the user types, so a single editing session collapses to one
  row, not thousands.
- **Promotion to history**: when the user leaves the field
  (`onFinishInput`) or switches fields, the per-field latest slot is **finalized**
  into the bounded history list (subject to dedup: skip if identical to the most
  recent entry for that `storageKey`).

Implementation-wise the store keeps:

- `PREF_EDIT_HISTORY_KEYS` — ordered index (JSON array of entry ids, newest last
  or with an explicit `updatedAt` sort) rather than an unordered `Set`, so
  truncation can drop the oldest deterministically.
- `PREF_EDIT_HISTORY_PREFIX + entryId` — per-entry JSON.
- A separate small map for the in-progress per-field latest slot
  (`PREF_EDIT_HISTORY_LATEST_PREFIX + storageKey`) so a crash mid-session still
  preserves the latest text.

Using an ordered list (not a `Set`) is a required change: the current
`getStringSet` approach cannot support O(1) oldest-eviction or a stable display
order.

### 3.4 Retention / truncation policy (requirement 2)

Enforce **three** independent caps, applied on every write, so no single
dimension can blow up memory:

1. **Max entries** — e.g. `MAX_HISTORY_ENTRIES = 200`. When exceeded, evict
   oldest by `updatedAt`.
2. **Max total characters** — e.g. `MAX_HISTORY_TOTAL_CHARS = 1_000_000`
   (~1–2 MB of UTF‑16). Evict oldest until under budget.
3. **Per-entry character cap** — e.g. `MAX_ENTRY_CHARS = 100_000`. Truncate a
   single entry's stored text (keep the tail, since the end is usually the
   newest/most-relevant content) and flag it truncated for the UI.
4. **Optional age cap** — e.g. drop entries older than `MAX_HISTORY_AGE_MS`
   (e.g. 30 days) opportunistically on read/write. This is a nice-to-have on top
   of the hard caps.

Truncation runs inside a single `prefs.edit { … }` transaction in a helper
`enforceRetention(prefs)` that:

- Reads the ordered index.
- Sorts by `updatedAt` descending.
- Drops entries beyond `MAX_HISTORY_ENTRIES`.
- Walks from newest, summing characters, dropping the rest once the char budget
  is hit.
- Removes the corresponding `PREF_EDIT_HISTORY_PREFIX + id` blobs.

**Also fix the existing unbounded fullapp archive**: because fullapp archival now
writes through `EditHistoryStore`, the same caps automatically bound it — this
closes the pre-existing leak in `archiveAndClearDraft`. Live drafts are naturally
bounded (one per field, cleared on sync), but we add a modest cap
(`MAX_LIVE_DRAFTS`, e.g. 50, evict oldest by `lastSavedAt`) to
`FullappEditorResult.saveDraft` as defense-in-depth against abandoned drafts for
thousands of distinct fields.

### 3.5 Capturing regular-keyboard text (requirement 1)

Capture happens in `LatinIME`, reading the whole field with the existing
`getOriginalFieldTextForFullapp()` helper (rename to a neutral
`readCurrentFieldText()` / add an alias; it already does the correct
before+selected+after read).

Hook points:

- **Debounced during editing** — piggyback on `onUpdateSelection(...)`
  (`LatinIME.java` ~line 1120), which already fires on text changes. When
  `oldSel != newSel` (i.e. text/selection actually changed), post a debounced
  (~1–2 s) `Runnable` on `mMainHandler` that snapshots the field into the
  per-field latest slot via `EditHistoryStore.updateLatest(target, text, sel)`.
  Debouncing avoids a write per keystroke.
- **Finalize on field exit** — in `onFinishInputInternal()`
  (`LatinIME.java` ~line 1060), flush the pending debounce and call
  `EditHistoryStore.finalizeLatest(target)` to promote the per-field slot into the
  bounded history (with dedup). This is the analogue of the existing
  `EmailLearner.flushNow(...)` call already made there, so the pattern and
  placement are established.
- **Also finalize on field switch** — `onStartInputViewInternal()` already runs
  per field start; if the incoming target differs from the previous one, finalize
  the previous field's latest slot first.

The `target` for regular capture comes from
`FullappEditorResult.createTargetSnapshot(getCurrentInputEditorInfo())` — the same
snapshot used by fullapp, so both sources share identity/fingerprint logic.

All capture work must run **off the UI-blocking path** where possible. The field
read is cheap, but the `SharedPreferences` writes should use `apply()` (async) or
be dispatched to `ExecutorUtils.getBackgroundExecutor(...)` (used elsewhere in
`FullappEditorActivity`/`LatinIME`) to avoid jank on large fields.

#### Privacy / exclusion rules (mandatory)

Regular capture must **never** record sensitive fields. Skip capture when any of
the following is true (all already computed by `InputAttributes` /
`SettingsValues`):

- `InputAttributes.mIsPasswordField` (password/PIN/visible-password input types).
- `InputAttributes.mNoLearning` (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- `SettingsValues.mIncognitoModeEnabled` (mirror the existing
  `shouldSkipEmailCapture(sv)` gate in `LatinIME`, which already unifies these).
- Optionally skip obvious non-text inputs (numbers/phone/URL are debatable; at
  minimum skip password variants).

Add a **user-facing on/off preference** for the whole feature (see §5), default
decision: capture **enabled** but with a clear settings toggle and the ability to
clear history. Given this records everything the user types, defaulting on is a
product call; the plan wires the toggle so it can ship default-off if desired.

### 3.6 Preserving fullapp sync (requirement 3) — the critical invariants

The replay path in `LatinIME.maybeSyncPendingFullappDraft` /
`attemptPendingFullappSync` must be **behaviorally identical** after this change.
Guarantees:

1. **Separate stores.** Regular capture writes to `EditHistoryStore` only. The
   sync-eligible **live drafts** remain exclusively in `FullappEditorResult`
   (`PREF_FULLAPP_DRAFT_*`). Nothing in the regular-capture path calls
   `saveDraft`, `markPendingReturn`, or `replaceEntireFieldText`. Therefore
   regular capture cannot create or mutate a syncable draft.
2. **Read order on reconnect is unchanged.** `onStartInputViewInternal` still
   calls `maybeSyncPendingFullappDraft(editorInfo)` first
   (`LatinIME.java` line 896), *before* any regular-capture finalize for the new
   field. So a pending fullapp draft is evaluated/synced before general history
   ever touches the field.
3. **"regular → fullapp → back" still lets fullapp win.** Walkthrough:
   - User types in regular mode → field = `A`. Regular capture may record `A` in
     history (harmless).
   - User opens fullapp → `launchFullappEditorActivity` snapshots
     `originalText = A`; user edits to `B`; draft saved with `originalText=A`,
     `draftText=B`.
   - User closes fullapp / navigates away without syncing → `markPendingReturn`.
   - User returns to the field (still shows `A`, unchanged) →
     `maybeSyncPendingFullappDraft`: `currentFieldText == A == originalText`, so
     `wasSupersededByRegularEditing` is **false**, and within the recency window
     (or forced via `consumePendingReturn`) → `replaceEntireFieldText(B)`.
     **Fullapp text `B` wins**, exactly as today. Regular history recording `A`
     earlier does not change any of these comparisons because they read the live
     `InputConnection`, not the history store.
4. **Do not let regular capture overwrite `originalText`.** `originalText` is
   captured only at fullapp launch. Regular capture never writes into
   `DraftRecord`, so this baseline is untouched.
5. **Timing guard.** The regular-capture debounce must be cancelled in
   `launchFullappEditorActivity()` (before `requestHideSelf`) so a late debounce
   cannot run against a field we are about to leave for fullapp. Add
   `mMainHandler.removeCallbacks(mEditHistoryCaptureRunnable)` there.

A regression test (see §7) will assert the exact requirement‑3 ordering scenario.

---

## 4. File-by-file change list

### New files

- `app/src/main/java/helium314/keyboard/latin/edithistory/EditHistoryStore.kt`
  — bounded read-only history store (`updateLatest`, `finalizeLatest`,
  `addEntry`, `getAllEntries`, `clearAll`, `enforceRetention`, cap constants).
- `app/src/main/java/helium314/keyboard/latin/edithistory/EditorTargetSnapshot.kt`
  — `TargetSnapshot` + JSON helpers moved here from `FullappEditorActivity.kt`
  (typealias/re-export kept in `FullappEditorResult` for source compatibility).
- `app/src/main/java/helium314/keyboard/latin/edithistory/AGENTS.md` — folder doc.
- `app/src/test/java/helium314/keyboard/latin/edithistory/EditHistoryStoreTest.kt`
  — retention/dedup/capture tests.

### Modified files

- `app/src/main/java/helium314/keyboard/settings/FullappEditorActivity.kt`
  - `FullappEditorResult`: route `archiveAndClearDraft` through
    `EditHistoryStore.addEntry(source = FULLAPP, …)` instead of the local
    `PREF_FULLAPP_ARCHIVE_*` store; add `MAX_LIVE_DRAFTS` eviction to `saveDraft`;
    delete `getAllArchivedDrafts`/archive JSON (moved to the store) or make it a
    thin adapter during migration.
  - Import `TargetSnapshot` from the new package.
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
  - Add `mEditHistoryCaptureRunnable` + debounce scheduling in
    `onUpdateSelection`.
  - Finalize/flush in `onFinishInputInternal` and on field switch in
    `onStartInputViewInternal`.
  - Cancel debounce in `launchFullappEditorActivity`.
  - Gate all capture behind the privacy checks (`shouldSkipEmailCapture`-style)
    and the new feature preference.
  - Rename/alias `getOriginalFieldTextForFullapp()` → `readCurrentFieldText()`.
- `app/src/main/java/helium314/keyboard/settings/screens/FullappDraftsScreen.kt`
  - Read from `EditHistoryStore` for the history section (both `FULLAPP` and
    `REGULAR` entries) plus `FullappEditorResult.getAllDrafts` for live drafts.
  - Add a source badge/filter (Regular vs Fullapp) and a **Clear history** action.
  - Rename user-facing strings (see below).
- `app/src/main/java/helium314/keyboard/settings/screens/MainSettingsScreen.kt`
  - Update the entry label from "Fullapp edit history" to "Edit history".
- `app/src/main/java/helium314/keyboard/settings/SettingsNavHost.kt`
  - Optional: rename destination `fullapp_drafts` → `edit_history` (keep old
    constant as alias to avoid breaking deep links).
- `app/src/main/java/helium314/keyboard/latin/settings/Settings.java`
  + `Defaults.kt`
  - Add `PREF_EDIT_HISTORY_ENABLED` (boolean) and optionally
    `PREF_EDIT_HISTORY_MAX_ENTRIES`.
- `app/src/main/res/values/strings.xml`
  - Generalize `settings_screen_fullapp_drafts` → "Edit history"; add
    `edit_history_source_regular`, `edit_history_source_fullapp`,
    `edit_history_clear`, `edit_history_enabled_*`, `edit_history_truncated_note`,
    empty-state copy. Keep existing `fullapp_*` strings that still apply to live
    drafts.
- `docs/soniox-transcription.md` is unrelated; update
  `.cursor/skills/full-app-mode/SKILL.md` and the relevant `AGENTS.md`
  files (`settings/`, `latin/`, `settings/screens/`, new `edithistory/`) to
  describe the split.

---

## 5. Settings / UX

- Rename the settings row and screen title to **"Edit history"**.
- Two sections in the screen:
  1. **Live in-progress fullapp edits** (unchanged; still sync-eligible).
  2. **Edit history** (merged fullapp-archived + regular), newest first, each row
     showing: app label, source badge (Regular / Fullapp), timestamp, text
     length, expandable fingerprint, copy button; long text shown truncated with
     a "truncated" note when the per-entry cap was hit.
- Add:
  - A **master toggle** (`PREF_EDIT_HISTORY_ENABLED`) — controls regular capture.
  - A **Clear history** button (`EditHistoryStore.clearAll`) with confirm dialog.
  - Search already exists in `FullappDraftsScreen` via `SearchScreen`; extend the
    filter to include the source and the regular entries.
- **Future (out of scope here, but designed for):** an in-keyboard history panel
  (a toolbar key / suggestion-strip surface) reusing `EditHistoryStore.getAllEntries`.
  Keeping the store in `latin/` (not `settings/`) is what makes this reuse clean.

---

## 6. Data migration

- Existing `PREF_FULLAPP_ARCHIVE_KEYS` / `PREF_FULLAPP_ARCHIVE_*` entries should be
  migrated into `EditHistoryStore` (source = `FULLAPP`) on first read, then the
  old keys removed. Implement in `AppUpgrade.kt`
  (`app/src/main/java/helium314/keyboard/latin/AppUpgrade.kt`) or lazily on first
  `EditHistoryStore` access (guarded by a one-shot `PREF_EDIT_HISTORY_MIGRATED`
  flag), whichever fits the existing upgrade conventions.
- Live drafts (`PREF_FULLAPP_DRAFT_*`) are **not** migrated — they stay where they
  are.
- Migration must apply retention caps so a large legacy archive is trimmed to the
  new budget.

---

## 7. Testing plan

Extend Robolectric tests (existing pattern:
`app/src/test/java/helium314/keyboard/settings/FullappEditorResultTest.kt`, which
clears `protectedPrefs()` and drives the store directly).

New `EditHistoryStoreTest.kt`:

- `updateLatest` collapses repeated updates for the same field into one slot.
- `finalizeLatest` promotes the slot into history and dedups identical
  consecutive entries.
- **Retention**: adding > `MAX_HISTORY_ENTRIES` drops the oldest; exceeding the
  char budget evicts oldest until under budget; a single oversized entry is
  truncated to `MAX_ENTRY_CHARS` (tail kept) and flagged.
- `clearAll` empties both the index and per-entry blobs (no orphans).
- Migration: seeded legacy `PREF_FULLAPP_ARCHIVE_*` entries appear as `FULLAPP`
  history and legacy keys are removed.

Augment `FullappEditorResultTest.kt` / add an IME-level test for the
**requirement‑3 invariant**:

- Simulate "regular types A → fullapp edits to B (originalText=A) → return with
  field still A" and assert `shouldSyncToCurrentField(...) == true` and that a
  regular-history entry for A does **not** flip `wasSupersededByRegularEditing`.
- Simulate "regular types A → fullapp to B → user keeps typing in regular to C →
  return with field C" and assert the draft is **kept but not synced**
  (`wasSupersededByRegularEditing(C) == true`), matching current behavior.
- Assert `MAX_LIVE_DRAFTS` eviction in `saveDraft`.

Run `./gradlew :app:testDebugUnitTest` (JVM/Robolectric) and, per the
`android-build-apk` skill, build the APK (`./tools/build-dist-apk.sh`) before
finishing implementation.

---

## 8. Risks and edge cases

- **Sensitive data leakage** — the biggest risk. Regular capture logs raw user
  text to device-protected prefs. Mitigations: password/no-learning/incognito
  exclusion (mandatory), master toggle, Clear-history action. Consider excluding
  by input-type variant (e.g. `TYPE_TEXT_VARIATION_PASSWORD`,
  `TYPE_NUMBER_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`) even if
  `mIsPasswordField` should already cover them.
- **Performance / jank** — never write to prefs per keystroke. Debounce reads +
  async writes; enforce retention in one transaction; use `apply()`.
- **Storage pressure** — the three-tier caps bound total size; migration trims
  legacy archives; `apply()` batching avoids fsync storms.
- **Duplicate/near-duplicate spam** — dedup consecutive identical text per field;
  update-in-place while editing so one session = one slot.
- **Field identity churn** — some apps report `fieldId == 0` and blank
  `fieldName`; the per-field slot then keys mostly on package+inputType and may
  merge distinct fields. Acceptable for a best-effort recovery log; matches the
  fuzziness the fullapp matcher already tolerates.
- **Direct-boot** — store lives in `protectedPrefs()` so capture works before
  unlock, consistent with existing fullapp behavior.
- **Package/settings coupling** — moving `TargetSnapshot` to `latin/edithistory`
  removes the `latin → settings` dependency that would otherwise be created by
  having `LatinIME` call into a `settings`-package store.

---

## 9. Rollout / sequencing

1. Introduce `EditHistoryStore` + `EditorTargetSnapshot` (move type), route
   fullapp archival through it, add retention caps, add migration. No user-visible
   change yet; fixes the existing unbounded-archive leak (requirement 2).
2. Add regular-keyboard capture in `LatinIME` behind `PREF_EDIT_HISTORY_ENABLED`
   with full privacy gating (requirement 1), plus the debounce-cancel guard in
   `launchFullappEditorActivity` (protects requirement 3).
3. Update the settings screen (merged history, source badges, toggle, clear).
4. Tests, APK build, docs/AGENTS/skill updates.

Each step is independently shippable and testable.
