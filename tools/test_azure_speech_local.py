"""Local credential status regressions without accessing the real Keychain."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location('azure_speech_local',
    Path(__file__).with_name('azure-speech-local.py'))
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


class SpeechCredentialStatusTest(unittest.TestCase):
    def status(self, key):
        output = io.StringIO()
        backend = Mock()
        backend.get_password.return_value = key
        with patch.object(local, 'profile', return_value={'region': 'centralus', 'resource': 'test-resource'}), \
                patch.object(local, 'keychain', return_value=backend), \
                patch.object(local.sys, 'argv', ['azure-speech-local.py', 'status']), \
                contextlib.redirect_stdout(output):
            self.assertEqual(0, local.main())
        backend.get_password.assert_called_once_with(local.SERVICE, 'test-resource')
        return output.getvalue()

    def test_missing_key_reports_false_in_status_json(self):
        self.assertIs(json.loads(self.status(None))['key_available'], False)

    def test_available_key_reports_true_without_printing_the_key(self):
        output = self.status('synthetic-test-secret')
        self.assertIs(json.loads(output)['key_available'], True)
        self.assertNotIn('synthetic-test-secret', output)

    def test_missing_key_still_prevents_a_credentialed_command(self):
        backend = Mock()
        backend.get_password.return_value = None
        with patch.object(local, 'keychain', return_value=backend), \
                patch.object(local.subprocess, 'run') as execute:
            with self.assertRaises(RuntimeError):
                local.run({'region': 'centralus', 'resource': 'test-resource'}, ['example'])
        execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
