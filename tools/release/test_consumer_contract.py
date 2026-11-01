"""Gateway consumer-contract selection and fetch with a fake GitHub CLI; no network, no AWS."""
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import consumer_contract as m

ROWS = '\n'.join(['refs/tags/v2026.10.27.01.00\tcommit\t' + 'b' * 40, 'refs/tags/v2026.10.30.18.00\tcommit\t' + 'c' * 40,
                  'refs/tags/v2026.10.30.18.01\tcommit\t' + 'd' * 40, 'refs/tags/v2026.10.31.09.00\ttag\t' + 'e' * 40,
                  'refs/tags/v1.2.3\tcommit\t' + 'f' * 40, 'garbage'])
CONTRACT = {'schema': 'gateway-domain-contract/v1', 'calls': [{'id': 'auth-me', 'method': 'GET', 'path': '/api/v1/auth/me'}], 'webPaths': ['/', '/sign-in']}
ENV = {'CROSS_REPO_READ_TOKEN': 'synthetic'}
NOW = datetime(2026, 10, 31, tzinfo=timezone.utc)


class Github:
    """`gh api` as the script calls it: tag refs and the raw file at a commit."""
    def __init__(self, rows=ROWS, text=None):
        self.rows, self.text, self.calls = rows, json.dumps(CONTRACT) if text is None else text, []

    def run(self, args, env=None):
        self.calls.append((args, env.get('GH_TOKEN')))
        if args[1].endswith('/git/matching-refs/tags/v'):
            return self.rows
        self.assertContents(args)
        return self.text

    def assertContents(self, args):
        assert args[1] == 'repos/' + m.GATEWAY + '/contents/' + m.CONTRACT + '?ref=' + 'd' * 40, args
        assert args[2:] == ['-H', 'Accept: application/vnd.github.raw+json'], args


class ConsumerContractTests(unittest.TestCase):
    def test_selection_is_the_newest_lightweight_calendar_tag_and_needs_the_read_token(self):
        github = Github()
        result = m.select(ENV, github.run, NOW)
        self.assertEqual(('v2026.10.30.18.01', 'd' * 40, m.GATEWAY, m.CONTRACT, m.SCHEMA, '2026-10-31T00:00:00+00:00'),
                         (result['releaseVersion'], result['sourceSha'], result['repository'], result['path'], result['schema'], result['selectedAt']))
        self.assertEqual(github.calls[0][1], 'synthetic')
        self.assertIn('repos/' + m.GATEWAY + '/git/matching-refs/tags/v', github.calls[0][0]); self.assertIn('--paginate', github.calls[0][0])
        self.assertEqual('v2026.10.30.18.01', m.select({'GH_TOKEN': 'workflow-step-token'}, github.run)['releaseVersion'])
        self.assertEqual(github.calls[1][1], 'workflow-step-token')
        with self.assertRaisesRegex(ValueError, 'CROSS_REPO_READ_TOKEN'): m.select({}, github.run)
        with self.assertRaisesRegex(ValueError, 'no calendar release tag'): m.select(ENV, Github('refs/tags/v1\tcommit\t' + 'a' * 40).run)
        self.assertEqual(('v2026.10.31.09.100', 'a' * 40), m.newestTag('refs/tags/v2026.10.31.09.100\tcommit\t' + 'a' * 40 + '\nrefs/tags/v2026.10.31.09.99\tcommit\t' + 'b' * 40))

    def test_fetch_writes_the_contract_at_the_selected_commit_and_validates_it(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'contracts' / 'domain-api-v1.json'
            output.parent.mkdir(); output.write_text('{"schema":"stale committed copy"}\n')
            github = Github()
            result = m.fetch(ENV, output, github.run, now=NOW)
            self.assertEqual((result['releaseVersion'], result['sourceSha'], result['output'], result['calls'], result['webPaths']),
                             ('v2026.10.30.18.01', 'd' * 40, str(output), 1, 2))
            self.assertEqual(json.loads(output.read_text()), CONTRACT); self.assertTrue(output.read_text().endswith('\n'))
            self.assertEqual([c[1] for c in github.calls], ['synthetic', 'synthetic'])
            injected = m.fetch(ENV, Path(temp) / 'new' / 'c.json', github.run, lambda sha, secret, run: json.dumps(CONTRACT) + '\n', NOW)
            self.assertEqual(injected['calls'], 1); self.assertTrue((Path(temp) / 'new' / 'c.json').is_file())
            for text, message in (('not json', 'not JSON'), (json.dumps({'schema': 'other', 'calls': [1], 'webPaths': []}), 'schema is not'),
                                  (json.dumps({**CONTRACT, 'calls': []}), 'lists no calls'), (json.dumps({**CONTRACT, 'webPaths': ['sign-in']}), 'absolute paths'),
                                  ('x' * (m.MAX_BODY + 1), 'exceeds bound')):
                with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                    m.fetch(ENV, Path(temp) / 'bad.json', Github(text=text).run)
            self.assertFalse((Path(temp) / 'bad.json').exists())

    def test_cli_skips_without_a_token_and_writes_output_and_receipt_with_one(self):
        with tempfile.TemporaryDirectory() as temp:
            output, receipt = Path(temp) / 'domain-api-v1.json', Path(temp) / 'receipts' / 'consumer-contract.json'
            with patch('builtins.print') as printed:
                self.assertIsNone(m.main(['fetch', '--output', str(output)], {}))
            self.assertIn('CROSS_REPO_READ_TOKEN is unset', printed.call_args[0][0]); self.assertFalse(output.exists())
            github = Github()
            with patch.object(m, 'gh', github.run), patch('builtins.print') as printed:
                result = m.main(['fetch', '--output', str(output), '--receipt', str(receipt)], ENV)
            self.assertEqual(result['releaseVersion'], 'v2026.10.30.18.01'); self.assertEqual(json.loads(output.read_text()), CONTRACT)
            self.assertEqual(json.loads(receipt.read_text()), result); self.assertEqual(json.loads(printed.call_args[0][0]), result)
            with patch.object(m, 'gh', github.run), self.assertRaises(FileExistsError):
                m.main(['fetch', '--output', str(output), '--receipt', str(receipt)], ENV)
            with self.assertRaises(SystemExit): m.main(['fetch'], ENV)

    def test_contract_absent_at_the_newest_tag_keeps_the_committed_copy(self):
        # Publishing can be paused (RELEASE_ON_MERGE=false), so the newest Gateway tag may predate the contract: skip, never fail.
        import tempfile, os
        from unittest.mock import Mock, patch
        with tempfile.TemporaryDirectory() as directory:
            output = os.path.join(directory, 'domain-api-v1.json')
            with open(output, 'w') as stream:
                stream.write('{"committed": true}')
            run = lambda args, env=None: 'refs/tags/v2026.10.30.18.00\tcommit\t' + 'c' * 40 + '\n'
            receipt = m.fetch({'CROSS_REPO_READ_TOKEN': 't'}, output, run=run, get=lambda sha, secret, run: None)
            self.assertEqual('contract absent at the newest Gateway tag', receipt['skipped'])
            with open(output) as stream:
                self.assertEqual('{"committed": true}', stream.read())
            with patch.object(m.subprocess, 'run', return_value=Mock(returncode=1, stdout='', stderr='gh: Not Found (HTTP 404)')):
                self.assertIsNone(m.download('c' * 40, 't'))
            with patch.object(m.subprocess, 'run', return_value=Mock(returncode=1, stdout='', stderr='gh: forbidden (HTTP 403)')):
                with self.assertRaisesRegex(ValueError, 'GitHub request failed'):
                    m.download('c' * 40, 't')

    def test_gh_wrapper_fails_closed_on_errors_and_oversized_output(self):
        ok = subprocess.CompletedProcess(['gh'], 0, stdout='fine', stderr='')
        with patch('subprocess.run', return_value=ok) as run:
            self.assertEqual('fine', m.gh(['api', 'x'], env={'GH_TOKEN': 't'}))
            self.assertEqual(run.call_args[0][0], ['gh', 'api', 'x']); self.assertEqual(run.call_args[1]['env'], {'GH_TOKEN': 't'})
        with patch('subprocess.run', return_value=subprocess.CompletedProcess(['gh'], 1, stdout='', stderr='denied')):
            with self.assertRaisesRegex(ValueError, 'GitHub request failed'): m.gh(['api', 'x'])
        with patch('subprocess.run', return_value=subprocess.CompletedProcess(['gh'], 0, stdout='x' * (4 * m.MAX_BODY + 1), stderr='')):
            with self.assertRaisesRegex(ValueError, 'exceeds bound'): m.gh(['api', 'x'])
        with patch('subprocess.run', return_value=ok) as run:
            self.assertEqual('fine', m.download('a' * 40, 'secret'))
            self.assertEqual(run.call_args[1]['env']['GH_TOKEN'], 'secret'); self.assertIn('?ref=' + 'a' * 40, run.call_args[0][0][2])


if __name__ == '__main__':
    unittest.main()
