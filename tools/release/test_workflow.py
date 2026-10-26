import json
from pathlib import Path
import unittest


class ReleaseWorkflowTests(unittest.TestCase):
    def test_publication_requires_verified_same_run_image_and_explicit_dev_approval(self):
        root = Path(__file__).resolve().parents[2]
        workflow = json.loads((root / '.github/workflows/release-image.yml').read_text())
        self.assertEqual({'workflow_dispatch'}, set(workflow['on']))
        verify, publish = workflow['jobs']['verify'], workflow['jobs']['publish']
        self.assertEqual('./.github/workflows/ci.yml', verify['uses'])
        self.assertIs(True, verify['with']['export-image'])
        self.assertEqual('verify', publish['needs'])
        self.assertEqual('dev', publish['environment'])
        steps = publish['steps']
        names = [step['name'] for step in steps]
        approval = names.index('Recheck live owner approval before upload credentials')
        load = names.index('Load and verify scanned image without rebuilding')
        oidc = next(i for i, step in enumerate(steps) if str(step.get('uses', '')).startswith('aws-actions/configure-aws-credentials@'))
        self.assertLess(approval, oidc)
        self.assertLess(load, oidc)
        commands = '\n'.join(step.get('run', '') for step in steps)
        self.assertNotIn('docker build', commands)
        self.assertIn('owner_release.py publish', commands)
        publisher=(root/'tools/release/owner_release.py').read_text()
        self.assertIn("['docker', 'push', '--platform', 'linux/amd64'",publisher)
        self.assertIn('owner_release.py approve', commands)
        self.assertIn('owner_release.py check', commands)
        self.assertIn('registry.bind(',publisher)
        self.assertIn("get('imageTagMutability') == 'IMMUTABLE'",publisher)
        self.assertEqual('write', publish['permissions']['id-token'])
        self.assertEqual('write',publish['permissions']['issues'])
        policy=json.loads(steps[oidc]['with']['inline-session-policy'])
        self.assertEqual(policy['Statement'][1]['Resource'],'arn:aws:ecr:us-east-2:509614632283:repository/lookahead-dev-ecr')
        self.assertEqual(steps[oidc]['with']['role-duration-seconds'],900)
        ci = (root / '.github/workflows/ci.yml').read_text()
        coverageStep=next(s for s in json.loads(ci)['jobs']['verify']['steps'] if s.get('name')=='Verify immutable image binding')
        self.assertEqual(coverageStep['env']['COVERAGE_FILE'],'${{ runner.temp }}/domain-release.coverage')
        self.assertIn('--fail-under=85',coverageStep['run'])
        sast = 'Enforce CodeQL severity' if root.name.endswith('web-public') else 'Require completed SAST'
        self.assertLess(ci.index(sast), ci.index('Export the exact scanned release image'))
        self.assertLess(ci.index('images --image'), ci.index('Export the exact scanned release image'))


if __name__ == '__main__':
    unittest.main()


class SharedPublisherSessionTests(unittest.TestCase):
    def test_credentials_use_trust_bound_publisher_session(self):
        from pathlib import Path
        import json
        workflow = json.loads((Path(__file__).resolve().parents[2] / '.github/workflows/release-image.yml').read_text())
        steps = [step for job in workflow['jobs'].values() for step in job.get('steps', [])
                 if step.get('uses', '').startswith('aws-actions/configure-aws-credentials@')]
        self.assertTrue(steps)
        for step in steps:
            self.assertEqual(step['with']['role-session-name'], 'image-publish-${{ github.run_id }}-1')
            self.assertIn('inline-session-policy', step['with'])
            self.assertLessEqual(step['with']['role-duration-seconds'], 900)
