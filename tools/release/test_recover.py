"""`05 · Recover DEV` (tools/release/recover.py): park, deploy the target, fall back, park again. AWS and GitHub are the injected
fakes of test_deploy; nothing is called for real."""
import json
from pathlib import Path
import tempfile
import unittest

import deploy
import recover as m
import owner_approval as owner
from test_deploy import APP, DeployFixture
from test_owner_approval import record

FALLBACK = 'v2026.10.20.10.00'


class RecoverFixture(DeployFixture):
    def setUp(self):
        super().setUp()
        self.github.env['GITHUB_WORKFLOW_REF'] = self.repository + '/' + deploy.RECOVER_WORKFLOW + '@refs/heads/main'
        self.github.data['actions/runs/12']['path'] = deploy.RECOVER_WORKFLOW
        self.github.data['git/ref/tags/' + FALLBACK] = {'object': {'type': 'commit', 'sha': 'a' * 40}}
        path = Path(tempfile.mkdtemp()) / 'fallback-source.json'
        record(self.repository, 'a' * 40, 'a' * 40, path)
        frozen = json.loads(path.read_text())
        frozen.update(deploy.versions.metadata(frozen['app'], FALLBACK)); frozen['selector'] = FALLBACK
        path.write_text(json.dumps(frozen))
        self.env = dict(self.github.env, FALLBACK_RELEASE_VERSION=FALLBACK, FALLBACK_SOURCE_RECORD=str(path),
                        FALLBACK_SOURCE='input', RECOVER_HEALTHY_MINUTES='15')
        self.out = Path(tempfile.mkdtemp())

    def recovery(self):
        planned = m.plan(self.env, self.aws, self.root, self.get, now=self.now)
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comment = lambda: {'id': 7, 'user': {'login': owner.OWNER}, 'body': m.commands(planned)['deploy'], 'created_at': stamp, 'updated_at': stamp}
        return planned

    def approvedRecovery(self, planned):
        return m.approve(planned, self.env, self.root, self.get, now=self.clock, sleep=lambda s: None)

    def stage(self, name, planned, receipt, unhealthy=False):
        self.aws.session = m.stagePlan(planned, name)['sessionName']
        self.aws.executeStatus = 'UPDATE_ROLLBACK_COMPLETE' if unhealthy else None
        return m.runStage(name, planned, receipt, self.root, self.aws, sleep=lambda s: None, now=self.clock,
                          fetch=lambda url: 200, log=lambda line, **_: None)


class PlanTests(RecoverFixture):
    def test_five_stages_bind_their_release_count_change_set_and_session(self):
        self.aws.serving('old')
        planned = self.recovery()
        self.assertEqual([('park', 'v2026.10.26.20.00', 0, 'app-12-park', 'web-deploy-12-1'), ('target', 'v2026.10.26.20.00', 1, 'app-12-target', 'web-deploy-12-2'),
                          ('park-again', 'v2026.10.26.20.00', 0, 'app-12-park-again', 'web-deploy-12-3'), ('fallback', FALLBACK, 1, 'app-12-fallback', 'web-deploy-12-4'),
                          ('park-final', FALLBACK, 0, 'app-12-park-final', 'web-deploy-12-5')],
                         [(s['stage'], s['releaseVersion'], s['desiredCount'], s['changeSet'], s['sessionName']) for s in planned['stages']])
        self.assertTrue(all(s['choices'] == ['deploy'] and not s['unchanged'] for s in planned['stages']))
        self.assertEqual((APP + '-' + FALLBACK, 'input', 15), (planned['fallbackImageTag'], planned['fallbackSource'], planned['healthyMinutes']))
        self.assertEqual(planned['planDigest'], deploy.digest({k: v for k, v in planned.items() if k != 'planDigest'}))
        m.validate(planned, self.env, self.root, self.get)
        tampered = dict(planned, healthyMinutes=40)
        with self.assertRaisesRegex(ValueError, 'Recovery plan digest changed'):
            m.validate(tampered, self.env, self.root, self.get)

    def test_a_busy_stack_still_plans_every_stage(self):
        self.aws.serving('old'); self.aws.stack['status'] = 'UPDATE_IN_PROGRESS'
        planned = self.recovery()
        self.assertEqual([True, False, True, False, True], [s['busyStackPark'] for s in planned['stages']])

    def test_recovery_needs_a_deployed_stack_a_distinct_fallback_and_a_bounded_wait(self):
        with self.assertRaisesRegex(ValueError, 'does not exist yet'):
            self.recovery()
        self.aws.serving('old')
        for bad in ('4', '41', 'x'):
            with self.assertRaisesRegex(ValueError, 'healthy_minutes must be 5..40'):
                m.plan(dict(self.env, RECOVER_HEALTHY_MINUTES=bad), self.aws, self.root, self.get, now=self.now)
        with self.assertRaisesRegex(ValueError, 'fallback release other than the target'):
            m.plan(dict(self.env, FALLBACK_RELEASE_VERSION=self.env['RELEASE_VERSION']), self.aws, self.root, self.get, now=self.now)


class FallbackChoiceTests(RecoverFixture):
    def runs(self, workflow, rows):
        self.github.data['actions/workflows/' + workflow + '/runs?branch=main&status=success&per_page=20'] = {'workflow_runs': rows}

    def history(self):
        self.runs('deploy-dev.yml', [{'id': 31, 'run_started_at': '2026-11-02T10:00:00Z', 'display_title': '04 · Deploy DEV | v2026.11.02.09.00 | desired 0 | workflow_dispatch | #9'},
                                     {'id': 30, 'run_started_at': '2026-11-01T10:00:00Z', 'display_title': '04 · Deploy DEV | v2026.11.01.09.00 | desired 1 | workflow_dispatch | #8'}])
        self.runs('release.yml', [{'id': 40, 'run_started_at': '2026-11-02T08:00:00Z', 'display_title': '01 · Full | main'}])
        self.runs('publish.yml', [])
        jobs = {31: 'success', 30: 'success', 40: 'success'}
        for run, conclusion in jobs.items():
            name = 'Deploy to DEV (04 · Deploy DEV) / Deploy to DEV' if run == 40 else 'Deploy to DEV'
            self.github.data['actions/runs/' + str(run) + '/jobs?per_page=100'] = {'jobs': [{'name': 'Security gate', 'conclusion': 'success'}, {'name': name, 'conclusion': conclusion}]}
        self.github.data['actions/runs/30/artifacts?per_page=100'] = {'artifacts': [{'name': 'deploy-record-v2026.11.01.09.00-30'}]}
        self.github.data['actions/runs/40/artifacts?per_page=100'] = {'artifacts': [{'name': 'release-record'}, {'name': 'deploy-record-v2026.11.02.07.00-40'}]}

    def test_newest_verified_serving_deploy_wins_and_park_runs_are_skipped(self):
        self.history()
        self.assertEqual({'releaseVersion': 'v2026.11.02.07.00', 'source': 'last verified deploy, run 40'}, m.chooseFallback(self.env, self.get))
        # the target itself is never its own fallback: the next older verified release is used
        self.assertEqual('v2026.11.01.09.00', m.chooseFallback(dict(self.env, RELEASE_VERSION='v2026.11.02.07.00'), self.get)['releaseVersion'])

    def test_failed_deploy_jobs_do_not_count_and_no_history_requires_the_input(self):
        self.history()
        for run in (40, 30):
            self.github.data['actions/runs/' + str(run) + '/jobs?per_page=100']['jobs'][1]['conclusion'] = 'failure'
        with self.assertRaisesRegex(ValueError, 'dispatch again with fallback_release'):
            m.chooseFallback(self.env, self.get)

    def test_an_explicit_fallback_is_used_as_given_and_must_be_another_tag(self):
        self.assertEqual({'releaseVersion': FALLBACK, 'source': 'input'}, m.chooseFallback(dict(self.env, FALLBACK_INPUT=FALLBACK), self.get))
        for bad in ('main', self.env['RELEASE_VERSION']):
            with self.assertRaisesRegex(ValueError, 'fallback_release must be another release tag'):
                m.chooseFallback(dict(self.env, FALLBACK_INPUT=bad), self.get)
        with self.assertRaisesRegex(ValueError, 'Target release tag'):
            m.chooseFallback(dict(self.env, RELEASE_VERSION='latest'), self.get)


class ApprovalTests(RecoverFixture):
    def test_one_issue_covers_the_sequence_and_the_live_answer_is_rechecked(self):
        self.aws.serving('old')
        planned = self.recovery()
        receipt = self.approvedRecovery(planned)
        self.assertEqual((7, 'deploy'), (receipt['commentId'], receipt['mode']))
        issue = self.github.data['issues/3']
        self.assertIn('Recover ' + APP, issue['title'])
        for phrase in ('APPROVE RECOVER ' + planned['imageTag'], 'app-12-park-final', 'web-deploy-12-5', planned['fallbackImageTag'], 'Park', 'Fallback'):
            self.assertIn(phrase, issue['body'])
        m.verify(planned, receipt, self.env, self.root, self.get, self.now)
        self.github.comment = lambda: {'id': 8, 'user': {'login': owner.OWNER}, 'body': 'REJECT', 'created_at': receipt['createdAt'], 'updated_at': receipt['createdAt']}
        self.github.comments = [self.github.comment()]
        with self.assertRaisesRegex(ValueError, 'Owner rejected'):
            m.verify(planned, receipt, self.env, self.root, self.get, self.now)

    def test_no_answer_times_out(self):
        self.aws.serving('old')
        planned = self.recovery()
        self.github.autoApprove = False
        with self.assertRaisesRegex(TimeoutError, 'Recovery approval timed out'):
            self.approvedRecovery(planned)


class StageTests(RecoverFixture):
    def ready(self):
        self.aws.serving('old')
        planned = self.recovery()
        return planned, self.approvedRecovery(planned)

    def test_target_serves_after_the_park(self):
        planned, receipt = self.ready()
        parked = self.stage('park', planned, receipt)
        self.assertEqual(('parked', False, 0), (parked['outcome'], parked['busyStackPark'], self.aws.service['desired']))
        served = self.stage('target', planned, receipt)
        self.assertEqual(('healthy', 1), (served['outcome'], self.aws.service['running']))
        self.assertIn('observed', [s['step'] for s in served['steps']])
        self.assertEqual(['app-12-park', 'app-12-target'], [c[c.index('--change-set-name') + 1] for c in self.aws.ops('cloudformation', 'execute-change-set')])

    def failedTarget(self):
        planned, receipt = self.ready()
        self.stage('park', planned, receipt)
        failed = self.stage('target', planned, receipt, unhealthy=True)
        self.assertEqual('unhealthy', failed['outcome'])
        self.assertIn('UPDATE_ROLLBACK_COMPLETE', failed['error'])
        self.assertIn('stops', failed)
        self.assertEqual('parked', self.stage('park-again', planned, receipt)['outcome'])
        return planned, receipt

    def test_unhealthy_target_falls_back_to_a_serving_release(self):
        planned, receipt = self.failedTarget()
        self.assertEqual('healthy', self.stage('fallback', planned, receipt)['outcome'])
        self.assertEqual(1, self.aws.service['running'])

    def test_an_unhealthy_fallback_ends_parked(self):
        planned, receipt = self.failedTarget()
        self.assertEqual('unhealthy', self.stage('fallback', planned, receipt, unhealthy=True)['outcome'])
        self.assertEqual('parked', self.stage('park-final', planned, receipt)['outcome'])
        self.assertEqual(0, self.aws.service['desired'])

    def test_park_on_a_busy_stack_scales_down_directly_then_records_the_park(self):
        planned, receipt = self.ready()
        self.aws.stack['status'] = 'UPDATE_ROLLBACK_IN_PROGRESS'; self.aws.busyPolls = 1; self.aws.settleStatus = 'UPDATE_ROLLBACK_COMPLETE'
        parked = self.stage('park', planned, receipt)
        self.assertEqual(('parked', True), (parked['outcome'], parked['busyStackPark']))
        self.assertEqual(1, len(self.aws.ops('ecs', 'update-service')))

    def test_failures_before_any_change_or_in_a_park_stop_the_run(self):
        planned, receipt = self.ready()
        self.aws.session = 'someone-else'
        with self.assertRaises(m.StageFailed) as caught:
            m.runStage('target', planned, receipt, self.root, self.aws, sleep=lambda s: None, now=self.clock, fetch=lambda url: 200, log=lambda line, **_: None)
        self.assertEqual('unhealthy', caught.exception.result['outcome'])  # never reached a change set: no fallback on a wrong session
        self.aws.stack['status'] = 'UPDATE_ROLLBACK_FAILED'
        with self.assertRaisesRegex(ValueError, 'neither parked nor updated'):
            self.stage('park', planned, receipt)
        with self.assertRaisesRegex(ValueError, 'does not cover this recovery plan'):
            m.runStage('park', planned, dict(receipt, mode='refresh'), self.root, self.aws)

    def test_stop_reasons_are_read_when_a_serving_stage_fails(self):
        calls = []

        def call(*args):
            calls.append(args)
            if args[1] == 'list-tasks':
                return {'taskArns': ['arn:task/1']}
            return {'tasks': [{'taskDefinitionArn': 'arn:x/lookahead-dev-web:7', 'stopCode': 'TaskFailedToStart', 'stoppedReason': 'Task failed container health checks',
                               'healthStatus': 'UNHEALTHY', 'containers': [{'exitCode': 137}]}]}
        self.assertEqual([{'taskDefinition': 'lookahead-dev-web:7', 'stopCode': 'TaskFailedToStart', 'stoppedReason': 'Task failed container health checks',
                           'health': 'UNHEALTHY', 'exitCode': 137}], m.lastStops(call, deploy.loadConfig(self.root)))
        self.assertEqual([], m.lastStops(lambda *a: {'taskArns': []}, deploy.loadConfig(self.root)))

        def denied(*args):
            raise RuntimeError('aws ecs list-tasks failed: AccessDenied')
        self.assertIn('AccessDenied', m.lastStops(denied, deploy.loadConfig(self.root))[0]['error'])


class SummaryAndCommandTests(RecoverFixture):
    def test_summary_names_every_stage_and_whether_anything_serves(self):
        self.aws.serving('old')
        planned = self.recovery()
        directory = self.out / 'stages'; directory.mkdir()
        rows = {'park': ('parked', None), 'target': ('unhealthy', [{'taskDefinition': 'lookahead-dev-web:7', 'stoppedReason': 'Task failed container health checks', 'health': 'UNHEALTHY', 'exitCode': 137}]),
                'park-again': ('parked', None), 'fallback': ('healthy', None)}
        for name, (outcome, stops) in rows.items():
            stage = m.stagePlan(planned, name)
            deploy.write(directory / (name + '.json'), {'stage': name, 'releaseVersion': stage['releaseVersion'], 'desiredCount': stage['desiredCount'], 'outcome': outcome,
                                                        'taskDefinition': 'lookahead-dev-web:8', 'startedAt': 's', 'finishedAt': 'f', **({'stops': stops, 'error': 'rolled back'} if stops else {})})
        text, served = m.summary(planned, directory)
        self.assertEqual(FALLBACK, served)
        for phrase in ('park-final | — | — | not run', 'Task failed container health checks (health UNHEALTHY, exit 137)', '**Serving:** `' + FALLBACK + '`'):
            self.assertIn(phrase, text)
        (directory / 'fallback.json').unlink()
        text, served = m.summary(planned, directory)
        self.assertIsNone(served); self.assertIn('Nothing serves', text)

    def test_command_line_runs_each_mode_and_fails_when_nothing_serves(self):
        self.aws.serving('old')
        planned = self.recovery()
        outputs = self.out / 'outputs.txt'; summary = self.out / 'summary.md'
        env = dict(self.env, GITHUB_OUTPUT=str(outputs), GITHUB_STEP_SUMMARY=str(summary), FALLBACK_INPUT=FALLBACK)
        self.assertEqual(FALLBACK, m.main(['fallback'], env=env, get=self.get)['releaseVersion'])
        planPath, receiptPath, stages = self.out / 'plan.json', self.out / 'approval.json', self.out / 'result'
        m.main(['plan', '--output', str(planPath), '--root', str(self.root)], env=env, call=self.aws, get=self.get)
        deploy.write(receiptPath, self.approvedRecovery(deploy.readJson(planPath, 1024 * 1024)))
        m.main(['verify', '--plan', str(planPath), '--receipt', str(receiptPath), '--root', str(self.root)], env=env, call=self.aws, get=self.get, now=self.now)
        planned = deploy.readJson(planPath, 1024 * 1024)
        for name in ('park', 'target'):
            self.aws.session = m.stagePlan(planned, name)['sessionName']
            m.main(['stage', '--stage', name, '--plan', str(planPath), '--receipt', str(receiptPath), '--dir', str(stages), '--root', str(self.root)],
                   env=env, call=self.aws, get=self.get, sleep=lambda s: None, fetch=lambda url: 200)
        self.assertIn('healthy=true', outputs.read_text())
        self.assertIn('fallback_release=' + FALLBACK, outputs.read_text())
        self.assertEqual('v2026.10.26.20.00', m.main(['summary', '--plan', str(planPath), '--dir', str(stages)], env=env))
        self.assertIn('05 · Recover DEV', summary.read_text())
        (stages / 'target.json').unlink()
        with self.assertRaisesRegex(ValueError, 'without a serving release'):
            m.main(['summary', '--plan', str(planPath), '--dir', str(stages)], env=env)
        self.aws.session = 'web-deploy-12-9'
        with self.assertRaises(m.StageFailed):
            m.main(['stage', '--stage', 'target', '--plan', str(planPath), '--receipt', str(receiptPath), '--dir', str(stages), '--root', str(self.root)],
                   env=env, call=self.aws, get=self.get, sleep=lambda s: None, fetch=lambda url: 200)
        self.assertEqual('unhealthy', deploy.readJson(stages / 'target.json')['outcome'])


if __name__ == '__main__':
    unittest.main()
