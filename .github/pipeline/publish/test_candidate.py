"""Release record with a fake GitHub provider and fake receipts; no network, no AWS."""
from contextlib import redirect_stderr
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import candidate as m
from test_owner_approval import Github

ROOT = Path(__file__).resolve().parents[3]
APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'
REPO = next(repo for repo, (_, app) in m.approval.PUBLISHERS.items() if app == APP)
DIGEST = 'sha256:' + '1' * 64


class Fixture:
    """A run that the owner approved and that published image DIGEST for v2026.10.26.20.00."""
    def __init__(self, temp):
        self.temp = Path(temp)
        self.github = Github(REPO)
        self.env = dict(self.github.env)
        self.registry = self.temp / 'registry-image.json'
        # The real receipt (`registry.bind`, run #2 of 2026-10-28): `image` is the string `<repository>@<digest>`, not an object.
        self.registry.write_text(json.dumps({'revision': 'a' * 40, 'image': '509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr@' + DIGEST, 'imageId': 'sha256:' + '2' * 64, 'imageTag': APP + '-v2026.10.26.20.00'}))
        # The real receipt (`owner_approval.approve`): schema, runId, planDigest, the two times, the issue and the owner's comment.
        self.approval = self.temp / 'owner-approval.json'
        with patch('builtins.print'):
            self.approval.write_text(json.dumps(self.github.approved()))


class CandidateTests(unittest.TestCase):
    def test_published_digest_reads_the_registry_receipt_shape(self):
        self.assertEqual(DIGEST, m.publishedDigest({'image': 'repo@' + DIGEST}))
        self.assertEqual(DIGEST, m.publishedDigest({'image': {'imageDigest': DIGEST}}))
        for bad in ({}, {'image': 'repo'}, {'image': 'repo@sha256:short'}, {'image': None}):
            self.assertEqual('', m.publishedDigest(bad))

    def test_record_collects_receipts_of_this_run_and_marks_absent_ones(self):
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp)
            foreign = f.temp / 'foreign.json'; foreign.write_text(json.dumps({'releaseVersion': 'v2026.10.26.21.00'}))
            record = m.record(f.env, {'registryImage': f.registry, 'approval': f.approval}, f.github.now)
            self.assertEqual((record['schema'], record['app'], record['releaseVersion'], record['imageTag'], record['imageDigest'], record['recordedAt']),
                             (m.SCHEMA_RECORD, APP, 'v2026.10.26.20.00', APP + '-v2026.10.26.20.00', DIGEST, f.github.now.isoformat()))
            self.assertEqual((record['sourceSha'], record['workflowSha'], record['repository'], record['runId']), ('a' * 40, 'a' * 40, REPO, '12'))
            self.assertEqual(record['receipts']['registryImage'], json.loads(f.registry.read_text()))
            self.assertEqual((record['receipts']['approval']['schema'], record['receipts']['approval']['commentId']), ('image-owner-decision/v1', 7))
            # A run that stopped before the approval or the publication still writes its record: the missing receipts are null.
            early = m.record(f.env, {'registryImage': f.temp / 'missing.json', 'approval': None}, f.github.now)
            self.assertEqual((early['receipts']['registryImage'], early['receipts']['approval'], early['imageDigest']), (None, None, None))
            with self.assertRaisesRegex(ValueError, 'another release'): m.record(f.env, {'approval': foreign})
            (f.temp / 'large.json').write_text(' ' * (m.MAX_BODY + 1))
            with self.assertRaisesRegex(ValueError, 'exceeds bound'): m.record(f.env, {'approval': f.temp / 'large.json'})

    def test_record_keeps_the_shape_of_app_release_record_v1(self):
        # Earlier records hold these keys; the three receipts and `devDecision` were null in every one of them and stay null.
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp)
            record = m.record(f.env, {'registryImage': f.registry, 'approval': f.approval}, f.github.now)
            self.assertEqual({'schema', 'app', 'releaseVersion', 'imageTag', 'sourceSha', 'workflowSha', 'repository', 'runId', 'recordedAt', 'receipts',
                              'imageDigest', 'devDecision'}, set(record))
            self.assertEqual({'registryImage', 'approval', 'verification', 'decision', 'infraTooling'}, set(record['receipts']))
            self.assertEqual((None, None, None, None), (*(record['receipts'][name] for name in m.RETIRED_RECEIPTS), record['devDecision']))
            for name in m.RETIRED_RECEIPTS:
                with self.subTest(receipt=name), self.assertRaisesRegex(ValueError, 'no longer has'): m.record(f.env, {name: f.approval})

    def test_cli_writes_the_record_once_as_the_workflow_calls_it(self):
        with tempfile.TemporaryDirectory() as temp:
            f = Fixture(temp); out = f.temp / 'release-record.json'
            with patch('builtins.print') as printed:
                result = m.main(['record', '--registry-image', str(f.registry), '--approval', str(f.approval), '--output', str(out)], f.env)
            written = json.loads(out.read_text())
            self.assertEqual(result, written)
            self.assertEqual((written['schema'], written['imageDigest'], written['receipts']['approval']['commentId']), (m.SCHEMA_RECORD, DIGEST, 7))
            self.assertEqual(out.read_text(), json.dumps(written, sort_keys=True, indent=2) + '\n')
            summary = json.loads(printed.call_args.args[0])  # the job log line: the record without its receipts
            self.assertEqual({k: v for k, v in written.items() if k != 'receipts'}, summary)
            with self.assertRaisesRegex(ValueError, '--output'): m.main(['record'], f.env)
            with self.assertRaises(FileExistsError):  # the record is written once; a second write never overwrites
                m.main(['record', '--output', str(out)], f.env)
            # `record` is the only mode and takes only this run's own receipts.
            for gone in (['infra'], ['verify'], ['decide'], ['cutover'], ['rollback'], ['record', '--infra', 'x'], ['record', '--verification', 'x'], ['record', '--decision', 'x']):
                with self.subTest(arguments=gone), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stop:
                    m.main([*gone, '--output', str(f.temp / 'x.json')], f.env)
                self.assertEqual(2, stop.exception.code); self.assertFalse((f.temp / 'x.json').exists())


if __name__ == '__main__':
    unittest.main()
