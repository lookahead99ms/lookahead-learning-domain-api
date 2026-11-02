"""Tests for the per-application DEV deploy tool (one ECS service per application, ECS rolling updates); AWS and GitHub are
injected fakes, nothing is called for real."""
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
    'schema': 'lookahead-app-deploy-config/v2', 'app': APP, 'service': 'web', 'account': '509614632283', 'region': 'us-east-2',
    'imageRepository': '509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr',
    'deployRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-deploy', 'cloudFormationRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-cfn-provision',
    'sessionPrefix': 'web-deploy', 'cluster': 'arn:aws:ecs:us-east-2:509614632283:cluster/lookahead-dev-cluster', 'clusterName': 'lookahead-dev-cluster',
    'stack': 'lookahead-dev-web', 'logGroup': '/lookahead/dev/web', 'namespaceName': 'lookahead-dev.internal',
    'discovery': {'serviceName': 'web', 'serviceId': 'srv-web000000000001', 'serviceArn': 'arn:aws:servicediscovery:us-east-2:509614632283:service/srv-web000000000001'},
    'targetGroupArn': None, 'observationMinutes': 15, 'probeUrl': 'https://dev.devlookahead.com/',
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
            return {'Stacks': [{'StackStatus': self.stack['status'], 'StackId': 'id-' + opts['--stack-name'],
                                'Parameters': [{'ParameterKey': k, 'ParameterValue': v} for k, v in self.stack['parameters'].items()]}]}
        if key == 'servicediscovery list-instances':
            assert opts['--service-id'] == self.config['discovery']['serviceId'], opts
            return {'Instances': [{'Id': 'i'}] * self.discovered}
        if key == 'ecs describe-services':
            assert opts['--services'] == self.config['stack'], opts
            if self.service is None:
                return {'services': [], 'failures': [{'reason': 'MISSING'}]}
            return {'services': [{'status': 'ACTIVE', 'desiredCount': self.service['desired'], 'runningCount': self.service['running'],
                                  'deployments': [{'status': 'PRIMARY', 'rolloutState': self.service['rolloutState'], 'taskDefinition': 'td'}]}]}
        if key == 'ecs list-tasks':
            return {'taskArns': [TASK] if self.service and self.service['running'] else []}
        if key == 'ecs describe-tasks':
            return {'tasks': [{'healthStatus': self.health, 'lastStatus': 'RUNNING', 'startedAt': '2026-11-01T21:00:00+00:00',
                               'taskDefinitionArn': 'arn:aws:ecs:us-east-2:509614632283:task-definition/' + self.config['stack'] + ':3'}]}
        if key == 'ecs describe-task-definition':
            return {'taskDefinition': {'containerDefinitions': [{'image': self.config['imageRepository'] + '@' + self.stack['parameters']['ApplicationImage'].rsplit('@', 1)[-1]}]}}
        if key == 'logs filter-log-events':
            return {'events': self.logs}
        if key == 'elbv2 describe-target-health':
            return {'TargetHealthDescriptions': [{'TargetHealth': {'State': self.targets}}]}
        if key == 'cloudformation create-change-set':
            name = opts['--change-set-name']
            assert opts['--stack-name'] == self.config['stack'] and '--template-body' in opts and '--tags' in opts, opts
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
                                                           'DiscoveryServiceArn': self.config['discovery']['serviceArn']} if self.config['discovery'] else
                                                          {'ApplicationImage': self.config['imageRepository'] + '@sha256:' + image[0] * 64, 'DesiredCount': count,
                                                           'TargetGroupArn': self.config['targetGroupArn']})
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

    def planned(self, count=None):
        env = dict(self.env)
        if count is not None:
            env['DEPLOY_DESIRED_COUNT'] = count
        result = m.plan(env, self.aws, self.root, self.get, now=self.now)
        stamp = self.now.replace(microsecond=0).isoformat()
        # the fake owner answers the deploy command, not the image command, once the digest is known
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': 'APPROVE DEPLOY ' + result['imageTag'], 'created_at': stamp, 'updated_at': stamp}
        return result

    def approved(self, planned):
        return m.approve(planned, self.env, self.root, self.get, now=self.clock, sleep=lambda s: None)

    def deployer(self, planned, **kw):
        template = (self.root / 'deployment/dev/service.template.json').read_text()
        return m.Deployer(planned, m.loadConfig(self.root), template, self.aws, sleep=lambda s: None, now=self.clock, fetch=kw.get('fetch', lambda url: 200))


class PlanTests(DeployFixture):
    def test_first_deploy_creates_the_stack_with_one_serving_task(self):
        planned = self.planned()
        self.assertEqual(('lookahead-dev-web', False, False, 1, 'app-12', None, None), (planned['stack'], planned['stackExists'], planned['unchanged'], planned['desiredCount'],
                                                                                        planned['changeSet'], planned['currentImage'], planned['currentDesiredCount']))
        self.assertEqual(self.config['imageRepository'] + '@' + DIGEST, planned['image'])
        self.assertEqual({'ApplicationImage': planned['image'], 'DesiredCount': '1', 'DiscoveryServiceArn': self.config['discovery']['serviceArn'], **CONFIG['parameters']}, planned['parameters'])
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
        self.assertEqual((7, planned['planDigest'], 'app-deploy-approval/v1'), (receipt['commentId'], receipt['planDigest'], receipt['schema']))
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
        self.github.comment = lambda: {'id': 7, 'user': {'login': 'someone'}, 'body': 'APPROVE DEPLOY ' + planned['imageTag'], 'created_at': '2026-01-01T00:00:00+00:00', 'updated_at': '2026-01-01T00:00:00+00:00'}
        with self.assertRaises(TimeoutError):
            self.approved(planned)


class ExecuteTests(DeployFixture):
    def test_first_deploy_creates_the_stack_verifies_and_observes(self):
        planned = self.planned()
        outcome = self.deployer(planned).run()
        self.assertEqual(('success', 1), (outcome['outcome'], outcome['desiredCount']))
        self.assertEqual(['identity', 'change-set', 'executed', 'verified', 'observed'], [s['step'] for s in outcome['steps']])
        create = self.aws.ops('cloudformation', 'create-change-set')[0]
        self.assertEqual(('CREATE', 'app-12'), (create[create.index('--change-set-type') + 1], create[create.index('--change-set-name') + 1]))
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

    def test_unchanged_plan_touches_nothing_but_still_verifies_the_service(self):
        self.aws.serving('b')
        outcome = self.deployer(self.planned()).run()
        self.assertEqual(['identity', 'unchanged', 'verified', 'observed'], [s['step'] for s in outcome['steps']])
        self.assertEqual([], self.aws.ops('cloudformation', 'create-change-set'))

    def test_parking_scales_the_service_to_zero_without_a_probe(self):
        self.aws.serving('b')
        probes = []
        outcome = self.deployer(self.planned(count='0'), fetch=lambda url: probes.append(url) or 200).run()
        self.assertEqual(['identity', 'change-set', 'executed', 'parked'], [s['step'] for s in outcome['steps']])
        self.assertEqual(([('Service', 'Modify', 'False')], 0, []), ([(c['logical'], c['action'], c['replacement']) for c in outcome['steps'][1]['changes']], self.aws.service['desired'], probes))

    def test_circuit_breaker_rollback_fails_the_run_and_the_previous_revision_keeps_serving(self):
        self.aws.serving('old')
        self.aws.executeStatus = 'UPDATE_ROLLBACK_COMPLETE'
        deployer = self.deployer(self.planned())
        with self.assertRaisesRegex(ValueError, 'ended in UPDATE_ROLLBACK_COMPLETE.*rolled the service back'):
            deployer.run()
        self.assertEqual('failure', deployer.outcome['outcome'])
        self.assertEqual(('sha256:' + 'o' * 64, 1), (self.aws.stack['parameters']['ApplicationImage'].rsplit('@', 1)[-1], self.aws.service['running']))
        self.assertEqual([], self.aws.ops('cloudformation', 'delete-stack'))

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
        planned = self.planned()
        with self.assertRaisesRegex(ValueError, 'Public probe returned 503'):
            self.deployer(planned, fetch=lambda url: 503).run()
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
        self.config['discovery'] = None
        self.config['targetGroupArn'] = 'arn:aws:elasticloadbalancing:us-east-2:509614632283:targetgroup/lookahead-dev-gateway/0123456789abcdef'
        self.writeConfig(); self.aws = FakeAws(self.config)
        planned = self.planned()
        self.assertEqual(self.config['targetGroupArn'], planned['parameters']['TargetGroupArn'])
        self.assertNotIn('DiscoveryServiceArn', planned['parameters'])
        outcome = self.deployer(planned).run()
        self.assertEqual(('success', 1, None), (outcome['outcome'], outcome['steps'][3]['targets'], outcome['steps'][3]['discovered']))
        self.assertEqual([], self.aws.ops('servicediscovery', 'list-instances'))
        self.aws.targets = 'unhealthy'; self.aws.stack = None; self.aws.service = None
        with self.assertRaisesRegex(ValueError, 'target health did not complete'):
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
