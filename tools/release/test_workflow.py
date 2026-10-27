import json
from pathlib import Path
import unittest


class ReleaseWorkflowTests(unittest.TestCase):
    def test_publication_requires_verified_same_run_image_and_explicit_dev_approval(self):
        root = Path(__file__).resolve().parents[2]
        workflow = json.loads((root / '.github/workflows/release-image.yml').read_text())
        self.assertEqual({'workflow_dispatch'}, set(workflow['on']))
        resolve, verify, publish = workflow['jobs']['resolve'], workflow['jobs']['verify'], workflow['jobs']['publish']
        self.assertEqual(['resolve', 'verify', 'publish'], list(workflow['jobs']))
        self.assertEqual('./.github/workflows/ci.yml', verify['uses'])
        self.assertIs(True, verify['with']['export-image'])
        self.assertEqual('resolve', verify['needs'])
        self.assertEqual(['resolve', 'verify'], publish['needs'])
        self.assertEqual('dev', publish['environment'])
        steps = publish['steps']
        names = [step['name'] for step in steps]
        approval = names.index('Recheck live owner approval before upload credentials')
        load = names.index('Load and verify scanned image without rebuilding')
        oidc = next(i for i, step in enumerate(steps) if str(step.get('uses', '')).startswith('aws-actions/configure-aws-credentials@'))
        self.assertLess(approval, oidc)
        self.assertLess(load, oidc)
        self.assertLess(names.index('Recheck release tag before upload credentials'), oidc)
        self.assertEqual('release_version.py recheck --app domain --record "$RUNNER_TEMP/release-source.json"', steps[names.index('Recheck release tag before upload credentials')]['run'].split('tools/release/')[1])
        self.assertTrue(steps[names.index('Recheck release tag before upload credentials')]['run'].startswith('python3 tools/release/'))
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
        self.assertIn('--source-root release-source', commands)
        self.assertEqual('${{ runner.temp }}/release-source.json', publish['env']['RELEASE_SOURCE_RECORD'])
        self.assertIn('${{ runner.temp }}/release-source.json', steps[names.index('Preserve owner approval and publication outcome')]['with']['path'])
        policy=json.loads(steps[oidc]['with']['inline-session-policy'])
        self.assertEqual(policy['Statement'][1]['Resource'],'arn:aws:ecr:us-east-2:509614632283:repository/lookahead-dev-ecr')
        self.assertEqual(steps[oidc]['with']['role-duration-seconds'],900)
        ci = (root / '.github/workflows/ci.yml').read_text()
        self.assertTagDrivenSource(workflow, ci, 'domain')
        coverageStep=next(s for s in json.loads(ci)['jobs']['verify']['steps'] if s.get('name')=='Verify immutable image binding')
        self.assertEqual(coverageStep['env']['COVERAGE_FILE'],'${{ runner.temp }}/domain-release.coverage')
        self.assertIn('--fail-under=85',coverageStep['run'])
        sast = 'Enforce CodeQL severity' if root.name.endswith('web-public') else 'Require completed SAST'
        self.assertLess(ci.index(sast), ci.index('Export the exact scanned release image'))
        self.assertLess(ci.index('images --image'), ci.index('Export the exact scanned release image'))

    def assertTagDrivenSource(self, workflow, ci, app):
        """Platform rule: a calendar release tag selects source; no typed commit anywhere; tooling runs from main."""
        source = '${{ needs.resolve.outputs.source-sha }}'
        inputs = workflow['on']['workflow_dispatch']['inputs']
        self.assertEqual({'role-arn', 'ecr-repository', 'release-version'}, set(inputs))
        self.assertEqual(('string', True), (inputs['release-version']['type'], inputs['release-version']['required']))
        self.assertEqual('${{ github.workflow }} | ${{ inputs.release-version }} | ${{ github.ref_name }} | #${{ github.run_number }}', workflow['run-name'])
        resolve, verify, publish = workflow['jobs']['resolve'], workflow['jobs']['verify'], workflow['jobs']['publish']
        for job in workflow['jobs'].values():
            for key, value in {**job.get('env', {}), **{k: v for s in job.get('steps', []) for k, v in s.get('env', {}).items()}}.items():
                self.assertFalse('approved' in (key + str(value)).lower() and 'sha' in (key + str(value)).lower(), key)
        self.assertFalse(any('approved' in name.lower() and 'sha' in name.lower() for name in inputs))
        self.assertEqual({'contents': 'read'}, resolve['permissions'])
        self.assertEqual({'source-sha': '${{ steps.source.outputs.source_sha }}', 'release-version': '${{ steps.source.outputs.release_version }}'}, resolve['outputs'])
        resolveSteps = resolve['steps']
        self.assertEqual(('${{ github.sha }}', False), (resolveSteps[0]['with']['ref'], resolveSteps[0]['with']['persist-credentials']))
        self.assertEqual(('source', 'python3 tools/release/release_version.py resolve --app ' + app + ' --record "$RUNNER_TEMP/release-source.json"'), (resolveSteps[1]['id'], resolveSteps[1]['run']))
        self.assertEqual(('release-source', 21), (resolveSteps[2]['with']['name'], resolveSteps[2]['with']['retention-days']))
        self.assertEqual(source, verify['with']['source-sha'])
        self.assertEqual('${{ inputs.release-version }}', verify['with']['release-version'])
        steps = publish['steps']
        checkouts = [s for s in steps if str(s.get('uses', '')).startswith('actions/checkout@')]
        self.assertEqual([('${{ github.sha }}', None), (source, 'release-source')], [(s['with']['ref'], s['with'].get('path')) for s in checkouts])
        self.assertTrue(all(s['with']['persist-credentials'] is False for s in checkouts))
        downloads = {s['with']['name']: s['with']['path'] for s in steps if str(s.get('uses', '')).startswith('actions/download-artifact@')}
        self.assertEqual({'release-source': '${{ runner.temp }}', 'release-image-' + source: '${{ runner.temp }}/release-image'}, downloads)
        self.assertLess(next(i for i, s in enumerate(steps) if s.get('with', {}).get('name') == 'release-source'), next(i for i, s in enumerate(steps) if 'run' in s))
        uploads = {s['with']['name']: s['with']['path'] for s in steps if str(s.get('uses', '')).startswith('actions/upload-artifact@')}
        self.assertIn('registry-image-' + source, uploads)
        self.assertTrue(any('release-source.json' in path for path in uploads.values()))
        self.assertEqual((source, '${{ runner.temp }}/release-source.json'), (publish['env']['SOURCE_SHA'], publish['env']['RELEASE_SOURCE_RECORD']))
        commands = '\n'.join(step.get('run', '') for step in steps)
        self.assertNotIn('$GITHUB_SHA', commands)
        self.assertNotIn('github.sha', json.dumps(steps).replace('${{ github.sha }}', ''))
        self.assertIn('image.py import --directory "$RUNNER_TEMP/release-image" --source-root release-source', commands)
        # Reusable CI builds the resolved source while the executed workflow commit stays available for binding.
        self.assertIn('source-sha', ci)
        self.assertIn('${{ inputs.source-sha || github.sha }}', ci)
        self.assertIn('release-image-${{ inputs.source-sha || github.sha }}', ci)
        self.assertIn('SOURCE_REVISION=$SOURCE_SHA', ci)
        self.assertNotIn('SOURCE_REVISION=$GITHUB_SHA', ci)
        self.assertIn('git fetch --depth 1 origin \\"$GITHUB_SHA\\"' if ci.startswith('{') else 'git fetch --depth 1 origin "$GITHUB_SHA"', ci)
        self.assertLess(ci.index('git fetch --depth 1 origin'), ci.index('docker build'))


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
