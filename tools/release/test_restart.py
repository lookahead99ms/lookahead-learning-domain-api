"""Tests for `06 · Restart DEV` (tools/release/restart.py); AWS and GitHub are fakes, nothing is called for real."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import unittest.mock

import restart as m
import deploy
import owner_approval as owner
from test_deploy import APP, CONFIG, FakeAws, REPOSITORY, RESTARTED_TASK, ROOT, TASK

SHA = 'a' * 40
NOW = datetime(2026, 11, 5, 19, 0, tzinfo=timezone.utc)  # 14:00 in New York
NY_2350 = datetime(2026, 11, 6, 4, 50, tzinfo=timezone.utc)
REVISION = 'arn:aws:ecs:us-east-2:509614632283:task-definition/lookahead-dev-web:3'


class Github:
    """The GitHub API as restart.py reads it: repository, main, the run, one issue and its comments."""

    def __init__(self):
        self.env = {'GITHUB_REPOSITORY': REPOSITORY, 'GITHUB_ACTOR': owner.OWNER, 'GITHUB_TRIGGERING_ACTOR': owner.OWNER, 'GITHUB_REF': 'refs/heads/main',
                    'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_WORKFLOW_REF': REPOSITORY + '/' + m.WORKFLOW + '@refs/heads/main',
                    'GITHUB_SHA': SHA, 'GITHUB_RUN_ID': '12', 'GITHUB_REPOSITORY_ID': '10', 'GITHUB_REPOSITORY_OWNER_ID': '20'}
        self.data = {'': {'full_name': REPOSITORY, 'id': 10, 'owner': {'id': 20, 'login': owner.OWNER}, 'private': True}, 'commits/main': {'sha': SHA},
                     'actions/runs/12': {'id': 12, 'path': m.WORKFLOW, 'head_sha': SHA, 'head_branch': 'main', 'event': 'workflow_dispatch', 'run_attempt': 1,
                                         'status': 'in_progress', 'actor': {'login': owner.OWNER}, 'triggering_actor': {'login': owner.OWNER}}}
        self.issues, self.comments, self.answer = {}, [], None

    def get(self, repository, path, payload=None):
        assert repository == REPOSITORY, repository
        if path == 'issues' and payload is not None:
            number = 3 + len(self.issues)
            self.issues[number] = {'number': number, 'state': 'open', 'user': {'login': 'github-actions[bot]'}, 'title': payload['title'], 'body': payload['body']}
            return self.issues[number]
        if path.startswith('issues/') and path.endswith('/comments?per_page=100'):
            if self.answer and not self.comments:
                self.comments.append({'id': 7, 'user': {'login': owner.OWNER}, 'body': self.answer, 'created_at': NOW.isoformat(), 'updated_at': NOW.isoformat()})
            return list(self.comments)
        if path.startswith('issues/'):
            return self.issues[int(path.split('/')[1])]
        return self.data[path]


class RestartFixture(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / 'deployment/dev').mkdir(parents=True)
        shutil.copy(ROOT / 'deployment/dev/service.template.json', self.root / 'deployment/dev/service.template.json')
        self.config = json.loads(json.dumps(CONFIG))
        (self.root / 'deployment/dev/deploy-config.json').write_text(json.dumps(self.config))
        self.github = Github()
        self.env = dict(self.github.env)
        self.aws = FakeAws(self.config)
        self.aws.serving('a')  # one task on revision 3 with a hex digest, rollout long settled (no updatedAt reported)
        self.aws.session = 'web-deploy-restart-12-1'
        self.logged = []

    def get(self, repository, path, payload=None):
        return self.github.get(repository, path, payload)

    def planned(self, now=NOW):
        return m.plan(self.env, self.aws, self.root, self.get, now=now)

    def approved(self, planned):
        self.github.answer = m.command(planned)
        return m.approve(planned, self.env, self.root, self.get, now=lambda: NOW, sleep=lambda s: None)

    def restarter(self, planned, now=NOW):
        return m.Restarter(planned, deploy.loadConfig(self.root), self.aws, sleep=lambda s: None, now=lambda: now, log=lambda line, **_: self.logged.append(line))


class PlanTests(RestartFixture):
    def test_plan_binds_the_running_revision_task_and_image(self):
        planned = self.planned()
        self.assertEqual((m.SCHEMA_PLAN, APP, 'lookahead-dev-web', REVISION, TASK, 'web-deploy-restart-12-1', None, 5),
                         (planned['schema'], planned['app'], planned['stack'], planned['revision'], planned['task'], planned['sessionName'], planned['rolloutAgeSeconds'], planned['observationMinutes']))
        self.assertEqual('sha256:' + 'a' * 64, planned['imageDigest'])
        self.assertEqual(planned['planDigest'], owner.digest({k: v for k, v in planned.items() if k != 'planDigest'}))
        self.assertEqual('APPROVE RESTART ' + planned['planDigest'], m.command(planned))
        m.validatePlan(planned, self.env, self.root, self.get)
        changed = dict(planned, revision=REVISION.replace(':3', ':4'))
        with self.assertRaisesRegex(ValueError, 'plan digest changed'):
            m.validatePlan(changed, self.env, self.root, self.get)

    def test_plan_refuses_a_busy_service_the_blackout_and_a_foreign_run(self):
        self.aws.rolloutAt = (NOW - timedelta(seconds=120)).isoformat()  # a deploy may watch it for 13 more minutes
        with self.assertRaisesRegex(ValueError, 'completed 120 s ago and a deploy may be watching it until 19:13 UTC'):
            self.planned()
        self.aws.rolloutAt = (NOW - timedelta(seconds=m.WATCH_SECONDS)).isoformat()
        self.assertEqual(m.WATCH_SECONDS, self.planned()['rolloutAgeSeconds'])
        self.aws.rolloutAt = NOW.timestamp() - 3600  # epoch seconds are read too
        self.assertEqual(3600, self.planned()['rolloutAgeSeconds'])
        self.aws.rolloutAt = None
        self.aws.restart()  # a rollout in progress
        with self.assertRaisesRegex(ValueError, 'a rollout is in progress'):
            self.planned()
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.service['running'] = 0
        with self.assertRaisesRegex(ValueError, 'not serving one task'):
            self.planned()
        self.aws = FakeAws(self.config); self.aws.serving('a')
        with self.assertRaisesRegex(ValueError, 'No deploy between 23:30 and 00:30 New York time \\(at planning\\)'):
            self.planned(now=NY_2350)
        self.assertEqual([], self.aws.calls)  # the blackout is refused before any AWS read
        for field, value in (('GITHUB_EVENT_NAME', 'push'), ('GITHUB_WORKFLOW_REF', REPOSITORY + '/' + deploy.WORKFLOW + '@refs/heads/main'),
                             ('GITHUB_ACTOR', 'other'), ('GITHUB_RUN_ATTEMPT', '2'), ('GITHUB_REF', 'refs/heads/feature')):
            with self.subTest(field=field), self.assertRaises(ValueError):
                m.plan({**self.env, field: value}, self.aws, self.root, self.get, now=NOW)
        self.github.data['commits/main'] = {'sha': 'b' * 40}
        with self.assertRaisesRegex(ValueError, 'Main moved'):
            self.planned()


class ApprovalTests(RestartFixture):
    def test_approve_opens_the_issue_and_returns_the_owner_comment_then_verify_rechecks_it(self):
        planned = self.planned()
        receipt = self.approved(planned)
        self.assertEqual((m.SCHEMA_RECEIPT, 3, 7, NOW.isoformat(), 10, 15), (receipt['schema'], receipt['issue'], receipt['commentId'], receipt['approvedAt'], receipt['waitMinutes'], receipt['validMinutes']))
        issue = self.github.issues[3]
        self.assertIn('Restart ' + APP, issue['title'])
        for words in ('APPROVE RESTART ' + planned['planDigest'], 'force new deployment of revision 3', 'AWS::ECS::Service', 'no image, stack or configuration change', 'HEALTHY, same image, clean log'):
            self.assertIn(words, issue['body'])
        m.verify(planned, receipt, self.env, self.root, self.get, now=NOW + timedelta(minutes=14))
        with self.assertRaisesRegex(ValueError, 'Approval expired'):
            m.verify(planned, receipt, self.env, self.root, self.get, now=NOW + timedelta(minutes=16))
        with self.assertRaisesRegex(ValueError, 'Approval windows changed'):
            m.verify(planned, receipt, {**self.env, 'APPROVAL_VALID_MINUTES': '30'}, self.root, self.get, now=NOW)
        issue['body'] += ' edited'
        with self.assertRaisesRegex(ValueError, 'Approval issue changed or closed'):
            m.verify(planned, receipt, self.env, self.root, self.get, now=NOW)

    def test_rejection_silence_and_a_wrong_comment_never_approve(self):
        planned = self.planned()
        self.github.answer = 'REJECT'
        with self.assertRaisesRegex(ValueError, 'Owner rejected'):
            m.approve(planned, self.env, self.root, self.get, now=lambda: NOW, sleep=lambda s: None)
        self.github = Github(); self.github.answer = 'APPROVE RESTART ' + 'f' * 64
        with self.assertRaises(TimeoutError):
            m.approve(planned, self.env, self.root, self.get, now=lambda: NOW, sleep=lambda s: None)
        self.github = Github()
        with self.assertRaises(TimeoutError):
            m.approve(planned, {**self.env, 'APPROVAL_WAIT_MINUTES': '2'}, self.root, self.get, now=lambda: NOW, sleep=lambda s: None)
        with self.assertRaisesRegex(ValueError, 'between 2 and 15'):
            m.approve(planned, {**self.env, 'APPROVAL_WAIT_MINUTES': '40'}, self.root, self.get, now=lambda: NOW, sleep=lambda s: None)


class ExecuteTests(RestartFixture):
    def test_restart_forces_a_rollout_verifies_the_new_task_and_watches_it(self):
        planned = self.planned()
        outcome = self.restarter(planned).run()
        self.assertEqual(('success', ['identity', 'restarted', 'verified', 'observed']), (outcome['outcome'], [s['step'] for s in outcome['steps']]))
        self.assertEqual((RESTARTED_TASK, TASK), (outcome['steps'][2]['task'], outcome['steps'][2]['previousTask']))
        self.assertEqual(5, outcome['steps'][3]['minutes'])
        self.assertEqual(1, sum(c[1] == 'update-service' for c in self.aws.calls))
        self.assertEqual(['--force-new-deployment'], [a for a in next(c for c in self.aws.calls if c[1] == 'update-service') if a.startswith('--force')])
        self.assertEqual(5, sum(line.startswith('watch ') for line in self.logged))

    def test_restart_refuses_a_moved_revision_a_busy_service_the_blackout_and_a_wrong_session(self):
        planned = self.planned()
        self.aws.stack['parameters']['ApplicationImage'] = self.config['imageRepository'] + '@sha256:' + 'n' * 64; self.aws.restartRevision = REVISION.replace(':3', ':4')
        with self.assertRaisesRegex(ValueError, 'moved to another revision'):
            self.restarter(planned).run()
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.session = 'web-deploy-restart-12-1'; self.aws.restart()
        with self.assertRaisesRegex(ValueError, 'a rollout is in progress'):
            self.restarter(planned).run()
        self.assertEqual(0, sum(c[1] == 'update-service' for c in self.aws.calls))
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.session = 'web-deploy-restart-12-1'
        with self.assertRaisesRegex(ValueError, 'at execution'):
            self.restarter(planned, now=NY_2350).run()
        self.aws.session = 'web-deploy-12-1'
        with self.assertRaisesRegex(ValueError, 'session identity mismatch'):
            self.restarter(planned).run()

    def test_an_unhealthy_or_unchanged_task_a_log_marker_or_a_stalled_rollout_fails(self):
        planned = self.planned()
        self.aws.health = 'UNHEALTHY'
        with self.assertRaisesRegex(ValueError, 'task health did not complete'):
            self.restarter(planned).run()
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.session = 'web-deploy-restart-12-1'; self.aws.logs = [{'message': 'ERROR boot'}]
        with self.assertRaisesRegex(ValueError, 'failure markers: ERROR boot'):
            self.restarter(planned).run()
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.session = 'web-deploy-restart-12-1'; self.aws.forcePolls = 10 ** 6
        with self.assertRaisesRegex(ValueError, 'rollout of lookahead-dev-web did not complete within 600 seconds'):
            self.restarter(planned).run()
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.session = 'web-deploy-restart-12-1'
        self.aws.onRound = lambda count: setattr(self.aws, 'taskArns', [TASK])  # the rollout settles but the same task is listed
        with self.assertRaisesRegex(ValueError, 'did not replace the task'):
            self.restarter(planned).run()
        self.aws = FakeAws(self.config); self.aws.serving('a'); self.aws.session = 'web-deploy-restart-12-1'; self.aws.containerDigest = 'sha256:' + 'x' * 64
        with self.assertRaisesRegex(ValueError, 'Running container reports no image digest|does not carry the planned image'):
            self.planned() if False else self.restarter(planned).run()


class WorkflowAndCliTests(RestartFixture):
    def test_workflow_mirrors_the_deploy_workflow_with_restart_sessions_and_no_inputs(self):
        wf = json.loads((ROOT / m.WORKFLOW).read_text())
        self.assertEqual(('06 · Restart DEV', {}), (wf['name'], wf['on']['workflow_dispatch']))
        self.assertEqual(['validate', 'plan-restart', 'approve-restart', 'restart-dev', 'record'], list(wf['jobs']))
        self.assertEqual(json.loads((ROOT / deploy.WORKFLOW).read_text())['concurrency'], wf['concurrency'])  # never beside a deploy of this app
        prefix = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())['sessionPrefix']
        sessions = [s['with']['role-session-name'] for job in wf['jobs'].values() for s in job['steps'] if str(s.get('uses', '')).startswith('aws-actions/')]
        self.assertEqual([prefix + '-restart-plan-${{ github.run_id }}-1', prefix + '-restart-${{ github.run_id }}-1'], sessions)
        runs = [s['run'] for job in wf['jobs'].values() for s in job['steps'] if 'tools/release/restart.py ' in str(s.get('run', ''))]
        self.assertEqual(['plan', 'approve', 'verify', 'execute'], [r.split('tools/release/restart.py ')[1].split()[0] for r in runs])
        self.assertFalse(any('six_role' in json.dumps(s) for job in wf['jobs'].values() for s in job['steps']))
        self.assertEqual('dev', wf['jobs']['restart-dev']['environment'])
        names = [s['with']['name'] for job in wf['jobs'].values() for s in job['steps'] if str(s.get('uses', '')).startswith('actions/upload-artifact@')]
        self.assertEqual(['restart-plan', 'restart-approval', 'restart-result', 'restart-record-${{ github.run_id }}'], names)
        deployWf = json.loads((ROOT / deploy.WORKFLOW).read_text())
        self.assertIn('*/restart.py', json.dumps(deployWf['jobs']['validate']))

    def test_cli_plan_approve_verify_execute_write_once(self):
        out = self.root / 'out'; out.mkdir()
        env = dict(self.env, GITHUB_OUTPUT=str(out / 'github-output'))
        (out / 'github-output').write_text('')
        with unittest.mock.patch.object(deploy, 'utc', side_effect=lambda now=None: NOW if now is None else now):
            planned = m.main(['plan', '--root', str(self.root), '--output', str(out / 'plan.json')], env, call=self.aws, get=self.get)
            self.assertIn('plan_digest=' + planned['planDigest'], (out / 'github-output').read_text())
            with self.assertRaises(FileExistsError):
                m.main(['plan', '--root', str(self.root), '--output', str(out / 'plan.json')], env, call=self.aws, get=self.get)
            self.github.answer = m.command(planned)
            receipt = m.main(['approve', '--root', str(self.root), '--plan', str(out / 'plan.json'), '--receipt', str(out / 'approval.json')], env, get=self.get, sleep=lambda s: None)
            self.assertEqual(receipt, json.loads((out / 'approval.json').read_text()))
            m.main(['verify', '--root', str(self.root), '--plan', str(out / 'plan.json'), '--receipt', str(out / 'approval.json')], env, get=self.get)
            outcome = m.main(['execute', '--root', str(self.root), '--plan', str(out / 'plan.json'), '--receipt', str(out / 'approval.json'), '--output', str(out / 'result.json')],
                             env, call=self.aws, get=self.get, sleep=lambda s: None)
        self.assertEqual('success', outcome['outcome'])
        self.assertEqual(outcome, json.loads((out / 'result.json').read_text()))
        with self.assertRaisesRegex(ValueError, '--plan and --receipt required'):
            m.main(['verify', '--root', str(self.root)], env, get=self.get)


if __name__ == '__main__':
    unittest.main()
