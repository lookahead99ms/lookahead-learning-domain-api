"""UTC calendar releases. Read-only tag selection/validation; never create or move tags."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

APPS = ('domain', 'web', 'gateway-bff', 'initializer')
FIELDS = {'app', 'releaseVersion', 'buildVersion', 'imageTag'}
REPOSITORY = '509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr'


def metadata(app, version):
    if app not in APPS or not isinstance(version, str) or not re.fullmatch(r'v\d{4}\.\d{2}\.\d{2}\.\d{2}\.(?:\d{2}|[1-9]\d{2,})', version, re.ASCII):
        raise ValueError('Require app and vyyyy.mm.dd.hh.<00-nn> release')
    datetime.strptime(version[1:14], '%Y.%m.%d.%H')
    return {'app': app, 'releaseVersion': version, 'buildVersion': version[1:], 'imageTag': app + '-' + version}


def validate(value, app=None):
    expected = metadata(app or value.get('app'), value.get('releaseVersion'))
    if any(value.get(k) != v for k, v in expected.items()):
        raise ValueError('Release app/version/tag binding differs')
    return expected


def nextVersion(app, tags, now=None):
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None or app not in APPS:
        raise ValueError('Known app and timezone-aware clock required')
    prefix = now.astimezone(timezone.utc).strftime('v%Y.%m.%d.%H.')
    counters = []
    # Each app has its own source repository. Input is that repository's complete tag inventory.
    for tag in tags:
        if tag.startswith(prefix):
            metadata(app, tag)
            counters.append(int(tag.rsplit('.', 1)[1]))
    return prefix + format(max(counters, default=-1) + 1, '02d')


def gitTag(version, revision, app, get):
    result = metadata(app, version)
    if not re.fullmatch('[a-f0-9]{40}', revision):
        raise ValueError('Exact source SHA required')
    obj = get('git/ref/tags/' + version).get('object', {})
    seen = set()
    for _ in range(5):
        if obj.get('type') == 'commit' and obj.get('sha') == revision:
            return result
        sha = obj.get('sha', '')
        if obj.get('type') != 'tag' or not re.fullmatch('[a-f0-9]{40}', sha) or sha in seen:
            break
        seen.add(sha)
        obj = get('git/tags/' + sha).get('object', {})
    raise ValueError('Existing Git release tag must resolve to the exact reviewed source')


def archiveLabels(archive, release):
    """Bind release labels to the content-addressed runnable image config, without extraction."""
    expected = validate(release)
    with tarfile.open(archive, 'r:*') as package:
        def read(name):
            matches = [m for m in package.getmembers() if m.name == name]
            if len(matches) != 1 or not matches[0].isfile() or matches[0].size > 2 * 1024 * 1024:
                raise ValueError('Bounded unique regular image metadata required')
            return package.extractfile(matches[0]).read()
        manifest = json.loads(read('manifest.json'))
        if not isinstance(manifest, list) or len(manifest) != 1:
            raise ValueError('Exactly one image required')
        path = manifest[0]['Config']
        raw = read(path)
        digest = hashlib.sha256(raw).hexdigest()
        if path not in (digest + '.json', 'blobs/sha256/' + digest):
            raise ValueError('Image config digest changed')
        labels = json.loads(raw).get('config', {}).get('Labels', {})
        required = {'org.opencontainers.image.version': expected['releaseVersion'],
                    'org.opencontainers.image.revision': release['revision'], 'com.lookahead.app': expected['app']}
        if any(labels.get(k) != v for k, v in required.items()):
            raise ValueError('Built image app/version/source differs from release')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['next', 'check'])
    parser.add_argument('--app', required=True, choices=APPS)
    args = parser.parse_args(argv)
    if args.mode == 'next':
        # Read remote inventory directly: local tags may be stale. Output is a proposal, not a reservation.
        rows = subprocess.check_output(['git', 'ls-remote', '--tags', 'origin'], text=True, timeout=60)
        tags = [row.split('\t', 1)[1][10:] for row in rows.splitlines() if not row.endswith('^{}')]
        print(nextVersion(args.app, tags))
        return
    def get(path):
        raw = subprocess.check_output(['gh', 'api', 'repos/' + os.environ['GITHUB_REPOSITORY'] + '/' + path], text=True, timeout=30)
        return json.loads(raw)
    result = gitTag(os.environ['RELEASE_VERSION'], os.environ['GITHUB_SHA'], args.app, get)
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch':
        raise ValueError('Manual main release required')
    if os.environ.get('ECR_REPOSITORY', REPOSITORY) != REPOSITORY:
        raise ValueError('Exact shared DEV repository required')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
