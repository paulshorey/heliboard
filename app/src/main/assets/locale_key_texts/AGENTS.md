# assets/locale_key_texts

Locale-specific text shown on keys, hints, or popup sets.

## File patterns
- `<locale>.txt` - popup/hint text for one locale or locale variant.
- Regional variants such as `bn-BD.txt` or `de-CH.txt` - override the base locale where keyboard text truly differs.
- Script variants such as `hi-Latn.txt` or `sr-Latn.txt` - separate popup text for an alternate script.

## Special files
- `more_popups_all.txt` - shared popup additions used broadly.
- `more_popups_main.txt` - shared popup additions for main layouts.
- `more_popups_more.txt` - shared popup additions for more-symbol style layouts.
- `zz.txt` - generic fallback popup/hint text.

## Non-obvious notes
- These files are not translations of UI strings; they are part of keyboard behavior and long-press UX.
- Supported sections include `[popup_keys]` for long-press popups, `[extra_keys]` for `+` layout row additions, `[labels]` for locale-specific key labels, `[number_row]` for localized digit rows, and `[tlds]` for period-key domain popups.
- Lookup tries the locale tag, then the language-only fallback; Hebrew locales may fall back through the legacy `iw` asset name. The no-language path also probes `more_popup_keys.txt` in code before falling back to `zz.txt`, but that file is not currently shipped.
- When adding a new locale, copy from the closest existing locale/script rather than from English by default.
- If a popup change seems to do nothing, verify the active MorePopups tier and popup/hint source order: layout-file popups, symbol hints, locale popups, number hints, and the shared `more_popups_*` files can override expectations.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
