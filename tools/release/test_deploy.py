"""Tests for the per-application DEV deploy tool (one ECS service per application, ECS rolling updates); AWS and GitHub are
injected fakes, nothing is called for real."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import Mock, patch

import deploy as m
import owner_approval as owner
from test_owner_approval import Github

ROOT = Path(__file__).resolve().parents[2]
APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'
REPOSITORY = {'web': 'lookahead99ms/lookahead-learning-web', 'domain': 'lookahead99ms/lookahead-learning-domain-api', 'gateway-bff': 'lookahead99ms/lookahead-learning-gateway-bff'}[APP]
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
TASK = 'arn:aws:ecs:us-east-2:509614632283:task/lookahead-dev-cluster/0123456789abcdef0123456789abcdef'
# A synthetic Web-shaped configuration (service discovery); the target-group shape (Gateway) is exercised by rewriting it. The
# repository's real deploy-config.json is checked by test_workflow, and the repository's real template is used here for its
# Task/Service shape.
CONFIG = {
    'schema': 'lookahead-app-deploy-config/v3', 'app': APP, 'service': 'web', 'account': '509614632283', 'region': 'us-east-2',
    'imageRepository': '509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr',
    'deployRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-deploy', 'cloudFormationRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-cfn-provision',
    'sessionPrefix': 'web-deploy', 'cluster': 'arn:aws:ecs:us-east-2:509614632283:cluster/lookahead-dev-cluster', 'clusterName': 'lookahead-dev-cluster',
    'stack': 'lookahead-dev-web', 'logGroup': '/lookahead/dev/web', 'namespaceName': 'lookahead-dev.internal',
    'discovery': {'serviceName': 'web', 'serviceId': 'srv-web000000000001', 'serviceArn': 'arn:aws:servicediscovery:us-east-2:509614632283:service/srv-web000000000001'},
    'targetGroupArn': None, 'observationMinutes': 15, 'refreshObservationMinutes': 5, 'probeUrl': 'https://dev.devlookahead.com/',
    'probeRequired': False,
    'parameters': {'Cluster': 'arn:aws:ecs:us-east-2:509614632283:cluster/lookahead-dev-cluster', 'ApplicationSubnets': 'subnet-a,subnet-b', 'ServiceSecurityGroup': 'sg-1',
                   'TaskRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-app-task', 'ExecutionRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-ecs-execution',
                   'TlsSecretArn': 'arn:aws:secretsmanager:us-east-2:509614632283:secret:lookahead-dev-web-tls-pX1NCN', 'TlsVersion': '98331e44-009a-4d74-a6b5-22124e75c170',
                   'LogGroupName': '/lookahead/dev/web'},
    'logFailurePatterns': ['ERROR', 'startup denied', 'AccessDeniedException']}


class FakeAws:
    """Dispatches on `<service> <operation>`; `stack`/`service` hold what the fake account has for this application."""

    def __init__(self, config, image=None):
        self.config, self.calls = config, []
        self.image = image or DIGEST
        self.stack = None  # {'status', 'parameters'}
        self.service = None  # counts after the last execute
        self.changeSets = {}
        self.health, self.logs, self.discovered, self.targets = 'HEALTHY', [], 1, 'healthy'
        self.session = config['sessionPrefix'] + '-12-1'
        self.changeSetFailure = None
        self.previewOverride = None
        self.rollout = 'COMPLETED'
        self.executeStatus = None  # forces the stack status after execution (circuit breaker rollback)
        self.containerDigest = None  # digest the running container reports when it differs from the task definition
        # Busy-stack park (DLV-969): describe-stacks keeps the busy status for `busyPolls` more reads, then shows `settleStatus`;
        # `restoreDesired` times a stuck rollback puts DesiredCount 1 back; `scaleDenied` makes the direct scale-down fail.
        self.busyPolls, self.settleStatus, self.restoreDesired, self.scaleDenied = None, 'UPDATE_COMPLETE', 0, False
        # Observation (DLV-968): `onRound` runs at each list-tasks read, so a test can crash, replace or degrade the task mid-watch.
        self.taskArns, self.onRound, self.rounds = None, None, 0

    def __call__(self, *args):
        self.calls.append(args)
        key = args[0] + ' ' + args[1]
        opts, i = {}, 2
        while i < len(args):
            if args[i].startswith('--') and i + 1 < len(args) and not str(args[i + 1]).startswith('--'):
                opts[args[i]] = args[i + 1]; i += 2
            else:
                opts[args[i]] = True; i += 1
        if key == 'sts get-caller-identity':
            return {'Account': self.config['account'], 'Arn': 'arn:aws:sts::' + self.config['account'] + ':assumed-role/lookahead-dev-deploy/' + self.session}
        if key == 'ecr batch-get-image':
            return {'images': [{'imageId': {'imageDigest': self.image, 'imageTag': opts['--image-ids'].split('=')[1]}}], 'failures': []} if self.image else {'images': [], 'failures': [{'failureCode': 'ImageNotFound'}]}
        if key == 'cloudformation describe-stacks':
            assert opts['--stack-name'] == self.config['stack'], opts
            if self.stack is None:
                raise RuntimeError('aws cloudformation describe-stacks failed: Stack with id ' + opts['--stack-name'] + ' does not exist')
            if self.busyPolls is not None and self.stack['status'] in m.BUSY_PARKABLE:
                if self.busyPolls <= 0:
                    self.stack['status'] = self.settleStatus
                self.busyPolls -= 1
            return {'Stacks': [{'StackStatus': self.stack['status'], 'StackId': 'id-' + opts['--stack-name'],
                                'Parameters': [{'ParameterKey': k, 'ParameterValue': v} for k, v in self.stack['parameters'].items()]}]}
        if key == 'servicediscovery list-instances':
            assert opts['--service-id'] == self.config['discovery']['serviceId'], opts
            return {'Instances': [{'Id': 'i'}] * self.discovered}
        if key == 'ecs describe-services':
            assert opts['--services'] == self.config['stack'], opts
            if self.service is None:
                return {'services': [], 'failures': [{'reason': 'MISSING'}]}
            if self.restoreDesired and self.stack and self.stack['status'] in m.BUSY_PARKABLE:
                self.service['desired'] = 1; self.restoreDesired -= 1
            return {'services': [{'status': 'ACTIVE', 'desiredCount': self.service['desired'], 'runningCount': self.service['running'],
                                  'deployments': [{'status': 'PRIMARY', 'rolloutState': self.service['rolloutState'], 'taskDefinition': 'td'}]}]}
        if key == 'ecs update-service':
            assert (opts['--cluster'], opts['--service'], opts['--desired-count']) == (self.config['cluster'], self.config['stack'], '0'), opts
            if self.scaleDenied:
                raise RuntimeError('aws ecs update-service failed: AccessDeniedException: not authorized to perform: ecs:UpdateService')
            self.service = {'desired': 0, 'running': 0, 'rolloutState': 'COMPLETED'}
            return {'service': {'desiredCount': 0}}
        if key == 'ecs list-tasks':
            self.rounds += 1
            if self.onRound:
                self.onRound(self.rounds)
            if self.taskArns is not None:
                return {'taskArns': self.taskArns}
            return {'taskArns': [TASK] if self.service and self.service['running'] else []}
        if key == 'ecs describe-tasks':
            running = self.stack['parameters']['ApplicationImage']
            pulled = self.containerDigest or running.rsplit('@', 1)[-1]
            return {'tasks': [{'healthStatus': self.health, 'lastStatus': 'RUNNING', 'startedAt': '2026-11-01T21:00:00+00:00',
                               'taskDefinitionArn': 'arn:aws:ecs:us-east-2:509614632283:task-definition/' + self.config['stack'] + ':3',
                               'containers': [{'name': self.config['service'], 'image': running.rsplit('@', 1)[0] + '@' + pulled, 'imageDigest': pulled, 'lastStatus': 'RUNNING'}]}]}
        if key == 'logs filter-log-events':
            return {'events': self.logs}
        if key == 'elbv2 describe-target-health':
            return {'TargetHealthDescriptions': [{'TargetHealth': {'State': self.targets}}]}
        if key == 'cloudformation create-change-set':
            name = opts['--change-set-name']
            assert opts['--stack-name'] == self.config['stack'] and '--template-body' in opts and '--tags' in opts, opts
            self.tags = {row['Key']: row['Value'] for row in json.loads(opts['--tags'])}
            params = {r['ParameterKey']: r['ParameterValue'] for r in json.loads(opts['--parameters'])}
            create = opts['--change-set-type'] == 'CREATE'
            if not create and self.stack is None:
                raise RuntimeError('aws cloudformation create-change-set failed: Stack [' + opts['--stack-name'] + '] does not exist')
            if create:
                changes = [{'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': l, 'Action': 'Add', 'ResourceType': t, 'Replacement': None}}
                           for l, t in (('Task', 'AWS::ECS::TaskDefinition'), ('Service', 'AWS::ECS::Service'))]
            else:
                changed = {k for k, v in params.items() if self.stack['parameters'].get(k) != v}
                if not changed:
                    self.changeSets[name] = {'Status': 'FAILED', 'StatusReason': "The submitted information didn't contain changes."}
                    return {'Id': 'cs-' + name}
                changes = []
                if changed - {'DesiredCount'}:
                    changes.append({'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Task', 'Action': 'Modify', 'ResourceType': 'AWS::ECS::TaskDefinition', 'Replacement': 'True'}})
                changes.append({'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Service', 'Action': 'Modify', 'ResourceType': 'AWS::ECS::Service', 'Replacement': 'False'}})
            if self.previewOverride:
                changes = self.previewOverride
            status = self.changeSetFailure or 'CREATE_COMPLETE'
            self.changeSets[name] = {'Status': status, 'StatusReason': 'forced' if status == 'FAILED' else '', 'Changes': changes, 'apply': (params, create)}
            return {'Id': 'cs-' + name}
        if key == 'cloudformation describe-change-set':
            return self.changeSets[opts['--change-set-name']]
        if key == 'cloudformation execute-change-set':
            params, create = self.changeSets[opts['--change-set-name']]['apply']
            if self.executeStatus:  # the ECS circuit breaker rolled the service back; CloudFormation reports the rollback
                self.stack = {'status': self.executeStatus, 'parameters': self.stack['parameters'] if self.stack else {}}
                return None
            merged = dict(self.stack['parameters'] if self.stack else {}); merged.update(params)
            self.stack = {'status': 'CREATE_COMPLETE' if create else 'UPDATE_COMPLETE', 'parameters': merged}
            self.service = {'desired': int(merged['DesiredCount']), 'running': int(merged['DesiredCount']), 'rolloutState': self.rollout}
            return None
        raise AssertionError('unexpected aws call ' + key)

    def serving(self, image='old', count='1'):
        """The stack as a previous deploy left it."""
        params = dict(CONFIG['parameters']); params.update({'ApplicationImage': self.config['imageRepository'] + '@sha256:' + image[0] * 64, 'DesiredCount': count,
                                                           'RefreshToken': 'none', 'DiscoveryServiceArn': self.config['discovery']['serviceArn']} if self.config['discovery'] else
                                                          {'ApplicationImage': self.config['imageRepository'] + '@sha256:' + image[0] * 64, 'DesiredCount': count,
                                                           'RefreshToken': 'none', 'TargetGroupArn': self.config['targetGroupArn']})
        self.stack = {'status': 'UPDATE_COMPLETE', 'parameters': params}
        self.service = {'desired': int(count), 'running': int(count), 'rolloutState': 'COMPLETED'}

    def ops(self, service, operation):
        return [c for c in self.calls if c[0] == service and c[1] == operation]


class DeployFixture(unittest.TestCase):
    app, repository = APP, REPOSITORY

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / 'deployment/dev').mkdir(parents=True)
        shutil.copy(ROOT / 'deployment/dev/service.template.json', self.root / 'deployment/dev/service.template.json')
        self.config = json.loads(json.dumps(CONFIG))
        self.writeConfig()
        self.github = Github(self.repository, SHA)
        self.github.env['GITHUB_WORKFLOW_REF'] = self.repository + '/' + m.WORKFLOW + '@refs/heads/main'
        self.github.data['actions/runs/12']['path'] = m.WORKFLOW
        self.env = dict(self.github.env)
        self.run = self.env['GITHUB_RUN_ID']
        self.aws = FakeAws(self.config)
        self.now = self.github.now  # a fixed datetime; callables wrap it

    def writeConfig(self):
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(self.config))

    def clock(self):
        return self.github.now

    def get(self, repository, path, payload=None):
        return self.github.get(repository, path, payload)

    def planned(self, count=None, answer=None):
        env = dict(self.env)
        if count is not None:
            env['DEPLOY_DESIRED_COUNT'] = count
        result = m.plan(env, self.aws, self.root, self.get, now=self.now)
        stamp = self.now.replace(microsecond=0).isoformat()
        # the fake owner answers the plan's primary command (DEPLOY, or REFRESH for a duplicate image) unless a mode is named
        command = m.approvalCommands(result)[answer or result['choices'][0]]
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': command, 'created_at': stamp, 'updated_at': stamp}
        return result

    def approved(self, planned):
        return m.approve(planned, self.env, self.root, self.get, now=self.clock, sleep=lambda s: None)

    def deployer(self, planned, **kw):
        template = (self.root / 'deployment/dev/service.template.json').read_text()
        self.logged = []
        return m.Deployer(planned, m.loadConfig(self.root), template, self.aws, sleep=lambda s: None, now=kw.get('now', self.clock), fetch=kw.get('fetch', lambda url: 200),
                          mode=kw.get('mode', planned['choices'][0]), log=lambda line, **_: self.logged.append(line),
                          **({'parkOffHours': kw['parkOffHours']} if 'parkOffHours' in kw else {}))

    def gatewayShape(self):
        """Gateway's configuration: target group, its own DEV gate page through the public entrance, and the probe as a gate."""
        self.config.update({'discovery': None, 'probeRequired': True, 'probeUrl': 'https://dev.devlookahead.com/dev/access',
                            'targetGroupArn': 'arn:aws:elasticloadbalancing:us-east-2:509614632283:targetgroup/lookahead-dev-gateway/0123456789abcdef'})
        self.writeConfig(); self.aws = FakeAws(self.config)


class PlanTests(DeployFixture):
    def test_first_deploy_creates_the_stack_with_one_serving_task(self):
        planned = self.planned()
        self.assertEqual(('lookahead-dev-web', False, False, 1, 'app-12', None, None), (planned['stack'], planned['stackExists'], planned['unchanged'], planned['desiredCount'],
                                                                                        planned['changeSet'], planned['currentImage'], planned['currentDesiredCount']))
        self.assertEqual(self.config['imageRepository'] + '@' + DIGEST, planned['image'])
        self.assertEqual({'ApplicationImage': planned['image'], 'DesiredCount': '1', 'RefreshToken': 'none', 'DiscoveryServiceArn': self.config['discovery']['serviceArn'], **CONFIG['parameters']}, planned['parameters'])
        self.assertEqual([('Create', 'AWS::CloudFormation::Stack'), ('Create', 'AWS::ECS::Service')], [(r['action'], r['type']) for r in planned['resources']])
        self.assertEqual('web-deploy-12-1', planned['sessionName'])
        self.assertEqual(planned['planDigest'], m.digest({k: v for k, v in planned.items() if k != 'planDigest'}))

    def test_later_deploy_is_a_rolling_update_of_the_same_stack_and_names_the_serving_image(self):
        self.aws.serving('old')
        planned = self.planned()
        self.assertEqual((True, False, 'Modify'), (planned['stackExists'], planned['unchanged'], planned['resources'][0]['action']))
        self.assertIn('rolling update', planned['resources'][1]['note'])
        self.assertIn('currently serving sha256:' + 'o' * 12, planned['resources'][1]['note'])
        self.assertEqual(('1', self.config['imageRepository'] + '@sha256:' + 'o' * 64), (planned['currentDesiredCount'], planned['currentImage']))

    def test_same_image_and_count_is_a_recorded_no_op_and_parking_changes_only_the_count(self):
        self.aws.serving('b')
        self.assertTrue(self.planned()['unchanged'])
        parked = self.planned(count='0')
        self.assertEqual((0, False), (parked['desiredCount'], parked['unchanged']))
        self.assertIn('parked at zero tasks', parked['resources'][1]['note'])
        self.assertEqual('Park ' + APP, m.headline(parked)['action'])
        with self.assertRaisesRegex(ValueError, 'must be 1 \\(serve\\) or 0'):
            self.planned(count='2')

    def test_busy_stack_park_scales_down_directly_then_records_the_park(self):
        for status in sorted(m.BUSY_PARKABLE):
            self.aws.serving('old'); self.aws.stack['status'] = status
            parked = self.planned(count='0')
            self.assertEqual((True, status, False, ['deploy'], 0), (parked['busyStackPark'], parked['busyStackStatus'], parked['unchanged'], parked['choices'], parked['desiredCount']))
            self.assertEqual([('Modify', 'AWS::ECS::Service'), ('Modify', 'AWS::CloudFormation::Stack')], [(r['action'], r['type']) for r in parked['resources']])
            self.assertIn('directly', parked['resources'][0]['note'])
            self.assertEqual(self.config['stack'] + ' to zero tasks while the stack is ' + status, m.headline(parked)['change'])
            text = m.gist(parked)
            for phrase in ('ecs update-service', 'ecs:UpdateService', '30 minutes', 'UPDATE_ROLLBACK_FAILED', 'desired_count 1'):
                self.assertIn(phrase, text)
            stamp = self.now.replace(microsecond=0).isoformat()
            self.assertIn('park of a busy stack', m.body(parked, {'createdAt': stamp, 'expiresAt': stamp}))
        self.aws.serving('old')
        self.assertEqual((False, None), (self.planned(count='0')['busyStackPark'], self.planned(count='0')['busyStackStatus']))

    def test_busy_stack_refuses_serving_and_every_other_busy_or_failed_state(self):
        self.aws.serving('old'); self.aws.stack['status'] = 'UPDATE_IN_PROGRESS'
        with self.assertRaisesRegex(ValueError, 'is busy: UPDATE_IN_PROGRESS.*desired_count 0 first to park.*desired_count 1'):
            self.planned()
        for status in ('UPDATE_ROLLBACK_FAILED', 'UPDATE_COMPLETE_CLEANUP_IN_PROGRESS', 'CREATE_IN_PROGRESS', 'DELETE_IN_PROGRESS'):
            self.aws.stack['status'] = status
            with self.assertRaisesRegex(ValueError, 'not updatable: ' + status):
                self.planned(count='0')

    def test_plan_refuses_missing_image_failed_create_other_app_and_placeholders(self):
        self.aws.image = None
        with self.assertRaisesRegex(ValueError, 'not found in ECR'):
            self.planned()
        self.aws.image = DIGEST
        self.aws.stack = {'status': 'ROLLBACK_COMPLETE', 'parameters': {}}
        with self.assertRaisesRegex(ValueError, 'not updatable: ROLLBACK_COMPLETE'):
            self.planned()
        self.aws.stack = {'status': 'UPDATE_ROLLBACK_COMPLETE', 'parameters': {'ApplicationImage': 'old', 'DesiredCount': '1'}}
        self.assertTrue(self.planned()['stackExists'])  # a rolled-back update is still updatable
        self.aws.stack = {'status': 'REVIEW_IN_PROGRESS', 'parameters': {}}
        self.assertFalse(self.planned()['stackExists'])  # an aborted first CREATE is created again
        self.config['discovery']['serviceArn'] = 'PENDING-S2'; self.writeConfig()
        with self.assertRaisesRegex(ValueError, 'placeholder for DiscoveryServiceArn'):
            self.planned()
        self.config['discovery']['serviceArn'] = CONFIG['discovery']['serviceArn']; self.config['app'] = 'other'; self.writeConfig()
        with self.assertRaisesRegex(ValueError, 'another application'):
            self.planned()

    def test_provenance_accepts_the_deploy_workflow_or_a_publishing_caller_and_refuses_strangers(self):
        for key, value in (('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_WORKFLOW_REF', self.repository + '/.github/workflows/ci.yml@refs/heads/main'),
                           ('GITHUB_ACTOR', 'someone'), ('GITHUB_RUN_ATTEMPT', '2')):
            env = dict(self.env); env[key] = value
            with self.assertRaisesRegex(ValueError, 'Fresh owner run of the deploy workflow'):
                m.provenance(env, self.get)
        # 01 · Full calls deploy-dev.yml after publication: the run keeps the caller's workflow ref and path (user decision 2026-11-01).
        env = dict(self.env); env['GITHUB_WORKFLOW_REF'] = self.repository + '/.github/workflows/release.yml@refs/heads/main'
        self.github.data['actions/runs/12']['path'] = '.github/workflows/release.yml'
        self.assertEqual(self.app, m.provenance(env, self.get)['app'])
        env['GITHUB_EVENT_NAME'] = 'push'; self.github.data['actions/runs/12']['event'] = 'push'
        self.assertEqual(self.app, m.provenance(env, self.get)['app'])
        self.github.data['actions/runs/12']['path'] = m.WORKFLOW; self.github.data['actions/runs/12']['event'] = 'workflow_dispatch'
        env = dict(self.env); env['GITHUB_REPOSITORY'] = 'lookahead99ms/other'
        with self.assertRaisesRegex(ValueError, 'Unknown application repository'):
            m.provenance(env, self.get)
        env = dict(self.env); env['RELEASE_VERSION'] = 'v2026.01.01.00.00'
        with self.assertRaisesRegex(ValueError, 'Dispatched release tag differs'):
            m.provenance(env, self.get)

    def test_configuration_and_template_are_validated(self):
        bad = dict(self.config); bad['extra'] = 1
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(bad))
        with self.assertRaisesRegex(ValueError, 'Exact deploy configuration'):
            m.loadConfig(self.root)
        both = dict(self.config); both['targetGroupArn'] = 'arn:aws:elasticloadbalancing:us-east-2:509614632283:targetgroup/x/1'
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(both))
        with self.assertRaisesRegex(ValueError, 'Exactly one of discovery'):
            m.loadConfig(self.root)
        # Only the target-group shape (Gateway) may gate on the public probe; Web and Domain never, so a stopped Gateway cannot fail them.
        for shape in ({'probeRequired': True}, {'probeRequired': 'false'},
                      {'discovery': None, 'probeRequired': False, 'targetGroupArn': 'arn:aws:elasticloadbalancing:us-east-2:509614632283:targetgroup/x/1'}):
            (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps({**self.config, **shape}))
            with self.assertRaisesRegex(ValueError, 'probeRequired is true for Gateway'):
                m.loadConfig(self.root)
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps({**self.config, 'probeUrl': 'http://dev.devlookahead.com/'}))
        with self.assertRaisesRegex(ValueError, 'https probe URL required'):
            m.loadConfig(self.root)
        slotted = dict(self.config); slotted['stack'] = 'lookahead-dev-web-blue'
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(slotted))
        with self.assertRaisesRegex(ValueError, 'Single-service stack name'):
            m.loadConfig(self.root)
        template = json.loads((self.root / 'deployment/dev/service.template.json').read_text())
        template['Resources']['Extra'] = {'Type': 'AWS::S3::Bucket'}
        (self.root / 'deployment/dev/service.template.json').write_text(json.dumps(template))
        with self.assertRaisesRegex(ValueError, 'only Task/Service'):
            m.loadTemplate(self.root)
        template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        template['Parameters']['Slot'] = {'Type': 'String'}
        (self.root / 'deployment/dev/service.template.json').write_text(json.dumps(template))
        with self.assertRaisesRegex(ValueError, 'Single-service template'):
            m.loadTemplate(self.root)


class ApprovalTests(DeployFixture):
    def test_approve_opens_an_issue_naming_the_image_tag_and_returns_the_owner_comment(self):
        planned = self.planned()
        receipt = self.approved(planned)
        self.assertEqual((7, planned['planDigest'], 'app-deploy-approval/v2', 'deploy'), (receipt['commentId'], receipt['planDigest'], receipt['schema'], receipt['mode']))
        issue = self.github.data['issues/3']
        self.assertIn('APPROVE DEPLOY ' + planned['imageTag'], issue['body'])
        self.assertIn('Deploy ' + APP, issue['title'])
        self.assertIn('rolling update', issue['body'].lower())
        self.assertIn('AWS::ECS::Service', issue['body'])
        self.assertNotIn('slot', issue['body'].lower())
        m.verify(planned, receipt, self.env, self.root, self.get, self.now)

    def test_parking_issue_says_nothing_is_deleted(self):
        self.aws.serving('b')
        planned = self.planned(count='0')
        self.approved(planned)
        self.assertIn('Park ' + APP, self.github.data['issues/3']['title'])
        self.assertIn('nothing is deleted', self.github.data['issues/3']['body'])

    def test_verify_rejects_changed_plan_template_or_configuration_and_owner_reject(self):
        planned = self.planned()
        receipt = self.approved(planned)
        tampered = dict(planned); tampered['image'] = self.config['imageRepository'] + '@sha256:' + 'c' * 64
        with self.assertRaisesRegex(ValueError, 'plan digest changed'):
            m.verify(tampered, receipt, self.env, self.root, self.get, self.now)
        template = json.loads((self.root / 'deployment/dev/service.template.json').read_text())
        template['Description'] = 'edited'
        (self.root / 'deployment/dev/service.template.json').write_text(json.dumps(template))
        with self.assertRaisesRegex(ValueError, 'Template changed'):
            m.verify(planned, receipt, self.env, self.root, self.get, self.now)
        shutil.copy(ROOT / 'deployment/dev/service.template.json', self.root / 'deployment/dev/service.template.json')
        self.config['observationMinutes'] = 2; self.writeConfig()
        with self.assertRaisesRegex(ValueError, 'Configuration changed'):
            m.verify(planned, receipt, self.env, self.root, self.get, self.now)
        self.config['observationMinutes'] = 15; self.writeConfig()
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comments = [{'id': 8, 'user': {'login': owner.OWNER}, 'body': 'REJECT', 'created_at': stamp, 'updated_at': stamp}]
        with self.assertRaisesRegex(ValueError, 'Owner rejected'):
            m.verify(planned, receipt, self.env, self.root, self.get, self.now)

    def test_approve_times_out_without_an_owner_comment(self):
        planned = self.planned()
        # A deploy approval waits 10 minutes (a duplicate image 5); the issue says so and the receipt expiry matches.
        self.assertEqual(timedelta(minutes=10), m.approvalWindow(planned))
        self.assertEqual(timedelta(minutes=5), m.approvalWindow({**planned, 'unchanged': True}))
        self.assertIn('Approve within 10 minutes of this issue being created; the approval comment is then valid for 15 minutes.', m.gist(planned))
        self.github.comment = lambda: {'id': 7, 'user': {'login': 'someone'}, 'body': 'APPROVE DEPLOY ' + planned['imageTag'], 'created_at': '2026-01-01T00:00:00+00:00', 'updated_at': '2026-01-01T00:00:00+00:00'}
        sleeps = []
        with self.assertRaises(TimeoutError):
            m.approve(planned, self.env, self.root, self.get, now=self.clock, sleep=sleeps.append)
        self.assertEqual([20] * 30, sleeps)  # 10 minutes of 20-second polls
        # The repository variables tune both windows; the duplicate-image wait stays fixed at 5 minutes.
        tuned = {'APPROVAL_WAIT_MINUTES': '3', 'APPROVAL_VALID_MINUTES': '45'}
        self.assertEqual((timedelta(minutes=3), timedelta(minutes=5)), (m.approvalWindow(planned, tuned), m.approvalWindow({**planned, 'unchanged': True}, tuned)))
        self.assertIn('Approve within 3 minutes of this issue being created; the approval comment is then valid for 45 minutes.', m.gist(planned, tuned))
        sleeps = []
        with self.assertRaises(TimeoutError):
            m.approve(planned, dict(self.env, **tuned), self.root, self.get, now=self.clock, sleep=sleeps.append)
        self.assertEqual([20] * 9, sleeps)
        with self.assertRaisesRegex(ValueError, 'APPROVAL_WAIT_MINUTES must be a whole number of minutes from 2 to 15'):
            m.approve(planned, dict(self.env, APPROVAL_WAIT_MINUTES='16'), self.root, self.get, now=self.clock, sleep=lambda s: None)

    def test_late_approval_is_rechecked_fifteen_minutes_from_the_comment(self):
        planned = self.planned()
        T = self.now; late = (T + timedelta(minutes=9)).isoformat()
        self.github.autoApprove = False
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': 'APPROVE DEPLOY ' + planned['imageTag'], 'created_at': late, 'updated_at': late}
        clock = [T]
        def sleep(seconds):
            clock[0] = T + timedelta(minutes=9); self.github.comments = [self.github.comment()]
        receipt = m.approve(planned, self.env, self.root, self.get, now=lambda: clock[0], sleep=sleep)
        self.assertEqual((7, 'deploy', late), (receipt['commentId'], receipt['mode'], receipt['approvedAt']))
        approvedAt = T + timedelta(minutes=9)
        m.verify(planned, receipt, self.env, self.root, self.get, T + timedelta(minutes=20))  # approval at minute 9 still valid at minute 20
        m.verify(planned, receipt, self.env, self.root, self.get, approvedAt + timedelta(minutes=14, seconds=59))
        with self.assertRaisesRegex(ValueError, 'older than 15 minutes'):
            m.verify(planned, receipt, self.env, self.root, self.get, approvedAt + timedelta(minutes=15))
        legacy = {k: v for k, v in receipt.items() if k != 'approvedAt'}  # receipt without approvedAt: taken from the live comment
        m.verify(planned, legacy, self.env, self.root, self.get, approvedAt + timedelta(minutes=14, seconds=59))
        with self.assertRaisesRegex(ValueError, 'older than 15 minutes'):
            m.verify(planned, legacy, self.env, self.root, self.get, approvedAt + timedelta(minutes=15))
        with self.assertRaisesRegex(ValueError, 'differs from the receipt'):
            m.verify(planned, {**receipt, 'approvedAt': T.isoformat()}, self.env, self.root, self.get, T + timedelta(minutes=20))


class ExecuteTests(DeployFixture):
    def test_first_deploy_creates_the_stack_verifies_and_observes(self):
        planned = self.planned()
        outcome = self.deployer(planned).run()
        self.assertEqual(('success', 1), (outcome['outcome'], outcome['desiredCount']))
        self.assertEqual(['identity', 'change-set', 'executed', 'verified', 'observed'], [s['step'] for s in outcome['steps']])
        create = self.aws.ops('cloudformation', 'create-change-set')[0]
        self.assertEqual(('CREATE', 'app-12'), (create[create.index('--change-set-type') + 1], create[create.index('--change-set-name') + 1]))
        # The stack says what it serves (user 2026-11-05): release tag, deploy run and source commit, next to the environment tag.
        self.assertEqual({'LookAheadEnvironment': 'dev', 'ReleaseVersion': planned['releaseVersion'], 'DeployRun': self.run, 'SourceSha': planned['sourceSha']}, self.aws.tags)
        self.assertEqual(self.aws.stack['parameters']['ApplicationImage'], planned['image'])
        self.assertEqual(1, len(self.aws.ops('servicediscovery', 'list-instances')))
        self.assertEqual([], self.aws.ops('elbv2', 'describe-target-health'))
        self.assertEqual(15, outcome['steps'][-1]['minutes'])

    def test_rolling_update_replaces_the_task_definition_and_keeps_the_service(self):
        self.aws.serving('old')
        planned = self.planned()
        outcome = self.deployer(planned).run()
        self.assertEqual('success', outcome['outcome'])
        changes = outcome['steps'][1]['changes']
        self.assertEqual([('Task', 'Modify', 'True'), ('Service', 'Modify', 'False')], [(c['logical'], c['action'], c['replacement']) for c in changes])
        self.assertEqual(('UPDATE',), tuple(c[c.index('--change-set-type') + 1] for c in self.aws.ops('cloudformation', 'create-change-set')))
        self.assertEqual(planned['image'], self.aws.stack['parameters']['ApplicationImage'])

    def test_duplicate_image_verify_touches_nothing_and_skips_the_observation(self):
        self.aws.serving('b')
        planned = self.planned()
        self.assertEqual((True, ['refresh', 'verify']), (planned['unchanged'], planned['choices']))
        outcome = self.deployer(planned, mode='verify').run()
        self.assertEqual((['identity', 'unchanged', 'verified'], 'verify'), ([s['step'] for s in outcome['steps']], outcome['mode']))
        self.assertEqual([], self.aws.ops('cloudformation', 'create-change-set'))
        self.assertTrue(any(line.startswith('verified: ') for line in self.logged), self.logged)

    def test_duplicate_image_refresh_registers_a_new_revision_on_the_same_image_and_observes_briefly(self):
        self.aws.serving('b')
        planned = self.planned()
        outcome = self.deployer(planned, mode='refresh').run()
        self.assertEqual(['identity', 'refresh', 'change-set', 'executed', 'verified', 'observed'], [s['step'] for s in outcome['steps']])
        self.assertEqual(('run-' + self.run, planned['image'], 5), (self.aws.stack['parameters']['RefreshToken'], self.aws.stack['parameters']['ApplicationImage'], outcome['steps'][-1]['minutes']))
        self.assertEqual([('Task', 'Modify', 'True'), ('Service', 'Modify', 'False')], [(c['logical'], c['action'], c['replacement']) for c in outcome['steps'][2]['changes']])
        self.assertEqual(5, sum(1 for line in self.logged if line.startswith('observation ')))

    def test_a_mode_the_plan_does_not_offer_is_refused_before_any_aws_call(self):
        planned = self.planned()
        deployer = self.deployer(planned, mode='refresh')
        with self.assertRaisesRegex(ValueError, 'not one this plan offers'):
            deployer.run()
        self.assertEqual([], self.aws.ops('cloudformation', 'create-change-set'))

    def test_parking_scales_the_service_to_zero_without_a_probe(self):
        self.aws.serving('b')
        probes = []
        outcome = self.deployer(self.planned(count='0'), fetch=lambda url: probes.append(url) or 200).run()
        self.assertEqual(['identity', 'change-set', 'executed', 'parked'], [s['step'] for s in outcome['steps']])
        self.assertEqual(([('Service', 'Modify', 'False')], 0, []), ([(c['logical'], c['action'], c['replacement']) for c in outcome['steps'][1]['changes']], self.aws.service['desired'], probes))

    def busyPark(self, status='UPDATE_ROLLBACK_IN_PROGRESS'):
        self.aws.busyPolls, self.aws.settleStatus = None, 'UPDATE_COMPLETE'
        self.aws.serving('old'); self.aws.stack['status'] = status
        planned = self.planned(count='0')
        self.aws.busyPolls = 2
        return planned

    def test_busy_stack_park_scales_to_zero_waits_for_the_stack_then_records_the_park(self):
        planned = self.busyPark()
        self.aws.settleStatus, self.aws.restoreDesired = 'UPDATE_ROLLBACK_COMPLETE', 1  # the rollback puts DesiredCount 1 back once
        outcome = self.deployer(planned).run()
        self.assertEqual('success', outcome['outcome'])
        self.assertEqual(['identity', 'scaled-to-zero', 'scaled-to-zero', 'stack-settled', 'change-set', 'executed', 'parked'], [s['step'] for s in outcome['steps']])
        self.assertEqual('UPDATE_ROLLBACK_COMPLETE', outcome['steps'][3]['status'])
        scale = ('ecs', 'update-service', '--cluster', self.config['cluster'], '--service', self.config['stack'], '--desired-count', '0')
        self.assertEqual([scale, scale], self.aws.ops('ecs', 'update-service'))
        first = self.aws.calls.index(scale)
        self.assertLess(first, self.aws.calls.index(self.aws.ops('cloudformation', 'create-change-set')[0]))
        sent = {r['ParameterKey']: r['ParameterValue'] for r in json.loads(dict(zip(self.aws.ops('cloudformation', 'create-change-set')[0][::1], self.aws.ops('cloudformation', 'create-change-set')[0][1:]))['--parameters'])}
        self.assertEqual(('0', 0, 0), (sent['DesiredCount'], self.aws.service['desired'], self.aws.service['running']))
        self.assertEqual('UPDATE_COMPLETE', self.aws.stack['status'])

    def test_busy_stack_park_stops_on_rollback_failed_timeout_or_denied_scale_down(self):
        planned = self.busyPark()
        self.aws.settleStatus = 'UPDATE_ROLLBACK_FAILED'
        with self.assertRaisesRegex(ValueError, 'ended in UPDATE_ROLLBACK_FAILED after the scale-down; follow the break-glass runbook'):
            self.deployer(planned).run()
        self.assertEqual([], self.aws.ops('cloudformation', 'create-change-set'))
        planned = self.busyPark('UPDATE_IN_PROGRESS')
        self.aws.busyPolls = 10 ** 6
        with self.assertRaisesRegex(ValueError, 'did not complete within 1800 seconds'):
            self.deployer(planned).run()
        self.assertEqual([], self.aws.ops('cloudformation', 'create-change-set'))
        planned = self.busyPark()
        self.aws.scaleDenied = True
        deployer = self.deployer(planned)
        with self.assertRaisesRegex(RuntimeError, 'not authorized to perform: ecs:UpdateService'):
            deployer.run()
        self.assertEqual(('failure', []), (deployer.outcome['outcome'], self.aws.ops('cloudformation', 'create-change-set')))

    def test_circuit_breaker_rollback_fails_the_run_and_the_previous_revision_keeps_serving(self):
        self.aws.serving('old')
        self.aws.executeStatus = 'UPDATE_ROLLBACK_COMPLETE'
        deployer = self.deployer(self.planned())
        with self.assertRaisesRegex(ValueError, 'ended in UPDATE_ROLLBACK_COMPLETE.*rolled the service back'):
            deployer.run()
        self.assertEqual('failure', deployer.outcome['outcome'])
        self.assertEqual(('sha256:' + 'o' * 64, 1), (self.aws.stack['parameters']['ApplicationImage'].rsplit('@', 1)[-1], self.aws.service['running']))
        self.assertEqual([], self.aws.ops('cloudformation', 'delete-stack'))

    def test_image_check_reads_the_running_container_digest_and_never_describes_the_task_definition(self):
        outcome = self.deployer(self.planned()).run()
        self.assertEqual('success', outcome['outcome'])
        self.assertEqual([], self.aws.ops('ecs', 'describe-task-definition'))
        self.aws.containerDigest = 'sha256:' + 'f' * 64
        self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'Running task does not carry the approved image'):
            self.deployer(self.planned()).run()
        self.assertEqual([], self.aws.ops('ecs', 'describe-task-definition'))

    def test_unhealthy_task_log_markers_or_missing_discovery_fail_verification(self):
        self.aws.health = 'UNHEALTHY'
        with self.assertRaisesRegex(ValueError, 'task health did not complete'):
            self.deployer(self.planned()).run()
        self.aws.health = 'HEALTHY'; self.aws.logs = [{'message': 'ERROR startup denied'}]
        self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'Log window contains failure markers: ERROR startup denied'):
            self.deployer(self.planned()).run()
        self.aws.logs = []; self.aws.discovered = 0
        self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'Service discovery lists no web instance'):
            self.deployer(self.planned()).run()
        self.aws.discovered = 1; self.aws.rollout = 'IN_PROGRESS'
        self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'ECS rollout of lookahead-dev-web did not complete'):
            self.deployer(self.planned()).run()

    def test_probe_failure_or_lost_task_during_observation_fails_the_run(self):
        self.gatewayShape()
        planned = self.planned()
        with self.assertRaisesRegex(ValueError, 'Public probe of https://dev.devlookahead.com/dev/access returned 503'):
            self.deployer(planned, fetch=lambda url: 503).run()
        self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'returned no answer during observation'):
            self.deployer(self.planned(), fetch=lambda url: None).run()
        self.aws.stack = None; self.aws.service = None
        rounds = []
        def flaky(url):
            rounds.append(url)
            if len(rounds) == 2:
                self.aws.service['running'] = 0
            return 200
        with self.assertRaisesRegex(ValueError, 'stopped serving during observation'):
            self.deployer(self.planned(), fetch=flaky).run()

    def test_change_set_preview_must_match_the_plan_and_identity_must_match_the_session(self):
        self.aws.previewOverride = [{'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Service', 'Action': 'Modify', 'ResourceType': 'AWS::ECS::Service', 'Replacement': 'True'}}]
        with self.assertRaisesRegex(ValueError, 'preview differs from the approved plan'):
            self.deployer(self.planned()).run()
        self.aws.previewOverride = None; self.aws.changeSetFailure = 'FAILED'
        with self.assertRaisesRegex(ValueError, 'Change set app-12 failed: forced'):
            self.deployer(self.planned()).run()
        self.aws.changeSetFailure = None; self.aws.session = 'someone-else'
        with self.assertRaisesRegex(ValueError, 'Deploy session identity mismatch'):
            self.deployer(self.planned()).run()

    def test_target_group_shape_checks_target_health_instead_of_discovery(self):
        self.gatewayShape()
        planned = self.planned()
        self.assertEqual(self.config['targetGroupArn'], planned['parameters']['TargetGroupArn'])
        self.assertNotIn('DiscoveryServiceArn', planned['parameters'])
        outcome = self.deployer(planned).run()
        self.assertEqual(('success', 1, None), (outcome['outcome'], outcome['steps'][3]['targets'], outcome['steps'][3]['discovered']))
        self.assertEqual([], self.aws.ops('servicediscovery', 'list-instances'))
        self.aws.targets = 'unhealthy'; self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'target health did not complete'):
            self.deployer(self.planned()).run()


EASTERN_3AM = datetime(2026, 11, 5, 8, 0, tzinfo=timezone.utc)  # 03:00 in New York (EST): DEV's off hours
EASTERN_2PM = datetime(2026, 11, 5, 19, 0, tzinfo=timezone.utc)  # 14:00 in New York: on hours


class IndependentDeployTests(DeployFixture):
    """DLV-968 (user 2026-11-05): each application deploys on its own, whether the other two run or are stopped."""

    def stoppedBySwitch(self):
        """The stack serves an older image at DesiredCount 1, but `Application · Start or stop DEV` set the service to zero."""
        self.aws.serving()
        self.aws.service = {'desired': 0, 'running': 0, 'rolloutState': 'COMPLETED'}

    def test_web_and_domain_pass_with_the_public_page_down_and_only_record_it(self):
        probes = []
        outcome = self.deployer(self.planned(), fetch=lambda url: probes.append(url) or None).run()
        self.assertEqual('success', outcome['outcome'])
        observed = outcome['steps'][-1]
        self.assertEqual(('observed', None, False), (observed['step'], observed['probeStatus'], observed['probeRequired']))
        self.assertIn('not a gate for this application', observed['publicPage'])
        self.assertEqual(['https://dev.devlookahead.com/'], probes)  # once, at the end, never per round
        # every round re-read this application's own task: 1 at verification + 15 observation rounds
        self.assertEqual(16, len(self.aws.ops('ecs', 'list-tasks')))
        self.assertEqual(16, len(self.aws.ops('logs', 'filter-log-events')))
        self.assertTrue(all('task healthy' in line for line in self.logged if line.startswith('observation ')))
        self.aws.stack = None; self.aws.service = None
        self.assertEqual('answered 200', self.deployer(self.planned(), fetch=lambda url: 200).run()['steps'][-1]['publicPage'])

    def test_gateway_probes_its_own_gate_page_every_round(self):
        self.gatewayShape()
        probes = []
        outcome = self.deployer(self.planned(), fetch=lambda url: probes.append(url) or 200).run()
        self.assertEqual(('success', 200, True), (outcome['outcome'], outcome['steps'][-1]['probeStatus'], outcome['steps'][-1]['probeRequired']))
        self.assertEqual(['https://dev.devlookahead.com/dev/access'] * 15, probes)
        self.assertTrue(all(line.endswith('probe 200') for line in self.logged if line.startswith('observation ')))

    def test_a_crash_replacement_unhealthy_task_or_new_log_marker_during_observation_fails_the_run(self):
        replaced = TASK.rsplit('/', 1)[0] + '/' + 'f' * 32
        for change, message in ((lambda aws: setattr(aws, 'taskArns', [replaced]), 'was replaced or stopped during observation'),
                                (lambda aws: setattr(aws, 'taskArns', []), 'was replaced or stopped during observation'),
                                (lambda aws: setattr(aws, 'health', 'UNHEALTHY'), 'Task became RUNNING/UNHEALTHY during observation'),
                                (lambda aws: setattr(aws, 'logs', [{'message': 'ERROR pool exhausted'}]), 'failure markers: ERROR pool exhausted')):
            self.aws = FakeAws(self.config)
            self.aws.onRound = lambda count, change=change: change(self.aws) if count == 3 else None
            with self.assertRaisesRegex(ValueError, message):
                self.deployer(self.planned()).run()

    def test_an_off_hours_deploy_stops_a_service_it_found_stopped(self):
        self.stoppedBySwitch()
        outcome = self.deployer(self.planned(), now=lambda: EASTERN_3AM).run()
        self.assertEqual('success', outcome['outcome'])
        self.assertEqual(['identity', 'change-set', 'executed', 'verified', 'observed', 'stopped-for-off-hours'], [s['step'] for s in outcome['steps']])
        self.assertEqual('America/New_York 0:00-10:00', outcome['steps'][-1]['window'])
        self.assertEqual({'desired': 0, 'running': 0}, {k: self.aws.service[k] for k in ('desired', 'running')})
        self.assertEqual('1', self.aws.stack['parameters']['DesiredCount'])  # the stack keeps serving count; 10:00 start serves it again

    def test_on_hours_running_services_and_recovery_are_never_stopped(self):
        for setup, now, kw in ((self.stoppedBySwitch, EASTERN_2PM, {}),  # daytime: leave it serving
                               (lambda: self.aws.serving(), EASTERN_3AM, {}),  # it was running at night (started by hand): leave it
                               (self.stoppedBySwitch, EASTERN_3AM, {'parkOffHours': False})):  # 05 · Recover DEV ends serving
            self.aws = FakeAws(self.config)
            setup()
            outcome = self.deployer(self.planned(), now=lambda now=now: now, **kw).run()
            self.assertEqual(('success', 'observed', 1), (outcome['outcome'], outcome['steps'][-1]['step'], self.aws.service['running']))
            self.assertEqual([], self.aws.ops('ecs', 'update-service'))

    def test_a_failed_off_hours_deploy_still_stops_the_service_and_keeps_the_original_error(self):
        self.stoppedBySwitch()
        self.aws.onRound = lambda count: setattr(self.aws, 'health', 'UNHEALTHY') if count == 2 else None
        deployer = self.deployer(self.planned(), now=lambda: EASTERN_3AM)
        with self.assertRaisesRegex(ValueError, 'Task became RUNNING/UNHEALTHY'):
            deployer.run()
        self.assertEqual(('failure', 'stopped-for-off-hours'), (deployer.outcome['outcome'], deployer.steps[-1]['step']))
        self.assertIn('UNHEALTHY', deployer.outcome['error'])
        self.aws = FakeAws(self.config)
        self.stoppedBySwitch(); self.aws.scaleDenied = True
        self.aws.onRound = lambda count: setattr(self.aws, 'health', 'UNHEALTHY') if count == 2 else None
        deployer = self.deployer(self.planned(), now=lambda: EASTERN_3AM)
        with self.assertRaisesRegex(ValueError, 'Task became RUNNING/UNHEALTHY'):
            deployer.run()
        self.assertEqual('off-hours-stop-failed', deployer.steps[-1]['step'])
        self.assertIn('AccessDeniedException', deployer.steps[-1]['error'])

    def issueBody(self):
        self.approved(self.planned())
        issues = [key for key in self.github.data if key.startswith('issues/') and key.count('/') == 1]
        return self.github.data[max(issues, key=lambda key: int(key.split('/')[1]))]['body']

    def test_the_approval_issue_says_what_this_deploy_watches(self):
        web = self.issueBody()
        self.assertIn('watch this application alone for 15 minutes', web)
        self.assertIn('is checked once and only recorded, since it also needs the other applications', web)
        self.assertIn('Between 00:00 and 10:00 New York time', web)
        self.gatewayShape()
        self.assertIn('/dev/access` answers 200 every minute', self.issueBody())

NY_2350 = datetime(2026, 11, 6, 4, 50, tzinfo=timezone.utc)  # 23:50 New York (EST): inside the overnight blackout
NY_0020 = datetime(2026, 11, 6, 5, 20, tzinfo=timezone.utc)  # 00:20 New York: still inside
NY_0031 = datetime(2026, 11, 6, 5, 31, tzinfo=timezone.utc)  # 00:31 New York: outside


class BlackoutTests(DeployFixture):
    """DLV-968 (user 2026-11-05): no serving deploy 23:30-00:30 New York; DEV has no emergencies, so there is no override."""

    def test_blackout_refuses_a_serving_plan_but_not_a_park(self):
        for now in (NY_2350, NY_0020):
            with self.assertRaisesRegex(ValueError, 'No deploy between 23:30 and 00:30 New York time \\(at planning\\)'):
                m.plan(self.env, self.aws, self.root, self.get, now=now)
        self.assertEqual(0, len(self.aws.ops('ecr', 'batch-get-image')))  # refused before any AWS read
        self.assertEqual(0, m.plan({**self.env, 'DEPLOY_DESIRED_COUNT': '0'}, self.aws, self.root, self.get, now=NY_2350)['desiredCount'])
        self.assertNotIn('emergency', m.plan(self.env, self.aws, self.root, self.get, now=NY_0031))
        self.assertTrue(m.inBlackout(NY_2350) and m.inBlackout(NY_0020) and not m.inBlackout(NY_0031) and not m.inBlackout(EASTERN_2PM))

    def test_execution_rechecks_the_blackout_after_a_late_approval(self):
        planned = self.planned()  # planned in the daytime
        deployer = self.deployer(planned, now=lambda: NY_2350)
        with self.assertRaisesRegex(ValueError, 'No deploy between 23:30 and 00:30 New York time \\(at execution\\)'):
            deployer.run()
        self.assertEqual(('failure', []), (deployer.outcome['outcome'], self.aws.ops('cloudformation', 'create-change-set')))
        # a park and a verify-only answer are not deploys
        self.assertEqual('success', self.deployer(self.planned(count='0'), now=lambda: NY_2350).run()['outcome'])
        self.aws.serving('b'); self.aws.image = 'sha256:' + 'b' * 64
        self.assertEqual('success', self.deployer(self.planned(answer='verify'), now=lambda: NY_2350, mode='verify').run()['outcome'])

    def test_a_scheduled_stop_during_observation_ends_the_run_cleanly(self):
        self.aws.onRound = lambda count: self.aws.service.update({'desired': 0}) if count == 4 else None
        outcome = self.deployer(self.planned()).run()
        stopped = outcome['steps'][-1]
        self.assertEqual(('success', 'stopped-during-observation', 3), (outcome['outcome'], stopped['step'], stopped['minutes']))
        self.assertIn('served 3 clean minute(s)', stopped['note'])
        # a crash keeps the desired count at 1 and still fails the run
        self.aws = FakeAws(self.config)
        self.aws.onRound = lambda count: self.aws.service.update({'running': 0}) if count == 4 else None
        with self.assertRaisesRegex(ValueError, 'replaced or stopped during observation'):
            self.deployer(self.planned()).run()


class TransportAndCliTests(DeployFixture):
    def test_aws_transport_is_bounded_and_redacts_errors(self):
        with patch.object(m.subprocess, 'run', return_value=Mock(returncode=0, stdout='{"a": 1}', stderr='')) as run:
            self.assertEqual({'a': 1}, m.aws(['sts', 'get-caller-identity']))
        self.assertEqual(40, run.call_args.kwargs['timeout'])
        self.assertEqual(['aws', 'sts', 'get-caller-identity', '--region', 'us-east-2', '--output', 'json', '--no-cli-pager', '--cli-connect-timeout', '10', '--cli-read-timeout', '25'], run.call_args.args[0])
        with patch.object(m.subprocess, 'run', return_value=Mock(returncode=254, stdout='', stderr='An error occurred (AccessDenied) ' + 'x' * 500)):
            with self.assertRaisesRegex(RuntimeError, 'aws sts get-caller-identity failed') as raised:
                m.client()('sts', 'get-caller-identity')
        self.assertLess(len(str(raised.exception)), 400)
        with patch.object(m.subprocess, 'run', return_value=Mock(returncode=0, stdout='', stderr='')):
            self.assertIsNone(m.aws(['cloudformation', 'execute-change-set']))
        with self.assertRaisesRegex(RuntimeError, 'other'):
            m.tolerant(lambda: (_ for _ in ()).throw(RuntimeError('other failure')), absent=('does not exist',))

    def test_probe_returns_status_codes_and_requires_https(self):
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *a): return False
        self.assertEqual(200, m.probe('https://dev.devlookahead.com/', opener=lambda request, timeout: Response()))
        def failing(request, timeout):
            raise m.urllib.error.HTTPError(request.full_url, 503, 'unavailable', {}, None)
        self.assertEqual(503, m.probe('https://dev.devlookahead.com/', opener=failing))
        # Nothing answering (the Web run of 2026-11-05 died on this timeout) is None, never an exception.
        for error in (m.urllib.error.URLError(TimeoutError('timed out')), TimeoutError('timed out'), ConnectionRefusedError()):
            def silent(request, timeout, error=error):
                raise error
            self.assertIsNone(m.probe('https://dev.devlookahead.com/', opener=silent))
        with self.assertRaisesRegex(ValueError, 'https'):
            m.probe('http://dev.devlookahead.com/')

    def test_cli_plan_approve_verify_execute_write_once(self):
        out = self.root / 'out'
        env = dict(self.env); env['GITHUB_OUTPUT'] = str(out / 'github-output'); env['DEPLOY_DESIRED_COUNT'] = '1'
        out.mkdir()
        (out / 'github-output').write_text('')
        planned = m.main(['plan', '--root', str(self.root), '--output', str(out / 'plan.json')], env, call=self.aws, get=self.get)
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': 'APPROVE DEPLOY ' + planned['imageTag'], 'created_at': stamp, 'updated_at': stamp}
        self.assertIn('desired_count=1', (out / 'github-output').read_text())
        with self.assertRaises(FileExistsError):
            m.main(['plan', '--root', str(self.root), '--output', str(out / 'plan.json')], env, call=self.aws, get=self.get)
        with patch.object(m, 'utc', side_effect=lambda now=None: self.now if now is None else now):
            receipt = m.main(['approve', '--root', str(self.root), '--plan', str(out / 'plan.json'), '--receipt', str(out / 'approval.json')], env, get=self.get, sleep=lambda s: None)
            self.assertEqual(receipt, json.loads((out / 'approval.json').read_text()))
            m.main(['verify', '--root', str(self.root), '--plan', str(out / 'plan.json'), '--receipt', str(out / 'approval.json')], env, get=self.get)
            outcome = m.main(['execute', '--root', str(self.root), '--plan', str(out / 'plan.json'), '--receipt', str(out / 'approval.json'), '--output', str(out / 'result.json')],
                             env, call=self.aws, get=self.get, sleep=lambda s: None, fetch=lambda url: 200)
        self.assertEqual('success', outcome['outcome'])
        self.assertEqual(outcome, json.loads((out / 'result.json').read_text()))
        with self.assertRaisesRegex(ValueError, '--plan and --receipt required'):
            m.main(['verify', '--root', str(self.root)], env, get=self.get)
        with self.assertRaisesRegex(ValueError, '--output required'):
            m.main(['plan', '--root', str(self.root)], env, call=self.aws, get=self.get)


if __name__ == '__main__':
    unittest.main()
