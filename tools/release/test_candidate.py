"""DEV candidate stages with a fake GitHub provider and fake receipts; no network, no AWS."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import candidate as m
from test_owner_approval import Github

ROOT = Path(__file__).resolve().parents[2]
APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'
REPO = next(repo for repo, (_, app) in m.approval.PUBLISHERS.items() if app == APP)
DIGEST = 'sha256:' + '1' * 64


class Fixture:
    """A run that published image DIGEST for v2026.10.26.20.00 and deployed it as the DEV candidate."""
    def __init__(self, temp):
        self.temp = Path(temp)
        self.github = Github(REPO)
        self.env = dict(self.github.env)
        self.registry = self.temp / 'registry-image.json'
        self.registry.write_text(json.dumps({'revision': 'a' * 40, 'image': {'imageDigest': DIGEST, 'imageTag': APP + '-v2026.10.26.20.00'}}))
        self.result = self.temp / 'app-result'; self.result.mkdir()
        (self.result / 'candidate.json').write_text(json.dumps({'releaseVersion': 'v2026.10.26.20.00', 'running': {'imageDigest': DIGEST}, 'healthy': True}))

    def verified(self, url=None, get=None):
        return m.verify(self.env, self.result, self.registry, url, get or (lambda u: (200, 'release v2026.10.26.20.00 ok')), self.github.now)


class CandidateTests(unittest.TestCase):
    def test_infra_tooling_is_the_newest_calendar_tag_commit_and_needs_the_read_token(self):
        rows = '\n'.join(['refs/tags/v2026.10.27.01.00\tcommit\t' + 'b' * 40, 'refs/tags/v2026.10.28.09.00\tcommit\t' + 'c' * 40,
                          'refs/tags/v2026.10.28.09.01\tcommit\t' + 'd' * 40, 'refs/tags/v2026.10.28.10.00\ttag\t' + 'e' * 40,
                          'refs/tags/v1.2.3\tcommit\t' + 'f' * 40, 'garbage'])
        calls = []
        def run(args, env=None):
            calls.append((args, env.get('GH_TOKEN'))); return rows
        result = m.infraTooling({'CROSS_REPO_READ_TOKEN': 'synthetic'}, run, datetime(2026, 10, 28, tzinfo=timezone.utc))
        self.assertEqual(('v2026.10.28.09.01', 'd' * 40, m.INFRA, m.SCHEMA_INFRA), (result['releaseVersion'], result['sourceSha'], result['repository'], result['schema']))
        self.assertEqual(calls[0][1], 'synthetic'); self.assertIn('repos/' + m.INFRA + '/git/matching-refs/tags/v', calls[0][0])
        with self.assertRaisesRegex(ValueError, 'CROSS_REPO_READ_TOKEN'): m.infraTooling({}, run)
        with self.assertRaisesRegex(ValueError, 'no calendar release tag'): m.infraTooling({'CROSS_REPO_READ_TOKEN': 't'}, lambda a, env=None: 'refs/tags/v1\tcommit\t' + 'a' * 40)

    def test_verify_binds_result_to_release_and_published_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp)
            result = f.verified()
            self.assertEqual((result['schema'], result['releaseVersion'], result['imageDigest'], result['verified'], result['live']),
                             (m.SCHEMA_VERIFY, 'v2026.10.26.20.00', DIGEST, True, None))
            live = f.verified('https://dev.example.test/release')
            self.assertEqual(live['live'], {'url': 'https://dev.example.test/release', 'status': 200, 'namesRelease': True})
            for mode, message in (('tag', 'does not name release'), ('digest', 'published image digest'), ('empty', 'result is missing'),
                                  ('bind', 'does not bind'), ('http', 'must be https'), ('status', 'answered 503'), ('body', 'does not report release')):
                g = Fixture(tempfile.mkdtemp(dir=temp))
                url, get = None, None
                if mode == 'tag': (g.result / 'candidate.json').write_text(json.dumps({'releaseVersion': 'v2026.10.26.21.00', 'imageDigest': DIGEST}))
                if mode == 'digest': (g.result / 'candidate.json').write_text(json.dumps({'releaseVersion': 'v2026.10.26.20.00'}))
                if mode == 'empty': (g.result / 'candidate.json').unlink()
                if mode == 'bind': g.registry.write_text(json.dumps({'revision': '9' * 40, 'image': {'imageDigest': DIGEST}}))
                if mode == 'http': url = 'http://dev.example.test/release'
                if mode == 'status': url, get = 'https://dev.example.test/x', lambda u: (503, 'down')
                if mode == 'body': url, get = 'https://dev.example.test/x', lambda u: (200, 'v2026.10.26.21.00')
                with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, message): g.verified(url, get)

    def test_decision_keep_rollback_reject_edit_and_timeout(self):
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp); verification = f.verified()
            plan = m.planFor(f.env, f.github.get)
            keep, back = m.commands('dev', plan).values()
            self.assertTrue(keep.startswith('APPROVE KEEP DEV ') and back.startswith('APPROVE ROLLBACK DEV ') and keep.endswith(m.approval.digest(plan)))
            def comment(text, edited=False):
                stamp = f.github.now.replace(microsecond=0).isoformat()
                return {'id': 9, 'user': {'login': m.approval.OWNER}, 'body': text, 'created_at': stamp, 'updated_at': stamp[:-6] + '+01:00' if edited else stamp}
            def run(comments):
                f.github.autoApprove = False
                def get(repository, path, payload=None):
                    if path == 'issues/3/comments?per_page=100':
                        return comments
                    return f.github.get(repository, path, payload)
                return m.decide('dev', f.env, verification, get, lambda: f.github.now, lambda _: None)
            kept = run([comment(keep)])
            self.assertEqual((kept['decision'], kept['commentId'], kept['stage'], kept['schema']), ('keep', 9, 'dev', m.SCHEMA_DECISION))
            body = f.github.data['issues/3']['body']
            self.assertIn('### What this run does', body); self.assertIn(back, body); self.assertIn('`REJECT` stops this run', body)
            self.assertLess(body.index('### What this run does'), body.index('| Item | Detail |'))
            self.assertEqual(run([comment(back)])['decision'], 'rollback')
            with self.assertRaisesRegex(ValueError, 'rejected'): run([comment('REJECT', edited=True)])
            with self.assertRaisesRegex(TimeoutError, 'No keep-or-rollback decision'): run([comment(keep, edited=True), comment('keep please'), {**comment(keep), 'user': {'login': 'other'}}])
            with self.assertRaisesRegex(ValueError, 'Verified candidate required'): m.decide('dev', f.env, {**verification, 'verified': False}, f.github.get)
            with self.assertRaisesRegex(ValueError, 'another release'): m.decide('dev', f.env, {**verification, 'releaseVersion': 'v2026.10.26.21.00'}, f.github.get)
            with self.assertRaises(ValueError): m.decide('prod', f.env, verification, f.github.get)
            # Expired window and changed issue body fail closed on the recheck path.
            receipt = {k: kept[k] for k in ('schema', 'stage', 'runId', 'planDigest', 'createdAt', 'expiresAt', 'issue')}
            self.assertIsNone(m.decision('dev', plan, verification, receipt, f.env, f.github.get, f.github.now))
            with self.assertRaisesRegex(ValueError, 'expired'): m.decision('dev', plan, verification, receipt, f.env, f.github.get, f.github.now + timedelta(minutes=41))
            f.github.data['issues/3']['body'] += ' edited'
            with self.assertRaisesRegex(ValueError, 'issue changed'): m.decision('dev', plan, verification, receipt, f.env, f.github.get, f.github.now)

    def test_record_collects_receipts_of_this_run_and_marks_absent_ones(self):
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp); verification = f.verified()
            ver = f.temp / 'candidate-verify.json'; m.write(ver, verification)
            foreign = f.temp / 'foreign.json'; foreign.write_text(json.dumps({'releaseVersion': 'v2026.10.26.21.00'}))
            record = m.record(f.env, {'registryImage': f.registry, 'verification': ver, 'decision': f.temp / 'missing.json', 'approval': None}, f.github.now)
            self.assertEqual((record['schema'], record['app'], record['releaseVersion'], record['imageDigest'], record['devDecision']),
                             (m.SCHEMA_RECORD, APP, 'v2026.10.26.20.00', DIGEST, None))
            self.assertEqual(record['receipts']['verification'], verification); self.assertIsNone(record['receipts']['decision']); self.assertIsNone(record['receipts']['approval'])
            with self.assertRaisesRegex(ValueError, 'another release'): m.record(f.env, {'decision': foreign})

    def test_cli_modes_write_receipts_outputs_and_hold_the_infra_owned_tools(self):
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp); out = f.temp / 'github-output'; env = {**f.env, 'GITHUB_OUTPUT': str(out)}
            with patch.object(m, 'infraTooling', return_value={'schema': m.SCHEMA_INFRA, 'releaseVersion': 'v2026.10.28.09.00', 'sourceSha': 'c' * 40}):
                m.main(['infra', '--output', str(f.temp / 'infra.json')], env)
            self.assertIn('infra_ref=v2026.10.28.09.00\ninfra_sha=' + 'c' * 40, out.read_text())
            with patch.object(m, 'fetch', return_value=(200, 'v2026.10.26.20.00')):
                m.main(['verify', '--result-dir', str(f.result), '--registry-image', str(f.registry), '--url', 'https://dev.example.test/r', '--output', str(f.temp / 'verify.json')], env)
            self.assertTrue(json.loads((f.temp / 'verify.json').read_text())['verified'])
            with patch.object(m, 'decide', return_value={'decision': 'keep', 'plan': {}}):
                m.main(['decide', '--verification', str(f.temp / 'verify.json'), '--output', str(f.temp / 'decision.json')], env)
            self.assertIn('decision=keep', out.read_text())
            m.main(['record', '--registry-image', str(f.registry), '--verification', str(f.temp / 'verify.json'), '--output', str(f.temp / 'record.json')], env)
            self.assertEqual(json.loads((f.temp / 'record.json').read_text())['imageDigest'], DIGEST)
            for held in ('cutover', 'rollback'):
                with self.assertRaises(SystemExit) as stop: m.main([held, '--output', str(f.temp / 'x.json')], env)
                self.assertIn('Infra', str(stop.exception)); self.assertIn('nothing was changed', str(stop.exception).lower())
                self.assertFalse((f.temp / 'x.json').exists())
            with self.assertRaisesRegex(ValueError, '--output'): m.main(['record'], env)
            with self.assertRaises(FileExistsError):  # receipts are written once; a second write never overwrites
                m.main(['record', '--output', str(f.temp / 'record.json')], env)


if __name__ == '__main__':
    unittest.main()
