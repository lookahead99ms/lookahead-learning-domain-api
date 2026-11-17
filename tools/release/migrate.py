"""`07 · Migrate DEV data` (DLV-970): one owner approval, one Fargate task from the published Domain image of a release that runs the
DynamoDB migration entrypoint (`migrate`, `verify` or `status`) under the migration-task role, before the application of the same
release is deployed ("data first, then app"; no pipeline gate: the running release keeps serving while the data moves ahead).
Domain only; the other application repositories have no copy of this tool.

The task definition family is `lookahead-dev-domain-migrate`, never the service's own; the cluster, subnets, security group and
log group are the service's (deployment/dev/deploy-config.json) and the four table names are the configuration's pinned DEV
names. One approved run = one `ecs register-task-definition` (the image digest, the two roles, awslogs), one `ecs run-task`,
a wait for the task to stop, the exit code from `ecs describe-tasks` and the one JSON report line from CloudWatch Logs
(`logs get-log-events`). The service, its stack, its task definition family and the tables' schemas are never touched by this
tool; `verify` and `status` run the same task with that command and must record no ledger change. AWS is reached only through
the `aws` CLI with the credentials the workflow assumed (sessions `domain-deploy-migrate-plan-<run>-1` and
`domain-deploy-migrate-<run>-1` of `lookahead-dev-deploy`).
"""
import argparse
import hashlib
import json
import os
import re
import time

import deploy
import issue_template as issueTemplate
import owner_approval as owner
import release_version as versions

WORKFLOW = '.github/workflows/migrate-dev.yml'
SCHEMA_PLAN = 'app-migrate-plan/v1'
SCHEMA_RESULT = 'app-migrate-result/v1'
COMMANDS = ('migrate', 'verify', 'status')
FAMILY = 'lookahead-dev-domain-migrate'
CONTAINER = 'migrate'
LOG_PREFIX = 'migrate'
# The six-role model: the migration task runs as the migration-task role (the only writer of the ledger); the execution role pulls
# the image and writes the log; the workflow itself runs as the deploy role.
MIGRATION_TASK_ROLE = 'lookahead-dev-migration-task'
MAIN_CLASS = 'com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoMigration'
ENTRY_POINT = ['java', '-cp', '/opt/lookahead/app.jar', '-Dloader.main=' + MAIN_CLASS, 'org.springframework.boot.loader.launch.PropertiesLauncher']
JAVA_TOOL_OPTIONS = '-Djava.security.properties=/opt/lookahead/dns.security -Djdk.httpclient.keepalive.timeout=30 -Djdk.httpclient.disableRetryConnect=true'
TABLE_VARIABLES = {'UserAccountDetailsTable': 'LOOKAHEAD_DYNAMODB_USER_ACCOUNT_DETAILS_TABLE', 'AdministratorAuditEventsTable': 'LOOKAHEAD_DYNAMODB_ADMINISTRATOR_AUDIT_EVENTS_TABLE',
                   'UserStudyPlanDetailsTable': 'LOOKAHEAD_DYNAMODB_USER_STUDY_PLAN_DETAILS_TABLE', 'ProtectedOwnerIdentityTable': 'LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE'}
# The ledger lives in the Protected Owner Identity table under one partition key: applied steps, the CURRENT watermark and the LOCK.
LEDGER_PARTITION = 'DATA_VERSION#DOMAIN'
LEDGER_ITEMS = (('STEP#<ten-digit version>', 'one immutable item per applied step with its checksum'),
                ('CURRENT', 'the highest contiguous verified data version'),
                ('LOCK', 'the fenced migration lock, taken for the run and released at its end'))
STOP_SECONDS = 1800  # a migration task that has not stopped after 30 minutes fails this run; the task itself keeps running
LOG_SECONDS = 120  # the awslogs stream can lag the stop by a few seconds
require = owner.require


def provenance(env, get=owner.api):
    """A fresh owner dispatch of this workflow on main, bound to the frozen release-source record of this run (the same tag
    resolution as a deploy: the image is the published one of the dispatched release)."""
    repository = env.get('GITHUB_REPOSITORY')
    require(repository in owner.PUBLISHERS, 'Unknown application repository')
    app = owner.PUBLISHERS[repository][1]
    require(app == 'domain', 'Only the Domain migrates data')
    expected = {'GITHUB_ACTOR': owner.OWNER, 'GITHUB_TRIGGERING_ACTOR': owner.OWNER, 'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '1',
                'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_WORKFLOW_REF': repository + '/' + WORKFLOW + '@refs/heads/main'}
    require(all(env.get(k) == v for k, v in expected.items()), 'Fresh owner dispatch of 07 · Migrate DEV data from main required')
    sha, runId = env.get('GITHUB_SHA', ''), env.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) is not None and re.fullmatch('[1-9][0-9]*', runId) is not None, 'Exact source/run required')
    metadata = get(repository, '')
    require(metadata.get('full_name') == repository and metadata.get('owner', {}).get('login') == owner.OWNER
            and str(metadata.get('id')) == env.get('GITHUB_REPOSITORY_ID'), 'Repository identity changed')
    versions.mainBinding('workflow_dispatch', sha, get(repository, 'commits/main').get('sha', ''), lambda path: get(repository, path))
    run = get(repository, 'actions/runs/' + runId)
    fields = {'id': int(runId), 'path': WORKFLOW, 'head_sha': sha, 'head_branch': 'main', 'event': 'workflow_dispatch', 'run_attempt': 1, 'status': 'in_progress'}
    require(all(run.get(k) == v for k, v in fields.items())
            and all(run.get(k, {}).get('login') == owner.OWNER for k in ('actor', 'triggering_actor')), 'Run provenance changed')
    record = owner.releaseRecord(env)
    require(record.get('workflowSha') == sha and record.get('repository') == repository and str(record.get('runId')) == runId,
            'Release-source record belongs to another workflow run')
    release = versions.recheck(record, lambda path: get(repository, path), sha, app)
    require(env.get('RELEASE_VERSION') == release['releaseVersion'], 'Dispatched release tag differs from resolved record')
    return {**{k: release[k] for k in versions.FIELDS}, 'repository': repository, 'sourceSha': release['sourceSha'], 'workflowSha': sha, 'runId': runId}


def command(env):
    value = str(env.get('MIGRATE_COMMAND', '')).strip() or 'migrate'
    require(value in COMMANDS, 'MIGRATE_COMMAND must be migrate, verify or status')
    return value


def loadConfig(root):
    """The service's configuration; a migration needs the four pinned table names in it."""
    config = deploy.loadConfig(root)
    require(config['app'] == 'domain' and all(key in config['parameters'] for key in deploy.TABLE_PARAMETERS), 'Domain configuration with the four DynamoDB table names required')
    return config


def environment(config, runId, mode='migrate'):
    """What the entrypoint reads: the environment, the region, the four tables and the run identity it records in the ledger. Since the
    PostgreSQL retirement (2026-11-17) no command reads anything else: data step 5 kept its number but lost its import source."""
    variables = {'LOOKAHEAD_ENVIRONMENT': 'dev', 'AWS_REGION': config['region'], 'GITHUB_RUN_ID': runId, 'JAVA_TOOL_OPTIONS': JAVA_TOOL_OPTIONS}
    variables.update({variable: config['parameters'][parameter] for parameter, variable in TABLE_VARIABLES.items()})
    return variables


def secrets(config, mode):
    """The migration task carries no ECS secret: the PostgreSQL import secret of step 5 was retired with its source (2026-11-17)."""
    return {}


def ledgerRows(mode, table):
    action = 'Modify' if mode == 'migrate' else 'Read only'
    notes = {'migrate': 'written by the entrypoint under the migration-task role: ', 'verify': 'read; a verify records nothing: ', 'status': 'read; a status records nothing: '}
    return [{'action': action, 'type': 'AWS::DynamoDB::Table item', 'consoleName': table + ' · ' + LEDGER_PARTITION + ' / ' + key,
             'note': notes[mode] + meaning, 'pk': LEDGER_PARTITION, 'sk': key} for key, meaning in LEDGER_ITEMS]


def resourceRows(planned):
    mode, tables = planned['command'], planned['tables']
    rows = [{'action': 'Create', 'type': 'AWS::ECS::TaskDefinition', 'consoleName': FAMILY,
             'note': 'new revision: image ' + planned['image'].rsplit('@', 1)[-1][:19] + '…, task role ' + MIGRATION_TASK_ROLE + ', command ' + mode
                     + '; registered revisions stay (ECS keeps them); the service family lookahead-dev-domain is not touched'},
            {'action': 'Run once', 'type': 'AWS::ECS::Task', 'consoleName': FAMILY + ' on ' + planned['clusterName'],
             'note': 'one Fargate task in the service\'s subnets and security group; it runs ' + mode + ' to completion and stops; no service is created or changed'},
            {'action': 'No change', 'type': 'AWS::ECS::Service', 'consoleName': planned['stack'],
             'note': 'the running release keeps serving; deploy this release afterwards (04 · Deploy DEV)'}]
    for parameter, variable in TABLE_VARIABLES.items():
        name = tables[variable]
        if mode == 'migrate':
            note = 'the data steps of this release write here (items only; the table and its schema are Infra\'s)' if parameter != 'ProtectedOwnerIdentityTable' \
                else 'data steps plus the migration ledger below (items only; the table and its schema are Infra\'s)'
        else:
            note = 'read by ' + mode + '; nothing is written'
        rows.append({'action': 'Modify' if mode == 'migrate' else 'Read only', 'type': 'AWS::DynamoDB::Table', 'consoleName': name, 'note': note})
    return rows + ledgerRows(mode, tables['LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE'])


def plan(env, call, root, get=owner.api, now=None):
    release = provenance(env, get)
    config = loadConfig(root)
    mode = command(env)
    image = deploy.publishedImage(call, config, release['imageTag'])
    state = deploy.stackState(call, config['stack'])
    current = state['parameters'] if state and state['status'] != deploy.REVIEW else {}
    params = config['parameters']
    variables = environment(config, release['runId'], mode)
    result = {'schema': SCHEMA_PLAN, **release, 'command': mode, 'image': image, 'account': config['account'], 'region': config['region'],
              'deployRoleArn': config['deployRoleArn'], 'sessionName': config['sessionPrefix'] + '-migrate-' + release['runId'] + '-1',
              'cluster': config['cluster'], 'clusterName': config['clusterName'], 'stack': config['stack'], 'family': FAMILY, 'containerName': CONTAINER,
              'taskRoleArn': 'arn:aws:iam::' + config['account'] + ':role/' + MIGRATION_TASK_ROLE, 'executionRoleArn': params['ExecutionRoleArn'],
              'subnets': params['ApplicationSubnets'].split(','), 'securityGroup': params['ServiceSecurityGroup'],
              'logGroup': config['logGroup'], 'logStreamPrefix': LOG_PREFIX, 'entryPoint': ENTRY_POINT, 'environment': variables, 'secrets': secrets(config, mode),
              'tables': {variable: variables[variable] for variable in TABLE_VARIABLES.values()},
              'ledger': {'table': variables['LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE'], 'partition': LEDGER_PARTITION, 'items': [key for key, _ in LEDGER_ITEMS]},
              'currentDesiredCount': current.get('DesiredCount'), 'currentImage': current.get('ApplicationImage'),
              'configSha256': hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest(), 'plannedAt': deploy.utc(now).isoformat()}
    for key, value in result['environment'].items():
        require(not str(value).startswith('PENDING-'), 'Configuration still carries a placeholder for ' + key)
    result['resources'] = resourceRows(result)
    result['planDigest'] = owner.digest({k: v for k, v in result.items() if k != 'planDigest'})
    return result


def validatePlan(planned, env, root, get=owner.api):
    identity = provenance(env, get)
    require(planned.get('schema') == SCHEMA_PLAN and all(planned.get(k) == v for k, v in identity.items()), 'Migration plan identity changed')
    require(planned.get('planDigest') == owner.digest({k: v for k, v in planned.items() if k != 'planDigest'}), 'Migration plan digest changed')
    require(planned.get('configSha256') == hashlib.sha256(json.dumps(loadConfig(root), sort_keys=True).encode()).hexdigest(), 'Configuration changed since the plan')
    require(planned.get('command') == command(env), 'Dispatched command differs from the plan')
    require(re.fullmatch('sha256:[a-f0-9]{64}', planned.get('image', '').rsplit('@', 1)[-1]) is not None, 'Image digest required')


def commands(planned):
    """One answer: `APPROVE MIGRATE <tag>`, `APPROVE VERIFY <tag>` or `APPROVE STATUS <tag>` (the image tag as ECR shows it)."""
    return {'run': 'APPROVE ' + planned['command'].upper() + ' ' + planned['imageTag']}


def approvalCommand(planned):
    return commands(planned)['run']


def window(planned, env=None):
    return owner.approvalWait(env)


ACTIONS = {'migrate': 'Migrate domain data', 'verify': 'Verify domain data', 'status': 'Read domain data status'}


def headline(planned):
    mode = planned['command']
    change = ('one Fargate task ' + FAMILY + ' applies the data steps of this release to the four DynamoDB tables; the service is not touched' if mode == 'migrate'
              else 'one Fargate task ' + FAMILY + ' runs ' + mode + ' against the four DynamoDB tables; nothing is written')
    return {'env': 'DEV', 'action': ACTIONS[mode], 'subject': planned['imageTag'], 'change': change}


def gist(planned, env=None):
    t = issueTemplate.text
    mode = planned['command']
    head = {'migrate': '**Business change:** the data steps the ' + t(planned['app']) + ' release `' + t(planned['imageTag']) + '` needs are applied to the four DEV DynamoDB tables '
                       'and recorded in the migration ledger, so that `04 · Deploy DEV` can then serve this release (data first, then app). '
                       'The running release keeps serving throughout; no pipeline gate stops it.',
            'verify': '**Business change:** none. The migration entrypoint re-verifies every recorded data step of the DEV ledger against this release; nothing is written.',
            'status': '**Business change:** none. The migration entrypoint reports the ledger\'s CURRENT data version against what this release requires; nothing is written.'}[mode]
    steps = ['1. Recheck the tag, the plan digest and the configuration; then assume `' + t(planned['deployRoleArn']) + '` as session `' + t(planned['sessionName']) + '` (at most 60 minutes).',
             '2. `ecs register-task-definition`: family `' + FAMILY + '`, the published image `' + t(planned['image'].rsplit('@', 1)[-1][:19]) + '…`, task role `'
             + MIGRATION_TASK_ROLE + '`, execution role `' + t(planned['executionRoleArn'].rsplit('/', 1)[-1]) + '`, awslogs to `' + t(planned['logGroup']) + '`, entry point '
             '`java -cp /opt/lookahead/app.jar -Dloader.main=…DomainDynamoMigration org.springframework.boot.loader.launch.PropertiesLauncher ' + mode + '`.',
             '3. `ecs run-task`: one Fargate task on `' + t(planned['clusterName']) + '` in the service\'s subnets and security group, with LOOKAHEAD_ENVIRONMENT=dev, AWS_REGION, '
             'the four table names and GITHUB_RUN_ID=' + t(planned['runId']) + ' (recorded in the ledger as `github-' + t(planned['runId']) + '`); no secret.',
             '4. Wait at most ' + str(STOP_SECONDS // 60) + ' minutes for the task to stop (`ecs describe-tasks`), read its exit code, then the one JSON report line from the log stream '
             '`' + LOG_PREFIX + '/' + CONTAINER + '/<task id>` (`logs get-log-events`): `{"command":"' + mode + '","before":N,"after":M,"required":R,"applied":[…],"verified":[…],"ahead":…}`.',
             '5. The run succeeds only with exit code 0, a report line without `failed`, the command and run identity it names'
             + (' and `after` at `required`.' if mode == 'migrate' else ' and an empty `applied` list (no ledger change).')
             + ' The result artifact of this run holds every step; the issue is closed with the outcome.']
    tail = ('Exit code 1 means the entrypoint failed and the ledger is unchanged past the last recorded step: fix the cause and run again with the same release (the entrypoint '
            'continues from CURRENT). No service, stack, IAM, network or secret changes. Approve within ' + owner.minutesText(window(planned, env))
            + ' minutes of this issue being created; the approval comment is then valid for ' + owner.minutesText(owner.approvalValid(env)) + ' minutes.')
    return head + '\n\n' + '\n'.join(steps) + '\n\n' + tail


def body(planned, receipt, env=None):
    t = issueTemplate.text
    fields = ('releaseVersion', 'imageTag', 'image', 'command', 'family', 'taskRoleArn', 'executionRoleArn', 'sourceSha', 'workflowSha', 'stack',
              'currentDesiredCount', 'sessionName', 'repository', 'account', 'region')
    shown = [('**' + key + '**', owner.commitLink(planned, planned[key]) if key in ('sourceSha', 'workflowSha') else '`' + t(str(planned[key])) + '`')
             for key in fields if planned.get(key) is not None]
    tables = issueTemplate.table(('Variable', 'Table'), [('`' + t(k) + '`', '`' + t(v) + '`') for k, v in planned['tables'].items()])
    mode = planned['command']
    return issueTemplate.render(
        headline=headline(planned), gist=gist(planned, env), resources=planned['resources'],
        overview='**' + ACTIONS[mode] + ' with the published DEV image**\n\nAuthorize the one migration task described above inside this run. Without approval nothing runs in AWS.'
                 + (' The service keeps serving the running release; deploy this release with `04 · Deploy DEV` only after this run succeeded.' if mode == 'migrate' else ''),
        scope='### Migration task\n\n' + issueTemplate.table(('Field', 'Value'), shown) + '\n### Tables the task reads\n\n' + tables,
        validation=[('Release tag ' + t(planned['releaseVersion']), 'resolved to source commit ' + owner.commitLink(planned, planned['sourceSha'])),
                    ('Image', 'ECR tag `' + t(planned['imageTag']) + '` read back as `' + t(planned['image'].rsplit('@', 1)[-1]) + '`'),
                    ('Configuration', '`' + planned['configSha256'][:16] + '…` rechecked before execution'),
                    ('Command', '`' + mode + '`: ' + ('writes data steps and the ledger' if mode == 'migrate' else 'reads only; an applied step in the report fails the run')),
                    ('Service', 'not touched; currently ' + ('DesiredCount `' + t(planned['currentDesiredCount']) + '`' if planned.get('currentDesiredCount') is not None else 'no stack'))],
        evidence='Review the plan artifact of this run before approving.',
        risks='A failed step leaves the ledger unchanged past the last recorded step and the service unaffected; the task is not retried. A recorded step is never '
              'undone: the way back from a data step is a new step, not a flag (DLV-970; PostgreSQL, the former way back, was retired on 2026-11-17). '
              'This is owner approval enforced by workflow code, not independent review.',
        repository=planned['repository'], runId=planned['runId'], owner=owner.OWNER, expires=receipt['expiresAt'], created=receipt.get('createdAt'),
        permissionDeadline='The migration session lasts at most 60 minutes; IAM is not changed.',
        command=approvalCommand(planned), payload=planned)


def approve(planned, env, root, get=owner.api, now=lambda: deploy.utc(), sleep=time.sleep):
    validatePlan(planned, env, root, get)
    start, wait = now().replace(microsecond=0), window(planned, env)
    receipt = {'schema': deploy.SCHEMA_RECEIPT, 'runId': planned['runId'], 'planDigest': planned['planDigest'],
               'createdAt': start.isoformat(), 'expiresAt': (start + wait).isoformat()}
    preview = body(planned, receipt, env)
    require(len(preview) < 60000, 'Approval preview too large')
    issue = get(planned['repository'], 'issues', {'title': issueTemplate.title(headline(planned), planned['runId']), 'body': preview})
    receipt['issue'] = issue['number']
    print('https://github.com/' + planned['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(int(wait.total_seconds()) // 20):
        selected = deploy.selectedApproval(planned, receipt, get, now(), body, commands, window, env=env)
        if selected is not None:
            validatePlan(planned, env, root, get)
            return {**receipt, 'commentId': selected[0], 'mode': selected[1], 'approvedAt': selected[2]}
        sleep(20)
    raise TimeoutError('Migration approval timed out; start a new reviewed run')


def verify(planned, receipt, env, root, get=owner.api, now=None):
    now = now or deploy.utc()
    validatePlan(planned, env, root, get)
    selected = deploy.selectedApproval(planned, receipt, get, now, body, commands, window, recheck=True, env=env)
    require(selected is not None and selected[:2] == (receipt.get('commentId'), receipt.get('mode')), 'Exact live owner approval required')
    owner.stillValid(receipt, selected[2], now, env)


def taskDefinition(planned):
    """The `ecs register-task-definition` input: the service's shape (Fargate, read-only root, /tmp volume) with the migration entry point.
    Logs are blocking on purpose: the report line must never be dropped."""
    p = planned
    return {'family': p['family'], 'requiresCompatibilities': ['FARGATE'], 'networkMode': 'awsvpc', 'cpu': '512', 'memory': '1024',
            'runtimePlatform': {'operatingSystemFamily': 'LINUX', 'cpuArchitecture': 'X86_64'},
            'taskRoleArn': p['taskRoleArn'], 'executionRoleArn': p['executionRoleArn'], 'volumes': [{'name': 'applicationTmp'}],
            'containerDefinitions': [{'name': p['containerName'], 'image': p['image'], 'user': '10001:10001', 'readonlyRootFilesystem': True,
                                      'linuxParameters': {'capabilities': {'drop': ['ALL']}}, 'essential': True,
                                      'entryPoint': list(p['entryPoint']), 'command': [p['command']],
                                      'environment': [{'name': k, 'value': v} for k, v in sorted(p['environment'].items())],
                                      **({'secrets': [{'name': k, 'valueFrom': v} for k, v in sorted(p['secrets'].items())]} if p.get('secrets') else {}),
                                      'mountPoints': [{'sourceVolume': 'applicationTmp', 'containerPath': '/tmp', 'readOnly': False}],
                                      'logConfiguration': {'logDriver': 'awslogs', 'options': {'awslogs-group': p['logGroup'], 'awslogs-region': p['region'],
                                                                                                'awslogs-stream-prefix': p['logStreamPrefix']}}}]}


def reportLine(messages):
    """The entrypoint's one JSON line (`{"command":…}`) among the task's log messages, the last one when several; None when absent."""
    found = None
    for message in messages:
        text = str(message).strip()
        if text.startswith('{') and '"command"' in text:
            try:
                parsed = json.loads(text, object_pairs_hook=owner.unique)
            except ValueError:
                continue
            if isinstance(parsed, dict) and 'command' in parsed:
                found = parsed
    return found


class Migrator:
    """Executes one approved plan; every AWS step is recorded in `self.steps` and written whatever the outcome."""

    def __init__(self, planned, call, sleep=time.sleep, now=None, log=print):
        self.plan, self.call, self.sleep, self.now, self.log = planned, call, sleep, now or (lambda: deploy.utc()), log
        self.steps, self.outcome = [], None
        self.taskDefinitionArn = self.task = None

    def note(self, step, **detail):
        self.steps.append({'step': step, 'at': self.now().isoformat(), **detail})
        self.log(step + ': ' + json.dumps(detail, sort_keys=True, default=str)[:300], flush=True)

    def waitFor(self, label, check, seconds, every=15):
        for _ in range(max(1, seconds // every)):
            state = check()
            if state is not None:
                return state
            self.sleep(every)
        raise ValueError(label + ' did not complete within ' + str(seconds) + ' seconds')

    def identity(self):
        p = self.plan
        caller = self.call('sts', 'get-caller-identity')
        expected = 'arn:aws:sts::' + p['account'] + ':assumed-role/' + p['deployRoleArn'].rsplit('/', 1)[-1] + '/' + p['sessionName']
        require(caller.get('Arn') == expected and caller.get('Account') == p['account'], 'Migration session identity mismatch')
        self.note('identity', arn=expected)

    def register(self):
        registered = self.call('ecs', 'register-task-definition', '--cli-input-json', json.dumps(taskDefinition(self.plan), sort_keys=True)).get('taskDefinition', {})
        arn = str(registered.get('taskDefinitionArn', ''))
        require(arn.split('/')[-1].startswith(self.plan['family'] + ':') and registered.get('status', 'ACTIVE') == 'ACTIVE', 'Registered task definition is not a revision of ' + self.plan['family'])
        self.taskDefinitionArn = arn
        self.note('registered', taskDefinition=arn, revision=arn.rsplit(':', 1)[-1], family=self.plan['family'])

    def start(self):
        p = self.plan
        network = {'awsvpcConfiguration': {'subnets': p['subnets'], 'securityGroups': [p['securityGroup']], 'assignPublicIp': 'ENABLED'}}
        started = self.call('ecs', 'run-task', '--cluster', p['cluster'], '--task-definition', self.taskDefinitionArn, '--launch-type', 'FARGATE',
                            '--platform-version', '1.4.0', '--count', '1', '--started-by', 'migrate-' + p['runId'], '--network-configuration', json.dumps(network))
        tasks, failures = started.get('tasks') or [], started.get('failures') or []
        require(len(tasks) == 1 and not failures, 'Task did not start: ' + json.dumps(failures)[:300])
        self.task = tasks[0]['taskArn']
        self.note('started', task=self.task, command=p['command'])

    def describeTask(self):
        return self.call('ecs', 'describe-tasks', '--cluster', self.plan['cluster'], '--tasks', self.task)['tasks'][0]

    def waitStopped(self):
        task = self.waitFor('migration task ' + self.task.rsplit('/', 1)[-1], lambda: (lambda t: t if t.get('lastStatus') == 'STOPPED' else None)(self.describeTask()), STOP_SECONDS)
        container = next((c for c in task.get('containers', []) if c.get('name') == self.plan['containerName']), {})
        stopped = {'exitCode': container.get('exitCode'), 'stopCode': task.get('stopCode'), 'stoppedReason': str(task.get('stoppedReason', ''))[:300],
                   'containerReason': str(container.get('reason', ''))[:300]}
        digest = self.plan['image'].rsplit('@', 1)[-1]
        require(digest in (str(container.get('imageDigest', '')) + ' ' + str(container.get('image', ''))), 'The task did not run the approved image')
        self.note('stopped', task=self.task, **stopped)
        return stopped

    def report(self):
        p = self.plan
        stream = p['logStreamPrefix'] + '/' + p['containerName'] + '/' + self.task.rsplit('/', 1)[-1]

        def read():
            rows = deploy.tolerant(self.call, 'logs', 'get-log-events', '--log-group-name', p['logGroup'], '--log-stream-name', stream, '--start-from-head',
                                   '--limit', '10000', absent=('ResourceNotFoundException',))
            events = (rows or {}).get('events') or []
            found = reportLine(e.get('message', '') for e in events)
            return (found, len(events)) if found is not None else None
        try:
            found, count = self.waitFor('report line of ' + stream, read, LOG_SECONDS, 10)
        except ValueError:
            self.note('no-report', stream=stream)
            return None
        self.note('report', stream=stream, events=count, report=found)
        return found

    def judge(self, stopped, found):
        p = self.plan
        detail = (found or {}).get('error') or stopped['stoppedReason'] or stopped['containerReason']
        require(stopped['stopCode'] == 'EssentialContainerExited' and stopped['exitCode'] == 0,
                'Migration task ended with stop code ' + str(stopped['stopCode']) + ', exit code ' + str(stopped['exitCode']) + ': ' + str(detail)[:300])
        require(found is not None, 'No JSON report line in the log stream of the task')
        require(not found.get('failed'), 'Migration reported failure: ' + str(found.get('error', ''))[:300])
        require(found.get('command') == p['command'] and found.get('environment') == 'dev', 'Report names another command or environment')
        if p['command'] == 'migrate':
            require(found.get('runId') == 'github-' + p['runId'], 'Report belongs to another run')
            require(isinstance(found.get('after'), int) and isinstance(found.get('required'), int) and found['after'] >= found['required'],
                    'Data version ' + str(found.get('after')) + ' is still behind the required ' + str(found.get('required')))
        else:
            require(found.get('applied') == [], p['command'] + ' must record no ledger change, but applied ' + json.dumps(found.get('applied'))[:200])

    def run(self):
        p = self.plan
        outcome = {'schema': SCHEMA_RESULT, 'runId': p['runId'], 'planDigest': p['planDigest'], 'app': p['app'], 'command': p['command'],
                   'releaseVersion': p['releaseVersion'], 'image': p['image'], 'family': p['family'], 'taskDefinition': None, 'task': None,
                   'exitCode': None, 'stopCode': None, 'stoppedReason': None, 'report': None}
        try:
            self.identity()
            self.register()
            outcome['taskDefinition'] = self.taskDefinitionArn
            self.start()
            outcome['task'] = self.task
            stopped = self.waitStopped()
            outcome.update({k: stopped[k] for k in ('exitCode', 'stopCode', 'stoppedReason')})
            found = self.report()
            outcome['report'] = found
            self.judge(stopped, found)
            if found.get('ahead'):
                self.note('ahead', note='the ledger is ahead of this release; a newer release already migrated DEV (no change made by this run)')
            outcome['outcome'] = 'success'
        except Exception as error:
            outcome.update({'outcome': 'failure', 'error': str(error)[:500]})
            raise
        finally:
            outcome.update({'steps': self.steps, 'finishedAt': self.now().isoformat()})
            self.outcome = outcome
        return outcome


def main(argv=None, env=None, call=None, get=owner.api, sleep=time.sleep):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'approve', 'verify', 'execute'])
    parser.add_argument('--root', default='.', help='repository root holding deployment/dev')
    parser.add_argument('--plan', help='plan.json written by plan')
    parser.add_argument('--receipt', help='approval receipt (approve writes it, verify and execute read it)')
    parser.add_argument('--output', help='plan or result file to write (must not exist)')
    args = parser.parse_args(argv)
    call = call or deploy.client()
    if args.mode == 'plan':
        require(bool(args.output), '--output required')
        result = plan(env, call, args.root, get)
        deploy.write(args.output, result)
        deploy.output(env, plan_digest=result['planDigest'], command=result['command'])
        print(json.dumps({k: result[k] for k in ('releaseVersion', 'image', 'command', 'family', 'planDigest')}, sort_keys=True))
        return result
    require(bool(args.plan and args.receipt), '--plan and --receipt required')
    planned = deploy.readJson(args.plan)
    if args.mode == 'approve':
        receipt = approve(planned, env, args.root, get, sleep=sleep)
        deploy.write(args.receipt, receipt)
        return receipt
    receipt = deploy.readJson(args.receipt)
    verify(planned, receipt, env, args.root, get)
    if args.mode == 'verify':
        print('approval verified for migration plan ' + planned['planDigest'][:16])
        return receipt
    require(bool(args.output), '--output required')
    migrator = Migrator(planned, call, sleep=sleep)
    try:
        return migrator.run()
    finally:
        try:
            deploy.write(args.output, migrator.outcome)
        except OSError as error:  # the outcome matters more than the receipt file
            print('result not written: ' + str(error)[:200])
        print(json.dumps({k: migrator.outcome.get(k) for k in ('outcome', 'command', 'exitCode', 'error')}, sort_keys=True))


if __name__ == '__main__':
    main()
