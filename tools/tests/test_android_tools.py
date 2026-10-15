"""Exercise tool discovery and isolated skill refresh without machine/network changes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]


class AndroidEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools').mkdir()
        shutil.copy2(TOOLS / 'android-env.sh', self.root / 'tools')
        self.home = self.root / 'home with spaces'
        self.sdk = self.home / 'Library/Android/sdk'
        for folder in ['platforms/android-35', 'ndk/28.0.13004108', 'build-tools/35.0.0']:
            (self.sdk / folder).mkdir(parents=True)
        signer = self.sdk / 'build-tools/35.0.0/apksigner'
        signer.touch()
        signer.chmod(0o755)
        jdk = self.root / 'jdk/bin'
        jdk.mkdir(parents=True)
        for binary in ['java', 'javac']:
            p = jdk / binary
            p.write_text('#!/bin/sh\necho \'openjdk version "21.0.10"\' >&2\n')
            p.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), JAVA_HOME=str(jdk.parent),
                        ANDROID_HOME='/missing/old/home', ANDROID_SDK_ROOT='/missing/old/home')

    def run_env(self):
        return subprocess.run(['bash', '-c', 'set -e; source tools/android-env.sh; printf "%s" "$ANDROID_SDK_ROOT"'],
                              cwd=self.root, env=self.env, text=True, capture_output=True)

    def test_stale_environment_falls_back_and_handles_spaces(self):
        result = self.run_env()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, str(self.sdk))
        self.assertEqual((self.root / 'local.properties').read_text(), f'sdk.dir={self.sdk}\n')

    def test_existing_local_properties_are_preserved(self):
        content = f'# studio setting\nsdk.dir={self.sdk}\nother.setting=true\n'
        (self.root / 'local.properties').write_text(content)
        self.assertEqual(self.run_env().returncode, 0)
        self.assertEqual((self.root / 'local.properties').read_text(), content)

    def test_bad_explicit_properties_do_not_silently_switch_sdk(self):
        (self.root / 'local.properties').write_text('sdk.dir=/missing/explicit/sdk\n')
        self.assertNotEqual(self.run_env().returncode, 0)
        self.assertEqual((self.root / 'local.properties').read_text(), 'sdk.dir=/missing/explicit/sdk\n')

    def test_missing_build_tools_fail_before_writing_properties(self):
        (self.sdk / 'build-tools/35.0.0/apksigner').unlink()
        self.assertNotEqual(self.run_env().returncode, 0)
        self.assertFalse((self.root / 'local.properties').exists())


class SkillRefreshTest(unittest.TestCase):
    def test_refresh_preserves_discovery_and_stages_downloads(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'tools').mkdir()
            shutil.copy2(TOOLS / 'sync-gemini-skills.sh', root / 'tools')
            fixups = root / 'tools/gemini-skills-fixups.py'
            fixups.write_text('#!/bin/sh\nexit 0\n')
            fixups.chmod(0o755)
            (root / '.codex/skills').mkdir(parents=True)
            (root / '.codex/skills/local-skill').mkdir()
            (root / '.agents').mkdir()
            (root / '.agents/skills').symlink_to('../.codex/skills', target_is_directory=True)
            binaries = root / 'bin'
            binaries.mkdir()
            installer = binaries / 'npx'
            installer.write_text('''#!/bin/sh
set -eu
for skill in gemini-api-dev gemini-live-api-dev; do
  mkdir -p ".agents/skills/$skill"
  echo "downloaded $skill" > ".agents/skills/$skill/SKILL.md"
done
echo '{"skills":{}}' > skills-lock.json
''')
            installer.chmod(0o755)
            result = subprocess.run(['bash', 'tools/sync-gemini-skills.sh'], cwd=root,
                                    env=dict(os.environ, PATH=f'{binaries}:{os.environ["PATH"]}'),
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue((root / '.agents/skills').is_symlink())
            self.assertTrue((root / '.codex/skills/local-skill').is_dir())
            for skill in ['gemini-api-dev', 'gemini-live-api-dev']:
                self.assertEqual((root / f'.agents/skills/{skill}/SKILL.md').read_text(), f'downloaded {skill}\n')


if __name__ == '__main__':
    unittest.main()
