"""Per-application DEV deploy (DLV-968, decision V of 2026-11-01): one ECS service per application, ECS rolling updates.
Identical copy in the Web, Domain and Gateway/BFF repositories; the application differs only through
`deployment/dev/deploy-config.json` and `deployment/dev/service.template.json`.

One approved run = one CloudFormation change set `app-<run>` on the stack this repository owns: the task definition gets the
published image and the service gets `DesiredCount` (1 serves, 0 parks the service). ECS performs the rolling update
(surge to two tasks, the old task drained once the new one is healthy) and its deployment circuit breaker rolls a failed
revision back by itself; manual rollback is a new run with the previous tag. The run then verifies the running task, the
log window, service discovery or target health, and watches this application alone for the observation window (DLV-968,
user 2026-11-05: each application deploys on its own, whether the other two run or are stopped). Only Gateway, whose own DEV
gate page answers through the public entrance, must pass the public probe; for Web and Domain the public page needs the other
applications, so it is probed once and only recorded. In DEV's off hours a service that was stopped before the deploy is
stopped again afterwards. AWS is reached
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
from zoneinfo import ZoneInfo

import issue_template as issueTemplate
import owner_approval as owner
import release_version as versions

WORKFLOW = '.github/workflows/deploy-dev.yml'
# `05 · Recover DEV` (recover-dev.yml, tools/release/recover.py) runs park, deploy and fallback stages of this tool in one approved run.
RECOVER_WORKFLOW = '.github/workflows/recover-dev.yml'
# `01 · Full` (release.yml) and `03 · Publish` (publish.yml) call deploy-dev.yml after the image is published; a reusable-workflow
# call keeps the caller's workflow ref, run path and event (push or dispatch).
DEPLOY_WORKFLOWS = (WORKFLOW, '.github/workflows/release.yml', '.github/workflows/publish.yml', RECOVER_WORKFLOW)
SCHEMA_CONFIG = 'lookahead-app-deploy-config/v3'
SCHEMA_PLAN = 'app-deploy-plan/v3'
SCHEMA_RECEIPT = 'app-deploy-approval/v2'
SCHEMA_RESULT = 'app-deploy-result/v2'
TERMINAL_OK = {'CREATE_COMPLETE', 'UPDATE_COMPLETE'}
TERMINAL_BAD = {'CREATE_FAILED', 'ROLLBACK_COMPLETE', 'ROLLBACK_FAILED', 'UPDATE_ROLLBACK_COMPLETE', 'UPDATE_ROLLBACK_FAILED',
                'UPDATE_FAILED', 'DELETE_FAILED', 'IMPORT_ROLLBACK_COMPLETE'}
# A stack that can take the next change set. ROLLBACK_COMPLETE (a failed first CREATE) is excluded on purpose: CloudFormation
# only accepts deletion there, and this tool never deletes (see README). REVIEW_IN_PROGRESS is an aborted CREATE change set.
UPDATABLE = TERMINAL_OK | {'UPDATE_ROLLBACK_COMPLETE', 'IMPORT_COMPLETE'}
REVIEW = 'REVIEW_IN_PROGRESS'
# A stack stuck in an update or rollback refuses every change set. A park run (DesiredCount 0) still proceeds on such a stack:
# it scales the service to zero directly, waits for CloudFormation to finish, then records the park with the usual change set
# (DLV-969, user decision 2026-11-02: recovery is two pipeline runs, park then deploy, never the AWS CLI).
BUSY_PARKABLE = {'UPDATE_IN_PROGRESS', 'UPDATE_ROLLBACK_IN_PROGRESS'}
BUSY_SETTLE_SECONDS = 1800
NO_CHANGES = "didn't contain changes"
# DEV is off from midnight to 10:00 America/New_York (user decision 2026-11-05), the window of Infra's
# `Application · Start or stop DEV` schedule. A deploy in that window stops its service again if it found it stopped.
OFF_HOURS = ('America/New_York', 0, 10)
# The scheduled stops land from 00:00 New York (Gateway 00:00, Web 00:03, Domain 00:06; starts 10:00 to 10:08). A serving deploy is
# refused from 23:30 to 00:30 (user decision 2026-11-05: DEV has no emergencies, so there is no override; deploy after 00:30).
BLACKOUT = ('America/New_York', 23 * 60 + 30, 30)  # zone, start and end as minutes past midnight (wraps)
BLACKOUT_TEXT = 'between 23:30 and 00:30 New York time'
CONFIG_KEYS = {'schema', 'app', 'service', 'account', 'region', 'imageRepository', 'deployRoleArn', 'cloudFormationRoleArn',
               'sessionPrefix', 'cluster', 'clusterName', 'stack', 'logGroup', 'namespaceName', 'discovery', 'targetGroupArn',
               'observationMinutes', 'refreshObservationMinutes', 'probeUrl', 'probeRequired', 'parameters', 'logFailurePatterns'}
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
    require(isinstance(config['refreshObservationMinutes'], int) and 1 <= config['refreshObservationMinutes'] <= 60, 'Refresh observation window 1..60 minutes')
    discovery, group = config['discovery'], config['targetGroupArn']
    require((discovery is None) != (group is None), 'Exactly one of discovery (Web, Domain) or targetGroupArn (Gateway) is required')
    if discovery is not None:
        require({'serviceName', 'serviceId', 'serviceArn'} <= set(discovery), 'Discovery needs serviceName, serviceId and serviceArn')
    require(isinstance(config['probeUrl'], str) and config['probeUrl'].startswith('https://'), 'https probe URL required')
    # Only the application behind the public entrance (Gateway, the target-group shape) may make the public probe a gate; Web and
    # Domain would otherwise fail whenever Gateway is stopped (Web run 41993985015, 2026-11-05).
    require(config['probeRequired'] is (group is not None), 'probeRequired is true for Gateway (target group) and false for Web and Domain')
    return config


def loadTemplate(root):
    path = Path(root) / 'deployment/dev/service.template.json'
    text = path.read_text()
    require(len(text) <= 51200, 'Template must fit the CloudFormation inline body limit')
    template = json.loads(text, object_pairs_hook=owner.unique)
    params = template.get('Parameters', {})
    require({'ApplicationImage', 'DesiredCount', 'RefreshToken'} <= set(params) and 'Slot' not in params and set(template.get('Resources', {})) <= {'Task', 'Service'},
            'Single-service template with ApplicationImage, DesiredCount, RefreshToken and only Task/Service resources required')
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


def inBlackout(now):
    zone, start, end = BLACKOUT
    local = now.astimezone(ZoneInfo(zone))
    minute = local.hour * 60 + local.minute
    return minute >= start or minute < end


def requireOutsideBlackout(count, now, stage):
    """A park (count 0) is never a deploy; a serving deploy waits for 00:30, when the scheduled stops are done."""
    require(count == 0 or not inBlackout(now), 'No deploy ' + BLACKOUT_TEXT + ' (' + stage + '): the scheduled stops of DEV land from 00:00.'
            ' Deploy after 00:30 by dispatching 04 · Deploy DEV with this tag')


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
            'parameters': {row['ParameterKey']: row.get('ParameterValue') for row in stack.get('Parameters', [])},
            'tags': {row['Key']: row.get('Value') for row in stack.get('Tags', [])}}


def parameters(config, image, count, refresh='none'):
    rendered = dict(config['parameters'])
    rendered.update({'ApplicationImage': image, 'DesiredCount': str(count), 'RefreshToken': refresh})
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


def resourceRows(config, state, count, current, unchanged=False, busy=False):
    first = state is None
    if busy:
        return [{'action': 'Modify', 'type': 'AWS::ECS::Service', 'consoleName': config['stack'],
                 'note': 'stack busy (' + state['status'] + '): DesiredCount 0 set on the service directly, so the stuck rollout stops'},
                {'action': 'Modify', 'type': 'AWS::CloudFormation::Stack', 'consoleName': config['stack'],
                 'note': 'after the stack finishes its update or rollback (at most ' + str(BUSY_SETTLE_SECONDS // 60) + ' minutes), change set app-<run> records DesiredCount 0'}]
    if unchanged:
        return [{'action': 'Modify only on REFRESH', 'type': 'AWS::CloudFormation::Stack', 'consoleName': config['stack'],
                 'note': 'duplicate image: the stack already runs this image at DesiredCount ' + str(count) + '. APPROVE REFRESH registers a new task definition revision '
                         '(same image, new refresh token) and ECS restarts the task; APPROVE VERIFY and REJECT change nothing'},
                {'action': 'Modify only on REFRESH', 'type': 'AWS::ECS::Service', 'consoleName': config['stack'],
                 'note': 'rolling restart of the one task only on REFRESH (surge to two, old task drained once the new one is healthy)'}]
    rows = [{'action': 'Create' if first else 'Modify', 'type': 'AWS::CloudFormation::Stack', 'consoleName': config['stack'],
             'note': 'change set app-<run>: ' + ('first creation of the stack' if first else 'new task definition revision and DesiredCount ' + str(count))},
            {'action': 'Create' if first else 'Modify', 'type': 'AWS::ECS::Service', 'consoleName': config['stack'],
             'note': ('one task serves after an ECS rolling update (surge to two, old task drained once the new one is healthy)' if count == 1
                      else 'service parked at zero tasks; the task definition and stack stay')}]
    if current and current.get('ApplicationImage') and current.get('DesiredCount') == '1':
        rows[1]['note'] += '; currently serving ' + current['ApplicationImage'].rsplit('@', 1)[-1][:19] + '…'
    return rows


def plan(env, call, root, get=owner.api, now=None, stage=None, session=1, recovery=False):
    """One deploy plan. `05 · Recover DEV` plans each of its stages with `recovery=True`: a stage always executes (no duplicate-image
    choice), a serving stage may follow a park on a stack that is still busy at plan time, and `stage`/`session` name the stage's own
    change set (`app-<run>-<stage>`) and deploy session (`<prefix>-<run>-<session>`)."""
    release = provenance(env, get)
    config, (templateText, _) = loadConfig(root), loadTemplate(root)
    require(release['app'] == config['app'], 'Configuration belongs to another application')
    count = desiredCount(env)
    requireOutsideBlackout(count, utc(now), 'at planning')
    image = publishedImage(call, config, release['imageTag'])
    state = stackState(call, config['stack'])
    busy = state is not None and state['status'] in BUSY_PARKABLE
    if recovery:
        require(state is not None and state['status'] != REVIEW, 'Stack ' + config['stack'] + ' does not exist yet; recovery needs a deployed service (use 04 · Deploy DEV)')
    if busy and not recovery:
        require(count == 0, 'Stack ' + config['stack'] + ' is busy: ' + state['status'] + ' and refuses change sets.'
                ' Run 04 · Deploy DEV with desired_count 0 first to park the service; then deploy this release with desired_count 1')
    require(busy or state is None or state['status'] in UPDATABLE or state['status'] == REVIEW,
            'Stack ' + config['stack'] + ' is not updatable: ' + str(state and state['status']) + ' (manual recovery, see README)')
    exists = state is not None and state['status'] != REVIEW
    current = state['parameters'] if exists else {}
    rendered = parameters(config, image, count, current.get('RefreshToken', 'none') if exists else 'none')
    # A busy stack's parameters are the ones it is still trying to apply; a park there is always a change.
    unchanged = exists and not busy and not recovery and all(current.get(k) == v for k, v in rendered.items())
    # A duplicate image offers a restart (refresh) or a verification only; a parked duplicate can only be verified.
    choices = (['refresh', 'verify'] if count == 1 else ['verify']) if unchanged else ['deploy']
    result = {'schema': SCHEMA_PLAN, **release, 'image': image, 'desiredCount': count, 'account': config['account'], 'region': config['region'],
              'deployRoleArn': config['deployRoleArn'], 'cloudFormationRoleArn': config['cloudFormationRoleArn'],
              'sessionName': config['sessionPrefix'] + '-' + release['runId'] + '-' + str(session), 'stack': config['stack'], 'stackExists': exists,
              'unchanged': unchanged, 'choices': choices, 'refreshToken': 'run-' + release['runId'],
              'changeSet': 'app-' + release['runId'] + ('-' + stage if stage else ''), 'parameters': rendered,
              'currentImage': current.get('ApplicationImage'), 'currentDesiredCount': current.get('DesiredCount'),
              'observationMinutes': config['observationMinutes'], 'refreshObservationMinutes': config['refreshObservationMinutes'], 'probeUrl': config['probeUrl'],
              'probeRequired': config['probeRequired'],
              # The stack's tags are kept exactly as they are: CloudFormation copies them onto the ECS service, and changing one needs
              # ecs:TagResource and ecs:UntagResource for the provisioning role, which it lacks (Domain run 42110285052 failed on
              # UntagResource, 2026-11-05). Per-deploy tags (release, run, commit) return once an IAM transition grants them.
              'stackTags': dict(state['tags']) if exists and state.get('tags') else {'LookAheadEnvironment': 'dev'},
              'templateSha256': hashlib.sha256(templateText.encode()).hexdigest(),
              'configSha256': hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest(),
              'busyStackPark': busy and count == 0, 'busyStackStatus': state['status'] if busy and count == 0 else None,
              'resources': resourceRows(config, state if exists else None, count, current, unchanged, busy and count == 0), 'plannedAt': utc(now).isoformat()}
    if stage:
        result['stage'] = stage
    result['planDigest'] = digest({k: v for k, v in result.items() if k != 'planDigest'})
    return result


def validatePlan(planned, env, root, get=owner.api, identity=None):
    """`identity` lets `05 · Recover DEV` check several stage plans against one provenance lookup per release."""
    identity = identity or provenance(env, get)
    require(planned.get('schema') == SCHEMA_PLAN and all(planned.get(k) == v for k, v in identity.items()), 'Deploy plan identity changed')
    require(planned.get('planDigest') == digest({k: v for k, v in planned.items() if k != 'planDigest'}), 'Deploy plan digest changed')
    templateText, _ = loadTemplate(root)
    require(planned.get('templateSha256') == hashlib.sha256(templateText.encode()).hexdigest(), 'Template changed since the plan')
    require(planned.get('configSha256') == hashlib.sha256(json.dumps(loadConfig(root), sort_keys=True).encode()).hexdigest(), 'Configuration changed since the plan')
    require(re.fullmatch('sha256:[a-f0-9]{64}', planned.get('image', '').rsplit('@', 1)[-1]), 'Image digest required')


def approvalCommands(planned):
    """One comment per allowed answer: `APPROVE DEPLOY <tag>` for a changed plan; `APPROVE REFRESH <tag>` (restart on the same image)
    and `APPROVE VERIFY <tag>` (verification only) for a duplicate image (user decision 2026-11-02). The comment names the image
    version as ECR shows it (user decision 2026-10-28); the plan digest binds the exact plan through the issue body and the receipt."""
    return {mode: 'APPROVE ' + mode.upper() + ' ' + planned['imageTag'] for mode in planned['choices']}


def approvalCommand(planned):
    return approvalCommands(planned)[planned['choices'][0]]


def approvalWindow(planned, env=None):
    """A duplicate image is usually an accident: five minutes to answer, then the issue closes with nothing done (user decision 2026-11-02).
    Otherwise the repository variable APPROVAL_WAIT_MINUTES (default 10)."""
    return timedelta(minutes=5) if planned['unchanged'] else owner.approvalWait(env)


def headline(planned):
    action = 'Deploy ' + planned['app'] if planned['desiredCount'] == 1 else 'Park ' + planned['app']
    change = ('rolling update of ' + planned['stack'] if planned['stackExists'] else 'first start of ' + planned['stack']) if planned['desiredCount'] == 1 \
        else planned['stack'] + ' to zero tasks'
    if planned['unchanged']:
        change = 'DUPLICATE IMAGE, ' + planned['stack'] + ' already runs it: ' + (' or '.join(planned['choices']) + ' or reject')
    if planned.get('busyStackPark'):
        change = planned['stack'] + ' to zero tasks while the stack is ' + planned['busyStackStatus']
    return {'env': 'DEV', 'action': action, 'subject': planned['imageTag'], 'change': change}


def gist(planned, env=None):
    t = issueTemplate.text
    serving = ('1. Recheck the tag, the plan digest, the template and the configuration; then assume `' + t(planned['deployRoleArn']) + '` as session `'
               + t(planned['sessionName']) + '` (at most 60 minutes).',
               '2. Change set `' + t(planned['changeSet']) + '` on stack `' + t(planned['stack']) + '`: task definition with image `'
               + t(planned['image'].rsplit('@', 1)[-1][:19]) + '…`, DesiredCount ' + str(planned['desiredCount']) + '; the preview must match this plan before it is executed.',
               '3. ECS performs the rolling update: the new task starts beside the old one and the old one is drained once the new one is healthy; '
               'if the new revision never becomes healthy the deployment circuit breaker rolls the service back to the previous revision and this run fails.',
               '4. Verify: task HEALTHY, log window clean, ' + ('service discovery lists the task' if planned['parameters'].get('DiscoveryServiceArn') else 'target group healthy')
               + ', then watch this application alone for ' + str(planned['observationMinutes']) + ' minutes: the same task stays RUNNING and HEALTHY with a clean log'
               + (' and `' + t(planned['probeUrl']) + '` answers 200 every minute.' if planned.get('probeRequired') else
                  '; `' + t(planned['probeUrl']) + '` is checked once and only recorded, since it also needs the other applications.')
               + ' Between 00:00 and 10:00 New York time, a service that was stopped before this deploy is stopped again afterwards.')
    parked = ('1. Recheck the tag, the plan digest, the template and the configuration; then assume `' + t(planned['deployRoleArn']) + '` as session `' + t(planned['sessionName']) + '`.',
              '2. Change set `' + t(planned['changeSet']) + '` on stack `' + t(planned['stack']) + '`: DesiredCount 0. The stack, service and task definition stay; a later run with DesiredCount 1 starts it again.')
    if planned.get('busyStackPark'):
        parked = (parked[0],
                  '2. The stack is `' + t(planned['busyStackStatus']) + '` and refuses change sets, so the ECS service `' + t(planned['stack'])
                  + '` is set to DesiredCount 0 directly (`ecs update-service`); the stuck rollout stops and ECS reports the service stable. '
                  'This needs `ecs:UpdateService` for this deploy session (IAM transition in the README); without it the call is denied and nothing changes.',
                  '3. Wait at most ' + str(BUSY_SETTLE_SECONDS // 60) + ' minutes for CloudFormation to finish its update or rollback. `UPDATE_ROLLBACK_FAILED` or a timeout stops this run '
                  '(break-glass runbook on the Admin page Support runbooks).',
                  '4. Change set `' + t(planned['changeSet']) + '` on stack `' + t(planned['stack']) + '`: DesiredCount 0, so the stack records the park. '
                  'Then run this workflow again with the release to deploy and desired_count 1.')
    head = ('**Business change:** the published ' + t(planned['app']) + ' image `' + t(planned['imageTag']) + '` serves DEV after an ECS rolling update of `' + t(planned['stack']) + '`.'
            if planned['desiredCount'] == 1 else '**Business change:** the DEV ' + t(planned['app']) + ' service `' + t(planned['stack']) + '` is parked at zero tasks (scheduled scale-down); nothing is deleted.')
    if planned['unchanged']:
        commands = approvalCommands(planned)
        lines = ['**Warning: duplicate image.** `' + t(planned['stack']) + '` already runs `' + t(planned['imageTag']) + '` at DesiredCount ' + str(planned['desiredCount'])
                 + '. Nothing changes unless you answer within 5 minutes; silence or `REJECT` closes this issue with nothing done.']
        if 'refresh' in commands:
            lines.append('- `' + commands['refresh'] + '`: restart on the same image. Change set `' + t(planned['changeSet']) + '` sets the template parameter RefreshToken to `'
                         + t(planned['refreshToken']) + '`, CloudFormation registers a new task definition revision and ECS performs the rolling update (the new task reads '
                         'its configuration and, for Domain, the content release in S3 afresh); then verification and a ' + str(planned['refreshObservationMinutes']) + '-minute observation.')
        lines.append('- `' + commands['verify'] + '`: no AWS change; the tool only verifies that the service runs this image' + (' with one healthy task, a clean log window and its discovery or target registration.' if planned['desiredCount'] == 1 else ' at zero tasks.'))
        lines.append('- `REJECT`: this was an accident; nothing happens.')
        return '\n'.join(lines)
    return head + ' No IAM, network, database or secret changes.\n\n' + '\n'.join(serving if planned['desiredCount'] == 1 else parked) \
        + '\n\nRollback is a new run of this workflow with the previous tag. Approve within ' + owner.minutesText(approvalWindow(planned, env)) \
        + ' minutes of this issue being created; the approval comment is then valid for ' + owner.minutesText(owner.approvalValid(env)) + ' minutes.'


def body(planned, receipt, env=None):
    fields = ('releaseVersion', 'imageTag', 'image', 'desiredCount', 'sourceSha', 'workflowSha', 'stack', 'currentImage', 'currentDesiredCount', 'sessionName', 'repository', 'account', 'region')
    shown = [('**' + key + '**', owner.commitLink(planned, planned[key]) if key in ('sourceSha', 'workflowSha') else '`' + issueTemplate.text(str(planned[key])) + '`')
             for key in fields if planned.get(key) is not None]
    return issueTemplate.render(
        headline=headline(planned), gist=gist(planned, env), resources=planned['resources'],
        overview=('**Duplicate image: choose refresh, verify or reject**\n\nThe stack already serves this image. `' + approvalCommands(planned)[planned['choices'][0]]
                  + '` is the primary answer shown below' + ('; `' + approvalCommands(planned)['verify'] + '` verifies without changing AWS' if 'refresh' in planned['choices'] else '')
                  + '; `REJECT` or no answer within 5 minutes closes this issue with nothing done.') if planned['unchanged'] else
                 '**Deploy the published DEV image**\n\nAuthorize the one change set described above inside this run. Without approval nothing changes in AWS.',
        scope='### Deploy\n\n' + issueTemplate.table(('Field', 'Value'), shown),
        validation=[('Release tag ' + issueTemplate.text(planned['releaseVersion']), 'resolved to source commit ' + owner.commitLink(planned, planned['sourceSha'])),
                    ('Image', 'ECR tag `' + issueTemplate.text(planned['imageTag']) + '` read back as `' + issueTemplate.text(planned['image'].rsplit('@', 1)[-1]) + '`'),
                    ('Template and configuration', '`' + planned['templateSha256'][:16] + '…` / `' + planned['configSha256'][:16] + '…` rechecked before execution'),
                    ('Change', 'duplicate image: no-op unless you answer REFRESH (restart on the same image)' if planned['unchanged']
                     else 'park of a busy stack: direct scale to zero, wait, then the park change set' if planned.get('busyStackPark')
                     else 'rolling update' if planned['stackExists'] else 'first creation')],
        evidence='Review the plan artifact of this run before approving.',
        risks='A new revision that never becomes healthy is rolled back by the ECS circuit breaker and this run fails; the previous revision keeps serving. '
            'Old and new tasks overlap for the discovery TTL and the load-balancer drain. This is owner approval enforced by workflow code, not independent review.',
        repository=planned['repository'], runId=planned['runId'], owner=owner.OWNER, expires=receipt['expiresAt'], created=receipt.get('createdAt'),
        permissionDeadline='The deploy session lasts at most 60 minutes; IAM is not changed.',
        command=approvalCommand(planned), payload=planned)


def decision(planned, receipt, env, root, get=owner.api, now=None, recheck=False):
    validatePlan(planned, env, root, get)
    return selectedApproval(planned, receipt, get, now, recheck=recheck, env=env)


def selectedApproval(planned, receipt, get=owner.api, now=None, render=None, commands=None, window=None, recheck=False, env=None):
    """The owner's live answer on the approval issue of this receipt as (comment id, mode, comment time), or None while there is none.
    `render(planned, receipt, env)`, `commands(planned)` and `window(planned, env)` default to this deploy's issue body, commands and
    window; `05 · Recover DEV` passes its own. While waiting, `now` must lie inside the comment window; a `recheck` after the wait is
    bounded by `verify` from the comment instead. `env` carries the window variables (the process environment when None)."""
    render, commands, window = render or body, commands or approvalCommands, window or approvalWindow
    now = now or utc()
    created, expires = owner.timestamp(receipt['createdAt']), owner.timestamp(receipt['expiresAt'])
    require(receipt.get('schema') == SCHEMA_RECEIPT and receipt.get('planDigest') == planned['planDigest'] and receipt.get('runId') == planned['runId']
            and created <= now and (recheck or now < expires) and timedelta(0) < expires - created <= window(planned, env), 'Approval expired or binding changed')
    issue = get(planned['repository'], 'issues/' + str(receipt['issue']))
    require(issue.get('state') == 'open' and not issue.get('pull_request') and issue.get('user', {}).get('login') == 'github-actions[bot]'
            and issue.get('body') == render(planned, receipt, env), 'Approval issue changed')
    require(created <= owner.timestamp(issue['created_at']) < expires, 'Approval issue creation time outside review window')
    comments = get(planned['repository'], 'issues/' + str(receipt['issue']) + '/comments?per_page=100')
    require(isinstance(comments, list) and len(comments) < 100, 'Incomplete approval comment inventory')
    selected, commands = None, {command: mode for mode, command in commands(planned).items()}
    for comment in comments:
        if comment.get('user', {}).get('login') != owner.OWNER:
            continue
        command = comment.get('body', '').strip()
        require(command != 'REJECT', 'Owner rejected the deployment')
        if comment.get('created_at') != comment.get('updated_at'):
            continue
        when = owner.timestamp(comment['created_at'])
        if created <= when <= now and when < expires and command in commands:
            selected = (comment['id'], commands[command], comment['created_at'])
    return selected


def approve(planned, env, root, get=owner.api, now=lambda: utc(), sleep=time.sleep):
    validatePlan(planned, env, root, get)
    start, wait = now().replace(microsecond=0), approvalWindow(planned, env)
    receipt = {'schema': SCHEMA_RECEIPT, 'runId': planned['runId'], 'planDigest': planned['planDigest'],
               'createdAt': start.isoformat(), 'expiresAt': (start + wait).isoformat()}
    preview = body(planned, receipt, env)
    require(len(preview) < 60000, 'Approval preview too large')
    issue = get(planned['repository'], 'issues', {'title': issueTemplate.title(headline(planned), planned['runId']), 'body': preview})
    receipt['issue'] = issue['number']
    print('https://github.com/' + planned['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(int(wait.total_seconds()) // 20):
        selected = decision(planned, receipt, env, root, get, now())
        if selected is not None:
            return {**receipt, 'commentId': selected[0], 'mode': selected[1], 'approvedAt': selected[2]}
        sleep(20)
    raise TimeoutError('Deploy approval timed out; start a new reviewed run')


def verify(planned, receipt, env, root, get=owner.api, now=None):
    now = now or utc()
    selected = decision(planned, receipt, env, root, get, now, recheck=True)
    require(selected is not None and selected[:2] == (receipt.get('commentId'), receipt.get('mode')), 'Exact live owner approval required')
    owner.stillValid(receipt, selected[2], now, env)


class Deployer:
    """Executes one approved plan; every AWS step is recorded in `self.steps` and written whatever the outcome."""

    def __init__(self, planned, config, templateText, call, sleep=time.sleep, now=None, fetch=None, mode='deploy', log=print,
                 stackSeconds=1200, observeRounds=None, parkOffHours=True):
        self.plan, self.config, self.template, self.call = planned, config, templateText, call
        self.sleep, self.now, self.fetch, self.mode, self.log = sleep, now or (lambda: utc()), fetch or probe, mode, log
        # `05 · Recover DEV` bounds the wait for a serving stage (its healthy_minutes input) and observes for the shorter refresh window.
        self.stackSeconds, self.observeRounds = stackSeconds, observeRounds
        # `05 · Recover DEV` passes False: a recovery ends serving, whatever the hour.
        self.parkOffHours, self.wasStopped = parkOffHours, False
        self.task = self.logStart = self.revision = None
        self.steps = []

    def note(self, step, **detail):
        self.steps.append({'step': step, 'at': self.now().isoformat(), **detail})
        # One line per recorded step in the live job log (the long waits are otherwise silent).
        self.log(step + ': ' + json.dumps(detail, sort_keys=True, default=str)[:300], flush=True)

    def parameters(self):
        rendered = dict(self.plan['parameters'])
        if self.mode == 'refresh':
            rendered['RefreshToken'] = self.plan['refreshToken']
        return rendered

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
        rows = [{'ParameterKey': k, 'ParameterValue': str(v)} for k, v in self.parameters().items()]
        args = ['cloudformation', 'create-change-set', '--stack-name', p['stack'], '--change-set-name', p['changeSet'],
                '--change-set-type', 'UPDATE' if p['stackExists'] else 'CREATE', '--role-arn', p['cloudFormationRoleArn'],
                '--template-body', self.template, '--tags', json.dumps(self.stackTags()), '--parameters', json.dumps(rows)]
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

    def stackTags(self):
        """Exactly the tags the stack had at plan time (the environment tag for a new stack): a tag change is an ECS tag update the
        provisioning role may not make yet (see plan)."""
        return [{'Key': key, 'Value': value} for key, value in self.plan['stackTags'].items()]

    def describeChangeSet(self):
        described = self.call('cloudformation', 'describe-change-set', '--stack-name', self.plan['stack'], '--change-set-name', self.plan['changeSet'])
        return described if described.get('Status') in ('CREATE_COMPLETE', 'FAILED') else None

    def execute(self):
        p = self.plan
        self.call('cloudformation', 'execute-change-set', '--stack-name', p['stack'], '--change-set-name', p['changeSet'])
        state = self.waitFor('stack ' + p['stack'], self.stackStatus, self.stackSeconds)
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
        self.revision = counts['taskDefinition']
        self.acceptTask()
        registered = None
        if cfg['discovery'] is not None:
            registered = len(self.call('servicediscovery', 'list-instances', '--service-id', cfg['discovery']['serviceId']).get('Instances', []))
            require(registered >= 1, 'Service discovery lists no ' + cfg['discovery']['serviceName'] + ' instance')
        targets = None
        if cfg['targetGroupArn'] is not None:
            targets = self.waitFor('target health', lambda: (lambda rows: rows if rows and all(r['TargetHealth']['State'] == 'healthy' for r in rows) else None)(
                self.call('elbv2', 'describe-target-health', '--target-group-arn', cfg['targetGroupArn']).get('TargetHealthDescriptions', [])), 180)
        self.note('verified', task=self.task, counts=counts, discovered=registered, targets=None if targets is None else len(targets))

    def acceptTask(self):
        """The service's one RUNNING task: HEALTHY, this family, the approved image, a clean log since it started. It is the task the
        observation then holds the service to."""
        p, cfg = self.plan, self.config
        arns = self.call('ecs', 'list-tasks', '--cluster', cfg['cluster'], '--service-name', p['stack'], '--desired-status', 'RUNNING').get('taskArns', [])
        require(len(arns) == 1, 'Exactly one running task expected')
        task = self.waitFor('task health', lambda: (lambda t: t if t.get('healthStatus') == 'HEALTHY' else None)(
            self.call('ecs', 'describe-tasks', '--cluster', cfg['cluster'], '--tasks', arns[0])['tasks'][0]), 180)
        require(task.get('taskDefinitionArn', '').split('/')[-1].startswith(p['stack'] + ':'), 'Task belongs to another family')
        # The running container reports the digest it was pulled from (`describe-tasks`, granted to deploy sessions);
        # `describe-task-definition` is outside the deploy session's allow-list in the Infra bundle and is not needed.
        container = next((c for c in task.get('containers', []) if c.get('name') == cfg['service']), None)
        digest = p['image'].rsplit('@', 1)[-1]
        require(container is not None and digest in (str(container.get('imageDigest', '')) + ' ' + str(container.get('image', ''))),
                'Running task does not carry the approved image')
        startedAt = owner.timestamp(task['startedAt']) if task.get('startedAt') else self.now() - timedelta(minutes=10)
        self.task, self.logStart = arns[0], int(startedAt.timestamp() * 1000)
        self.requireCleanLog()
        return task

    def settleRestart(self, minute):
        """A new rollout of the verified revision during the watch (the content release's Domain restart, or a manual refresh; user
        2026-11-05, Domain run 42106678466): wait for it, accept its task as the one to hold the service to, and carry on. A rollout
        of another revision is another deploy and still fails."""
        p, cfg = self.plan, self.config
        self.log('observation ' + str(minute + 1) + ': a restart of the verified revision is rolling out; waiting for it to settle', flush=True)

        def settled():
            counts = serviceCounts(self.call, cfg)
            require(counts and counts['desired'] == 1 and counts['taskDefinition'] == self.revision,
                    'Another deployment replaced the verified revision during observation')
            return counts if counts['running'] == 1 and counts['rolloutState'] == 'COMPLETED' else None
        counts = self.waitFor('restart of ' + p['stack'], settled, 300)
        previous = self.task
        self.acceptTask()
        self.note('restarted-during-observation', minute=minute + 1, by='a restart of the verified revision (the content release or a manual refresh)',
                  previousTask=previous, task=self.task)
        return counts

    def scaleToZero(self):
        """Busy-stack park: DesiredCount 0 on exactly the configured service, outside the stuck stack update."""
        p, cfg = self.plan, self.config
        self.call('ecs', 'update-service', '--cluster', cfg['cluster'], '--service', p['stack'], '--desired-count', '0')
        self.note('scaled-to-zero', stack=p['stack'], stackStatus=p['busyStackStatus'])

    def settleStack(self):
        """Wait until CloudFormation has finished the update or rollback it was stuck in; only an updatable state continues."""
        p = self.plan

        def check():
            state = stackState(self.call, p['stack'])
            status = state['status'] if state else None
            require(status not in TERMINAL_BAD - UPDATABLE, 'Stack ' + p['stack'] + ' ended in ' + str(status)
                    + ' after the scale-down; follow the break-glass runbook (Support runbooks) before another run')
            if status in UPDATABLE:
                return status
            # A rollback restores the previous DesiredCount; scale to zero again until CloudFormation is done.
            counts = serviceCounts(self.call, self.config)
            if counts and counts['desired'] != 0:
                self.scaleToZero()
            return None
        status = self.waitFor('stack ' + p['stack'] + ' to finish its update or rollback', check, BUSY_SETTLE_SECONDS)
        self.note('stack-settled', stack=p['stack'], status=status)

    def requireCleanLog(self):
        cfg = self.config
        pattern = ' '.join('?"' + word + '"' for word in cfg['logFailurePatterns'])
        events = self.call('logs', 'filter-log-events', '--log-group-name', cfg['logGroup'], '--log-stream-name-prefix',
                           cfg['service'] + '/' + cfg['service'] + '/' + self.task.rsplit('/', 1)[-1],
                           '--start-time', str(self.logStart), '--filter-pattern', pattern).get('events', [])
        require(not events, 'Log window contains failure markers: ' + (events[0].get('message', '')[:200] if events else ''))

    def requireSameHealthyTask(self):
        """The task verification accepted is still the only running task (no crash, no replacement), HEALTHY, with a clean log."""
        cfg = self.config
        arns = self.call('ecs', 'list-tasks', '--cluster', cfg['cluster'], '--service-name', self.plan['stack'], '--desired-status', 'RUNNING').get('taskArns', [])
        require(arns == [self.task], 'The verified task ' + self.task.rsplit('/', 1)[-1] + ' was replaced or stopped during observation')
        task = self.call('ecs', 'describe-tasks', '--cluster', cfg['cluster'], '--tasks', self.task)['tasks'][0]
        require(task.get('lastStatus') == 'RUNNING' and task.get('healthStatus') == 'HEALTHY',
                'Task became ' + str(task.get('lastStatus')) + '/' + str(task.get('healthStatus')) + ' during observation')
        self.requireCleanLog()

    def requireOutsideBlackout(self):
        requireOutsideBlackout(self.plan['desiredCount'], self.now(), 'at execution')

    def offHours(self):
        zone, start, end = OFF_HOURS
        return start <= self.now().astimezone(ZoneInfo(zone)).hour < end

    def stopAgainForOffHours(self):
        """A deploy in DEV's off hours leaves the service as it found it: stopped (user 2026-11-05). Only this application's service is
        touched, directly as `Application · Start or stop DEV` does; the stack keeps DesiredCount 1, so the 10:00 start serves it again."""
        if not (self.parkOffHours and self.wasStopped and self.offHours()):
            return
        p, cfg = self.plan, self.config
        self.call('ecs', 'update-service', '--cluster', cfg['cluster'], '--service', p['stack'], '--desired-count', '0')
        counts = self.waitFor('ECS scale-in of ' + p['stack'],
                              lambda: (lambda c: c if c and c['running'] == 0 and c['desired'] == 0 else None)(serviceCounts(self.call, cfg)), 300)
        self.note('stopped-for-off-hours', stack=p['stack'], counts=counts, window=OFF_HOURS[0] + ' ' + str(OFF_HOURS[1]) + ':00-' + str(OFF_HOURS[2]) + ':00')

    def verifyParked(self):
        counts = self.waitFor('ECS scale-in of ' + self.plan['stack'],
                              lambda: (lambda c: c if c and c['running'] == 0 and c['desired'] == 0 else None)(serviceCounts(self.call, self.config)), 300)
        self.note('parked', counts=counts)

    def observe(self, rounds=None):
        """Every minute: the service still runs its one task, the same task verification accepted, HEALTHY, with a clean log. Gateway
        (`probeRequired`) also needs the public probe to answer 200 each round; Web and Domain record one probe at the end, never a gate."""
        p, cfg = self.plan, self.config
        rounds = rounds or p['observationMinutes']
        required = p['probeRequired']
        status = None
        for minute in range(rounds):
            counts = serviceCounts(self.call, cfg)
            if counts and counts['desired'] == 0 and minute > 0:
                # DesiredCount 0 is set only by the start/stop switch or the scheduled stop, never by a crash (a crash keeps
                # the desired count at 1); the revision verified clean and served every round so far.
                self.note('stopped-during-observation', minutes=minute, by='the start/stop switch or the scheduled stop',
                          note='the verified revision served ' + str(minute) + ' clean minute(s); the next start serves it again')
                return
            if counts and counts['desired'] == 1 and counts['rolloutState'] == 'IN_PROGRESS':
                # A new PRIMARY deployment while the desired count is 1: a restart of this revision is waited for; another revision is
                # another deploy. A crash never starts a deployment (the rollout state stays COMPLETED while ECS replaces the task).
                require(counts['taskDefinition'] == self.revision, 'Another deployment replaced the verified revision during observation')
                counts = self.settleRestart(minute)
            require(counts and counts['running'] == 1 and counts['rolloutState'] == 'COMPLETED', 'Service stopped serving during observation')
            self.requireSameHealthyTask()
            if required:
                status = self.fetch(p['probeUrl'])
                require(status == 200, 'Public probe of ' + p['probeUrl'] + ' returned ' + ('no answer' if status is None else str(status)) + ' during observation')
            self.log('observation ' + str(minute + 1) + '/' + str(rounds) + ': running ' + str(counts['running']) + ', task healthy'
                     + (', probe ' + str(status) if required else ''), flush=True)
            if minute < rounds - 1:
                self.sleep(60)
        if not required:
            status = self.fetch(p['probeUrl'])
        self.note('observed', minutes=rounds, probe=p['probeUrl'], probeStatus=status, probeRequired=required,
                  publicPage='answered ' + str(status) if status is not None else 'no answer (another application may be stopped); not a gate for this application')

    def run(self):
        outcome = {'schema': SCHEMA_RESULT, 'runId': self.plan['runId'], 'planDigest': self.plan['planDigest'], 'app': self.plan['app'],
                   'releaseVersion': self.plan['releaseVersion'], 'image': self.plan['image'], 'desiredCount': self.plan['desiredCount'], 'stack': self.plan['stack']}
        try:
            require(self.mode in self.plan['choices'], 'Approved mode ' + str(self.mode) + ' is not one this plan offers')
            outcome['mode'] = self.mode
            if self.mode != 'verify':
                self.requireOutsideBlackout()
            self.identity()
            if self.mode != 'verify' and self.plan['desiredCount'] == 1:
                before = serviceCounts(self.call, self.config)
                self.wasStopped = bool(before) and before['desired'] == 0
            if self.mode == 'verify':
                self.note('unchanged', stack=self.plan['stack'], verifyOnly=True)
            else:
                if self.plan.get('busyStackPark'):
                    self.scaleToZero()
                    self.settleStack()
                if self.mode == 'refresh':
                    self.note('refresh', stack=self.plan['stack'], refreshToken=self.plan['refreshToken'])
                if self.changeSet() is not None:
                    self.execute()
            if self.plan['desiredCount'] == 1:
                self.verifyServing()
                if self.mode == 'refresh':
                    self.observe(self.plan['refreshObservationMinutes'])
                elif self.mode == 'deploy':
                    self.observe(self.observeRounds)
            else:
                self.verifyParked()
            self.stopAgainForOffHours()
            outcome['outcome'] = 'success'
        except Exception as error:
            outcome.update({'outcome': 'failure', 'error': str(error)[:500]})
            if not any(step['step'] == 'stopped-for-off-hours' for step in self.steps):
                try:  # a failed night-time deploy does not leave DEV running either; the original error stays the outcome
                    self.stopAgainForOffHours()
                except Exception as stop:
                    self.note('off-hours-stop-failed', error=str(stop)[:200])
            raise
        finally:
            outcome.update({'steps': self.steps, 'finishedAt': self.now().isoformat()})
            self.outcome = outcome
        return outcome


def probe(url, opener=urllib.request.urlopen):
    """The HTTP status, or None when nothing answered (timeout, refused connection, DNS); the caller decides what that means."""
    require(url.startswith('https://'), 'https probe URL required')
    try:
        with opener(urllib.request.Request(url, headers={'User-Agent': 'lookahead-deploy-probe'}), timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        return error.code
    except (urllib.error.URLError, OSError):
        return None


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
    deployer = Deployer(planned, config, templateText, call, sleep=sleep, fetch=fetch, mode=receipt.get('mode', 'deploy'))
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
