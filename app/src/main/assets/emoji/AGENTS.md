# assets/emoji

Emoji category data loaded by the in-keyboard emoji picker.

## Direct files
- `ACTIVITIES.txt` - activity emoji list.
- `ANIMALS_AND_NATURE.txt` - animals and nature emoji list.
- `EMOTICONS.txt` - legacy emoticon-oriented emoji/category data.
- `FLAGS.txt` - flag emoji list.
- `FOOD_AND_DRINK.txt` - food and drink emoji list.
- `OBJECTS.txt` - object emoji list.
- `PEOPLE_AND_BODY.txt` - people and body emoji list.
- `SMILEYS_AND_EMOTION.txt` - smileys and emotion emoji list.
- `SYMBOLS.txt` - symbols emoji list.
- `TRAVEL_AND_PLACES.txt` - travel and places emoji list.
- `minApi.txt` - minimum Android API gating for newer emoji sequences.

## Non-obvious notes
- `minApi.txt` is essential for avoiding unsupported emoji on old Android versions.
- This folder works with both the emoji UI code and the generator under `tools/make-emoji-keys/`.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
