import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('apk_handoff', Path(__file__).resolve().parents[1] / 'apk-handoff.py')
handoff = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(handoff)


class ApkHandoffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.git('init', '-q', '-b', 'feature')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'core.filemode', 'true')
        (self.root / '.gitignore').write_text('local.properties\n')
        (self.root / 'source.kt').write_text('version one')
        (self.root / 'dist').mkdir()
        with zipfile.ZipFile(self.root / handoff.APK, 'w') as archive:
            archive.writestr('AndroidManifest.xml', 'fixture manifest')
        self.git('add', '.')
        handoff.record(handoff.source_digest(self.root), self.root)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.PIPE).decode().strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')

    def remote(self):
        remote = Path(self.temp.name) / 'remote.git'
        subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True)
        # Exercise push verification without any network access.
        self.git('config', f'url.{remote.as_uri()}.insteadOf', 'https://github.com/example/keyboard.git')
        self.git('remote', 'add', 'origin', 'https://github.com/example/keyboard.git')
        self.git('push', '-q', 'origin', 'feature')

    def test_staging_and_committing_do_not_change_fingerprint(self):
        self.commit()
        self.assertEqual(handoff.check(self.root)['variant'], 'debug')

    def test_changed_source_is_rejected(self):
        (self.root / 'source.kt').write_text('version two')
        with self.assertRaisesRegex(ValueError, 'stale'):
            handoff.check(self.root)

    def test_new_untracked_source_is_rejected(self):
        (self.root / 'extra.kt').write_text('new code')
        with self.assertRaisesRegex(ValueError, 'stale'):
            handoff.check(self.root)

    def test_deleted_source_is_rejected(self):
        (self.root / 'source.kt').unlink()
        with self.assertRaisesRegex(ValueError, 'stale'):
            handoff.check(self.root)

    def test_mode_change_is_rejected(self):
        (self.root / 'source.kt').chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'stale'):
            handoff.check(self.root)

    def test_symlink_migration_agrees_before_and_after_commit(self):
        (self.root / 'old').mkdir()
        (self.root / 'old/file').write_text('skill')
        self.git('add', '.')
        (self.root / 'old').rename(self.root / 'new')
        (self.root / 'old').symlink_to('new', target_is_directory=True)
        before = handoff.source_digest(self.root)
        self.commit()
        self.assertEqual(before, handoff.source_digest(self.root))

    def test_build_source_race_is_rejected(self):
        before = handoff.source_digest(self.root)
        (self.root / 'source.kt').write_text('changed during Gradle')
        with self.assertRaisesRegex(ValueError, 'while building'):
            handoff.record(before, self.root)

    def test_tampered_apk_is_rejected(self):
        with zipfile.ZipFile(self.root / handoff.APK, 'a') as archive:
            archive.writestr('extra.txt', 'changed bytes')
        with self.assertRaisesRegex(ValueError, 'bytes'):
            handoff.check(self.root)

    def test_ignored_local_settings_are_not_source(self):
        (self.root / 'local.properties').write_text('sdk.dir=/local/sdk')
        handoff.check(self.root)

    def test_link_rejects_uncommitted_build(self):
        with self.assertRaisesRegex(ValueError, 'Commit'):
            handoff.download_link(self.root)

    def test_link_is_pinned_to_pushed_commit(self):
        self.commit()
        self.remote()
        head = self.git('rev-parse', 'HEAD')
        self.assertEqual(handoff.download_link(self.root), f'https://github.com/example/keyboard/raw/{head}/dist/HeliBoard.apk')

    def test_link_rejects_unpushed_commit(self):
        self.commit()
        self.remote()
        self.git('commit', '--allow-empty', '-qm', 'not pushed')
        with self.assertRaisesRegex(ValueError, 'Push'):
            handoff.download_link(self.root)


if __name__ == '__main__':
    unittest.main()
