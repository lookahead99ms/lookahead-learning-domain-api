"""Per-application DEV deploy (DLV-968, decision V of 2026-11-01): one ECS service per application, ECS rolling updates.
Identical copy in the Web, Domain and Gateway/BFF repositories; the application differs only through
`deployment/dev/deploy-config.json` and `deployment/dev/service.template.json`.

One approved run = one CloudFormation change set `app-<run>` on the stack this repository owns: the task definition gets the
published image and the service gets `DesiredCount` (1 serves, 0 parks the service). ECS performs the rolling update
(surge to two tasks, the old task drained once the new one is healthy) and its deployment circuit breaker rolls a failed
revision back by itself; manual rollback is a new run with the previous tag. The run then verifies the running task, the
log window, service discovery or target health, and watches the public probe for the observation window. AWS is reached
only through the `aws` CLI with the credentials the workflow assumed; the plan and the receipt bind every identifier, and
the approval comment names the image tag.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import issue_template as issueTemplate
import owner_approval as owner
import release_version as versions

WORKFLOW = '.github/workflows/deploy-dev.yml'
# `01 · Full` (release.yml) and `03 · Publish` (publish.yml) call deploy-dev.yml after the image is published; a reusable-workflow
# call keeps the caller's workflow ref, run path and event (push or dispatch).
DEPLOY_WORKFLOWS = (WORKFLOW, '.github/workflows/release.yml', '.github/workflows/publish.yml')
SCHEMA_CONFIG = 'lookahead-app-deploy-config/v2'
SCHEMA_PLAN = 'app-deploy-plan/v2'
SCHEMA_RECEIPT = 'app-deploy-approval/v1'
SCHEMA_RESULT = 'app-deploy-result/v2'
TERMINAL_OK = {'CREATE_COMPLETE', 'UPDATE_COMPLETE'}
TERMINAL_BAD = {'CREATE_FAILED', 'ROLLBACK_COMPLETE', 'ROLLBACK_FAILED', 'UPDATE_ROLLBACK_COMPLETE', 'UPDATE_ROLLBACK_FAILED',
                'UPDATE_FAILED', 'DELETE_FAILED', 'IMPORT_ROLLBACK_COMPLETE'}
# A stack that can take the next change set. ROLLBACK_COMPLETE (a failed first CREATE) is excluded on purpose: CloudFormation
# only accepts deletion there, and this tool never deletes (see README). REVIEW_IN_PROGRESS is an aborted CREATE change set.
UPDATABLE = TERMINAL_OK | {'UPDATE_ROLLBACK_COMPLETE', 'IMPORT_COMPLETE'}
REVIEW = 'REVIEW_IN_PROGRESS'
NO_CHANGES = "didn't contain changes"
CONFIG_KEYS = {'schema', 'app', 'service', 'account', 'region', 'imageRepository', 'deployRoleArn', 'cloudFormationRoleArn',
               'sessionPrefix', 'cluster', 'clusterName', 'stack', 'logGroup', 'namespaceName', 'discovery', 'targetGroupArn',
               'observationMinutes', 'probeUrl', 'parameters', 'logFailurePatterns'}
require = owner.require
digest = owner.digest


def utc(now=None):
    return (now or datetime.now(timezone.utc)).astimezone(timezone.utc)


def readJson(path, limit=256 * 1024):
    path = Path(path)
    require(path.is_file() and path.stat().st_size <= limit, 'Bounded JSON file required: ' + path.name)
    with open(path) as stream:
        return json.load(stream, object_pairs_hook=owner.unique)


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'x') as stream:  # write-once: a receipt is never overwritten
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n')


def aws(args, run=None):
    """One bounded AWS CLI call; JSON out; stderr is not echoed (it can quote request bodies)."""
    result = (run or subprocess.run)(['aws', *args, '--region', 'us-east-2', '--output', 'json', '--no-cli-pager',
                                      '--cli-connect-timeout', '10', '--cli-read-timeout', '25'], capture_output=True, text=True, timeout=40)
    if result.returncode != 0:
        raise RuntimeError('aws ' + ' '.join(args[:2]) + ' failed: ' + (result.stderr or '').strip()[:300])
    return json.loads(result.stdout or 'null', object_pairs_hook=owner.unique) if result.stdout.strip() else None


def client(run=None):
    return lambda *args: aws(list(args), run)


def tolerant(call, *args, absent):
    """Describe calls that mean 'not there yet' return None instead of failing."""
    try:
        return call(*args)
    except RuntimeError as error:
        if any(marker in str(error) for marker in absent):
            return None
        raise


def loadConfig(root):
    config = readJson(Path(root) / 'deployment/dev/deploy-config.json')
    require(config.get('schema') == SCHEMA_CONFIG and set(config) == CONFIG_KEYS, 'Exact deploy configuration required')
    require(re.fullmatch('[0-9]{12}', config['account']) and config['region'] == 'us-east-2', 'DEV account and region required')
    require(re.fullmatch(r'lookahead-dev-[a-z]+', config['stack']), 'Single-service stack name required')
    require(isinstance(config['observationMinutes'], int) and 1 <= config['observationMinutes'] <= 60, 'Observation window 1..60 minutes')
    discovery, group = config['discovery'], config['targetGroupArn']
    require((discovery is None) != (group is None), 'Exactly one of discovery (Web, Domain) or targetGroupArn (Gateway) is required')
    if discovery is not None:
        require({'serviceName', 'serviceId', 'serviceArn'} <= set(discovery), 'Discovery needs serviceName, serviceId and serviceArn')
    return config


def loadTemplate(root):
    path = Path(root) / 'deployment/dev/service.template.json'
    text = path.read_text()
    require(len(text) <= 51200, 'Template must fit the CloudFormation inline body limit')
    template = json.loads(text, object_pairs_hook=owner.unique)
    params = template.get('Parameters', {})
    require({'ApplicationImage', 'DesiredCount'} <= set(params) and 'Slot' not in params and set(template.get('Resources', {})) <= {'Task', 'Service'},
            'Single-service template with ApplicationImage and DesiredCount and only Task/Service resources required')
    return text, template


def provenance(env, get=owner.api):
    """A fresh owner run on main of `04 · Deploy DEV`, or of `01 · Full` / `03 · Publish` calling it after publication, bound to
    the frozen release-source record of this run."""
    repository = env.get('GITHUB_REPOSITORY')
    require(repository in owner.PUBLISHERS, 'Unknown application repository')
    app = owner.PUBLISHERS[repository][1]
    event = env.get('GITHUB_EVENT_NAME')
    expected = {'GITHUB_ACTOR': owner.OWNER, 'GITHUB_TRIGGERING_ACTOR': owner.OWNER, 'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '1'}
    workflow = next((w for w in DEPLOY_WORKFLOWS if env.get('GITHUB_WORKFLOW_REF') == repository + '/' + w + '@refs/heads/main'), None)
    require(all(env.get(k) == v for k, v in expected.items()) and event in versions.EVENTS and workflow is not None,
            'Fresh owner run of the deploy workflow (or of 01 · Full / 03 · Publish calling it) from main required')
    sha, runId = env.get('GITHUB_SHA', ''), env.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[1-9][0-9]*', runId), 'Exact source/run required')
    metadata = get(repository, '')
    require(metadata.get('full_name') == repository and metadata.get('owner', {}).get('login') == owner.OWNER
            and str(metadata.get('id')) == env.get('GITHUB_REPOSITORY_ID'), 'Repository identity changed')
    versions.mainBinding(event, sha, get(repository, 'commits/main').get('sha', ''), lambda path: get(repository, path))
    run = get(repository, 'actions/runs/' + runId)
    fields = {'id': int(runId), 'path': workflow, 'head_sha': sha, 'head_branch': 'main', 'event': event, 'run_attempt': 1, 'status': 'in_progress'}
    require(all(run.get(k) == v for k, v in fields.items())
            and all(run.get(k, {}).get('login') == owner.OWNER for k in ('actor', 'triggering_actor')), 'Run provenance changed')
    record = owner.releaseRecord(env)
    require(record.get('workflowSha') == sha and record.get('repository') == repository and str(record.get('runId')) == runId,
            'Release-source record belongs to another workflow run')
    release = versions.recheck(record, lambda path: get(repository, path), sha, app)
    require(env.get('RELEASE_VERSION') == release['releaseVersion'], 'Dispatched release tag differs from resolved record')
    return {**{k: release[k] for k in versions.FIELDS}, 'repository': repository, 'sourceSha': release['sourceSha'], 'workflowSha': sha, 'runId': runId}


def desiredCount(env):
    value = str(env.get('DEPLOY_DESIRED_COUNT', '1')).strip() or '1'
    require(value in ('0', '1'), 'DEPLOY_DESIRED_COUNT must be 1 (serve) or 0 (park)')
    return int(value)


def publishedImage(call, config, imageTag):
    """The immutable ECR tag written by `01 · Full` is the only way a digest enters a deploy."""
    rows = call('ecr', 'batch-get-image', '--repository-name', config['imageRepository'].split('/', 1)[1], '--image-ids', 'imageTag=' + imageTag)
    images = rows.get('images') or []
    require(len(images) == 1 and not rows.get('failures'), 'Published image ' + imageTag + ' not found in ECR; run 01 · Full first')
    found = images[0]['imageId'].get('imageDigest', '')
    require(re.fullmatch('sha256:[a-f0-9]{64}', found), 'Image digest required')
    return config['imageRepository'] + '@' + found


def stackState(call, name):
    found = tolerant(call, 'cloudformation', 'describe-stacks', '--stack-name', name, absent=('does not exist',))
    if not found or not found.get('Stacks'):
        return None
    stack = found['Stacks'][0]
    return {'status': stack.get('StackStatus'), 'stackId': stack.get('StackId'),
            'parameters': {row['ParameterKey']: row.get('ParameterValue') for row in stack.get('Parameters', [])}}


def parameters(config, image, count):
    rendered = dict(config['parameters'])
    rendered.update({'ApplicationImage': image, 'DesiredCount': str(count)})
    if config['discovery'] is not None:
        rendered['DiscoveryServiceArn'] = config['discovery']['serviceArn']
    if config['targetGroupArn'] is not None:
        rendered['TargetGroupArn'] = config['targetGroupArn']
    for key, value in rendered.items():
        require(not str(value).startswith('PENDING-'), 'Deploy configuration still carries a placeholder for ' + key + ' (foundation step pending)')
    return rendered


def serviceCounts(call, config):
    rows = call('ecs', 'describe-services', '--cluster', config['cluster'], '--services', config['stack'])
    service = next((s for s in rows.get('services', []) if s.get('status') == 'ACTIVE'), None)
    if service is None:
        return None
    primary = next((d for d in service.get('deployments', []) if d.get('status') == 'PRIMARY'), {})
    return {'desired': service.get('desiredCount', 0), 'running': service.get('runningCount', 0),
            'rolloutState': primary.get('rolloutState'), 'taskDefinition': primary.get('taskDefinition')}


def resourceRows(config, state, count, current):
    first = state is None
    rows = [{'action': 'Create' if first else 'Modify', 'type': 'AWS::CloudFormation::Stack', 'consoleName': config['stack'],
             'note': 'change set app-<run>: ' + ('first creation of the stack' if first else 'new task definition revision and DesiredCount ' + str(count))},
            {'action': 'Create' if first else 'Modify', 'type': 'AWS::ECS::Service', 'consoleName': config['stack'],
             'note': ('one task serves after an ECS rolling update (surge to two, old task drained once the new one is healthy)' if count == 1
                      else 'service parked at zero tasks; the task definition and stack stay')}]
    if current and current.get('ApplicationImage') and current.get('DesiredCount') == '1':
        rows[1]['note'] += '; currently serving ' + current['ApplicationImage'].rsplit('@', 1)[-1][:19] + '…'
    return rows


def plan(env, call, root, get=owner.api, now=None):
    release = provenance(env, get)
    config, (templateText, _) = loadConfig(root), loadTemplate(root)
    require(release['app'] == config['app'], 'Configuration belongs to another application')
    count = desiredCount(env)
    image = publishedImage(call, config, release['imageTag'])
    state = stackState(call, config['stack'])
    require(state is None or state['status'] in UPDATABLE or state['status'] == REVIEW,
            'Stack ' + config['stack'] + ' is not updatable: ' + str(state and state['status']) + ' (manual recovery, see README)')
    exists = state is not None and state['status'] != REVIEW
    current = state['parameters'] if exists else {}
    rendered = parameters(config, image, count)
    unchanged = exists and all(current.get(k) == v for k, v in rendered.items())
    result = {'schema': SCHEMA_PLAN, **release, 'image': image, 'desiredCount': count, 'account': config['account'], 'region': config['region'],
              'deployRoleArn': config['deployRoleArn'], 'cloudFormationRoleArn': config['cloudFormationRoleArn'],
              'sessionName': config['sessionPrefix'] + '-' + release['runId'] + '-1', 'stack': config['stack'], 'stackExists': exists,
              'unchanged': unchanged, 'changeSet': 'app-' + release['runId'], 'parameters': rendered,
              'currentImage': current.get('ApplicationImage'), 'currentDesiredCount': current.get('DesiredCount'),
              'observationMinutes': config['observationMinutes'], 'probeUrl': config['probeUrl'],
              'templateSha256': hashlib.sha256(templateText.encode()).hexdigest(),
              'configSha256': hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest(),
              'resources': resourceRows(config, state if exists else None, count, current), 'plannedAt': utc(now).isoformat()}
    result['planDigest'] = digest({k: v for k, v in result.items() if k != 'planDigest'})
    return result


def validatePlan(planned, env, root, get=owner.api):
    identity = provenance(env, get)
    require(planned.get('schema') == SCHEMA_PLAN and all(planned.get(k) == v for k, v in identity.items()), 'Deploy plan identity changed')
    require(planned.get('planDigest') == digest({k: v for k, v in planned.items() if k != 'planDigest'}), 'Deploy plan digest changed')
    templateText, _ = loadTemplate(root)
    require(planned.get('templateSha256') == hashlib.sha256(templateText.encode()).hexdigest(), 'Template changed since the plan')
    require(planned.get('configSha256') == hashlib.sha256(json.dumps(loadConfig(root), sort_keys=True).encode()).hexdigest(), 'Configuration changed since the plan')
    require(re.fullmatch('sha256:[a-f0-9]{64}', planned.get('image', '').rsplit('@', 1)[-1]), 'Image digest required')


def approvalCommand(planned):
    # The comment names the image version as ECR shows it (user decision 2026-10-28); the plan digest binds the exact plan
    # through the issue body and the receipt.
    return 'APPROVE DEPLOY ' + planned['imageTag']


def headline(planned):
    action = 'Deploy ' + planned['app'] if planned['desiredCount'] == 1 else 'Park ' + planned['app']
    change = ('rolling update of ' + planned['stack'] if planned['stackExists'] else 'first start of ' + planned['stack']) if planned['desiredCount'] == 1 \
        else planned['stack'] + ' to zero tasks'
    return {'env': 'DEV', 'action': action, 'subject': planned['imageTag'], 'change': change}


def gist(planned):
    t = issueTemplate.text
    serving = ('1. Recheck the tag, the plan digest, the template and the configuration; then assume `' + t(planned['deployRoleArn']) + '` as session `'
               + t(planned['sessionName']) + '` (at most 60 minutes).',
               '2. Change set `' + t(planned['changeSet']) + '` on stack `' + t(planned['stack']) + '`: task definition with image `'
               + t(planned['image'].rsplit('@', 1)[-1][:19]) + '…`, DesiredCount ' + str(planned['desiredCount']) + '; the preview must match this plan before it is executed.',
               '3. ECS performs the rolling update: the new task starts beside the old one and the old one is drained once the new one is healthy; '
               'if the new revision never becomes healthy the deployment circuit breaker rolls the service back to the previous revision and this run fails.',
               '4. Verify: task HEALTHY, log window clean, ' + ('service discovery lists the task' if planned['parameters'].get('DiscoveryServiceArn') else 'target group healthy')
               + ', then watch the public probe for ' + str(planned['observationMinutes']) + ' minutes.')
    parked = ('1. Recheck the tag, the plan digest, the template and the configuration; then assume `' + t(planned['deployRoleArn']) + '` as session `' + t(planned['sessionName']) + '`.',
              '2. Change set `' + t(planned['changeSet']) + '` on stack `' + t(planned['stack']) + '`: DesiredCount 0. The stack, service and task definition stay; a later run with DesiredCount 1 starts it again.')
    head = ('**Business change:** the published ' + t(planned['app']) + ' image `' + t(planned['imageTag']) + '` serves DEV after an ECS rolling update of `' + t(planned['stack']) + '`.'
            if planned['desiredCount'] == 1 else '**Business change:** the DEV ' + t(planned['app']) + ' service `' + t(planned['stack']) + '` is parked at zero tasks (scheduled scale-down); nothing is deleted.')
    return head + ' No IAM, network, database or secret changes.\n\n' + '\n'.join(serving if planned['desiredCount'] == 1 else parked) \
        + '\n\nRollback is a new run of this workflow with the previous tag. Approval expires 40 minutes after this issue is created.'


def body(planned, receipt):
    fields = ('releaseVersion', 'imageTag', 'image', 'desiredCount', 'sourceSha', 'workflowSha', 'stack', 'currentImage', 'currentDesiredCount', 'sessionName', 'repository', 'account', 'region')
    shown = [('**' + key + '**', owner.commitLink(planned, planned[key]) if key in ('sourceSha', 'workflowSha') else '`' + issueTemplate.text(str(planned[key])) + '`')
             for key in fields if planned.get(key) is not None]
    return issueTemplate.render(
        headline=headline(planned), gist=gist(planned), resources=planned['resources'],
        overview='**Deploy the published DEV image**\n\nAuthorize the one change set described above inside this run. Without approval nothing changes in AWS.',
        scope='### Deploy\n\n' + issueTemplate.table(('Field', 'Value'), shown),
        validation=[('Release tag ' + issueTemplate.text(planned['releaseVersion']), 'resolved to source commit ' + owner.commitLink(planned, planned['sourceSha'])),
                    ('Image', 'ECR tag `' + issueTemplate.text(planned['imageTag']) + '` read back as `' + issueTemplate.text(planned['image'].rsplit('@', 1)[-1]) + '`'),
                    ('Template and configuration', '`' + planned['templateSha256'][:16] + '…` / `' + planned['configSha256'][:16] + '…` rechecked before execution'),
                    ('Change', 'no-op (stack already at this image and count)' if planned['unchanged'] else 'rolling update' if planned['stackExists'] else 'first creation')],
        evidence='Review the plan artifact of this run before approving.',
        risks='A new revision that never becomes healthy is rolled back by the ECS circuit breaker and this run fails; the previous revision keeps serving. '
            'Old and new tasks overlap for the discovery TTL and the load-balancer drain. This is owner approval enforced by workflow code, not independent review.',
        repository=planned['repository'], runId=planned['runId'], owner=owner.OWNER, expires=receipt['expiresAt'], created=receipt.get('createdAt'),
        permissionDeadline='The deploy session lasts at most 60 minutes; IAM is not changed.',
        command=approvalCommand(planned), payload=planned)


def decision(planned, receipt, env, root, get=owner.api, now=None):
    validatePlan(planned, env, root, get)
    now = now or utc()
    created, expires = owner.timestamp(receipt['createdAt']), owner.timestamp(receipt['expiresAt'])
    require(receipt.get('schema') == SCHEMA_RECEIPT and receipt.get('planDigest') == planned['planDigest'] and receipt.get('runId') == planned['runId']
            and created <= now < expires and timedelta(0) < expires - created <= timedelta(minutes=40), 'Approval expired or binding changed')
    issue = get(planned['repository'], 'issues/' + str(receipt['issue']))
    require(issue.get('state') == 'open' and not issue.get('pull_request') and issue.get('user', {}).get('login') == 'github-actions[bot]'
            and issue.get('body') == body(planned, receipt), 'Approval issue changed')
    require(created <= owner.timestamp(issue['created_at']) < expires, 'Approval issue creation time outside review window')
    comments = get(planned['repository'], 'issues/' + str(receipt['issue']) + '/comments?per_page=100')
    require(isinstance(comments, list) and len(comments) < 100, 'Incomplete approval comment inventory')
    selected = None
    for comment in comments:
        if comment.get('user', {}).get('login') != owner.OWNER:
            continue
        command = comment.get('body', '').strip()
        require(command != 'REJECT', 'Owner rejected the deployment')
        if comment.get('created_at') != comment.get('updated_at'):
            continue
        when = owner.timestamp(comment['created_at'])
        if created <= when <= now and when < expires and command == approvalCommand(planned):
            selected = comment['id']
    return selected


def approve(planned, env, root, get=owner.api, now=lambda: utc(), sleep=time.sleep):
    validatePlan(planned, env, root, get)
    start = now().replace(microsecond=0)
    receipt = {'schema': SCHEMA_RECEIPT, 'runId': planned['runId'], 'planDigest': planned['planDigest'],
               'createdAt': start.isoformat(), 'expiresAt': (start + timedelta(minutes=40)).isoformat()}
    preview = body(planned, receipt)
    require(len(preview) < 60000, 'Approval preview too large')
    issue = get(planned['repository'], 'issues', {'title': issueTemplate.title(headline(planned), planned['runId']), 'body': preview})
    receipt['issue'] = issue['number']
    print('https://github.com/' + planned['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(120):
        selected = decision(planned, receipt, env, root, get, now())
        if selected is not None:
            return {**receipt, 'commentId': selected}
        sleep(20)
    raise TimeoutError('Deploy approval timed out; start a new reviewed run')


def verify(planned, receipt, env, root, get=owner.api, now=None):
    selected = decision(planned, receipt, env, root, get, now)
    require(selected is not None and selected == receipt.get('commentId'), 'Exact live owner approval required')


class Deployer:
    """Executes one approved plan; every AWS step is recorded in `self.steps` and written whatever the outcome."""

    def __init__(self, planned, config, templateText, call, sleep=time.sleep, now=None, fetch=None):
        self.plan, self.config, self.template, self.call = planned, config, templateText, call
        self.sleep, self.now, self.fetch = sleep, now or (lambda: utc()), fetch or probe
        self.steps = []

    def note(self, step, **detail):
        self.steps.append({'step': step, 'at': self.now().isoformat(), **detail})

    def identity(self):
        caller = self.call('sts', 'get-caller-identity')
        expected = 'arn:aws:sts::' + self.plan['account'] + ':assumed-role/' + self.plan['deployRoleArn'].rsplit('/', 1)[-1] + '/' + self.plan['sessionName']
        require(caller.get('Arn') == expected and caller.get('Account') == self.plan['account'], 'Deploy session identity mismatch')
        self.note('identity', arn=expected)

    def waitFor(self, label, check, seconds, every=15):
        for _ in range(max(1, seconds // every)):
            state = check()
            if state is not None:
                return state
            self.sleep(every)
        raise ValueError(label + ' did not complete within ' + str(seconds) + ' seconds')

    def changeSet(self):
        p = self.plan
        rows = [{'ParameterKey': k, 'ParameterValue': str(v)} for k, v in p['parameters'].items()]
        args = ['cloudformation', 'create-change-set', '--stack-name', p['stack'], '--change-set-name', p['changeSet'],
                '--change-set-type', 'UPDATE' if p['stackExists'] else 'CREATE', '--role-arn', p['cloudFormationRoleArn'],
                '--template-body', self.template, '--tags', json.dumps([{'Key': 'LookAheadEnvironment', 'Value': 'dev'}]), '--parameters', json.dumps(rows)]
        created = self.call(*args)
        described = self.waitFor('change set ' + p['changeSet'], self.describeChangeSet, 120, 10)
        if described['Status'] == 'FAILED':
            require(NO_CHANGES in described.get('StatusReason', ''), 'Change set ' + p['changeSet'] + ' failed: ' + described.get('StatusReason', '')[:200])
            self.note('change-set-empty', stack=p['stack'], name=p['changeSet'])
            return None
        changes = [{'logical': c['ResourceChange'].get('LogicalResourceId'), 'action': c['ResourceChange'].get('Action'),
                    'type': c['ResourceChange'].get('ResourceType'), 'replacement': c['ResourceChange'].get('Replacement')}
                   for c in described.get('Changes', []) if c.get('Type') == 'Resource']
        # A task definition revision is a replacement by nature; the service is updated in place (the rolling update).
        require(changes and all(c['logical'] in ('Task', 'Service') and (c['logical'] == 'Task' or c['replacement'] != 'True') for c in changes),
                'Change set preview differs from the approved plan: ' + json.dumps(changes))
        self.note('change-set', stack=p['stack'], name=p['changeSet'], id=created.get('Id'), changes=changes)
        return changes

    def describeChangeSet(self):
        described = self.call('cloudformation', 'describe-change-set', '--stack-name', self.plan['stack'], '--change-set-name', self.plan['changeSet'])
        return described if described.get('Status') in ('CREATE_COMPLETE', 'FAILED') else None

    def execute(self):
        p = self.plan
        self.call('cloudformation', 'execute-change-set', '--stack-name', p['stack'], '--change-set-name', p['changeSet'])
        state = self.waitFor('stack ' + p['stack'], self.stackStatus, 1200)
        require(state in TERMINAL_OK, 'Stack ' + p['stack'] + ' ended in ' + str(state) + ' (ECS rolled the service back to the previous revision if the new one never became healthy)')
        self.note('executed', stack=p['stack'], name=p['changeSet'], status=state)

    def stackStatus(self):
        state = stackState(self.call, self.plan['stack'])
        status = state['status'] if state else None
        return status if status in TERMINAL_OK | TERMINAL_BAD else None

    def verifyServing(self):
        p, cfg = self.plan, self.config
        counts = self.waitFor('ECS rollout of ' + p['stack'],
                              lambda: (lambda c: c if c and c['rolloutState'] == 'COMPLETED' and c['running'] == 1 and c['desired'] == 1 else None)(serviceCounts(self.call, cfg)), 300)
        arns = self.call('ecs', 'list-tasks', '--cluster', cfg['cluster'], '--service-name', p['stack'], '--desired-status', 'RUNNING').get('taskArns', [])
        require(len(arns) == 1, 'Exactly one running task expected')
        task = self.waitFor('task health', lambda: (lambda t: t if t.get('healthStatus') == 'HEALTHY' else None)(
            self.call('ecs', 'describe-tasks', '--cluster', cfg['cluster'], '--tasks', arns[0])['tasks'][0]), 180)
        require(task.get('taskDefinitionArn', '').split('/')[-1].startswith(p['stack'] + ':'), 'Task belongs to another family')
        require(p['image'].rsplit('@', 1)[-1] in json.dumps(self.call('ecs', 'describe-task-definition', '--task-definition', task['taskDefinitionArn'])),
                'Running task definition does not carry the approved image')
        startedAt = owner.timestamp(task['startedAt']) if task.get('startedAt') else self.now() - timedelta(minutes=10)
        taskId = arns[0].rsplit('/', 1)[-1]
        pattern = ' '.join('?"' + word + '"' for word in cfg['logFailurePatterns'])
        events = self.call('logs', 'filter-log-events', '--log-group-name', cfg['logGroup'], '--log-stream-name-prefix', cfg['service'] + '/' + cfg['service'] + '/' + taskId,
                           '--start-time', str(int(startedAt.timestamp() * 1000)), '--filter-pattern', pattern).get('events', [])
        require(not events, 'Log window contains failure markers: ' + (events[0].get('message', '')[:200] if events else ''))
        registered = None
        if cfg['discovery'] is not None:
            registered = len(self.call('servicediscovery', 'list-instances', '--service-id', cfg['discovery']['serviceId']).get('Instances', []))
            require(registered >= 1, 'Service discovery lists no ' + cfg['discovery']['serviceName'] + ' instance')
        targets = None
        if cfg['targetGroupArn'] is not None:
            targets = self.waitFor('target health', lambda: (lambda rows: rows if rows and all(r['TargetHealth']['State'] == 'healthy' for r in rows) else None)(
                self.call('elbv2', 'describe-target-health', '--target-group-arn', cfg['targetGroupArn']).get('TargetHealthDescriptions', [])), 180)
        self.note('verified', task=arns[0], counts=counts, discovered=registered, targets=None if targets is None else len(targets))

    def verifyParked(self):
        counts = self.waitFor('ECS scale-in of ' + self.plan['stack'],
                              lambda: (lambda c: c if c and c['running'] == 0 and c['desired'] == 0 else None)(serviceCounts(self.call, self.config)), 300)
        self.note('parked', counts=counts)

    def observe(self):
        p, cfg = self.plan, self.config
        rounds = p['observationMinutes']
        for minute in range(rounds):
            counts = serviceCounts(self.call, cfg)
            require(counts and counts['running'] == 1 and counts['rolloutState'] == 'COMPLETED', 'Service stopped serving during observation')
            status = self.fetch(p['probeUrl'])
            require(status == 200, 'Public probe returned ' + str(status) + ' during observation')
            if minute < rounds - 1:
                self.sleep(60)
        self.note('observed', minutes=rounds)

    def run(self):
        outcome = {'schema': SCHEMA_RESULT, 'runId': self.plan['runId'], 'planDigest': self.plan['planDigest'], 'app': self.plan['app'],
                   'releaseVersion': self.plan['releaseVersion'], 'image': self.plan['image'], 'desiredCount': self.plan['desiredCount'], 'stack': self.plan['stack']}
        try:
            self.identity()
            if self.plan['unchanged']:
                self.note('unchanged', stack=self.plan['stack'])
            elif self.changeSet() is not None:
                self.execute()
            if self.plan['desiredCount'] == 1:
                self.verifyServing()
                self.observe()
            else:
                self.verifyParked()
            outcome['outcome'] = 'success'
        except Exception as error:
            outcome.update({'outcome': 'failure', 'error': str(error)[:500]})
            raise
        finally:
            outcome.update({'steps': self.steps, 'finishedAt': self.now().isoformat()})
            self.outcome = outcome
        return outcome


def probe(url, opener=urllib.request.urlopen):
    require(url.startswith('https://'), 'https probe URL required')
    try:
        with opener(urllib.request.Request(url, headers={'User-Agent': 'lookahead-deploy-probe'}), timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        return error.code


def output(env, **values):
    path = env.get('GITHUB_OUTPUT')
    if path:
        with open(path, 'a') as stream:
            for key, value in values.items():
                stream.write(key + '=' + str(value) + '\n')


def main(argv=None, env=None, call=None, get=owner.api, sleep=time.sleep, fetch=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'approve', 'verify', 'execute'])
    parser.add_argument('--root', default='.', help='repository root holding deployment/dev')
    parser.add_argument('--plan', help='plan.json written by plan')
    parser.add_argument('--receipt', help='approval receipt (approve writes it, verify and execute read it)')
    parser.add_argument('--output', help='plan or result file to write (must not exist)')
    args = parser.parse_args(argv)
    call = call or client()
    if args.mode == 'plan':
        require(bool(args.output), '--output required')
        result = plan(env, call, args.root, get)
        write(args.output, result)
        output(env, plan_digest=result['planDigest'], desired_count=result['desiredCount'])
        print(json.dumps({k: result[k] for k in ('releaseVersion', 'image', 'desiredCount', 'stackExists', 'unchanged', 'planDigest')}, sort_keys=True))
        return result
    require(bool(args.plan and args.receipt), '--plan and --receipt required')
    planned = readJson(args.plan)
    if args.mode == 'approve':
        receipt = approve(planned, env, args.root, get, sleep=sleep)
        write(args.receipt, receipt)
        return receipt
    receipt = readJson(args.receipt)
    verify(planned, receipt, env, args.root, get)
    if args.mode == 'verify':
        print('approval verified for plan ' + planned['planDigest'][:16])
        return receipt
    require(bool(args.output), '--output required')
    config, (templateText, _) = loadConfig(args.root), loadTemplate(args.root)
    deployer = Deployer(planned, config, templateText, call, sleep=sleep, fetch=fetch)
    try:
        return deployer.run()
    finally:
        try:
            write(args.output, deployer.outcome)
        except OSError as error:  # the deploy outcome matters more than the receipt file
            print('result not written: ' + str(error)[:200])
        print(json.dumps({k: deployer.outcome.get(k) for k in ('outcome', 'desiredCount', 'error')}, sort_keys=True))


if __name__ == '__main__':
    main()
