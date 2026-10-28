import json
from pathlib import Path
import unittest


def assertCallInputsMirrorDispatch(test, workflow):
    """workflow_call takes the same inputs as workflow_dispatch, minus any default on a required input: GitHub rejects
    a required workflow_call input that also carries a default, which made the whole publisher file invalid (DLV-914,
    every pipeline run failed at startup on 2026-10-28)."""
    dispatch, call = workflow['on']['workflow_dispatch']['inputs'], workflow['on']['workflow_call']['inputs']
    test.assertEqual(list(dispatch), list(call))
    for name, spec in dispatch.items():
        expected = {key: value for key, value in spec.items() if not (key == 'default' and spec.get('required'))}
        test.assertEqual(expected, call[name], name)
        test.assertFalse(call[name].get('required') and 'default' in call[name], name + ' is required and has a default')


class ReleaseWorkflowTests(unittest.TestCase):
    def test_publication_requires_verified_same_run_image_and_explicit_dev_approval(self):
        root = Path(__file__).resolve().parents[2]
        workflow = json.loads((root / '.github/workflows/release-image.yml').read_text())
        self.assertEqual({'workflow_dispatch', 'workflow_call'}, set(workflow['on']))
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
        # runner.* is only valid inside steps; a job-level runner.temp made the whole file invalid (GitHub rejected every
        # run at startup from 2026-10-27 until DLV-914 fixed it), so the record path is exported by the first step instead.
        self.assertNotIn('RELEASE_SOURCE_RECORD', publish['env'])
        self.assertNotIn('runner.', json.dumps(publish['env']))
        export = next(i for i, s in enumerate(publish['steps']) if s.get('name') == 'Point the release-source record at the runner temp directory')
        self.assertEqual('echo "RELEASE_SOURCE_RECORD=$RUNNER_TEMP/release-source.json" >> "$GITHUB_ENV"', publish['steps'][export]['run'])
        self.assertEqual('release-source', publish['steps'][export - 1]['with']['name'])  # the record is restored first, then named
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
        # The merge-driven pipeline calls this same workflow with the tag it cut; dispatch by tag stays for reruns.
        assertCallInputsMirrorDispatch(self, workflow)
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
        self.assertEqual(source, publish['env']['SOURCE_SHA'])
        self.assertIn('RELEASE_SOURCE_RECORD=$RUNNER_TEMP/release-source.json', '\n'.join(step.get('run', '') for step in publish['steps']))
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
        # A build without a release tag (pull request, plain push) reports releaseTag dev and jar version 0.0.1-SNAPSHOT;
        # the Dockerfile test suite rejects any other tag shape, so the fallback must never pass the snapshot as the tag.
        plain = ci.replace('\\"', '"')  # the workflow file is JSON-shaped YAML, so quotes inside run strings are escaped
        self.assertIn('if [ -z "$RELEASE_VERSION" ]; then RELEASE_VERSION=dev; BUILD_VERSION=0.0.1-SNAPSHOT; else BUILD_VERSION="${RELEASE_VERSION#v}"; fi', plain)
        self.assertIn('--build-arg "RELEASE_VERSION=$RELEASE_VERSION" --build-arg "BUILD_VERSION=$BUILD_VERSION"', plain)
        self.assertNotIn('RELEASE_VERSION=0.0.1-SNAPSHOT', plain)


class PipelineWorkflowTests(unittest.TestCase):
    """Merge-driven pipeline (user decision 2026-10-27): merge -> tag -> build/scan/publish -> held DEV deploy -> PROD dummy."""
    ROOT = Path(__file__).resolve().parents[2]
    # The hosted checkout folder is the repository name (lookahead-learning-web, not web-public), so detect the app from the build file, never the folder.
    APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'

    def test_two_triggers_one_pipeline_tag_first_and_the_publisher_shared_through_workflow_call(self):
        workflow = json.loads((self.ROOT / '.github/workflows/pipeline.yml').read_text())
        publisher = json.loads((self.ROOT / '.github/workflows/release-image.yml').read_text())
        self.assertEqual({'push': {'branches': ['main']}, 'workflow_dispatch': {}}, workflow['on'])
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        self.assertEqual({'group': 'dev-pipeline-${{ github.repository }}', 'cancel-in-progress': False}, workflow['concurrency'])
        self.assertEqual(['tag', 'build-scan-publish', 'deploy-dev', 'deploy-prod'], list(workflow['jobs']))
        tag, publish, dev, prod = (workflow['jobs'][k] for k in ('tag', 'build-scan-publish', 'deploy-dev', 'deploy-prod'))
        # Tag job: the only contents:write; cuts the tag at the run commit; refuses off-main before anything else.
        self.assertEqual({'contents': 'write'}, tag['permissions'])
        self.assertEqual([('${{ github.sha }}', False)], [(s['with']['ref'], s['with']['persist-credentials']) for s in tag['steps'] if 'uses' in s and s['uses'].startswith('actions/checkout@')])
        runs = [s['run'] for s in tag['steps'] if 'run' in s]
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', runs[0])
        self.assertEqual('python3 tools/release/pipeline_tag.py cut --app ' + self.APP + ' --record "$RUNNER_TEMP/release-tag.json"', runs[1])
        self.assertEqual('cut', next(s['id'] for s in tag['steps'] if s.get('run') == runs[1]))
        self.assertEqual({'release-version': '${{ steps.cut.outputs.release_version }}', 'source-sha': '${{ steps.cut.outputs.source_sha }}'}, tag['outputs'])
        self.assertEqual(('release-tag', 21), next((s['with']['name'], s['with']['retention-days']) for s in tag['steps'] if str(s.get('uses', '')).startswith('actions/upload-artifact@')))
        # Publisher: one implementation for merge runs and manual reruns by tag.
        self.assertEqual('./.github/workflows/release-image.yml', publish['uses'])
        self.assertEqual('tag', publish['needs'])
        self.assertEqual('${{ needs.tag.outputs.release-version }}', publish['with']['release-version'])
        self.assertEqual('509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr', publish['with']['ecr-repository'])
        self.assertEqual("${{ vars.DEV_PUBLISH_ROLE_ARN || 'arn:aws:iam::509614632283:role/lookahead-dev-deploy' }}", publish['with']['role-arn'])
        self.assertEqual('write', publish['permissions']['id-token'])
        self.assertEqual({'workflow_dispatch', 'workflow_call'}, set(publisher['on']))
        assertCallInputsMirrorDispatch(self, publisher)
        for job, settings in publisher['jobs'].items():
            for key, value in settings.get('permissions', {}).items():
                self.assertEqual(publish['permissions'].get(key), value, job + ' ' + key)
        for name, job in workflow['jobs'].items():
            if name != 'build-scan-publish':
                self.assertNotIn('id-token', job.get('permissions', {}), name)
            if name != 'tag':
                self.assertNotEqual('write', job.get('permissions', {}).get('contents'), name)
        # No commit is ever an input: no dispatch inputs, no SHA anywhere but the trusted checkout ref.
        text = json.dumps(workflow)
        self.assertNotIn('inputs.', text)
        self.assertNotIn('$GITHUB_SHA', text)
        self.assertEqual(text.count('github.sha'), text.count('"ref": "${{ github.sha }}"'))
        # DEV deploy: present, held behind the repository variable, dispatching Infra with app + release tag only.
        self.assertEqual(['tag', 'build-scan-publish'], dev['needs'])
        self.assertEqual({'RELEASE_VERSION': '${{ needs.tag.outputs.release-version }}', 'DEV_DEPLOY_ENABLED': '${{ vars.DEV_DEPLOY_ENABLED }}',
                          'INFRA_DISPATCH_TOKEN': '${{ secrets.INFRA_DISPATCH_TOKEN }}'}, dev['env'])
        self.assertEqual(['python3 tools/release/pipeline_tag.py deploy-request --app ' + self.APP], [s['run'] for s in dev['steps'] if 'run' in s])
        helper = (self.ROOT / 'tools/release/pipeline_tag.py').read_text()
        self.assertIn("HOLD = 'DEV deploy held until the first hosted ECR publication is proven (set DEV_DEPLOY_ENABLED=true)'", helper)
        self.assertIn("'-f', 'app=' + app, '-f', 'release_version=' + release['releaseVersion']", helper)
        self.assertIn("'--repo', INFRA", helper)
        # PROD: dummy stage after DEV.
        self.assertEqual('deploy-dev', prod['needs'])
        self.assertEqual({}, prod['permissions'])
        self.assertIn('no PROD environment yet; runs once PROD is configured', prod['steps'][-1]['run'])
        self.assertNotIn('uses', prod['steps'][-1])
        # Third-party actions stay the pinned ones the publisher already uses.
        pinned = {s['uses'] for job in publisher['jobs'].values() for s in job.get('steps', []) if 'uses' in s}
        self.assertTrue({s['uses'] for job in workflow['jobs'].values() for s in job.get('steps', []) if 'uses' in s} <= pinned)


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
