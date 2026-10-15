Describe the problem and resulting behavior, plus any relevant implementation decisions.

Validation:
- Commands and actual results:
- Phone/emulator checks performed, or pending manual acceptance:

APK for testing (required for every PR, including infrastructure/docs):
- Run `./tools/build-dist-apk.sh`, commit `dist/HeliBoard.apk` and `dist/apk-metadata.json`, then push.
- Replace this line with `python3 tools/apk-handoff.py link` output.
- Give the reviewer concrete manual test steps for this change.
