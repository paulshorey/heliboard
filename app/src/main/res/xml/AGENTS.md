# res/xml

XML metadata and templates that define the IME, spell checker, and popup keyboard templates.

## Direct files
- `kbd_emoji.xml` - emoji keyboard template/config.
- `kbd_popup_keys_keyboard_template.xml` - base popup-keys keyboard template.
- `kbd_suggestions_pane_template.xml` - suggestion pane template.
- `method.xml` - IME metadata, subtype declarations, and inline layout mapping comments.
- `method_dummy.xml` - dummy/placeholder IME metadata resource used by the manifest.
- `spellchecker.xml` - spell checker service metadata.

## Related qualifier folders
- `xml-sw600dp/` - tablet popup-key template override.
- `xml-sw600dp-land/` - large-tablet landscape popup-key template override.

## Non-obvious notes
- `method.xml` and `assets/layouts/` must remain aligned when layouts or subtypes change.
- The popup template files across base and tablet qualifiers should stay semantically aligned unless the screen-size difference is intentional.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
