# assets/dicts

Bundled binary dictionaries shipped inside the app.

## Direct files
- `main_bg.dict` - bundled Bulgarian dictionary.
- `main_bn.dict` - bundled Bengali dictionary.
- `main_de.dict` - bundled German dictionary.
- `main_el.dict` - bundled Greek dictionary.
- `main_en-GB.dict` - bundled English (UK) dictionary.
- `main_en-US.dict` - bundled English (US) dictionary.
- `main_es.dict` - bundled Spanish dictionary.
- `main_fr.dict` - bundled French dictionary.
- `main_hu.dict` - bundled Hungarian dictionary.
- `main_it.dict` - bundled Italian dictionary.
- `main_nl.dict` - bundled Dutch dictionary.
- `main_pl.dict` - bundled Polish dictionary.
- `main_pt-BR.dict` - bundled Portuguese (Brazil) dictionary.
- `main_pt-PT.dict` - bundled Portuguese (Portugal) dictionary.
- `main_ro.dict` - bundled Romanian dictionary.
- `main_ru.dict` - bundled Russian dictionary.
- `main_sv.dict` - bundled Swedish dictionary.
- `main_tr.dict` - bundled Turkish dictionary.

## Non-obvious notes
- These are binary assets consumed by the dictionary/JNI stack; do not hand-edit them.
- Debug builds may intentionally omit one large dictionary to keep the APK small enough for distribution.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
