#!/usr/bin/env python3
"""Bind a scanned local image archive to the exact source; never rebuild on import."""
import os
import release_version as versions
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


def workflowFile(root, workflowSha):
    """Bytes of ci.yml at the executed workflow commit (GITHUB_SHA), read from the trusted checkout."""
    return subprocess.check_output(['git', '-C', str(root), 'show', workflowSha + ':.github/workflows/ci.yml'])


def inputs(root, sourceRoot=None, workflowSha=None):
    """Bind the build to two commits: the tag-resolved application source (revision, S) and the
    executed workflow commit (workflowSha, M). Locally, without GITHUB_SHA, both are the checkout."""
    root, source = Path(root), Path(sourceRoot or root)
    git = ('git', '-C', str(source))
    if command(*git, 'diff', '--name-only', 'HEAD') or command(*git, 'ls-files', '--others', '--exclude-standard'):
        raise ValueError('Release source must be committed and clean')
    revision = command(*git, 'rev-parse', 'HEAD')
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('Require exact Git revision')
    if os.environ.get('SOURCE_SHA') and os.environ['SOURCE_SHA'] != revision:
        raise ValueError('Checkout differs from the resolved release source')
    workflowSha = workflowSha or os.environ.get('GITHUB_SHA')
    if workflowSha:
        if not re.fullmatch(r'[a-f0-9]{40}', workflowSha):
            raise ValueError('Require exact executed workflow commit')
        workflowSha256 = hashlib.sha256(workflowFile(root, workflowSha)).hexdigest()
    else:
        workflowSha, workflowSha256 = revision, digest(root / '.github/workflows/ci.yml')
    release = versions.metadata('domain', os.environ['RELEASE_VERSION']) if os.environ.get('RELEASE_VERSION') else {}
    return {**release, 'revision': revision, 'workflowSha': workflowSha, 'dockerfileSha256': digest(source / 'Dockerfile'),
            'workflowSha256': workflowSha256}


def validate(receipt, archive, expected):
    required = {'schemaVersion', 'revision', 'dockerfileSha256', 'workflowSha256', 'imageId', 'archiveSha256', 'platform'}
    versioned = bool(expected.get('releaseVersion'))
    if versioned:
        # v3 records the executed workflow commit (M) beside the built source commit (S).
        required |= versions.FIELDS | {'workflowSha'}
        versions.validate(receipt, 'domain')
    else:
        expected = {key: value for key, value in expected.items() if key != 'workflowSha'}
    if not isinstance(receipt, dict) or set(receipt) != required or receipt['schemaVersion'] != ('lookahead-scanned-image/v3' if versioned else 'lookahead-scanned-image/v1') or receipt['platform'] != 'linux/amd64':
        raise ValueError('Invalid scanned image receipt')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', receipt['imageId']):
        raise ValueError('Invalid image identity')
    if versioned and not re.fullmatch(r'[a-f0-9]{40}', str(receipt['workflowSha'])):
        raise ValueError('Invalid executed workflow commit')
    if any(receipt.get(key) != value for key, value in expected.items()) or receipt['archiveSha256'] != digest(archive):
        raise ValueError('Image source or archive binding changed')
    if archiveIdentity(archive) != receipt['imageId']:
        raise ValueError('Archive config differs from scanned identity')
    if versioned: versions.archiveLabels(archive, receipt)
    return receipt['imageId']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['export', 'import'])
    parser.add_argument('--image')
    parser.add_argument('--directory', required=True)
    parser.add_argument('--source-root', help='Tag-resolved source checkout when the tooling runs from main')
    args = parser.parse_args()
    root = Path.cwd()
    directory = Path(args.directory)
    archive, receiptPath = directory / 'image.tar', directory / 'image.json'
    if any(p.is_symlink() for p in [directory, *directory.parents, archive, receiptPath]):
        raise ValueError('Image artifact symlinks rejected')
    sourceRoot = Path(args.source_root) if args.source_root else None
    if sourceRoot is not None and (sourceRoot.is_symlink() or not sourceRoot.is_dir()):
        raise ValueError('Release source root must be a real directory')
    expected = inputs(root, sourceRoot)
    if args.action == 'export':
        if not args.image or archive.exists() or receiptPath.exists():
            raise ValueError('Export requires image and new artifact paths')
        directory.mkdir(parents=True, exist_ok=True)
        imageId = identity(args.image)
        subprocess.run(['docker', 'image', 'save', '--output', str(archive), imageId], check=True, stdout=subprocess.DEVNULL)
        configId = archiveIdentity(archive)
        if identity(args.image) != imageId:
            raise ValueError('Export image reference changed')
        versioned = bool(expected.get('releaseVersion'))
        receipt = {'schemaVersion': 'lookahead-scanned-image/v3' if versioned else 'lookahead-scanned-image/v1',
                   **{key: value for key, value in expected.items() if versioned or key != 'workflowSha'}, 'imageId': configId,
                   'archiveSha256': digest(archive), 'platform': 'linux/amd64'}
        validate(receipt, archive, expected)
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
