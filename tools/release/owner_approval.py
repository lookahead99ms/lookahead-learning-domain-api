"""Bounded owner approval for one tested image; portable copy in Domain tools/release.

This is workflow-code enforcement, not an independent reviewer. Never calls AWS.
"""
import release_version as versions
import issue_template as issueTemplate
from datetime import datetime, timedelta, timezone
import hashlib
import json
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
    release = versions.gitTag(env.get('RELEASE_VERSION'), sha, app, lambda path: get(repository, path))
    return {**release, 'repository': repository, 'sourceSha': sha, 'runId': runId, 'account': ACCOUNT, 'region': REGION,
            'target': versions.REPOSITORY + ':' + release['imageTag']}


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


def body(plan, receipt):
    return issueTemplate.render(
        overview='**Publish the verified DEV image**\n\nAuthorize one upload of the tested image shown below. '
            'Publishing enables later selection; it does not deploy services, run SQL, install IAM or create repositories. '
            'Without approval, no upload occurs.',
        scope=issueTemplate.records([{key: value for key, value in plan.items() if key in
            ('target', 'releaseVersion', 'imageTag', 'account', 'region', 'imageId', 'role', 'repository', 'sourceSha')}]),
        evidence='Source: https://github.com/' + plan['repository'] + '/commit/' + plan['sourceSha']
            + '\n\nReview the same-run CI/verification logs and saved image-review artifacts before approving.',
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
    issue = get(plan['repository'], 'issues', {'title': 'Approve DEV image publication · run ' + plan['runId'], 'body': preview})
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
