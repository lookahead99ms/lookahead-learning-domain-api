#!/usr/bin/env python3
"""`01 · Full`: cut this app's calendar release tag at the run commit.

Tag-driven, never commit-driven. The pipeline cuts the next tag (`release_version.nextVersion`) at
$GITHUB_SHA: the merge commit on `push`, the main head on `workflow_dispatch` (refused off main).
The tag is created through the GitHub API with GITHUB_TOKEN (`contents: write` in the tag job only).
POST git/refs refuses an existing ref, so a tag is never moved or force-updated. A tag of this
sequence that already names the run commit is reused (a dispatch from the same main head); a name
another run claimed first fails. Everything downstream resolves the tag with release_version.py;
no commit SHA is ever an input. The DEV deploy runs inside the same pipeline run (candidate.py and
Infra tooling at Infra's release tag), never as a dispatch of another repository's workflow. This
file is identical in the Domain, Web and Gateway/BFF repositories.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess

import release_version as versions

SCHEMA = 'release-tag/v1'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def gh(args, env=None):
    result = subprocess.run(['gh', *args], capture_output=True, text=True, timeout=60, env=env)
    require(result.returncode == 0, 'GitHub request failed; no tag was recorded')
    require(len(result.stdout) <= 4 * 1024 * 1024, 'GitHub response exceeds bound')
    return result.stdout


def inventory(repository, run=None):
    run = run or gh
    """Every `v*` tag ref as (tag, objectType, sha). Lines, not pages, so `--paginate` output stays parseable."""
    rows = run(['api', 'repos/' + repository + '/git/matching-refs/tags/v', '--paginate',
                '-q', '.[] | [.ref, .object.type, .object.sha] | @tsv'])
    result = []
    for line in rows.splitlines():
        parts = line.split('\t')
        if len(parts) == 3 and parts[0].startswith('refs/tags/'):
            result.append((parts[0][len('refs/tags/'):], parts[1], parts[2]))
    return result


def existing(sha, tags):
    """Newest calendar tag that already names `sha` directly (lightweight), else None. Annotated tags are not reused."""
    matches = [tag for tag, kind, target in tags
               if kind == 'commit' and target == sha and re.fullmatch(versions.PATTERN, tag, re.ASCII)]
    return max(matches, key=lambda tag: (tag[:14], int(tag.rsplit('.', 1)[1]))) if matches else None


def cut(app, repository, sha, runId, record, run=None, now=None):
    run = run or gh
    require(re.fullmatch('[a-f0-9]{40}', str(sha)), 'Exact run commit required')
    require(not Path(record).exists(), 'Release-tag record already exists; use a new run')
    tags = inventory(repository, run)
    reused = existing(sha, tags)
    version = reused or versions.nextVersion(app, [tag for tag, _, _ in tags], now)
    if not reused:
        try:
            # POST git/refs refuses an existing ref (422): created exactly once, never force-updated.
            run(['api', 'repos/' + repository + '/git/refs', '--method', 'POST',
                 '-f', 'ref=refs/tags/' + version, '-f', 'sha=' + sha])
        except ValueError:
            raise ValueError('Release tag ' + version + ' could not be created (another run may have claimed it); start a new run')
    commit, tagObject = versions.peel(version, lambda path: json.loads(run(['api', 'repos/' + repository + '/' + path])))
    require(commit == sha and tagObject is None, 'Release tag ' + version + ' does not name the run commit')
    created = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
    result = {'schema': SCHEMA, **versions.metadata(app, version), 'sourceSha': sha, 'workflowSha': sha, 'runId': str(runId),
              'repository': repository, 'reused': bool(reused), 'createdAt': created}
    Path(record).write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    return result


def main(argv=None, env=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['cut'])
    parser.add_argument('--app', required=True, choices=versions.APPS)
    parser.add_argument('--record', help='cut: frozen release-tag record to write (must not exist)')
    args = parser.parse_args(argv)
    require(args.record is not None, '--record path required')
    event, sha, repository = env.get('GITHUB_EVENT_NAME'), env.get('GITHUB_SHA', ''), env.get('GITHUB_REPOSITORY', '')
    require(env.get('GITHUB_REF') == 'refs/heads/main' and event in versions.EVENTS and env.get('GITHUB_RUN_ATTEMPT') == '1',
            'Tags are cut by first-attempt runs on main only (merge push or manual dispatch); refusing')
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Exact repository required')
    def get(path):
        return json.loads(gh(['api', 'repos/' + repository + '/' + path]))
    versions.mainBinding(event, sha, get('commits/main').get('sha', ''), get)
    result = cut(args.app, repository, sha, env.get('GITHUB_RUN_ID', ''), args.record)
    if env.get('GITHUB_OUTPUT'):
        with open(env['GITHUB_OUTPUT'], 'a') as output:
            output.write('release_version=' + result['releaseVersion'] + '\nsource_sha=' + result['sourceSha'] + '\n')
    print(json.dumps(result, sort_keys=True))
    return result


if __name__ == '__main__':
    main()
