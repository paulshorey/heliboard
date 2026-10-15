#!/usr/bin/env python3
"""Record/verify a canonical APK and print its pushed, immutable GitHub download link."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
APK = Path('dist/HeliBoard.apk')
METADATA = Path('dist/apk-metadata.json')


def git(*args: str, root: Path = ROOT) -> bytes:
    return subprocess.check_output(['git', *args], cwd=root)


def source_digest(root: Path = ROOT) -> str:
    """Fingerprint Git-visible working files, including new files, modes and symlinks.

    dist is output. Ignored SDKs, caches, credentials and local.properties are not
    inputs to this fingerprint; this is freshness evidence, not a hermetic build.
    """
    names = set(git('ls-files', '-z', '--cached', '--others', '--exclude-standard', root=root).split(b'\0'))
    digest = hashlib.sha256()
    for name in sorted(names):
        if not name or name.startswith(b'dist/'):
            continue
        path = root / os.fsdecode(name)
        # During a directory-to-symlink migration, the index can still list old
        # descendants. They are aliases, not independent working-tree inputs.
        if any((root / parent).is_symlink() for parent in path.relative_to(root).parents):
            continue
        if path.is_symlink():
            mode, content = b'120000', os.fsencode(os.readlink(path))
        elif path.is_file():
            mode = b'100755' if path.stat().st_mode & 0o111 else b'100644'
            content = path.read_bytes()
        elif not path.exists():
            continue  # tracked deletion; committed and uncommitted forms agree
        else:
            raise ValueError(f'Unsupported source entry: {name!r}')
        digest.update(name + b'\0' + mode + b'\0' + hashlib.sha256(content).digest())
    return digest.hexdigest()


def apk_digest(root: Path = ROOT) -> str:
    path = root / APK
    with zipfile.ZipFile(path) as archive:
        if 'AndroidManifest.xml' not in archive.namelist() or archive.testzip():
            raise ValueError('Canonical APK is incomplete or corrupt; rebuild it.')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(expected: str, root: Path = ROOT) -> None:
    actual = source_digest(root)
    if expected != actual:
        raise ValueError('Source changed while building; finish edits and rebuild the APK.')
    data = dict(schema=1, variant='debug', application_id='helium314.keyboard.debug',
                built_at=datetime.now(timezone.utc).isoformat(),
                source_sha256=actual, apk_sha256=apk_digest(root))
    (root / METADATA).write_text(json.dumps(data, indent=2) + '\n')


def check(root: Path = ROOT) -> dict:
    data = json.loads((root / METADATA).read_text())
    if data.get('schema') != 1 or data.get('variant') != 'debug':
        raise ValueError('Unknown APK metadata; rebuild the APK.')
    if data.get('source_sha256') != source_digest(root):
        raise ValueError('APK is stale for the current files; run ./tools/build-dist-apk.sh.')
    if data.get('apk_sha256') != apk_digest(root):
        raise ValueError('APK bytes do not match build metadata; rebuild the APK.')
    return data


def download_link(root: Path = ROOT) -> str:
    check(root)
    if git('status', '--porcelain', root=root).strip():
        raise ValueError('Commit all intended source, APK and metadata changes before sharing.')
    git('ls-files', '--error-unmatch', str(APK), str(METADATA), root=root)
    head = git('rev-parse', 'HEAD', root=root).decode().strip()
    branch = git('symbolic-ref', '--short', 'HEAD', root=root).decode().strip()
    remote = git('ls-remote', 'origin', f'refs/heads/{branch}', root=root).decode().split()
    if not remote or remote[0] != head:
        raise ValueError('Push this branch to origin before sharing the APK.')
    # Read the configured URL before any local insteadOf transport rewrite.
    origin = git('config', '--get', 'remote.origin.url', root=root).decode().strip()
    match = re.fullmatch(r'(?:git@github\.com:|https://github\.com/|ssh://git@github\.com/)([^/]+/[^/]+?)(?:\.git)?/?', origin)
    if not match:
        raise ValueError('Expected a github.com origin URL; determine the download host explicitly.')
    return f'https://github.com/{match[1]}/raw/{head}/{APK.as_posix()}'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('snapshot', help='fingerprint current source before Gradle runs')
    recorder = commands.add_parser('record', help='called by build-dist-apk.sh after a successful build')
    recorder.add_argument('--source-sha256', required=True)
    commands.add_parser('check', help='verify local APK freshness and bytes')
    commands.add_parser('link', help='verify committed/pushed build and print Markdown')
    args = parser.parse_args()
    try:
        if args.command == 'snapshot':
            print(source_digest())
        elif args.command == 'record':
            record(args.source_sha256)
        elif args.command == 'check':
            data = check()
            print(f'APK verified: {APK} ({(ROOT / APK).stat().st_size:,} bytes)\nSHA-256: {data["apk_sha256"]}')
        else:
            print(f'[Download HeliBoard APK]({download_link()})')
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print(f'APK handoff failed: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
