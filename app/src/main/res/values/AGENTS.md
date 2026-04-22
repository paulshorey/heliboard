# res/values

Canonical resource bucket for strings, themes, dimensions, and configuration defaults.

## Direct files
- `attrs.xml` - custom attributes used by views/themes.
- `bools.xml` - default boolean feature/config values.
- `colors.xml` - base color resources.
- `config-common.xml` - shared keyboard config values.
- `config-per-form-factor.xml` - form-factor specific config defaults.
- `config-screen-metrics.xml` - screen-metric-driven sizing/config values.
- `config-spellchecker-thresholds.xml` - spell-check thresholds.
- `config.xml` - core keyboard configuration resources.
- `dimens.xml` - shared dimensions.
- `donottranslate-config-spacing-and-punctuations.xml` - punctuation/spacing config strings that should not be translated.
- `donottranslate-debug-settings.xml` - debug-only non-translated strings.
- `donottranslate.xml` - non-translated technical strings.
- `platform-theme.xml` - platform theme bridge resources.
- `strings-talkback-descriptions.xml` - accessibility/TalkBack strings.
- `strings.xml` - canonical user-facing source strings.
- `themes-common.xml` - shared theme pieces.
- `themes-holo_base.xml` - Holo theme base.
- `themes-lxx-base-border.xml` - bordered LXX theme base.
- `themes-lxx-base.xml` - core LXX theme base.
- `themes-lxx.xml` - LXX theme definitions.
- `themes-rounded-base-border.xml` - bordered rounded theme base.
- `themes-rounded-base.xml` - rounded theme base.
- `touch-position-correction.xml` - touch bias/correction resource data.

## Qualifier patterns
- `values-<locale>/` - translations and locale-specific overrides.
- `values-sw*` / `values-land/` - form-factor and orientation overrides.
- `values-night*` / `values-v*` - night-mode and API-level overrides.

## Non-obvious notes
- Most translation work should start from `values/strings.xml` and `values/strings-talkback-descriptions.xml`.
- Layout and typing behavior often depend on a combination of `config.xml`, `config-common.xml`, `dimens.xml`, and a qualifier override.
- If you add a new keyboard layout or subtype, verify whether user-visible strings and configuration names here also need updates.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
