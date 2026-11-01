"""Per-application DEV deploy (DLV-968): plan, owner approval, verify, execute. Identical copy in the Web, Domain and
Gateway/BFF repositories; the application differs only through `deployment/dev/deploy-config.json` and the template.

One approved run = three CloudFormation change sets on stacks this repository owns: `app-<run>-candidate` (the idle slot gets
the published image and DesiredCount 1), a flip (Cloud Map alias instance for Web and Domain, `ActiveSlot` change set on
the listener stack for Gateway), then after the observation window `app-<run>-retire` (previous slot to DesiredCount 0).
Nothing is ever deleted. AWS is reached only through the `aws` CLI with the credentials the workflow assumed; the plan
and the receipt bind every identifier, and the approval comment names the plan digest.
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
SCHEMA_CONFIG = 'lookahead-app-deploy-config/v1'
SCHEMA_PLAN = 'app-deploy-plan/v1'
SCHEMA_RECEIPT = 'app-deploy-approval/v1'
SCHEMA_RESULT = 'app-deploy-result/v1'
TERMINAL_OK = {'CREATE_COMPLETE', 'UPDATE_COMPLETE'}
# A stack that can take the next change set. ROLLBACK_COMPLETE (a failed first CREATE) is excluded on purpose: CloudFormation
# only accepts deletion there, and this tool never deletes (see README, manual recovery). REVIEW_IN_PROGRESS is an aborted
# CREATE change set and is planned as a fresh CREATE.
UPDATABLE = TERMINAL_OK | {'UPDATE_ROLLBACK_COMPLETE', 'IMPORT_COMPLETE'}
REVIEW = 'REVIEW_IN_PROGRESS'
TERMINAL_BAD = {'CREATE_FAILED', 'ROLLBACK_COMPLETE', 'ROLLBACK_FAILED', 'UPDATE_ROLLBACK_COMPLETE', 'UPDATE_ROLLBACK_FAILED',
                'UPDATE_FAILED', 'DELETE_FAILED', 'IMPORT_ROLLBACK_COMPLETE'}
NO_CHANGES = "didn't contain changes"
CONFIG_KEYS = {'schema', 'app', 'service', 'account', 'region', 'imageRepository', 'deployRoleArn', 'cloudFormationRoleArn',
               'sessionPrefix', 'cluster', 'clusterName', 'logGroup', 'namespaceName', 'slots', 'flip', 'coupling',
               'observationMinutes', 'probeUrl', 'parameters', 'slotParameters', 'logFailurePatterns'}
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
    require(set(config['slots']) == {'blue', 'green'} and all('stack' in s for s in config['slots'].values()), 'Both slots with stack names required')
    flip = config['flip']
    require(flip.get('mode') in ('alias', 'listener'), 'Flip mode alias or listener required')
    if flip['mode'] == 'alias':
        require({'serviceName', 'serviceId', 'instanceId', 'hostnameSuffix'} <= set(flip)
                and all({'discoveryService', 'discoveryServiceArn'} <= set(s) for s in config['slots'].values()), 'Alias flip needs the Cloud Map names')
        coupling = config.get('coupling') or {}
        require(not coupling.get('listenerStack') or 'gatewayServicePrefix' in coupling, 'Phase 1 coupling needs the Gateway service prefix')
    else:
        require('listenerStack' in flip and all('targetGroupArn' in s for s in config['slots'].values()),
                'Listener flip needs the listener stack and both target groups')
    require(re.fullmatch('[0-9]{12}', config['account']) and config['region'] == 'us-east-2', 'DEV account and region required')
    require(isinstance(config['observationMinutes'], int) and 1 <= config['observationMinutes'] <= 60, 'Observation window 1..60 minutes')
    return config


def loadTemplate(root):
    path = Path(root) / 'deployment/dev/service.template.json'
    text = path.read_text()
    require(len(text) <= 51200, 'Template must fit the CloudFormation inline body limit')
    template = json.loads(text, object_pairs_hook=owner.unique)
    params = template.get('Parameters', {})
    require({'Slot', 'ApplicationImage', 'DesiredCount'} <= set(params) and set(template.get('Resources', {})) <= {'Task', 'Service'},
            'Slot template with Slot, ApplicationImage, DesiredCount and only Task/Service resources required')
    return text, template


def provenance(env, get=owner.api):
    """A fresh owner dispatch of `04 · Deploy DEV` on main, bound to the frozen release-source record of this run."""
    repository = env.get('GITHUB_REPOSITORY')
    require(repository in owner.PUBLISHERS, 'Unknown application repository')
    app = owner.PUBLISHERS[repository][1]
    expected = {'GITHUB_ACTOR': owner.OWNER, 'GITHUB_TRIGGERING_ACTOR': owner.OWNER, 'GITHUB_REF': 'refs/heads/main',
                'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_WORKFLOW_REF': repository + '/' + WORKFLOW + '@refs/heads/main'}
    require(all(env.get(k) == v for k, v in expected.items()), 'Fresh owner dispatch of the deploy workflow from main required')
    sha, runId = env.get('GITHUB_SHA', ''), env.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[1-9][0-9]*', runId), 'Exact source/run required')
    metadata = get(repository, '')
    require(metadata.get('full_name') == repository and metadata.get('owner', {}).get('login') == owner.OWNER
            and str(metadata.get('id')) == env.get('GITHUB_REPOSITORY_ID'), 'Repository identity changed')
    versions.mainBinding('workflow_dispatch', sha, get(repository, 'commits/main').get('sha', ''), lambda path: get(repository, path))
    run = get(repository, 'actions/runs/' + runId)
    fields = {'id': int(runId), 'path': WORKFLOW, 'head_sha': sha, 'head_branch': 'main', 'event': 'workflow_dispatch',
              'run_attempt': 1, 'status': 'in_progress'}
    require(all(run.get(k) == v for k, v in fields.items())
            and all(run.get(k, {}).get('login') == owner.OWNER for k in ('actor', 'triggering_actor')), 'Run provenance changed')
    record = owner.releaseRecord(env)
    require(record.get('workflowSha') == sha and record.get('repository') == repository and str(record.get('runId')) == runId,
            'Release-source record belongs to another workflow run')
    release = versions.recheck(record, lambda path: get(repository, path), sha, app)
    require(env.get('RELEASE_VERSION') == release['releaseVersion'], 'Dispatched release tag differs from resolved record')
    return {**{k: release[k] for k in versions.FIELDS}, 'repository': repository, 'sourceSha': release['sourceSha'],
            'workflowSha': sha, 'runId': runId}


def publishedImage(call, config, imageTag):
    """The immutable ECR tag written by `01 · Full` is the only way a digest enters a deploy."""
    rows = call('ecr', 'batch-get-image', '--repository-name', config['imageRepository'].split('/', 1)[1],
                '--image-ids', 'imageTag=' + imageTag)
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
    parameters = {row['ParameterKey']: row.get('ParameterValue') for row in stack.get('Parameters', [])}
    return {'status': stack.get('StackStatus'), 'stackId': stack.get('StackId'), 'parameters': parameters}


def activeSlot(call, config):
    """Which slot serves today: the alias CNAME (Web, Domain) or the listener stack's ActiveSlot (Gateway)."""
    flip = config['flip']
    if flip['mode'] == 'alias':
        require(re.fullmatch('srv-[a-z0-9]+', flip.get('serviceId', '')), 'Alias Cloud Map service is not configured yet (Infra step I1)')
        found = tolerant(call, 'servicediscovery', 'get-instance', '--service-id', flip['serviceId'], '--instance-id', flip['instanceId'],
                         absent=('InstanceNotFound', 'ResourceNotFoundException'))
        cname = ((found or {}).get('Instance') or {}).get('Attributes', {}).get('AWS_INSTANCE_CNAME', '')
        match = re.fullmatch(re.escape(config['service'] + '-') + '(blue|green)' + re.escape(flip['hostnameSuffix']) + r'\.?', cname)
        return match.group(1) if match else None
    listener = stackState(call, flip['listenerStack'])
    require(listener is not None and listener['status'] in UPDATABLE, 'Listener stack ' + flip['listenerStack'] + ' must exist and be updatable')
    return listener['parameters'].get('ActiveSlot')


def slotParameters(config, slot, image, desiredCount):
    row = config['slots'][slot]
    parameters = dict(config['parameters'])
    parameters.update({key: row[source] for key, source in config['slotParameters'].items()})
    parameters.update({'Slot': slot, 'ApplicationImage': image, 'DesiredCount': str(desiredCount)})
    return parameters


def serviceCounts(call, config, slot):
    rows = call('ecs', 'describe-services', '--cluster', config['cluster'], '--services', config['slots'][slot]['stack'])
    service = next((s for s in rows.get('services', []) if s.get('status') == 'ACTIVE'), None)
    if service is None:
        return None
    primary = next((d for d in service.get('deployments', []) if d.get('status') == 'PRIMARY'), {})
    return {'desired': service.get('desiredCount', 0), 'running': service.get('runningCount', 0),
            'rolloutState': primary.get('rolloutState'), 'taskDefinition': primary.get('taskDefinition')}


def gatewayServes(call, config, slot):
    """Phase 1 only (Web, Domain): True when the listener points at `slot` and a Gateway task actually runs there."""
    coupling = config.get('coupling') or {}
    if not coupling.get('listenerStack') or config['flip']['mode'] != 'alias':
        return False
    state = stackState(call, coupling['listenerStack'])
    if not state or state['parameters'].get('ActiveSlot') != slot:
        return False
    rows = call('ecs', 'describe-services', '--cluster', config['cluster'], '--services', coupling['gatewayServicePrefix'] + slot)
    return any(s.get('status') == 'ACTIVE' and s.get('runningCount', 0) >= 1 for s in rows.get('services', []))


def couplingCheck(call, config, candidate):
    """Phase 1 only: the Gateway reaches web-<slot> and domain-<slot> by its own slot name (removed in phase 2)."""
    coupling = config.get('coupling') or {}
    for prefix in coupling.get('requiresSameSlot', []):
        rows = call('ecs', 'describe-services', '--cluster', config['cluster'], '--services', prefix + candidate)
        service = next((s for s in rows.get('services', []) if s.get('status') == 'ACTIVE'), {})
        require(service.get('runningCount', 0) >= 1, prefix + candidate + ' must be running before the Gateway can serve from slot ' + candidate)
    return {'checkedServices': [prefix + candidate for prefix in coupling.get('requiresSameSlot', [])]}


def resourceRows(config, candidate, previous, candidateExists, candidateUpToDate, flip):
    rows = [{'action': 'Modify' if candidateExists else 'Create', 'type': 'AWS::CloudFormation::Stack', 'consoleName': config['slots'][candidate]['stack'],
             'note': ('already holds this image at DesiredCount 1 (an earlier run stopped before the flip); no candidate change set, verification only' if candidateUpToDate
                      else 'change set app-<run>-candidate: new task definition revision with the published image, DesiredCount 1')},
            {'action': 'Modify', 'type': 'AWS::ECS::Service', 'consoleName': config['slots'][candidate]['stack'], 'note': 'one Fargate task serves slot ' + candidate}]
    if flip['mode'] == 'alias':
        rows.append({'action': 'Modify', 'type': 'AWS::ServiceDiscovery::Instance', 'consoleName': flip['serviceName'] + '.' + config['namespaceName'] + ' → ' + flip['instanceId'],
                     'note': 'CNAME to ' + config['service'] + '-' + candidate + flip['hostnameSuffix'] + ' after the candidate is verified'})
    else:
        rows.append({'action': 'Modify', 'type': 'AWS::CloudFormation::Stack', 'consoleName': flip['listenerStack'],
                     'note': 'change set app-<run>-flip: ActiveSlot=' + candidate + '; listener 443 forwards to target group ' + candidate})
    if previous:
        rows.append({'action': 'Modify', 'type': 'AWS::CloudFormation::Stack', 'consoleName': config['slots'][previous]['stack'],
                     'note': 'change set app-<run>-retire after ' + str(config['observationMinutes']) + ' minutes: DesiredCount 0; the stack and task definition stay'})
    return rows


def plan(env, call, root, get=owner.api, now=None):
    release = provenance(env, get)
    config, (templateText, _) = loadConfig(root), loadTemplate(root)
    require(release['app'] == config['app'], 'Configuration belongs to another application')
    image = publishedImage(call, config, release['imageTag'])
    active = activeSlot(call, config)
    states = {slot: stackState(call, config['slots'][slot]['stack']) for slot in ('blue', 'green')}
    for slot, state in states.items():
        require(state is None or state['status'] in UPDATABLE or state['status'] == REVIEW,
                'Slot stack ' + config['slots'][slot]['stack'] + ' is not updatable: ' + str(state and state['status']) + ' (manual recovery, see README)')
    candidate = 'green' if active == 'blue' else 'blue'
    # Only a slot whose stack still exists can be retired; an alias left pointing at a deleted stack has nothing to scale down.
    previous = active if active in ('blue', 'green') and active != candidate and states[active] is not None else None
    coupling = couplingCheck(call, config, candidate)
    # phase 1: the Gateway reaches <app>-<its own slot>; never rebuild the slot a running Gateway is using
    require(not gatewayServes(call, config, candidate), 'Slot ' + candidate + ' is serving the Gateway (phase 1 coupling); move the Gateway first')
    candidateState = states[candidate]
    candidateExists = candidateState is not None and candidateState['status'] != REVIEW
    candidateUpToDate = bool(candidateExists and candidateState['parameters'].get('ApplicationImage') == image and candidateState['parameters'].get('DesiredCount') == '1')
    result = {'schema': SCHEMA_PLAN, **release, 'image': image, 'account': config['account'], 'region': config['region'],
              'deployRoleArn': config['deployRoleArn'], 'cloudFormationRoleArn': config['cloudFormationRoleArn'],
              'sessionName': config['sessionPrefix'] + '-' + release['runId'] + '-1',
              'activeSlot': active, 'candidateSlot': candidate, 'previousSlot': previous,
              'candidateStack': config['slots'][candidate]['stack'], 'candidateStackExists': candidateExists, 'candidateUpToDate': candidateUpToDate,
              'previousStack': config['slots'][previous]['stack'] if previous else None,
              'previousParameters': sorted(states[previous]['parameters']) if previous else [],
              'previousDesiredCount': states[previous]['parameters'].get('DesiredCount') if previous else None,
              'changeSets': {'candidate': 'app-' + release['runId'] + '-candidate', 'flip': 'app-' + release['runId'] + '-flip',
                             'retire': 'app-' + release['runId'] + '-retire', 'rollback': 'app-' + release['runId'] + '-rollback'},
              'parameters': slotParameters(config, candidate, image, 1), 'flip': dict(config['flip']), 'coupling': coupling,
              'observationMinutes': config['observationMinutes'], 'probeUrl': config['probeUrl'],
              'templateSha256': hashlib.sha256(templateText.encode()).hexdigest(),
              'configSha256': hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest(),
              'resources': resourceRows(config, candidate, previous, candidateExists, candidateUpToDate, config['flip']),
              'plannedAt': utc(now).isoformat()}
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


def aliasTarget(planned):
    return planned['flip']['serviceName'] + '-' + planned['candidateSlot'] + planned['flip']['hostnameSuffix']


def headline(planned):
    return {'env': 'DEV', 'action': 'Deploy ' + planned['app'], 'subject': planned['imageTag'] + ' to ' + planned['candidateSlot'],
            'change': ('alias → ' + planned['candidateSlot'] if planned['flip']['mode'] == 'alias' else 'listener ActiveSlot → ' + planned['candidateSlot'])
                      + (', ' + planned['previousSlot'] + ' → 0 after ' + str(planned['observationMinutes']) + ' min' if planned['previousSlot'] else ', first slot')}


def gist(planned):
    t = issueTemplate.text
    steps = ['1. Recheck the tag, the plan digest, the template and the configuration; then assume `' + t(planned['deployRoleArn']) + '` as session `' + t(planned['sessionName']) + '` (at most 60 minutes; every wait budget inside the run sums below that).',
             '2. Change set `' + t(planned['changeSets']['candidate']) + '` on stack `' + t(planned['candidateStack']) + '`: task definition with image `' + t(planned['image'].rsplit('@', 1)[-1][:19]) + '…`, DesiredCount 1; the preview must match this plan before it is executed.',
             '3. Wait until the ECS rollout is COMPLETED, the task is HEALTHY, service discovery lists it and the log window is clean; on any failure the candidate is set back to DesiredCount 0 and nothing is flipped.',
             '4. Flip: ' + ('upsert Cloud Map instance `' + t(planned['flip']['instanceId']) + '` in service `' + t(planned['flip']['serviceName']) + '` to `' + t(aliasTarget(planned)) + '`.' if planned['flip']['mode'] == 'alias'
                            else 'change set `' + t(planned['changeSets']['flip']) + '` on `' + t(planned['flip']['listenerStack']) + '` with ActiveSlot=' + t(planned['candidateSlot']) + '.'),
             '5. Observe for ' + str(planned['observationMinutes']) + ' minutes (service, task health, public probe); then '
             + ('change set `' + t(planned['changeSets']['retire']) + '` sets `' + t(planned['previousStack']) + '` to DesiredCount 0. The stack, service and task definition stay.' if planned['previousStack'] else 'there is no previous slot to retire.')]
    return ('**Business change:** the published ' + t(planned['app']) + ' image `' + t(planned['imageTag']) + '` starts serving DEV from slot `'
            + t(planned['candidateSlot']) + '`; the previous slot is scaled to zero, never deleted. No IAM, network, database or secret changes.\n\n'
            + '\n'.join(steps) + '\n\nRollback is a new run of this workflow with the previous tag. Approval expires 40 minutes after this issue is created.')


def body(planned, receipt):
    fields = ('releaseVersion', 'imageTag', 'image', 'sourceSha', 'workflowSha', 'candidateStack', 'previousStack', 'sessionName', 'repository', 'account', 'region')
    shown = [('**' + key + '**', owner.commitLink(planned, planned[key]) if key in ('sourceSha', 'workflowSha') else '`' + issueTemplate.text(str(planned[key])) + '`')
             for key in fields if planned.get(key)]
    return issueTemplate.render(
        headline=headline(planned), gist=gist(planned), resources=planned['resources'],
        overview='**Deploy the published DEV image to the idle slot**\n\nAuthorize the three change sets described above, in order, inside this run. '
            'Without approval nothing changes in AWS.',
        scope='### Deploy\n\n' + issueTemplate.table(('Field', 'Value'), shown),
        validation=[('Release tag ' + issueTemplate.text(planned['releaseVersion']), 'resolved to source commit ' + owner.commitLink(planned, planned['sourceSha'])),
                    ('Image', 'ECR tag `' + issueTemplate.text(planned['imageTag']) + '` read back as `' + issueTemplate.text(planned['image'].rsplit('@', 1)[-1]) + '`'),
                    ('Template and configuration', '`' + planned['templateSha256'][:16] + '…` / `' + planned['configSha256'][:16] + '…` rechecked before execution'),
                    ('Coupling', issueTemplate.text(json.dumps(planned['coupling'])))],
        evidence='Review the plan artifact of this run before approving.',
        risks='A failed candidate is scaled back to zero and the alias or listener is not flipped. A failed retire leaves four tasks running until the next run. '
            'This is owner approval enforced by workflow code, not independent review.',
        repository=planned['repository'], runId=planned['runId'], owner=owner.OWNER, expires=receipt['expiresAt'], created=receipt.get('createdAt'),
        permissionDeadline='The deploy session lasts at most 60 minutes; IAM is not changed.',
        command='APPROVE DEPLOY ' + planned['planDigest'], payload=planned)


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
        if created <= when <= now and when < expires and command == 'APPROVE DEPLOY ' + planned['planDigest']:
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
        for attempt in range(max(1, seconds // every)):
            state = check()
            if state is not None:
                return state
            self.sleep(every)
        raise ValueError(label + ' did not complete within ' + str(seconds) + ' seconds')

    def changeSet(self, stack, name, parameters, *, create=False, templateBody=None):
        """With a template body (this repository's slot stacks) the parameters are complete and the stack tag is set; with the
        previous template every other parameter of that stack keeps its value and tags are left alone (never on an Infra stack)."""
        rows = [{'ParameterKey': k, 'ParameterValue': str(v)} for k, v in parameters.items()]
        args = ['cloudformation', 'create-change-set', '--stack-name', stack, '--change-set-name', name,
                '--change-set-type', 'CREATE' if create else 'UPDATE', '--role-arn', self.plan['cloudFormationRoleArn']]
        if templateBody:
            args += ['--template-body', templateBody, '--tags', json.dumps([{'Key': 'LookAheadEnvironment', 'Value': 'dev'}])]
        else:
            current = stackState(self.call, stack)
            require(current is not None and current['status'] in UPDATABLE, 'Stack ' + stack + ' must exist and be updatable')
            rows += [{'ParameterKey': k, 'UsePreviousValue': True} for k in sorted(current['parameters']) if k not in parameters]
            args += ['--use-previous-template']
        args += ['--parameters', json.dumps(rows)]
        created = self.call(*args)
        described = self.waitFor('change set ' + name, lambda: self.describeChangeSet(stack, name), 120, 10)
        if described['Status'] == 'FAILED':
            require(NO_CHANGES in described.get('StatusReason', ''), 'Change set ' + name + ' failed: ' + described.get('StatusReason', '')[:200])
            self.note('change-set-empty', stack=stack, name=name)
            return None
        changes = [{'logical': c['ResourceChange'].get('LogicalResourceId'), 'action': c['ResourceChange'].get('Action'),
                    'type': c['ResourceChange'].get('ResourceType'), 'replacement': c['ResourceChange'].get('Replacement')}
                   for c in described.get('Changes', []) if c.get('Type') == 'Resource']
        self.note('change-set', stack=stack, name=name, id=created.get('Id'), changes=changes)
        return changes

    def describeChangeSet(self, stack, name):
        described = self.call('cloudformation', 'describe-change-set', '--stack-name', stack, '--change-set-name', name)
        return described if described.get('Status') in ('CREATE_COMPLETE', 'FAILED') else None

    def execute(self, stack, name):
        self.call('cloudformation', 'execute-change-set', '--stack-name', stack, '--change-set-name', name)
        state = self.waitFor('stack ' + stack, lambda: self.stackStatus(stack), 600)
        require(state in TERMINAL_OK, 'Stack ' + stack + ' ended in ' + str(state))
        self.note('executed', stack=stack, name=name, status=state)

    def stackStatus(self, stack):
        state = stackState(self.call, stack)
        status = state['status'] if state else None
        return status if status in TERMINAL_OK | TERMINAL_BAD else None

    def expectChanges(self, changes, allowed):
        """`allowed` maps logical id → whether replacement is acceptable (a task definition revision is a replacement; a service never is)."""
        require(changes is not None, 'Change set produced no changes; the stack already matches, re-plan')
        require(bool(changes) and all(c['logical'] in allowed and (allowed[c['logical']] or c['replacement'] != 'True') for c in changes),
                'Change set preview differs from the approved plan: ' + json.dumps(changes))

    def candidate(self):
        p = self.plan
        if p.get('candidateUpToDate'):
            self.note('candidate-unchanged', stack=p['candidateStack'], reason='already holds the approved image at DesiredCount 1')
            return
        changes = self.changeSet(p['candidateStack'], p['changeSets']['candidate'], p['parameters'], create=not p['candidateStackExists'], templateBody=self.template)
        self.expectChanges(changes, {'Task': True, 'Service': False})
        self.execute(p['candidateStack'], p['changeSets']['candidate'])

    def rollbackCandidate(self, reason):
        p = self.plan
        self.note('rollback-start', reason=reason[:300])
        changes = self.changeSet(p['candidateStack'], p['changeSets']['rollback'], {'DesiredCount': 0})
        if changes:
            self.execute(p['candidateStack'], p['changeSets']['rollback'])
        self.note('rollback-done')

    def verifyCandidate(self):
        p, slot, cfg = self.plan, self.plan['candidateSlot'], self.config
        counts = self.waitFor('ECS rollout of ' + p['candidateStack'],
                              lambda: (lambda c: c if c and c['rolloutState'] == 'COMPLETED' and c['running'] == 1 and c['desired'] == 1 else None)(serviceCounts(self.call, cfg, slot)),
                              300)
        arns = self.call('ecs', 'list-tasks', '--cluster', cfg['cluster'], '--service-name', p['candidateStack'], '--desired-status', 'RUNNING').get('taskArns', [])
        require(len(arns) == 1, 'Exactly one running task expected')
        task = self.waitFor('task health', lambda: (lambda t: t if t.get('healthStatus') == 'HEALTHY' else None)(
            self.call('ecs', 'describe-tasks', '--cluster', cfg['cluster'], '--tasks', arns[0])['tasks'][0]), 180)
        require(task.get('taskDefinitionArn', '').split('/')[-1].startswith(p['candidateStack'] + ':'), 'Task belongs to another family')
        startedAt = owner.timestamp(task['startedAt']) if task.get('startedAt') else self.now() - timedelta(minutes=10)
        taskId = arns[0].rsplit('/', 1)[-1]
        pattern = ' '.join('?"' + word + '"' for word in cfg['logFailurePatterns'])
        events = self.call('logs', 'filter-log-events', '--log-group-name', cfg['logGroup'], '--log-stream-name-prefix', cfg['service'] + '/' + cfg['service'] + '/' + taskId,
                           '--start-time', str(int(startedAt.timestamp() * 1000)), '--filter-pattern', pattern).get('events', [])
        require(not events, 'Candidate log window contains failure markers: ' + (events[0].get('message', '')[:200] if events else ''))
        discovered = self.call('servicediscovery', 'discover-instances', '--namespace-name', cfg['namespaceName'],
                               '--service-name', cfg['slots'][slot]['discoveryService'], '--health-status', 'HEALTHY').get('Instances', []) if p['flip']['mode'] == 'alias' else None
        require(discovered is None or len(discovered) >= 1, 'Service discovery lists no healthy ' + cfg['slots'][slot]['discoveryService'] + ' instance')
        targets = None
        if p['flip']['mode'] == 'listener':
            group = cfg['slots'][slot]['targetGroupArn']
            targets = self.waitFor('target health', lambda: (lambda rows: rows if rows and all(r['TargetHealth']['State'] == 'healthy' for r in rows) else None)(
                self.call('elbv2', 'describe-target-health', '--target-group-arn', group).get('TargetHealthDescriptions', [])), 180)
        self.note('candidate-verified', task=arns[0], counts=counts, discovered=None if discovered is None else len(discovered), targets=None if targets is None else len(targets))

    def flip(self):
        p, cfg, flip = self.plan, self.config, self.plan['flip']
        if flip['mode'] == 'alias':
            target = aliasTarget(p)
            operation = self.call('servicediscovery', 'register-instance', '--service-id', flip['serviceId'], '--instance-id', flip['instanceId'],
                                  '--attributes', 'AWS_INSTANCE_CNAME=' + target).get('OperationId')
            status = self.waitFor('alias operation', lambda: (lambda s: s if s in ('SUCCESS', 'FAIL') else None)(
                self.call('servicediscovery', 'get-operation', '--operation-id', operation)['Operation']['Status']), 180, 5)
            require(status == 'SUCCESS', 'Alias update failed')
            self.note('flipped', mode='alias', cname=target, operation=operation)
            return
        changes = self.changeSet(flip['listenerStack'], p['changeSets']['flip'], {'ActiveSlot': p['candidateSlot']})
        require(changes is not None and all(c['replacement'] != 'True' for c in changes), 'Listener flip must not replace resources')
        self.execute(flip['listenerStack'], p['changeSets']['flip'])
        self.note('flipped', mode='listener', activeSlot=p['candidateSlot'])

    def observe(self):
        p, cfg = self.plan, self.config
        rounds = p['observationMinutes']
        for minute in range(rounds):
            counts = serviceCounts(self.call, cfg, p['candidateSlot'])
            require(counts and counts['running'] == 1 and counts['rolloutState'] == 'COMPLETED', 'Candidate stopped serving during observation')
            status = self.fetch(p['probeUrl'])
            require(status == 200, 'Public probe returned ' + str(status) + ' during observation')
            if minute < rounds - 1:
                self.sleep(60)
        self.note('observed', minutes=rounds)

    def retire(self):
        p, cfg = self.plan, self.config
        if not p['previousStack']:
            self.note('retire-skipped', reason='no previous slot')
            return
        if gatewayServes(self.call, cfg, p['previousSlot']):
            self.note('retire-skipped', reason='Gateway still serves from slot ' + p['previousSlot'] + ' (phase 1 coupling); two tasks stay until the Gateway moves')
            return
        if p.get('previousDesiredCount') == '0':
            self.note('retire-skipped', reason='previous slot already at DesiredCount 0')
            return
        changes = self.changeSet(p['previousStack'], p['changeSets']['retire'], {'DesiredCount': 0})
        if changes:
            self.expectChanges(changes, {'Service': False})
            self.execute(p['previousStack'], p['changeSets']['retire'])
        self.note('retired', stack=p['previousStack'])

    def run(self):
        outcome = {'schema': SCHEMA_RESULT, 'runId': self.plan['runId'], 'planDigest': self.plan['planDigest'], 'app': self.plan['app'],
                   'releaseVersion': self.plan['releaseVersion'], 'image': self.plan['image'], 'candidateSlot': self.plan['candidateSlot']}
        try:
            self.identity()
            self.candidate()
            try:
                self.verifyCandidate()
            except Exception as error:  # the candidate never served: scale it back, keep the previous slot untouched
                self.rollbackCandidate(str(error))
                raise
            self.flip()
            self.observe()
            self.retire()
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
        output(env, candidate_slot=result['candidateSlot'], plan_digest=result['planDigest'])
        print(json.dumps({k: result[k] for k in ('releaseVersion', 'image', 'candidateSlot', 'previousSlot', 'planDigest')}, sort_keys=True))
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
        print(json.dumps({k: deployer.outcome.get(k) for k in ('outcome', 'candidateSlot', 'error')}, sort_keys=True))


if __name__ == '__main__':
    main()
