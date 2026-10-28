"""Merge-driven tag cutting and the held DEV deploy request, with a fake `gh`; no network."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import pipeline_tag as m
import release_version as versions

SHA, OLD, REPO = 'a' * 40, '9' * 40, 'owner/repo'
NOW = datetime(2026, 10, 27, 14, 30, tzinfo=timezone.utc)


class FakeGithub:
    """Minimal GitHub: tag inventory, ref creation that refuses duplicates, ref read-back."""
    def __init__(self, tags=None, main=SHA):
        self.tags = dict(tags or {})
        self.main = main
        self.calls = []
        self.refuseCreate = False

    def run(self, args, env=None):
        self.calls.append((tuple(args), env))
        path = args[1] if args[0] == 'api' else ''
        if path.endswith('/git/matching-refs/tags/v'):
            return ''.join('refs/tags/%s\t%s\t%s\n' % (tag, kind, sha) for tag, (kind, sha) in self.tags.items())
        if path.endswith('/git/refs'):
            ref = next(a for a in args if a.startswith('ref=')).split('=', 1)[1][len('refs/tags/'):]
            sha = next(a for a in args if a.startswith('sha=')).split('=', 1)[1]
            if ref in self.tags or self.refuseCreate:
                raise ValueError('GitHub request failed; no tag or deploy request was recorded')
            self.tags[ref] = ('commit', sha)
            return '{}'
        if '/git/ref/tags/' in path:
            kind, sha = self.tags[path.rsplit('/', 1)[1]]
            return json.dumps({'object': {'type': kind, 'sha': sha}})
        if path.endswith('/commits/main'):
            return json.dumps({'sha': self.main})
        if '/compare/' in path:
            base, head = path.rsplit('/', 1)[1].split('...')
            return json.dumps({'status': 'identical' if base == head else 'ahead', 'merge_base_commit': {'sha': base}})
        raise AssertionError('Unexpected request ' + ' '.join(args))


class CutTests(unittest.TestCase):
    def test_cuts_next_counter_at_run_commit_records_it_and_never_moves_a_tag(self):
        fake = FakeGithub({'v2026.10.27.14.00': ('commit', OLD), 'v2026.10.27.13.07': ('commit', OLD), 'release-1': ('commit', OLD)})
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp) / 'release-tag.json'
            result = m.cut('domain', REPO, SHA, '77', record, fake.run, NOW)
            self.assertEqual(result['releaseVersion'], 'v2026.10.27.14.01')
            self.assertEqual((result['sourceSha'], result['workflowSha'], result['runId'], result['reused']), (SHA, SHA, '77', False))
            self.assertEqual(result['imageTag'], 'domain-v2026.10.27.14.01')
            self.assertEqual(fake.tags['v2026.10.27.14.01'], ('commit', SHA))
            self.assertEqual(json.loads(record.read_text()), result)
            create = next(call for call, _ in fake.calls if '--method' in call)
            self.assertEqual(create[create.index('--method') + 1], 'POST')
            self.assertIn('sha=' + SHA, create)
            with self.assertRaisesRegex(ValueError, 'already exists'):
                m.cut('domain', REPO, SHA, '78', record, fake.run, NOW)

    def test_reuses_the_tag_that_already_names_the_run_commit_and_ignores_annotated_or_foreign_tags(self):
        fake = FakeGithub({'v2026.10.27.14.00': ('commit', SHA), 'v2026.10.27.14.01': ('commit', SHA),
                           'v2026.10.27.14.02': ('tag', 'b' * 40), 'v2026.10.27.13.00': ('commit', OLD)})
        self.assertEqual(m.existing(SHA, m.inventory(REPO, fake.run)), 'v2026.10.27.14.01')
        self.assertIsNone(m.existing('c' * 40, m.inventory(REPO, fake.run)))
        with tempfile.TemporaryDirectory() as temp:
            result = m.cut('web', REPO, SHA, '5', Path(temp) / 'r.json', fake.run, NOW)
        self.assertEqual((result['releaseVersion'], result['reused']), ('v2026.10.27.14.01', True))
        self.assertFalse(any('--method' in call for call, _ in fake.calls))

    def test_claimed_name_wrong_target_bad_sha_and_hourly_reset_fail_or_advance_safely(self):
        with tempfile.TemporaryDirectory() as temp:
            fake = FakeGithub({'v2026.10.27.14.00': ('commit', OLD)}); fake.refuseCreate = True
            with self.assertRaisesRegex(ValueError, 'could not be created'):
                m.cut('domain', REPO, SHA, '1', Path(temp) / 'a.json', fake.run, NOW)
            fake = FakeGithub()
            original = fake.run
            def swapped(args, env=None):
                out = original(args, env)
                if '/git/ref/tags/' in args[1]: fake.tags[args[1].rsplit('/', 1)[1]] = ('commit', OLD); return original(args, env)
                return out
            with self.assertRaisesRegex(ValueError, 'does not name the run commit'):
                m.cut('domain', REPO, SHA, '1', Path(temp) / 'b.json', swapped, NOW)
            with self.assertRaisesRegex(ValueError, 'Exact run commit'):
                m.cut('domain', REPO, 'main', '1', Path(temp) / 'c.json', FakeGithub().run, NOW)
            fake = FakeGithub({'v2026.10.27.13.41': ('commit', OLD)})
            self.assertEqual(m.cut('gateway-bff', REPO, SHA, '1', Path(temp) / 'd.json', fake.run, NOW)['releaseVersion'], 'v2026.10.27.14.00')

    def test_cli_requires_first_attempt_main_run_binds_main_per_event_and_writes_outputs(self):
        base = {'GITHUB_REPOSITORY': REPO, 'GITHUB_SHA': SHA, 'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'push',
                'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_RUN_ID': '9'}
        with tempfile.TemporaryDirectory() as temp:
            fake = FakeGithub(main='f' * 40)  # main moved on after the merge push: the merge commit is still an ancestor
            output = Path(temp) / 'out'
            with patch.object(m, 'gh', fake.run), patch('builtins.print'):
                result = m.main(['cut', '--app', 'domain', '--record', str(Path(temp) / 'push.json')], {**base, 'GITHUB_OUTPUT': str(output)})
            self.assertEqual(result['releaseVersion'][:1], 'v')
            self.assertEqual(output.read_text(), 'release_version=' + result['releaseVersion'] + '\nsource_sha=' + SHA + '\n')
            with patch.object(m, 'gh', FakeGithub(main='f' * 40).run), self.assertRaisesRegex(ValueError, 'Main moved'):
                m.main(['cut', '--app', 'domain', '--record', str(Path(temp) / 'dispatch.json')], {**base, 'GITHUB_EVENT_NAME': 'workflow_dispatch'})
            with patch.object(m, 'gh', FakeGithub().run), patch('builtins.print'):
                self.assertFalse(m.main(['cut', '--app', 'domain', '--record', str(Path(temp) / 'dispatch.json')], {**base, 'GITHUB_EVENT_NAME': 'workflow_dispatch'})['reused'])
            for key, value in [('GITHUB_REF', 'refs/heads/feature'), ('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_RUN_ATTEMPT', '2'), ('GITHUB_REPOSITORY', 'bad')]:
                with self.subTest(key=key), patch.object(m, 'gh', FakeGithub().run), self.assertRaises(ValueError):
                    m.main(['cut', '--app', 'domain', '--record', str(Path(temp) / 'x.json')], {**base, key: value})
            with self.assertRaisesRegex(ValueError, '--record'):
                m.main(['cut', '--app', 'domain'], base)
            # A 40-hex SHA is never an input: the CLI has no such option.
            with self.assertRaises(SystemExit):
                m.main(['cut', '--app', 'domain', '--record', 'r.json', '--sha', SHA], base)


class DeployRequestTests(unittest.TestCase):
    def test_held_until_enabled_then_dispatches_infra_deploy_with_app_and_tag_only(self):
        env = {'RELEASE_VERSION': 'v2026.10.27.14.01', 'DEV_DEPLOY_ENABLED': 'false', 'INFRA_DISPATCH_TOKEN': 'synthetic'}
        run = Mock()
        with patch('builtins.print') as shown:
            self.assertFalse(m.deployRequest('web', env, run)['requested'])
        shown.assert_called_once_with(m.HOLD, flush=True)
        self.assertIn('DEV_DEPLOY_ENABLED=true', m.HOLD)
        run.assert_not_called()
        with self.assertRaisesRegex(ValueError, 'INFRA_DISPATCH_TOKEN'):
            m.deployRequest('web', {**env, 'DEV_DEPLOY_ENABLED': 'true', 'INFRA_DISPATCH_TOKEN': ''}, run)
        run.assert_not_called()
        with patch('builtins.print'):
            self.assertTrue(m.deployRequest('web', {**env, 'DEV_DEPLOY_ENABLED': 'true'}, run)['requested'])
        args, kwargs = run.call_args
        self.assertEqual(args[0], ['workflow', 'run', 'application-deploy.yml', '--repo', m.INFRA, '--ref', 'main',
                                   '-f', 'app=web', '-f', 'release_version=v2026.10.27.14.01'])
        self.assertEqual(kwargs['env']['GH_TOKEN'], 'synthetic')
        with self.assertRaises(ValueError):
            m.deployRequest('web', {**env, 'RELEASE_VERSION': SHA}, run)
        with patch.object(m, 'gh', run), patch('builtins.print'):
            self.assertTrue(m.main(['deploy-request', '--app', 'web'], {**env, 'DEV_DEPLOY_ENABLED': 'true'})['requested'])

    def test_gh_transport_is_bounded_and_fails_closed(self):
        with patch.object(m.subprocess, 'run', return_value=Mock(returncode=0, stdout='ok')) as call:
            self.assertEqual(m.gh(['api', 'x']), 'ok')
            self.assertEqual(call.call_args.kwargs['timeout'], 60)
        for result in [Mock(returncode=1, stdout='sensitive'), Mock(returncode=0, stdout='x' * (4 * 1024 * 1024 + 1))]:
            with patch.object(m.subprocess, 'run', return_value=result), self.assertRaises(ValueError) as error:
                m.gh(['api', 'x'])
            self.assertNotIn('sensitive', str(error.exception))
        self.assertEqual(m.inventory(REPO, lambda args, env=None: 'garbage\nrefs/tags/v1\tcommit\n'), [])


if __name__ == '__main__':
    unittest.main()
