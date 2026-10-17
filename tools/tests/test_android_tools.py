"""Exercise tool discovery without machine/network changes."""
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

    def test_jvm_option_banner_does_not_hide_the_java_version(self):
        java = Path(self.env['JAVA_HOME']) / 'bin/java'
        java.write_text('#!/bin/sh\necho "Picked up JAVA_TOOL_OPTIONS: -XX:TieredStopAtLevel=1" >&2\n'
                        'echo \'openjdk version "21.0.10"\' >&2\n')
        result = self.run_env()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, str(self.sdk))


if __name__ == '__main__':
    unittest.main()
