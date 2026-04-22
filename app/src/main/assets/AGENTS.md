# app/src/main/assets

Runtime data loaded from assets rather than compiled resource values.

## Direct files
- `dictionaries_in_dict_repo.csv` - generated index of dictionaries available in the external dictionaries repository.
- `khipro-mappings.json` - Khipro sequence-to-output mapping data.

## Subfolders
- `dicts/` - bundled binary dictionaries shipped with the app.
- `emoji/` - emoji category data and API-gating metadata.
- `layouts/` - keyboard layout definitions loaded at runtime.
- `locale_key_texts/` - locale-specific popup/hint text files.

## Non-obvious notes
- `khipro-mappings.json` is effectively part of the input-method logic for that layout family, not just passive data.
- `dictionaries_in_dict_repo.csv` is maintained by tooling and should not drift from `tools/release.py` expectations.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
