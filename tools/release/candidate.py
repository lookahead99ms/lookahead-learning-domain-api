#!/usr/bin/env python3
"""DEV candidate stages of `01 · Full`: Infra tooling selection, verification, keep-or-rollback decision, release record.

Tag-driven. Every mode reads the frozen release-source record of this run (RELEASE_SOURCE_RECORD) and never a typed
commit. Modes:

  infra      pick the newest Infra calendar release tag (Infra tooling is checked out at that commit, never at main)
  verify     prove the deployed DEV candidate is this release: the Infra candidate result names the tag and the
             published image digest; an optional live URL must answer 200 and name the tag
  decide     owner issue bound to this run: `APPROVE KEEP DEV <digest>` proceeds, `APPROVE ROLLBACK DEV <digest>`
             restores the previous release, `REJECT` stops the run with nothing changed; edited comments, expiry
             (40 minutes) and drift fail closed exactly like the publish approval
  cutover    held: the DEV public cutover tool lives in Infra and is not available yet (AWS lane, DLV-914)
  rollback   held: the DEV rollback tool lives in Infra and is not available yet (AWS lane, DLV-914)
  record     one release record per run: tag, source, image digest, approvals, verification and decision receipts

Identical copy in the Domain, Web and Gateway/BFF repositories. Never calls AWS.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request

import issue_template as issueTemplate
import owner_approval as approval
import release_version as versions

INFRA = 'lookahead99ms/lookahead-learning-infra'
SCHEMA_INFRA, SCHEMA_VERIFY, SCHEMA_DECISION, SCHEMA_RECORD = ('infra-tooling/v1', 'candidate-verify/v1',
                                                              'candidate-decision/v1', 'app-release-record/v1')
STAGES = ('dev',)
DECISIONS = {'KEEP': 'keep', 'ROLLBACK': 'rollback'}
HELD = {'cutover': 'DEV public cutover is not automated yet: Infra owns the listener switch (aws/operations/docs/'
                   'lower-cost-dev-release-slots.md); the AWS lane adds `application_cutover` under DLV-914. Nothing was changed.',
        'rollback': 'DEV rollback is not automated yet: Infra owns the slot restore; the AWS lane adds `application_rollback` '
                    'under DLV-914. The previous release keeps serving; nothing was changed.'}
MAX_BODY = 2 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write(path, value, *, new=True):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x' if new else 'w') as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write('\n')


def output(env, **values):
    if env.get('GITHUB_OUTPUT'):
        with open(env['GITHUB_OUTPUT'], 'a') as stream:
            for key, value in values.items():
                stream.write(key + '=' + str(value) + '\n')


def gh(args, env=None):
    result = subprocess.run(['gh', *args], capture_output=True, text=True, timeout=60, env=env)
    require(result.returncode == 0, 'GitHub request failed; nothing was recorded')
    require(len(result.stdout) <= 4 * MAX_BODY, 'GitHub response exceeds bound')
    return result.stdout


def infraTooling(env, run=None, now=None):
    """Newest Infra calendar tag and the commit it names: the deploy stage runs Infra tooling from that commit."""
    run = run or gh
    token = env.get('CROSS_REPO_READ_TOKEN', '')
    require(bool(token), 'Secret CROSS_REPO_READ_TOKEN is missing: Infra is private and its tooling is read at its release tag')
    rows = run(['api', 'repos/' + INFRA + '/git/matching-refs/tags/v', '--paginate', '-q', '.[] | [.ref, .object.type, .object.sha] | @tsv'],
               env={**os.environ, 'GH_TOKEN': token})
    tags = {}
    for line in rows.splitlines():
        parts = line.split('\t')
        if len(parts) == 3 and parts[0].startswith('refs/tags/') and parts[1] == 'commit':
            name = parts[0][len('refs/tags/'):]
            if re.fullmatch(versions.PATTERN, name, re.ASCII) and re.fullmatch('[a-f0-9]{40}', parts[2]):
                tags[name] = parts[2]
    require(bool(tags), 'Infra has no calendar release tag; the merge-driven Infra pipeline cuts one per main merge')
    newest = max(tags, key=lambda tag: (tag[:14], int(tag.rsplit('.', 1)[1])))
    created = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
    return {'schema': SCHEMA_INFRA, 'repository': INFRA, 'releaseVersion': newest, 'sourceSha': tags[newest], 'selectedAt': created,
            'selection': 'newest calendar tag of ' + INFRA + ' at selection time; tooling checked out at that commit'}


def releaseRecord(env):
    return approval.releaseRecord(env)


def readJson(path, limit=MAX_BODY):
    path = Path(path)
    require(path.is_file() and path.stat().st_size <= limit, 'Receipt missing or exceeds bound: ' + path.name)
    return json.loads(path.read_text(), object_pairs_hook=approval.unique)


def fetch(url, timeout=20):
    """Bounded GET without redirects: status and the first bytes of the body."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, headers={'User-Agent': 'lookahead-candidate-verify', 'Accept': 'application/json, text/plain'})
    with opener.open(request, timeout=timeout) as response:
        return response.status, response.read(64 * 1024).decode('utf-8', 'replace')


def verify(env, resultDir, registryPath, url=None, get=None, now=None):
    """The running DEV candidate is this release: Infra's result names the tag and the digest ECR returned at publish."""
    release = releaseRecord(env)
    tag = release['releaseVersion']
    bound = readJson(registryPath)
    digest = str(bound.get('image', {}).get('imageDigest', ''))
    require(re.fullmatch('sha256:[a-f0-9]{64}', digest) and bound.get('revision') == release['sourceSha'],
            'Publication receipt does not bind this release source to an image digest')
    resultDir = Path(resultDir)
    files = sorted(p for p in resultDir.glob('*.json') if p.is_file() and not p.is_symlink())
    require(bool(files), 'Infra candidate result is missing; the deploy stage did not record a running candidate')
    text = '\n'.join(p.read_text() for p in files)
    require(len(text) <= 4 * MAX_BODY, 'Candidate result exceeds bound')
    require(tag in text, 'Candidate result does not name release ' + tag)
    require(digest in text, 'Candidate result does not name the published image digest')
    live = None
    if url:
        require(url.startswith('https://'), 'Live verification URL must be https')
        status, body = (get or fetch)(url)
        require(status == 200, 'Live candidate answered ' + str(status))
        require(tag in body, 'Live candidate does not report release ' + tag)
        live = {'url': url, 'status': status, 'namesRelease': True}
    checked = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
    return {'schema': SCHEMA_VERIFY, 'stage': 'dev', 'releaseVersion': tag, 'sourceSha': release['sourceSha'], 'imageDigest': digest,
            'resultFiles': [p.name for p in files], 'live': live, 'verifiedAt': checked, 'verified': True}


def headline(stage, plan):
    return {'env': stage.upper(), 'action': 'Keep or roll back', 'subject': plan['imageTag'], 'change': 'verified candidate ' + plan['releaseVersion']}


def commands(stage, plan):
    """Digest-bound like every approval on this platform (issue_template accepts `APPROVE <WORDS> <sha256>` only)."""
    bound = ' ' + stage.upper() + ' ' + approval.digest(plan)
    return {'KEEP': 'APPROVE KEEP' + bound, 'ROLLBACK': 'APPROVE ROLLBACK' + bound}


def body(stage, plan, verification, receipt):
    t = issueTemplate.text
    keep, back = commands(stage, plan).values()
    gist = '\n'.join([
        '**Business change:** release `' + t(plan['releaseVersion']) + '` of ' + t(plan['app']) + ' is running as the ' + stage.upper()
        + ' candidate and passed verification. Decide whether it stays.',
        '',
        '1. `' + keep + '` keeps this release: the candidate becomes the served ' + stage.upper() + ' release and the pipeline proceeds to the next stage.',
        '2. `' + back + '` restores the previously served release; this candidate is stopped and nothing of it remains in service.',
        '3. `REJECT` stops this run with nothing changed. Any other comment is ignored; an edited comment never counts; after 40 minutes with no decision the run fails without changing anything.',
        '',
        'This decision changes which release serves ' + stage.upper() + '; it never publishes, rebuilds or deletes an image.'])
    shown = [('**' + key + '**', '`' + t(str(value)) + '`') for key, value in
             (('releaseVersion', plan['releaseVersion']), ('imageTag', plan['imageTag']), ('sourceSha', plan['sourceSha']),
              ('imageDigest', verification['imageDigest']), ('verifiedAt', verification['verifiedAt']),
              ('live', (verification.get('live') or {}).get('url') or 'no live URL configured'))]
    return issueTemplate.render(
        headline=headline(stage, plan), gist=gist, resources=[],
        overview='**Keep or roll back the verified ' + stage.upper() + ' candidate**\n\nOne comment decides. Without a decision nothing changes.',
        scope='### Candidate\n\n' + issueTemplate.table(('Field', 'Value'), shown),
        validation=[('Release tag ' + t(plan['releaseVersion']), 'resolved to source commit `' + t(plan['sourceSha'][:12]) + '`'),
                    ('Published image digest', '`' + t(verification['imageDigest']) + '`'),
                    ('Candidate result', 'names the tag and the digest (' + ', '.join(t(f) for f in verification['resultFiles']) + ')')],
        evidence='Review the verify stage log and the application-candidate-result artifact before deciding.',
        risks='KEEP and ROLLBACK both change which release serves ' + stage.upper() + '. The tools that perform them are Infra-owned; '
              'if one is unavailable the run stops and reports it without changing anything.',
        repository=plan['repository'], runId=plan['runId'], owner=approval.OWNER, expires=receipt['expiresAt'], created=receipt.get('createdAt'),
        permissionDeadline='No AWS credentials are issued to this decision job.',
        command=keep, payload={'plan': plan, 'verification': verification, 'rollbackCommand': back})


def planFor(env, get=approval.api):
    identity = approval.provenance(env, get)
    return {'schema': 'candidate-decision-plan/v1', **identity}


def decision(stage, plan, verification, receipt, env, get=approval.api, now=None):
    now = now or datetime.now(timezone.utc)
    created, expires = approval.timestamp(receipt['createdAt']), approval.timestamp(receipt['expiresAt'])
    require(receipt.get('schema') == SCHEMA_DECISION and receipt.get('planDigest') == approval.digest(plan) and receipt.get('runId') == plan['runId']
            and created <= now < expires and timedelta(0) < expires - created <= timedelta(minutes=40), 'Decision window expired or binding changed')
    issue = get(plan['repository'], 'issues/' + str(receipt['issue']))
    require(issue.get('state') == 'open' and not issue.get('pull_request') and issue.get('user', {}).get('login') == 'github-actions[bot]'
            and issue.get('body') == body(stage, plan, verification, receipt), 'Decision issue changed')
    comments = get(plan['repository'], 'issues/' + str(receipt['issue']) + '/comments?per_page=100')
    require(isinstance(comments, list) and len(comments) < 100, 'Incomplete decision comment inventory')
    wanted = {text: name for name, text in commands(stage, plan).items()}
    for comment in comments:
        if comment.get('user', {}).get('login') != approval.OWNER:
            continue
        text = comment.get('body', '').strip()
        require(text != 'REJECT', 'Owner rejected the candidate decision; nothing was changed')  # even an edited REJECT blocks
        if comment.get('created_at') != comment.get('updated_at'):
            continue
        when = approval.timestamp(comment['created_at'])
        if text in wanted and created <= when <= now and when < expires:
            return {'commentId': comment['id'], 'decision': DECISIONS[wanted[text]]}
    return None


def decide(stage, env, verification, get=approval.api, now=lambda: datetime.now(timezone.utc), sleep=time.sleep):
    require(stage in STAGES, 'Unsupported stage')
    require(verification.get('schema') == SCHEMA_VERIFY and verification.get('verified') is True, 'Verified candidate required before a decision')
    plan = planFor(env, get)
    require(verification.get('releaseVersion') == plan['releaseVersion'] and verification.get('sourceSha') == plan['sourceSha'],
            'Verification belongs to another release')
    start = now().replace(microsecond=0)
    receipt = {'schema': SCHEMA_DECISION, 'stage': stage, 'runId': plan['runId'], 'planDigest': approval.digest(plan),
               'createdAt': start.isoformat(), 'expiresAt': (start + timedelta(minutes=40)).isoformat()}
    preview = body(stage, plan, verification, receipt)
    require(len(preview) < 60000, 'Decision preview too large')
    issue = get(plan['repository'], 'issues', {'title': issueTemplate.title(headline(stage, plan), plan['runId']), 'body': preview})
    receipt['issue'] = issue['number']
    print('https://github.com/' + plan['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(120):
        found = decision(stage, plan, verification, receipt, env, get, now())
        if found is not None:
            return {**receipt, **found, 'plan': plan}
        sleep(20)
    raise TimeoutError('No keep-or-rollback decision within 40 minutes; nothing was changed')


def record(env, paths, now=None):
    """Everything this run proved, in one file; absent optional receipts are recorded as absent, never invented."""
    release = releaseRecord(env)
    result = {'schema': SCHEMA_RECORD, 'app': release['app'], 'releaseVersion': release['releaseVersion'], 'imageTag': release['imageTag'],
              'sourceSha': release['sourceSha'], 'workflowSha': release['workflowSha'], 'repository': release['repository'], 'runId': release['runId'],
              'recordedAt': (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat(), 'receipts': {}}
    for name, path in paths.items():
        path = Path(path) if path else None
        if path is None or not path.is_file():
            result['receipts'][name] = None
            continue
        data = readJson(path)
        require(not any(k in data and data[k] != release[k] for k in ('releaseVersion', 'sourceSha', 'runId') if k in release),
                'Receipt ' + name + ' belongs to another release or run')
        result['receipts'][name] = data
    bound = result['receipts'].get('registryImage') or {}
    result['imageDigest'] = (bound.get('image') or {}).get('imageDigest')
    decided = result['receipts'].get('decision') or {}
    result['devDecision'] = decided.get('decision')
    return result


def main(argv=None, env=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['infra', 'verify', 'decide', 'cutover', 'rollback', 'record'])
    parser.add_argument('--stage', default='dev', choices=STAGES)
    parser.add_argument('--output', help='receipt to write (must not exist)')
    parser.add_argument('--result-dir', help='verify: Infra application-candidate-result directory')
    parser.add_argument('--registry-image', help='verify / record: registry-image.json of this run')
    parser.add_argument('--url', help='verify: optional https URL whose 200 body names the release tag')
    parser.add_argument('--verification', help='decide / record: candidate-verify receipt')
    parser.add_argument('--approval', help='record: owner-approval.json')
    parser.add_argument('--decision', help='record: candidate-decision receipt')
    parser.add_argument('--infra', help='record: infra-tooling receipt')
    args = parser.parse_args(argv)
    if args.mode in HELD:
        raise SystemExit(HELD[args.mode])
    require(bool(args.output), '--output path required')
    if args.mode == 'infra':
        result = infraTooling(env)
        output(env, infra_ref=result['releaseVersion'], infra_sha=result['sourceSha'])
    elif args.mode == 'verify':
        require(bool(args.result_dir and args.registry_image), '--result-dir and --registry-image required')
        result = verify(env, args.result_dir, args.registry_image, args.url or None)
    elif args.mode == 'decide':
        require(bool(args.verification), '--verification required')
        result = decide(args.stage, env, readJson(args.verification))
        output(env, decision=result['decision'])
    else:
        result = record(env, {'registryImage': args.registry_image, 'approval': args.approval, 'verification': args.verification,
                              'decision': args.decision, 'infraTooling': args.infra})
    write(args.output, result)
    print(json.dumps({k: v for k, v in result.items() if k not in ('plan', 'receipts')}, sort_keys=True))
    return result


if __name__ == '__main__':
    main()
