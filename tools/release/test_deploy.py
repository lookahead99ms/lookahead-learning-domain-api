"""Tests for the per-application DEV deploy tool; AWS and GitHub are injected fakes, nothing is called for real."""
import json
import os
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
# A synthetic Web-shaped configuration (alias flip); the listener flip is exercised by rewriting it. The repository's real
# deploy-config.json is checked by test_workflow, and the repository's real template is used here for its Task/Service shape.
CONFIG = {
    'schema': 'lookahead-app-deploy-config/v1', 'app': APP, 'service': 'web', 'account': '509614632283', 'region': 'us-east-2',
    'imageRepository': '509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr',
    'deployRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-deploy', 'cloudFormationRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-cfn-provision',
    'sessionPrefix': 'web-deploy', 'cluster': 'arn:aws:ecs:us-east-2:509614632283:cluster/lookahead-dev-cluster', 'clusterName': 'lookahead-dev-cluster',
    'logGroup': '/lookahead/dev/web', 'namespaceName': 'lookahead-dev.internal',
    'slots': {'blue': {'stack': 'lookahead-dev-web-blue', 'discoveryService': 'web-blue', 'discoveryServiceArn': 'arn:aws:servicediscovery:us-east-2:509614632283:service/srv-blue0000000001'},
              'green': {'stack': 'lookahead-dev-web-green', 'discoveryService': 'web-green', 'discoveryServiceArn': 'arn:aws:servicediscovery:us-east-2:509614632283:service/srv-green000000001'}},
    'flip': {'mode': 'alias', 'serviceName': 'web', 'serviceId': 'srv-aliasweb0001', 'instanceId': 'stable', 'hostnameSuffix': '.lookahead-dev.internal'},
    'coupling': {'listenerStack': 'lookahead-dev-ingress', 'gatewayServicePrefix': 'lookahead-dev-gateway-'}, 'observationMinutes': 15, 'probeUrl': 'https://dev.devlookahead.com/',
    'parameters': {'Cluster': 'arn:aws:ecs:us-east-2:509614632283:cluster/lookahead-dev-cluster', 'ApplicationSubnets': 'subnet-a,subnet-b', 'ServiceSecurityGroup': 'sg-1',
                   'TaskRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-app-task', 'ExecutionRoleArn': 'arn:aws:iam::509614632283:role/lookahead-dev-ecs-execution',
                   'TlsSecretArn': 'arn:aws:secretsmanager:us-east-2:509614632283:secret:lookahead-dev-web-tls-pX1NCN', 'TlsVersion': '98331e44-009a-4d74-a6b5-22124e75c170',
                   'LogGroupName': '/lookahead/dev/web'},
    'slotParameters': {'DiscoveryServiceArn': 'discoveryServiceArn'}, 'logFailurePatterns': ['ERROR', 'startup denied', 'AccessDeniedException']}


class FakeAws:
    """Dispatches on `<service> <operation>`; `state` holds the slots, alias and listener the fake account has."""

    def __init__(self, config, image=None):
        self.config, self.calls = config, []
        self.image = image or DIGEST
        self.stacks = {}  # name -> {'status', 'parameters'}
        self.alias = None
        self.listener = {'ActiveSlot': 'blue'}
        self.services = {}  # service -> counts
        self.changeSets = {}
        self.health, self.logs, self.discovered, self.targets = 'HEALTHY', [], 1, 'healthy'
        self.operation = 'SUCCESS'
        self.session = config['sessionPrefix'] + '-12-1'
        self.changeSetFailure = None
        self.previewOverride = None
        self.candidate = 'blue'

    def __call__(self, *args):
        self.calls.append(args)
        key = args[0] + ' ' + args[1]
        opts, i = {}, 2
        while i < len(args):  # `--flag value` pairs and bare flags such as --use-previous-template
            if args[i].startswith('--') and i + 1 < len(args) and not str(args[i + 1]).startswith('--'):
                opts[args[i]] = args[i + 1]; i += 2
            else:
                opts[args[i]] = True; i += 1
        if key == 'sts get-caller-identity':
            return {'Account': self.config['account'], 'Arn': 'arn:aws:sts::' + self.config['account'] + ':assumed-role/lookahead-dev-deploy/' + self.session}
        if key == 'ecr batch-get-image':
            return {'images': [{'imageId': {'imageDigest': self.image, 'imageTag': opts['--image-ids'].split('=')[1]}}], 'failures': []} if self.image else {'images': [], 'failures': [{'failureCode': 'ImageNotFound'}]}
        if key == 'cloudformation describe-stacks':
            name = opts['--stack-name']
            if name == self.config['flip'].get('listenerStack') or name == (self.config.get('coupling') or {}).get('listenerStack'):
                return {'Stacks': [{'StackStatus': 'UPDATE_COMPLETE', 'StackId': 'id-' + name, 'Parameters': [{'ParameterKey': k, 'ParameterValue': v} for k, v in self.listener.items()]}]}
            if name not in self.stacks:
                raise RuntimeError('aws cloudformation describe-stacks failed: Stack with id ' + name + ' does not exist')
            stack = self.stacks[name]
            return {'Stacks': [{'StackStatus': stack['status'], 'StackId': 'id-' + name, 'Parameters': [{'ParameterKey': k, 'ParameterValue': v} for k, v in stack['parameters'].items()]}]}
        if key == 'servicediscovery get-instance':
            if self.alias is None:
                raise RuntimeError('aws servicediscovery get-instance failed: InstanceNotFound')
            return {'Instance': {'Id': 'stable', 'Attributes': {'AWS_INSTANCE_CNAME': self.alias}}}
        if key == 'servicediscovery register-instance':
            self.alias = opts['--attributes'].split('=', 1)[1]
            return {'OperationId': 'op-1'}
        if key == 'servicediscovery get-operation':
            return {'Operation': {'Status': self.operation}}
        if key == 'servicediscovery discover-instances':
            return {'Instances': [{'InstanceId': 'i'}] * self.discovered}
        if key == 'ecs describe-services':
            counts = self.services.get(opts['--services'])
            if counts is None:
                return {'services': [], 'failures': [{'reason': 'MISSING'}]}
            return {'services': [{'status': 'ACTIVE', 'desiredCount': counts['desired'], 'runningCount': counts['running'],
                                  'deployments': [{'status': 'PRIMARY', 'rolloutState': counts['rolloutState'], 'taskDefinition': 'td'}]}]}
        if key == 'ecs list-tasks':
            return {'taskArns': ['arn:aws:ecs:us-east-2:509614632283:task/lookahead-dev-cluster/0123456789abcdef0123456789abcdef']}
        if key == 'ecs describe-tasks':
            return {'tasks': [{'healthStatus': self.health, 'lastStatus': 'RUNNING', 'startedAt': '2026-10-31T21:00:00+00:00',
                               'taskDefinitionArn': 'arn:aws:ecs:us-east-2:509614632283:task-definition/' + self.taskFamily + ':3'}]}
        if key == 'logs filter-log-events':
            return {'events': self.logs}
        if key == 'elbv2 describe-target-health':
            return {'TargetHealthDescriptions': [{'TargetHealth': {'State': self.targets}}]}
        if key == 'cloudformation create-change-set':
            stack, name = opts['--stack-name'], opts['--change-set-name']
            rows = json.loads(opts['--parameters'])
            params = {r['ParameterKey']: r['ParameterValue'] for r in rows if 'ParameterValue' in r}
            previous = {r['ParameterKey'] for r in rows if r.get('UsePreviousValue')}
            create = opts['--change-set-type'] == 'CREATE'
            listenerStack = self.config['flip'].get('listenerStack') or (self.config.get('coupling') or {}).get('listenerStack')
            if not create and stack not in self.stacks and stack != listenerStack:
                raise RuntimeError('aws cloudformation create-change-set failed: Stack [' + stack + '] does not exist')
            if create:
                changes = [{'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': l, 'Action': 'Add', 'ResourceType': t, 'Replacement': None}} for l, t in (('Task', 'AWS::ECS::TaskDefinition'), ('Service', 'AWS::ECS::Service'))]
            else:
                before = self.stacks.get(stack, {'parameters': {}})['parameters']
                changed = {k for k, v in params.items() if before.get(k) != v}
                if not changed and stack != self.config['flip'].get('listenerStack'):
                    self.changeSets[(stack, name)] = {'Status': 'FAILED', 'StatusReason': "The submitted information didn't contain changes."}
                    return {'Id': 'cs-' + name}
                changes = []
                if 'ApplicationImage' in changed:
                    changes.append({'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Task', 'Action': 'Modify', 'ResourceType': 'AWS::ECS::TaskDefinition', 'Replacement': 'True'}})
                changes.append({'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Service' if stack != self.config['flip'].get('listenerStack') else 'Listener', 'Action': 'Modify', 'ResourceType': 'AWS::ECS::Service', 'Replacement': 'False'}})
            if self.previewOverride == 'no-changes' and name.endswith('candidate'):
                self.changeSets[(stack, name)] = {'Status': 'FAILED', 'StatusReason': "The submitted information didn't contain changes."}
                return {'Id': 'cs-' + name}
            if self.previewOverride and name.endswith('candidate'):
                changes = self.previewOverride
            status = self.changeSetFailure if self.changeSetFailure and name.endswith('candidate') else 'CREATE_COMPLETE'
            self.changeSets[(stack, name)] = {'Status': status, 'StatusReason': 'forced' if status == 'FAILED' else '', 'Changes': changes,
                                              'apply': (params, previous, create)}
            return {'Id': 'cs-' + name}
        if key == 'cloudformation describe-change-set':
            return self.changeSets[(opts['--stack-name'], opts['--change-set-name'])]
        if key == 'cloudformation execute-change-set':
            stack, name = opts['--stack-name'], opts['--change-set-name']
            params, previous, create = self.changeSets[(stack, name)]['apply']
            if stack == self.config['flip'].get('listenerStack'):
                self.listener.update(params)
                return None
            if stack in self.stacks and self.stacks[stack]['status'] == 'REVIEW_IN_PROGRESS' and not create:
                raise RuntimeError('aws cloudformation execute-change-set failed: cannot update a stack in REVIEW_IN_PROGRESS')
            merged = dict(self.stacks.get(stack, {'parameters': {}})['parameters']); merged.update(params)
            self.stacks[stack] = {'status': 'CREATE_COMPLETE' if create else 'UPDATE_COMPLETE', 'parameters': merged}
            self.services[stack] = {'desired': int(merged['DesiredCount']), 'running': int(merged['DesiredCount']), 'rolloutState': 'COMPLETED'}
            return None
        raise AssertionError('unexpected aws call ' + key)

    @property
    def taskFamily(self):
        return self.config['slots'][self.candidate]['stack']


def c_index(call, flag):
    return call.index(flag) + 1


class DeployFixture(unittest.TestCase):
    app, repository = APP, REPOSITORY

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / 'deployment/dev').mkdir(parents=True)
        shutil.copy(ROOT / 'deployment/dev/service.template.json', self.root / 'deployment/dev/service.template.json')
        config = json.loads(json.dumps(CONFIG))
        self.config = config
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(config))
        self.github = Github(self.repository, SHA)
        self.github.env['GITHUB_WORKFLOW_REF'] = self.repository + '/' + m.WORKFLOW + '@refs/heads/main'
        self.github.data['actions/runs/12']['path'] = m.WORKFLOW
        self.env = dict(self.github.env)
        self.run = self.env['GITHUB_RUN_ID']
        self.aws = FakeAws(config)
        self.now = self.github.now  # a fixed datetime; callables wrap it

    def clock(self):
        return self.github.now

    def get(self, repository, path, payload=None):
        return self.github.get(repository, path, payload)

    def planned(self):
        result = m.plan(self.env, self.aws, self.root, self.get, now=self.now)
        self.aws.candidate = result['candidateSlot']
        stamp = self.now.replace(microsecond=0).isoformat()
        # the fake owner answers the deploy command, not the image command, once the digest is known
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': 'APPROVE DEPLOY ' + result['planDigest'], 'created_at': stamp, 'updated_at': stamp}
        return result

    def approved(self, planned):
        return m.approve(planned, self.env, self.root, self.get, now=self.clock, sleep=lambda s: None)

    def deployer(self, planned, **kw):
        template = (self.root / 'deployment/dev/service.template.json').read_text()
        return m.Deployer(planned, m.loadConfig(self.root), template, self.aws, sleep=lambda s: None, now=self.clock, fetch=kw.get('fetch', lambda url: 200))


class PlanTests(DeployFixture):
    def test_first_deploy_targets_blue_with_a_create_change_set_and_no_previous_slot(self):
        planned = self.planned()
        self.assertEqual(('blue', None, False, None), (planned['candidateSlot'], planned['previousSlot'], planned['candidateStackExists'], planned['activeSlot']))
        self.assertEqual(self.config['imageRepository'] + '@' + DIGEST, planned['image'])
        self.assertEqual({'Slot': 'blue', 'DesiredCount': '1'}, {k: planned['parameters'][k] for k in ('Slot', 'DesiredCount')})
        self.assertEqual(planned['parameters']['DiscoveryServiceArn'], self.config['slots']['blue']['discoveryServiceArn'])
        self.assertEqual(self.app + '-v2026.10.26.20.00', planned['imageTag'])
        self.assertEqual(['app-12-candidate', 'app-12-flip', 'app-12-retire', 'app-12-rollback'], list(planned['changeSets'].values()))
        self.assertEqual(owner.digest({k: v for k, v in planned.items() if k != 'planDigest'}), planned['planDigest'])
        self.assertEqual(['Create', 'Modify', 'Modify'], [row['action'] for row in planned['resources']])

    def test_second_deploy_targets_the_idle_slot_creates_it_and_retires_the_alias_target(self):
        self.aws.alias = 'web-blue.lookahead-dev.internal'
        self.aws.listener = {'ActiveSlot': 'green'}
        self.aws.stacks['lookahead-dev-web-blue'] = {'status': 'UPDATE_COMPLETE', 'parameters': {'ApplicationImage': 'old', 'DesiredCount': '1', 'Slot': 'blue'}}
        planned = self.planned()
        self.assertEqual(('green', 'blue', 'lookahead-dev-web-blue', False, '1'), (planned['candidateSlot'], planned['previousSlot'], planned['previousStack'], planned['candidateStackExists'], planned['previousDesiredCount']))
        self.assertEqual(['Create', 'Modify', 'Modify', 'Modify'], [row['action'] for row in planned['resources']])  # the candidate green stack does not exist yet
        self.assertEqual(['ApplicationImage', 'DesiredCount', 'Slot'], planned['previousParameters'])

    def test_alias_pointing_at_a_deleted_stack_or_an_aborted_create_leaves_nothing_to_retire(self):
        self.aws.alias = 'web-blue.lookahead-dev.internal'  # blue stack gone
        planned = self.planned()
        self.assertEqual(('green', None, None), (planned['candidateSlot'], planned['previousSlot'], planned['previousStack']))
        self.aws.stacks['lookahead-dev-web-green'] = {'status': 'REVIEW_IN_PROGRESS', 'parameters': {}}  # an earlier CREATE change set never executed
        planned = self.planned()
        self.assertFalse(planned['candidateStackExists'])
        outcome = self.deployer(planned).run()
        self.assertEqual('CREATE', next(c for c in self.aws.calls if c[1] == 'create-change-set')[c_index(next(c for c in self.aws.calls if c[1] == 'create-change-set'), '--change-set-type')])
        self.assertEqual('success', outcome['outcome'])

    def test_a_run_that_stopped_before_the_flip_can_be_repeated_without_a_candidate_change_set(self):
        self.aws.stacks['lookahead-dev-web-blue'] = {'status': 'UPDATE_COMPLETE', 'parameters': {**m.slotParameters(self.config, 'blue', self.config['imageRepository'] + '@' + DIGEST, 1)}}
        self.aws.services['lookahead-dev-web-blue'] = {'desired': 1, 'running': 1, 'rolloutState': 'COMPLETED'}
        planned = self.planned()
        self.assertTrue(planned['candidateUpToDate'])
        self.assertIn('verification only', planned['resources'][0]['note'])
        outcome = self.deployer(planned).run()
        self.assertEqual(['identity', 'candidate-unchanged', 'candidate-verified', 'flipped', 'observed', 'retire-skipped'], [s['step'] for s in outcome['steps']])
        self.assertEqual([], [c for c in self.aws.calls if c[1] == 'create-change-set'])

    def test_plan_refuses_missing_image_failed_create_gateway_serving_slot_and_unconfigured_alias(self):
        self.aws.image = None
        with self.assertRaisesRegex(ValueError, 'not found in ECR'):
            self.planned()
        self.aws.image = DIGEST
        self.aws.stacks['lookahead-dev-web-blue'] = {'status': 'ROLLBACK_COMPLETE', 'parameters': {}}
        with self.assertRaisesRegex(ValueError, 'not updatable: ROLLBACK_COMPLETE'):
            self.planned()
        self.aws.stacks['lookahead-dev-web-blue'] = {'status': 'UPDATE_ROLLBACK_COMPLETE', 'parameters': {'ApplicationImage': 'old', 'DesiredCount': '0'}}
        self.assertEqual('blue', self.planned()['candidateSlot'])  # a rolled-back update is still updatable
        self.aws.listener = {'ActiveSlot': 'blue'}  # phase 1: a running Gateway reaches web-blue by name; never rebuild it underneath
        self.assertEqual('blue', self.planned()['candidateSlot'])  # listener default alone (no Gateway task) does not block the first deploys
        self.aws.services['lookahead-dev-gateway-blue'] = {'desired': 1, 'running': 1, 'rolloutState': 'COMPLETED'}
        with self.assertRaisesRegex(ValueError, 'serving the Gateway'):
            self.planned()
        del self.aws.services['lookahead-dev-gateway-blue']
        self.config['flip']['serviceId'] = 'PENDING-I1'
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, 'not configured yet'):
            self.planned()

    def test_provenance_requires_the_deploy_workflow_dispatch_by_the_owner(self):
        for key, value in (('GITHUB_EVENT_NAME', 'push'), ('GITHUB_WORKFLOW_REF', self.repository + '/.github/workflows/release.yml@refs/heads/main'), ('GITHUB_ACTOR', 'someone')):
            env = dict(self.env); env[key] = value
            with self.assertRaisesRegex(ValueError, 'Fresh owner dispatch'):
                m.provenance(env, self.get)
        env = dict(self.env); env['GITHUB_REPOSITORY'] = 'lookahead99ms/other'
        with self.assertRaisesRegex(ValueError, 'Unknown application repository'):
            m.provenance(env, self.get)
        env = dict(self.env); env['RELEASE_VERSION'] = 'v2026.01.01.00.00'
        with self.assertRaisesRegex(ValueError, 'Dispatched release tag differs'):
            m.provenance(env, self.get)

    def test_configuration_and_template_are_validated(self):
        with self.assertRaisesRegex(ValueError, 'Exact deploy configuration'):
            bad = dict(self.config); bad['extra'] = 1
            (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(bad)); m.loadConfig(self.root)
        listener = dict(self.config); listener['flip'] = {'mode': 'listener'}
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(listener))
        with self.assertRaisesRegex(ValueError, 'Listener flip needs'):
            m.loadConfig(self.root)
        template = json.loads((self.root / 'deployment/dev/service.template.json').read_text())
        template['Resources']['Extra'] = {'Type': 'AWS::S3::Bucket'}
        (self.root / 'deployment/dev/service.template.json').write_text(json.dumps(template))
        with self.assertRaisesRegex(ValueError, 'only Task/Service'):
            m.loadTemplate(self.root)


class ApprovalTests(DeployFixture):
    def test_approve_opens_an_issue_and_returns_the_owner_comment(self):
        planned = self.planned()
        receipt = self.approved(planned)
        self.assertEqual((m.SCHEMA_RECEIPT, planned['planDigest'], 3, 7), (receipt['schema'], receipt['planDigest'], receipt['issue'], receipt['commentId']))
        issue = self.github.data['issues/3']
        self.assertIn('APPROVE DEPLOY ' + planned['planDigest'], issue['body'])
        self.assertIn('lookahead-dev-web-blue', issue['body'])
        self.assertTrue(issue['title'].startswith('DEV: Deploy ' + self.app + ' ' + planned['imageTag'] + ' to blue'))
        m.verify(planned, receipt, self.env, self.root, self.get, now=self.now)

    def test_verify_rejects_changed_plan_template_or_configuration_and_owner_reject(self):
        planned = self.planned()
        receipt = self.approved(planned)
        altered = dict(planned); altered['candidateSlot'] = 'green'
        with self.assertRaisesRegex(ValueError, 'digest changed'):
            m.verify(altered, receipt, self.env, self.root, self.get, now=self.now)
        template = (self.root / 'deployment/dev/service.template.json').read_text()
        (self.root / 'deployment/dev/service.template.json').write_text(template.replace('"Family"', '"Family" '))
        with self.assertRaisesRegex(ValueError, 'Template changed'):
            m.verify(planned, receipt, self.env, self.root, self.get, now=self.now)
        (self.root / 'deployment/dev/service.template.json').write_text(template)
        self.github.comments.append({'id': 99, 'user': {'login': owner.OWNER}, 'body': 'REJECT', 'created_at': receipt['createdAt'], 'updated_at': receipt['createdAt']})
        with self.assertRaisesRegex(ValueError, 'rejected'):
            m.verify(planned, receipt, self.env, self.root, self.get, now=self.now)

    def test_approve_times_out_without_an_owner_comment(self):
        planned = self.planned()
        self.github.autoApprove = False
        with self.assertRaises(TimeoutError):
            self.approved(planned)


class ExecuteTests(DeployFixture):
    def test_first_deploy_creates_the_slot_flips_the_alias_observes_and_has_nothing_to_retire(self):
        planned = self.planned()
        outcome = self.deployer(planned).run()
        self.assertEqual('success', outcome['outcome'])
        self.assertEqual(['identity', 'change-set', 'executed', 'candidate-verified', 'flipped', 'observed', 'retire-skipped'], [s['step'] for s in outcome['steps']])
        self.assertEqual('web-blue.lookahead-dev.internal', self.aws.alias)
        self.assertEqual({'desired': 1, 'running': 1, 'rolloutState': 'COMPLETED'}, self.aws.services['lookahead-dev-web-blue'])
        create = next(c for c in self.aws.calls if c[1] == 'create-change-set')
        self.assertIn('--role-arn', create); self.assertIn(self.config['cloudFormationRoleArn'], create); self.assertIn('CREATE', create)

    def test_second_deploy_retires_the_previous_slot_after_observation(self):
        self.aws.alias = 'web-blue.lookahead-dev.internal'
        self.aws.listener = {'ActiveSlot': 'green'}
        self.aws.stacks['lookahead-dev-web-blue'] = {'status': 'UPDATE_COMPLETE', 'parameters': {**m.slotParameters(self.config, 'blue', 'old', 1)}}
        self.aws.services['lookahead-dev-web-blue'] = {'desired': 1, 'running': 1, 'rolloutState': 'COMPLETED'}
        planned = self.planned()
        slept = []
        deployer = self.deployer(planned); deployer.sleep = slept.append
        outcome = deployer.run()
        self.assertEqual('retired', outcome['steps'][-1]['step'])
        self.assertEqual('0', self.aws.stacks['lookahead-dev-web-blue']['parameters']['DesiredCount'])
        self.assertEqual('web-green.lookahead-dev.internal', self.aws.alias)
        self.assertEqual(14, slept.count(60))
        retire = next(c for c in self.aws.calls if c[1] == 'create-change-set' and 'app-12-retire' in c)
        self.assertNotIn('--tags', retire)  # a previous-template change set never touches tags
        rows = json.loads(retire[c_index(retire, '--parameters')])
        self.assertEqual({'DesiredCount': '0'}, {r['ParameterKey']: r['ParameterValue'] for r in rows if 'ParameterValue' in r})
        self.assertEqual(sorted(k for k in m.slotParameters(self.config, 'blue', 'old', 1) if k != 'DesiredCount'), sorted(r['ParameterKey'] for r in rows if r.get('UsePreviousValue')))
        logs = next(c for c in self.aws.calls if c[1] == 'filter-log-events')
        self.assertEqual(('/lookahead/dev/web', 'web/web/0123456789abcdef0123456789abcdef', '?"ERROR" ?"startup denied" ?"AccessDeniedException"'),
                         (logs[c_index(logs, '--log-group-name')], logs[c_index(logs, '--log-stream-name-prefix')], logs[c_index(logs, '--filter-pattern')]))
        self.assertTrue(logs[c_index(logs, '--start-time')].isdigit()); self.assertNotIn('--limit', logs)

    def test_phase_one_coupling_keeps_the_previous_slot_while_the_gateway_runs_on_it(self):
        self.aws.alias = 'web-blue.lookahead-dev.internal'
        self.aws.listener = {'ActiveSlot': 'blue'}
        self.aws.services['lookahead-dev-gateway-blue'] = {'desired': 1, 'running': 1, 'rolloutState': 'COMPLETED'}
        self.aws.stacks['lookahead-dev-web-blue'] = {'status': 'UPDATE_COMPLETE', 'parameters': {**m.slotParameters(self.config, 'blue', 'old', 1)}}
        planned = self.planned()
        outcome = self.deployer(planned).run()
        self.assertEqual('retire-skipped', outcome['steps'][-1]['step'])
        self.assertIn('phase 1 coupling', outcome['steps'][-1]['reason'])
        self.assertEqual('1', self.aws.stacks['lookahead-dev-web-blue']['parameters']['DesiredCount'])

    def test_unhealthy_candidate_is_scaled_back_and_nothing_flips(self):
        planned = self.planned()
        self.aws.health = 'UNHEALTHY'
        deployer = self.deployer(planned)
        with self.assertRaisesRegex(ValueError, 'task health did not complete'):
            deployer.run()
        self.assertEqual('failure', deployer.outcome['outcome'])
        self.assertIsNone(self.aws.alias)
        self.assertEqual('0', self.aws.stacks['lookahead-dev-web-blue']['parameters']['DesiredCount'])
        self.assertIn('rollback-done', [s['step'] for s in deployer.outcome['steps']])

    def test_log_failure_markers_block_the_flip(self):
        planned = self.planned()
        self.aws.logs = [{'message': 'Web TLS validation failed; startup denied'}]
        deployer = self.deployer(planned)
        with self.assertRaisesRegex(ValueError, 'failure markers'):
            deployer.run()
        self.assertIsNone(self.aws.alias)

    def test_probe_failure_during_observation_fails_the_run_after_the_flip(self):
        planned = self.planned()
        deployer = self.deployer(planned, fetch=lambda url: 503)
        with self.assertRaisesRegex(ValueError, 'Public probe returned 503'):
            deployer.run()
        self.assertEqual('web-blue.lookahead-dev.internal', self.aws.alias)

    def test_change_set_preview_must_match_the_plan_and_identity_must_match_the_session(self):
        planned = self.planned()
        self.aws.changeSetFailure = 'FAILED'
        with self.assertRaisesRegex(ValueError, 'failed: forced'):
            self.deployer(planned).run()
        self.aws.changeSetFailure = None
        self.aws.previewOverride = [{'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Service', 'Action': 'Modify', 'ResourceType': 'AWS::ECS::Service', 'Replacement': 'True'}}]
        with self.assertRaisesRegex(ValueError, 'preview differs'):
            self.deployer(planned).run()
        self.aws.previewOverride = [{'Type': 'Resource', 'ResourceChange': {'LogicalResourceId': 'Bucket', 'Action': 'Add', 'ResourceType': 'AWS::S3::Bucket', 'Replacement': None}}]
        with self.assertRaisesRegex(ValueError, 'preview differs'):
            self.deployer(planned).run()
        self.aws.previewOverride = 'no-changes'
        with self.assertRaisesRegex(ValueError, 'produced no changes'):
            self.deployer(planned).run()
        self.aws.previewOverride = None
        self.aws.session = 'image-publish-1-1'
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            self.deployer(planned).run()

    def test_listener_flip_uses_a_change_set_on_the_ingress_stack(self):
        config = json.loads((self.root / 'deployment/dev/deploy-config.json').read_text())
        config['flip'] = {'mode': 'listener', 'listenerStack': 'lookahead-dev-ingress'}
        config['coupling'] = {'requiresSameSlot': ['lookahead-dev-web-', 'lookahead-dev-domain-']}
        for slot in config['slots'].values():
            slot['targetGroupArn'] = 'arn:aws:elasticloadbalancing:us-east-2:509614632283:targetgroup/lookahead-dev-gateway-' + slot['stack'].split('-')[-1] + '/abc'
        config['slotParameters'] = {'TargetGroupArn': 'targetGroupArn'}
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(config))
        self.aws = FakeAws(config)
        self.aws.listener = {'ActiveSlot': 'blue', 'VpcId': 'vpc-1', 'NlbArn': 'nlb'}
        with self.assertRaisesRegex(ValueError, 'must be running before the Gateway'):
            self.planned()
        self.aws.services['lookahead-dev-web-green'] = self.aws.services['lookahead-dev-domain-green'] = {'desired': 1, 'running': 1, 'rolloutState': 'COMPLETED'}
        planned = self.planned()
        self.assertEqual(('green', {'checkedServices': ['lookahead-dev-web-green', 'lookahead-dev-domain-green']}), (planned['candidateSlot'], planned['coupling']))
        outcome = self.deployer(planned).run()
        self.assertEqual('green', self.aws.listener['ActiveSlot'])
        self.assertEqual('listener', next(s for s in outcome['steps'] if s['step'] == 'flipped')['mode'])
        flip = next(c for c in self.aws.calls if c[1] == 'create-change-set' and 'lookahead-dev-ingress' in c)
        self.assertIn('--use-previous-template', flip); self.assertNotIn('--tags', flip)  # never retag the Infra-owned listener stack
        rows = json.loads(flip[c_index(flip, '--parameters')])
        self.assertEqual([{'ParameterKey': 'ActiveSlot', 'ParameterValue': 'green'}, {'ParameterKey': 'NlbArn', 'UsePreviousValue': True}, {'ParameterKey': 'VpcId', 'UsePreviousValue': True}], rows)
        candidate = next(c for c in self.aws.calls if c[1] == 'create-change-set' and 'app-12-candidate' in c)
        self.assertIn('--tags', candidate)


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
        env = dict(self.env); env['GITHUB_OUTPUT'] = str(out / 'github-output')
        out.mkdir()
        (out / 'github-output').write_text('')
        planned = m.main(['plan', '--root', str(self.root), '--output', str(out / 'plan.json')], env, call=self.aws, get=self.get)
        self.aws.candidate = planned['candidateSlot']
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': 'APPROVE DEPLOY ' + planned['planDigest'], 'created_at': stamp, 'updated_at': stamp}
        self.assertIn('candidate_slot=blue', (out / 'github-output').read_text())
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
