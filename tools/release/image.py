#!/usr/bin/env python3
"""Bind a scanned local image archive to the exact source; never rebuild on import."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def identity(image):
    info = json.loads(command('docker', 'image', 'inspect', image))[0]
    if info.get('Os') != 'linux' or info.get('Architecture') != 'amd64' or not re.fullmatch(r'sha256:[a-f0-9]{64}', info.get('Id', '')):
        raise ValueError('Require exact linux/amd64 image identity')
    return info['Id']


def archiveIdentity(archive):
    """Read config bytes without extracting untrusted archive paths."""
    with tarfile.open(archive, 'r:*') as package:
        members = package.getmembers()
        names = [member.name for member in members]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate image archive members rejected')
        def read(name):
            member = package.getmember(name)
            if not member.isfile() or member.size > 2 * 1024 * 1024:
                raise ValueError('Require bounded regular image metadata')
            return package.extractfile(member).read()
        manifest = json.loads(read('manifest.json'))
        if not isinstance(manifest, list) or len(manifest) != 1:
            raise ValueError('Require exactly one archived image')
        configPath = manifest[0]['Config']
        match = re.fullmatch(r'(?:blobs/sha256/([a-f0-9]{64})|([a-f0-9]{64})\.json)', configPath)
        if not match:
            raise ValueError('Require content-addressed image config')
        raw = read(configPath)
        configDigest = hashlib.sha256(raw).hexdigest()
        if configDigest != (match.group(1) or match.group(2)):
            raise ValueError('Archived config digest changed')
        config = json.loads(raw)
        if config.get('os') != 'linux' or config.get('architecture') != 'amd64':
            raise ValueError('Require archived linux/amd64 config')
        if 'index.json' in names:
            runnable, attestations = [], []
            def walk(descriptor, depth=0):
                if depth > 4:
                    raise ValueError('Image index nesting exceeds boundary')
                value = descriptor.get('digest', '')
                if not re.fullmatch(r'sha256:[a-f0-9]{64}', value):
                    raise ValueError('Invalid indexed manifest digest')
                content = read('blobs/sha256/' + value.split(':')[1])
                if 'sha256:' + hashlib.sha256(content).hexdigest() != value:
                    raise ValueError('Indexed manifest digest changed')
                document = json.loads(content)
                if document.get('schemaVersion') != 2:
                    raise ValueError('Invalid indexed manifest schema')
                if 'manifests' in document:
                    children = document['manifests']
                    if not isinstance(children, list) or not 1 <= len(children) <= 8:
                        raise ValueError('Invalid image index inventory')
                    for child in children: walk(child, depth + 1)
                elif descriptor.get('annotations', {}).get('vnd.docker.reference.type') == 'attestation-manifest':
                    if descriptor.get('platform') != {'os': 'unknown', 'architecture': 'unknown'}:
                        raise ValueError('Attestation cannot substitute runnable platform')
                    attestations.append(descriptor['annotations'].get('vnd.docker.reference.digest'))
                else:
                    if document.get('config', {}).get('digest') != 'sha256:' + configDigest:
                        raise ValueError('Image index includes another runnable config')
                    declared = descriptor.get('platform', {'os':'linux', 'architecture':'amd64'})
                    if declared.get('os') != 'linux' or declared.get('architecture') != 'amd64':
                        raise ValueError('Image index platform substitution')
                    runnable.append(value)
            index = json.loads(read('index.json'))
            if index.get('schemaVersion') != 2 or not isinstance(index.get('manifests'), list) or len(index['manifests']) != 1:
                raise ValueError('Require one archive root image')
            walk(index['manifests'][0])
            if len(runnable) != 1 or any(target != runnable[0] for target in attestations):
                raise ValueError('Require one runnable image and bound attestations')
        return 'sha256:' + configDigest


def loadedReference(output):
    references = re.findall(r'^Loaded image(?: ID)?: ([^\s]+)$', output, re.MULTILINE)
    if len(references) != 1:
        raise ValueError('Require exactly one loaded image reference')
    return references[0]


def verifyLoaded(reference, expectedConfig):
    identity(reference)
    with tempfile.TemporaryDirectory(prefix='lookahead-image-') as temporary:
        saved = Path(temporary) / 'loaded.tar'
        subprocess.run(['docker', 'image', 'save', '--output', str(saved), reference], check=True, stdout=subprocess.DEVNULL)
        if archiveIdentity(saved) != expectedConfig:
            raise ValueError('Loaded config differs from scanned artifact')
    return reference


def inputs(root):
    if command('git', 'diff', '--name-only', 'HEAD') or command('git', 'ls-files', '--others', '--exclude-standard'):
        raise ValueError('Release source must be committed and clean')
    revision = command('git', 'rev-parse', 'HEAD')
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('Require exact Git revision')
    return {'revision': revision, 'dockerfileSha256': digest(root / 'Dockerfile'),
            'workflowSha256': digest(root / '.github/workflows/ci.yml')}


def validate(receipt, archive, expected):
    required = {'schemaVersion', 'revision', 'dockerfileSha256', 'workflowSha256', 'imageId', 'archiveSha256', 'platform'}
    if not isinstance(receipt, dict) or set(receipt) != required or receipt['schemaVersion'] != 'lookahead-scanned-image/v1' or receipt['platform'] != 'linux/amd64':
        raise ValueError('Invalid scanned image receipt')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', receipt['imageId']):
        raise ValueError('Invalid image identity')
    if any(receipt.get(key) != value for key, value in expected.items()) or receipt['archiveSha256'] != digest(archive):
        raise ValueError('Image source or archive binding changed')
    if archiveIdentity(archive) != receipt['imageId']:
        raise ValueError('Archive config differs from scanned identity')
    return receipt['imageId']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['export', 'import'])
    parser.add_argument('--image')
    parser.add_argument('--directory', required=True)
    args = parser.parse_args()
    root = Path.cwd()
    directory = Path(args.directory)
    archive, receiptPath = directory / 'image.tar', directory / 'image.json'
    if any(p.is_symlink() for p in [directory, *directory.parents, archive, receiptPath]):
        raise ValueError('Image artifact symlinks rejected')
    expected = inputs(root)
    if args.action == 'export':
        if not args.image or archive.exists() or receiptPath.exists():
            raise ValueError('Export requires image and new artifact paths')
        directory.mkdir(parents=True, exist_ok=True)
        imageId = identity(args.image)
        subprocess.run(['docker', 'image', 'save', '--output', str(archive), imageId], check=True, stdout=subprocess.DEVNULL)
        configId = archiveIdentity(archive)
        if identity(args.image) != imageId:
            raise ValueError('Export image reference changed')
        receipt = {'schemaVersion': 'lookahead-scanned-image/v1', **expected, 'imageId': configId,
                   'archiveSha256': digest(archive), 'platform': 'linux/amd64'}
        receiptPath.write_text(json.dumps(receipt, sort_keys=True) + '\n')
        print(imageId)
    else:
        receipt = json.loads(receiptPath.read_text())
        imageId = validate(receipt, archive, expected)
        loaded = loadedReference(command('docker', 'image', 'load', '--input', str(archive)))
        print(verifyLoaded(loaded, imageId))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, json.JSONDecodeError, tarfile.TarError, TypeError):
        print('Immutable image binding failed; no publication permitted.', file=sys.stderr)
        sys.exit(1)
