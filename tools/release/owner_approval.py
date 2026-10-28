"""Bounded owner approval for one tested image; portable copy in Domain tools/release.

This is workflow-code enforcement, not an independent reviewer. Never calls AWS.
"""
import release_version as versions
import issue_template as issueTemplate
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import re
import subprocess
import time

OWNER = 'lookahead99ms'
ACCOUNT = '509614632283'
REGION = 'us-east-2'
PUBLISHERS = {
    'lookahead99ms/lookahead-learning-infra': ('.github/workflows/database-job-images.yml', 'initializer'),
    'lookahead99ms/lookahead-learning-domain-api': ('.github/workflows/release-image.yml', 'domain'),
}


def require(value, message):
    if not value:
        raise ValueError(message)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON field')
        result[key] = value
    return result


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def api(repository, path, payload=None):
    args = ['gh', 'api', 'repos/' + repository + ('/' + path if path else '')]
    if payload is not None:
        args += ['--method', 'POST', '--input', '-']
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, 'GitHub approval request failed; no publication authorized')
    require(len(result.stdout) <= 2 * 1024 * 1024, 'GitHub response exceeds bound')
    return json.loads(result.stdout, object_pairs_hook=unique)


def provenance(env, get=api):
    repository = env.get('GITHUB_REPOSITORY')
    require(repository in PUBLISHERS, 'Unknown image publisher')
    workflow, app = PUBLISHERS[repository]
    expected = {'GITHUB_ACTOR': OWNER, 'GITHUB_TRIGGERING_ACTOR': OWNER,
                'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_WORKFLOW_REF': repository + '/' + workflow + '@refs/heads/main'}
    require(all(env.get(k) == v for k, v in expected.items()), 'Fresh owner/manual/main run required')
    sha, runId = env.get('GITHUB_SHA', ''), env.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[1-9][0-9]*', runId), 'Exact source/run required')
    metadata = get(repository, '')
    require(metadata.get('full_name') == repository and metadata.get('owner', {}).get('login') == OWNER
            and str(metadata.get('id')) == env.get('GITHUB_REPOSITORY_ID')
            and str(metadata.get('owner', {}).get('id')) == env.get('GITHUB_REPOSITORY_OWNER_ID'), 'Repository identity changed')
    require(repository != 'lookahead99ms/lookahead-learning-infra' or metadata.get('private') is True,
            'Infra approval evidence must stay private')
    require(get(repository, 'commits/main').get('sha') == sha, 'Main moved; review a new run')
    run = get(repository, 'actions/runs/' + runId)
    fields = {'id': int(runId), 'path': workflow, 'head_sha': sha, 'head_branch': 'main',
              'event': 'workflow_dispatch', 'run_attempt': 1, 'status': 'in_progress'}
    require(all(run.get(k) == v for k, v in fields.items())
            and all(run.get(k, {}).get('full_name') == repository for k in ('repository', 'head_repository'))
            and all(run.get(k, {}).get('login') == OWNER for k in ('actor', 'triggering_actor')), 'Run provenance changed')
    environment = get(repository, 'environments/dev')
    require(environment.get('deployment_branch_policy') == {'protected_branches': False, 'custom_branch_policies': True},
            'Explicit main-only DEV environment required')
    branches = get(repository, 'environments/dev/deployment-branch-policies?per_page=100')
    rows = branches.get('branch_policies', [])
    require(branches.get('total_count') == len(rows) == 1 and rows[0].get('name') == 'main'
            and rows[0].get('type') == 'branch', 'DEV must allow exactly the main branch, no tags')
    # The workflow and its approval code run from main (workflowSha). The image is built from the
    # commit the release tag resolves to (sourceSha), frozen once per run and re-checked here.
    record = releaseRecord(env)
    require(record.get('workflowSha') == sha and record.get('repository') == repository and str(record.get('runId')) == runId,
            'Release-source record belongs to another workflow run')
    release = versions.recheck(record, lambda path: get(repository, path), sha, app)
    require(env.get('RELEASE_VERSION') == release['releaseVersion'], 'Dispatched release tag differs from resolved record')
    return {**{k: release[k] for k in versions.FIELDS}, 'repository': repository, 'sourceSha': release['sourceSha'],
            'workflowSha': sha, 'runId': runId, 'account': ACCOUNT, 'region': REGION,
            'target': versions.REPOSITORY + ':' + release['imageTag']}


def releaseRecord(env):
    """Frozen tag resolution written by `release_version.py resolve` at run start; never an operator SHA."""
    path = env.get('RELEASE_SOURCE_RECORD', '')
    require(bool(path) and os.path.isfile(path) and os.path.getsize(path) <= 64 * 1024, 'Frozen release-source record required')
    with open(path) as stream:
        record = json.load(stream, object_pairs_hook=unique)
    require(record.get('schema') == 'release-source/v1' and re.fullmatch('[a-f0-9]{40}', str(record.get('sourceSha', ''))),
            'Frozen release-source record required')
    return record


def validatePlan(plan, env, get=api):
    identity = provenance(env, get)
    require(plan.get('schema') == 'image-owner-approval/v1' and all(plan.get(k) == v for k, v in identity.items()),
            'Approval plan identity changed')
    require(re.fullmatch('arn:aws:iam::' + ACCOUNT + r':role/[A-Za-z0-9+=,.@_/-]+', plan.get('role', '')),
            'Scoped publisher role required')
    require(re.fullmatch('sha256:[a-f0-9]{64}', plan.get('imageId', '')), 'Tested configuration digest required')
    files = plan.get('files', {})
    require(isinstance(files, dict) and 1 <= len(files) <= 12
            and all(re.fullmatch(r'[a-z0-9][a-z0-9.-]*', name) and re.fullmatch('[a-f0-9]{64}', value)
                    for name, value in files.items()), 'Exact bounded image/proof hashes required')


def sourceChanges(repository, sha, get=api):
    rows = get(repository, 'commits/' + sha + '/pulls?per_page=100')
    require(isinstance(rows, list) and len(rows) < 100, 'Incomplete source PR inventory')
    return {'meaning': 'PRs associated with this exact source; not a diff from the last deployment',
            'pullRequests': [{'number': row['number'], 'title': row['title'],
                              'url': 'https://github.com/' + repository + '/pull/' + str(row['number'])}
                             for row in rows if row.get('merged_at') and row.get('base', {}).get('ref') == 'main']}


def headline(plan):
    """Title and alert: which image goes in and what source change it carries."""
    rows = (plan.get('sourceChanges') or {}).get('pullRequests') or []
    newest = max(rows, key=lambda row: int(row.get('number', 0))) if rows else None
    return {'env': 'DEV', 'action': 'Publish image', 'subject': plan['imageTag'],
            'change': newest['title'] if newest and newest.get('title') else 'no merged PR associated with this source'}


def commitLink(plan, sha):
    return '[' + sha[:12] + '](https://github.com/' + plan['repository'] + '/commit/' + sha + ')'


def pullRequestTable(plan):
    """Merged PRs associated with the exact source commit; absence is stated, never implied."""
    changes = plan.get('sourceChanges')
    if changes is None:
        return 'Merged PR context was not captured for this run; review the source commit directly.'
    rows = changes.get('pullRequests') or []
    if not rows:
        return 'No merged pull request is associated with this source commit.'
    return issueTemplate.table(('PR', 'Title', 'Merged'),
                               [('[#' + str(r['number']) + '](' + r['url'] + ')', issueTemplate.text(r['title']), 'into main') for r in rows])


def gist(plan):
    """Plain-language description, read before any table: what this run does, in order, and why."""
    t = issueTemplate.text
    app = plan.get('app') or PUBLISHERS.get(plan.get('repository'), ('', 'application'))[1]
    files = ', '.join('`' + t(name) + '`' for name in sorted(plan.get('files') or {}))
    lines = ['**Business change:** the tested ' + t(app) + ' image `' + t(plan['imageTag']) + '` (release `' + t(plan['releaseVersion'])
             + '`) becomes available in the DEV registry for a later deployment; nothing is deployed and no running service changes.',
             '',
             '1. Recheck that release tag `' + t(plan['releaseVersion']) + '` still resolves to source commit `' + t(plan['sourceSha'][:12])
             + '` and that this approval is unchanged, before any AWS credentials are issued.',
             '2. Assume the scoped publisher role `' + t(plan['role']) + '` for one upload session of at most 15 minutes.',
             '3. Push the already scanned image (configuration digest `' + t(plan['imageId']) + '`) once to `' + t(plan['target'])
             + '`; the tag is immutable and is never overwritten.',
             '4. Read the published digest back from ECR and retain the publication receipt and proof files (' + (files or 'none listed') + ') as run artifacts.',
             '',
             'No service, task or SQL job is started; no IAM role, repository or stack is created or changed; existing image tags are never '
             'deleted or rebuilt; approval expires 40 minutes after this issue is created.']
    return '\n'.join(lines)


def body(plan, receipt):
    fields = ('target', 'releaseVersion', 'imageTag', 'sourceSha', 'workflowSha', 'imageId', 'role', 'repository', 'account', 'region')
    shown = [('**' + key + '**', commitLink(plan, plan[key]) if key in ('sourceSha', 'workflowSha') else '`' + issueTemplate.text(plan[key]) + '`')
             for key in fields if key in plan]
    return issueTemplate.render(
        headline=headline(plan), gist=gist(plan), resources=[],
        overview='**Publish the verified DEV image**\n\nAuthorize one upload of the tested image shown below. '
            'Publishing enables later selection; it does not deploy services, run SQL, install IAM or create repositories. '
            'Without approval, no upload occurs.',
        scope='### Image\n\n' + issueTemplate.table(('Field', 'Value'), shown) + '\n### Merged pull requests for this source\n\n' + pullRequestTable(plan),
        validation=[('Release tag ' + issueTemplate.text(plan['releaseVersion']), 'resolved to source commit ' + commitLink(plan, plan['sourceSha'])),
                    ('Workflow commit (main)', commitLink(plan, plan['workflowSha'])),
                    ('Image configuration digest', '`' + issueTemplate.text(plan['imageId']) + '`'),
                    ('Source selection', 'commit derived from the tag, not typed')],
        evidence='Review the same-run CI/verification logs and saved image-review artifacts before approving.',
        risks='An upload timeout may leave an image in ECR. Preserve intent and reconcile the exact tag/configuration; '
            'do not automatically retry, overwrite, delete or rebuild. This is owner approval enforced by workflow code, not independent review.',
        repository=plan['repository'], runId=plan['runId'], owner=OWNER, expires=receipt['expiresAt'],
        created=receipt.get('createdAt'),
        permissionDeadline='This approval does not extend IAM permissions; the write session is at most 15 minutes.',
        command='APPROVE IMAGE ' + digest(plan), payload=plan)


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'Timezone-aware approval timestamp required')
    return result


def decision(plan, receipt, env, get=api, now=None):
    validatePlan(plan, env, get)
    now = now or datetime.now(timezone.utc)
    created, expires = timestamp(receipt['createdAt']), timestamp(receipt['expiresAt'])
    require(receipt.get('schema') == 'image-owner-decision/v1' and receipt.get('planDigest') == digest(plan)
            and receipt.get('runId') == plan['runId'] and created <= now < expires
            and timedelta(0) < expires - created <= timedelta(minutes=40), 'Approval expired or binding changed')
    issue = get(plan['repository'], 'issues/' + str(receipt['issue']))
    require(issue.get('state') == 'open' and not issue.get('pull_request')
            and issue.get('user', {}).get('login') == 'github-actions[bot]'
            and issue.get('body') == body(plan, receipt), 'Approval issue changed')
    require(created <= timestamp(issue['created_at']) < expires,
            'Approval issue creation time outside review window')
    comments = get(plan['repository'], 'issues/' + str(receipt['issue']) + '/comments?per_page=100')
    require(isinstance(comments, list) and len(comments) < 100, 'Incomplete approval comment inventory')
    selected = None
    for comment in comments:
        if comment.get('user', {}).get('login') != OWNER:
            continue
        command = comment.get('body', '').strip()
        # Even an edited owner rejection blocks this attempt; approval never overrides REJECT.
        require(command != 'REJECT', 'Owner rejected publication')
        if comment.get('created_at') != comment.get('updated_at'):
            continue
        when = timestamp(comment['created_at'])
        if created <= when <= now and when < expires and command == 'APPROVE IMAGE ' + digest(plan):
            selected = comment['id']
    return selected


def approve(plan, env, get=api, now=lambda: datetime.now(timezone.utc), sleep=time.sleep):
    validatePlan(plan, env, get)
    # GitHub issue/comment timestamps have whole-second precision. Align the
    # receipt before rendering so same-second creation is valid without extending expiry.
    start = now().replace(microsecond=0)
    receipt = {'schema': 'image-owner-decision/v1', 'runId': plan['runId'], 'planDigest': digest(plan),
               'createdAt': start.isoformat(), 'expiresAt': (start + timedelta(minutes=40)).isoformat()}
    preview = body(plan, receipt)
    require(len(preview) < 60000, 'Approval preview too large')
    issue = get(plan['repository'], 'issues', {'title': issueTemplate.title(headline(plan), plan['runId']), 'body': preview})
    receipt['issue'] = issue['number']
    # A nonsecret review URL is useful while the job waits; no approval is posted by this tool.
    print('https://github.com/' + plan['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(120):
        selected = decision(plan, receipt, env, get, now())
        if selected is not None:
            return {**receipt, 'commentId': selected}
        sleep(20)
    raise TimeoutError('Image approval timed out; start a new reviewed run')


def verify(plan, receipt, env, get=api, now=None):
    selected = decision(plan, receipt, env, get, now)
    require(selected is not None and selected == receipt.get('commentId'), 'Exact live owner approval required')
