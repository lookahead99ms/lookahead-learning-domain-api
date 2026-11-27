"""Closing consumed approval issues with a fake GitHub; no network."""
import json
from pathlib import Path
import tempfile
import unittest

import issue_close as m

ENV = {'GITHUB_REPOSITORY': 'lookahead99ms/example', 'GITHUB_RUN_ID': '42', 'GITHUB_SERVER_URL': 'https://github.com'}


class Github:
    def __init__(self, issues=None):
        self.issues = issues or {7: {'number': 7, 'state': 'open', 'user': {'login': m.BOT}, 'title': 'DEV: Publish image web-v2026.10.28.15.01 · run 42',
                                     'body': '[Run 42](https://github.com/lookahead99ms/example/actions/runs/42)'}}
        self.calls = []

    def __call__(self, args, payload=None):
        self.calls.append((args, payload))
        path = args[0]
        if path.endswith('/issues?state=open&creator=github-actions%5Bbot%5D&per_page=50'):
            return [i for i in self.issues.values() if i['state'] == 'open']
        if path.endswith('/comments'):
            return {'id': 1}
        number = int(path.rsplit('/', 1)[1])
        if payload and payload.get('state') == 'closed':
            self.issues[number].update(state='closed', state_reason=payload['state_reason']); return self.issues[number]
        return self.issues[number]


class CloseTests(unittest.TestCase):
    def test_receipt_issue_is_commented_and_closed_with_the_outcome(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / 'owner-approval.json'; receipt.write_text(json.dumps({'issue': 7, 'commentId': 9}))
            fake = Github()
            result = m.main(['--stage', 'Publish to ECR', '--outcome', 'success', '--detail', 'Published; digest read back.', '--receipt', str(receipt)], ENV, fake)
            self.assertEqual(result, {'issue': 7, 'state': 'closed', 'state_reason': 'completed', 'outcome': 'success'})
            comment = next(p for a, p in fake.calls if a[0].endswith('/comments'))
            self.assertTrue(comment['body'].startswith('**Publish to ECR finished successfully.** Published; digest read back.'))
            self.assertIn('[run 42](https://github.com/lookahead99ms/example/actions/runs/42)', comment['body'])
            self.assertIn('a later run opens a new issue', comment['body'])
            self.assertEqual(fake.issues[7]['state'], 'closed')
            # A second close is a no-op: the issue is no longer open.
            self.assertIsNone(m.main(['--stage', 'Publish to ECR', '--outcome', 'success', '--detail', 'x', '--receipt', str(receipt)], ENV, fake))

    def test_failure_and_cancellation_close_as_not_planned(self):
        for outcome in ('failure', 'cancelled', 'skipped'):
            with tempfile.TemporaryDirectory() as temp:
                receipt = Path(temp) / 'r.json'; receipt.write_text(json.dumps({'issue': 7}))
                fake = Github(); result = m.main(['--stage', 'Deploy to DEV', '--outcome', outcome, '--detail', 'Nothing was deployed.', '--receipt', str(receipt)], ENV, fake)
                self.assertEqual((result['state_reason'], fake.issues[7]['state']), ('not_planned', 'closed'))

    def test_match_finds_this_runs_issue_when_no_receipt_exists(self):
        fake = Github()
        fake.issues[8] = {'number': 8, 'state': 'open', 'user': {'login': m.BOT}, 'title': 'DEV: Publish image web-v2026.10.28.15.01 · run 41',
                          'body': '[Run 41](https://github.com/lookahead99ms/example/actions/runs/41)'}
        result = m.main(['--stage', 'Approve ECR publish', '--outcome', 'failure', '--detail', 'Rejected or expired.', '--match', 'Publish image', '--receipt', '/nonexistent/r.json'], ENV, fake)
        self.assertEqual(result['issue'], 7); self.assertEqual(fake.issues[8]['state'], 'open')
        self.assertIsNone(m.main(['--stage', 'x', '--outcome', 'failure', '--detail', 'd', '--match', 'Keep or roll back'], ENV, Github()))
        fake = Github(); fake.issues[9] = dict(fake.issues[7], number=9)
        with self.assertRaisesRegex(ValueError, 'Several open issues match'):
            m.main(['--stage', 'x', '--outcome', 'failure', '--detail', 'd', '--match', 'Publish image'], ENV, fake)

    def test_only_open_bot_issues_are_touched_and_inputs_are_exact(self):
        fake = Github({7: {'number': 7, 'state': 'open', 'user': {'login': 'someone'}, 'title': 't', 'body': ''}})
        self.assertIsNone(m.close('lookahead99ms/example', '42', 'https://github.com', 7, 'x', 'success', 'd', fake))
        self.assertEqual([a[0] for a, _ in fake.calls], ['repos/lookahead99ms/example/issues/7'])
        with self.assertRaises(ValueError): m.close('lookahead99ms/example', '42', 'https://github.com', 7, 'x', 'unknown', 'd', Github())
        with self.assertRaises(ValueError): m.main(['--stage', 'x', '--outcome', 'success', '--detail', 'd', '--match', 'y'], {'GITHUB_REPOSITORY': 'bad', 'GITHUB_RUN_ID': '42'}, Github())
        with self.assertRaises(SystemExit): m.main(['--stage', 'x', '--outcome', 'maybe', '--detail', 'd'], ENV, Github())
        self.assertIsNone(m.main(['--stage', 'x', '--outcome', 'success', '--detail', 'd'], ENV, Github()))  # neither receipt nor match: nothing to close


if __name__ == '__main__':
    unittest.main()
