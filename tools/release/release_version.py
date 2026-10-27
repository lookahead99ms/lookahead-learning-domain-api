"""UTC calendar releases. Read-only tag selection/resolution; never create or move tags.

Source is selected by an existing release tag. The tag is resolved once per run to its exact
commit, that commit must be an ancestor of main, and the frozen record is re-checked before any
credential or upload step. The commit is derived and recorded; it is never an operator input.
"""
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


def calendar(version):
    """Validate the shared UTC calendar tag format; no app binding."""
    if not isinstance(version, str) or not re.fullmatch(r'v\d{4}\.\d{2}\.\d{2}\.\d{2}\.(?:\d{2}|[1-9]\d{2,})', version, re.ASCII):
        raise ValueError('Require vyyyy.mm.dd.hh.<00-nn> release tag')
    datetime.strptime(version[1:14], '%Y.%m.%d.%H')
    return version


def metadata(app, version):
    if app not in APPS:
        raise ValueError('Require app and vyyyy.mm.dd.hh.<00-nn> release')
    calendar(version)
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


def peel(version, get):
    """Resolve an existing tag to (commit, tagObject) with bounded annotated-tag peeling."""
    obj = get('git/ref/tags/' + version).get('object', {})
    tagObject, seen = None, set()
    for _ in range(5):
        sha = obj.get('sha', '')
        if not re.fullmatch('[a-f0-9]{40}', sha):
            break
        if obj.get('type') == 'commit':
            return sha, tagObject
        if obj.get('type') != 'tag' or sha in seen:
            break
        seen.add(sha)
        tagObject = tagObject or sha
        obj = get('git/tags/' + sha).get('object', {})
    raise ValueError('Existing Git release tag must resolve to exactly one commit')


def gitTag(version, revision, app, get):
    """Exact check used where the build checkout is already known: tag must name `revision`."""
    result = metadata(app, version)
    if not re.fullmatch('[a-f0-9]{40}', revision):
        raise ValueError('Exact source SHA required')
    commit, _ = peel(version, get)
    if commit != revision:
        raise ValueError('Existing Git release tag must resolve to the exact reviewed source')
    return result


def onMain(commit, mainSha, get):
    """Require `commit` to be an ancestor of (or equal to) the observed main head."""
    if not re.fullmatch('[a-f0-9]{40}', mainSha):
        raise ValueError('Exact observed main SHA required')
    compare = get('compare/' + commit + '...' + mainSha)
    if compare.get('status') not in ('identical', 'ahead') or compare.get('merge_base_commit', {}).get('sha') != commit:
        raise ValueError('Release tag commit must be on main')


def resolve(version, get, mainSha, app=None):
    """Resolve a release tag once: exact commit, tag object and observed main. Never moves a tag."""
    result = metadata(app, version) if app else {'releaseVersion': calendar(version)}
    commit, tagObject = peel(version, get)
    onMain(commit, mainSha, get)
    return {**result, 'schema': 'release-source/v1', 'selector': version, 'sourceSha': commit,
            'tagObjectSha': tagObject, 'mainSha': mainSha}


def recheck(record, get, mainSha, app=None):
    """Before credentials or upload: the same tag must still name the same commit and tag object."""
    if not isinstance(record, dict) or record.get('schema') != 'release-source/v1':
        raise ValueError('Frozen release-source record required')
    current = resolve(record['selector'], get, mainSha, app)
    if any(current.get(k) != record.get(k) for k in ('selector', 'sourceSha', 'tagObjectSha', 'releaseVersion')):
        raise ValueError('Release tag changed after resolution; start a new run')
    return current


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
    parser.add_argument('mode', choices=['next', 'check', 'resolve', 'recheck'])
    parser.add_argument('--app', required=True, choices=APPS)
    parser.add_argument('--record', help='Frozen release-source JSON written by resolve and read by recheck')
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
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch':
        raise ValueError('Manual main release required')
    if os.environ.get('ECR_REPOSITORY', REPOSITORY) != REPOSITORY:
        raise ValueError('Exact shared DEV repository required')
    if args.mode == 'check':
        # The build checkout is the tag-resolved source; the workflow itself still runs from main.
        revision = os.environ.get('SOURCE_SHA') or os.environ['GITHUB_SHA']
        print(json.dumps(gitTag(os.environ['RELEASE_VERSION'], revision, args.app, get), sort_keys=True))
        return
    if not args.record:
        raise ValueError('--record path required')
    workflowSha = os.environ['GITHUB_SHA']
    mainSha = get('commits/main').get('sha', '')
    if mainSha != workflowSha:
        raise ValueError('Main moved; review a new run')
    if args.mode == 'resolve':
        if os.path.exists(args.record):
            raise ValueError('Release-source record already exists; use a new run')
        result = {**resolve(os.environ['RELEASE_VERSION'], get, mainSha, args.app), 'workflowSha': workflowSha,
                  'repository': os.environ['GITHUB_REPOSITORY'], 'runId': os.environ.get('GITHUB_RUN_ID', '')}
        Path(args.record).write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    else:
        result = json.loads(Path(args.record).read_text())
        if result.get('workflowSha') != workflowSha or result.get('repository') != os.environ['GITHUB_REPOSITORY']:
            raise ValueError('Release-source record belongs to another workflow run')
        recheck(result, get, mainSha, args.app)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('source_sha=' + result['sourceSha'] + '\nrelease_version=' + result['releaseVersion'] + '\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
