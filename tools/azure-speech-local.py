#!/usr/bin/env python3
"""Local macOS Speech credentials and SDK commands; never embeds a key in builds."""
import argparse
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


SERVICE = 'HeliBoard.AzureSpeech'
PROFILE = Path.home() / 'Library/Application Support/HeliBoard/azure-speech.json'
REGIONS = ('centralus', 'swedencentral', 'southeastasia')


def keychain():
    if sys.platform != 'darwin':
        raise RuntimeError('macOS Keychain is required.')
    # Select the native backend explicitly; never fall back to a plaintext backend.
    from keyring.backends.macOS import Keyring
    return Keyring()


def profile():
    data = json.loads(PROFILE.read_text())
    if data.get('region') not in REGIONS or not data.get('resource'):
        raise RuntimeError('Run configure with a supported region and resource name.')
    return data


def speech_key(data):
    key = keychain().get_password(SERVICE, data['resource'])
    if not key:
        raise RuntimeError('The Speech key is missing from Keychain. Run configure.')
    return key


def configure(args):
    key = sys.stdin.read().strip() if args.key_stdin else getpass.getpass('Azure Speech key: ').strip()
    if not key or any(character.isspace() for character in key):
        raise RuntimeError('The Speech key must be a nonempty single value.')
    data = {'region': args.region, 'resource': args.resource}
    if args.tenant:
        data['tenant'] = args.tenant
    if args.subscription:
        data['subscription'] = args.subscription
    keychain().set_password(SERVICE, args.resource, key)
    PROFILE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # The profile contains only metadata. Atomic replacement also keeps its mode 0600.
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=PROFILE.parent, delete=False) as output:
            temporary_path = Path(output.name)
            json.dump(data, output, indent=2)
            output.write('\n')
        temporary_path.replace(PROFILE)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    print('Speech key saved in macOS Keychain. Local metadata saved outside the repository.')
    return 0


def run(data, command):
    if command and command[0] == '--':
        command = command[1:]
    if not command:
        raise RuntimeError('Provide a command after run --.')
    environment = os.environ.copy()
    environment['SPEECH_KEY'] = speech_key(data)
    environment['SPEECH_REGION'] = data['region']
    environment['PATH'] = str(Path(sys.executable).parent) + os.pathsep + environment.get('PATH', '')
    return subprocess.run(command, env=environment, check=False).returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    save = commands.add_parser('configure', help='Store the key in Keychain and nonsecret local metadata.')
    save.add_argument('--region', choices=REGIONS, required=True)
    save.add_argument('--resource', required=True)
    save.add_argument('--tenant')
    save.add_argument('--subscription')
    save.add_argument('--key-stdin', action='store_true', help='Read the key from stdin instead of a hidden prompt.')
    commands.add_parser('status', help='Show configuration and key availability without revealing the key.')
    commands.add_parser('copy-key', help='Copy the key to the macOS clipboard for device settings.')
    execute = commands.add_parser('run', help='Supply credentials only to the requested child command.')
    execute.add_argument('command', nargs=argparse.REMAINDER)
    smoke = commands.add_parser('smoke-test', help='Run the repository Speech SDK check.')
    smoke.add_argument('--pcm', type=Path, required=True)
    smoke.add_argument('--language')
    args = parser.parse_args()
    if args.action == 'configure':
        return configure(args)
    data = profile()
    if args.action == 'status':
        print(json.dumps({**data, 'key_available': bool(keychain().get_password(SERVICE, data['resource'])),
                          'key_storage': 'macOS Keychain', 'profile': str(PROFILE)}, indent=2))
        return 0
    if args.action == 'copy-key':
        subprocess.run(['/usr/bin/pbcopy'], input=speech_key(data).encode(), check=True)
        print('Speech key copied to the clipboard.')
        return 0
    if args.action == 'smoke-test':
        command = [sys.executable, str(Path(__file__).with_name('mai-streaming-smoke-test.py')),
                   '--pcm', str(args.pcm)]
        if args.language:
            command.extend(['--language', args.language])
        return run(data, command)
    return run(data, args.command)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception:
        # Native errors and child exception details can contain credentials; do not echo them.
        print('Local Speech command failed. Check configuration, Keychain access, and the Python environment.',
              file=sys.stderr)
        sys.exit(1)
