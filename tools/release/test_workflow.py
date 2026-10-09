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
        approval = names.index('Require exact source and protected DEV environment')
        load = names.index('Load and verify scanned image without rebuilding')
        oidc = next(i for i, step in enumerate(steps) if str(step.get('uses', '')).startswith('aws-actions/configure-aws-credentials@'))
        self.assertLess(approval, oidc)
        self.assertLess(load, oidc)
        commands = '\n'.join(step.get('run', '') for step in steps)
        self.assertNotIn('docker build', commands)
        self.assertIn('docker push --platform linux/amd64', commands)
        self.assertIn('registry.py approval', commands)
        self.assertIn('registry.py bind', commands)
        self.assertIn('test "$mutability" = IMMUTABLE', commands)
        self.assertEqual('write', publish['permissions']['id-token'])
        ci = (root / '.github/workflows/ci.yml').read_text()
        sast = 'Enforce CodeQL severity' if root.name.endswith('web-public') else 'Require completed SAST'
        self.assertLess(ci.index(sast), ci.index('Export the exact scanned release image'))
        self.assertLess(ci.index('images --image'), ci.index('Export the exact scanned release image'))


if __name__ == '__main__':
    unittest.main()
