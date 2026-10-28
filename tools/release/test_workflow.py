"""The numbered pipeline of this repository against the platform standard (Content `01 · Content Full`, Infra `00 · Infra Pipeline`).

One implementation, `release.yml` (`01 · Full`), runs on merge push and on dispatch; `02 · Build`, `03 · Publish` and
`04 · Deploy DEV` are thin callers that pass `action`. Stages in order: Security gate, Release tag, Build, Approve
publication, Publish, Deploy DEV, Verify DEV, Keep or roll back DEV (then Keep DEV / Roll back DEV), 05 · Deploy PROD
(not configured, shown skipped), Record the release. Identical test in Domain, Web and Gateway/BFF apart from APP.
"""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
# The hosted checkout folder is the repository name (lookahead-learning-web, not web-public): detect the app from the build file.
APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'
A = "(inputs.action || 'full')"
STAGES = ['validate', 'release', 'build', 'approve', 'publish', 'deploy-dev', 'verify-dev', 'decide-dev', 'keep-dev', 'rollback-dev', 'deploy-prod', 'record']
NAMES = {'validate': 'Security gate', 'release': 'Release tag', 'build': 'Build', 'approve': 'Approve publication', 'publish': 'Publish',
         'deploy-dev': 'Deploy DEV', 'verify-dev': 'Verify DEV', 'decide-dev': 'Keep or roll back DEV', 'keep-dev': 'Keep DEV: cut over to the kept release',
         'rollback-dev': 'Roll back DEV', 'deploy-prod': '05 · Deploy PROD (not configured)', 'record': 'Record the release'}
ENTRIES = {'build.yml': ('02 · Build', 'build', False), 'publish.yml': ('03 · Publish', 'publish', True), 'deploy-dev.yml': ('04 · Deploy DEV', 'deploy-dev', True)}
PINNED = {'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1', 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a',
          'actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333', 'actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97',
          'aws-actions/configure-aws-credentials@e1253824e5c10ff9df46874f81ed3ec929e19cfd'}


def load(name):
    return json.loads((ROOT / '.github/workflows' / name).read_text())


def runs(job):
    return [s['run'] for s in job.get('steps', []) if 'run' in s]


def names(job):
    return [s.get('name') or s.get('uses') for s in job.get('steps', [])]


def uses(job, prefix):
    return [s for s in job.get('steps', []) if str(s.get('uses', '')).startswith(prefix)]


class StandardShapeTests(unittest.TestCase):
    def test_numbered_names_run_name_triggers_inputs_and_thin_entries(self):
        full = load('release.yml')
        self.assertEqual('01 · Full', full['name'])
        self.assertEqual('${{ github.workflow }} | ${{ inputs.release_version || github.ref_name }} | ${{ github.event_name }} | #${{ github.run_number }}', full['run-name'])
        self.assertEqual({'push', 'workflow_dispatch', 'workflow_call'}, set(full['on']))
        self.assertEqual({'branches': ['main']}, full['on']['push'])
        dispatch, call = full['on']['workflow_dispatch']['inputs'], full['on']['workflow_call']['inputs']
        self.assertEqual(['action', 'release_version'], list(dispatch)); self.assertEqual(['action', 'release_version'], list(call))
        self.assertEqual(('choice', ['full', 'build', 'publish', 'deploy-dev'], 'full'), (dispatch['action']['type'], dispatch['action']['options'], dispatch['action']['default']))
        self.assertEqual(('string', True), (call['action']['type'], call['action']['required']))  # choice is dispatch-only on GitHub
        for spec in (dispatch['release_version'], call['release_version']):
            self.assertEqual(('string', False, ''), (spec['type'], spec['required'], spec['default']))
        for name, spec in call.items():  # a required workflow_call input with a default makes the whole file invalid
            self.assertFalse(spec.get('required') and 'default' in spec, name)
        self.assertEqual({'contents': 'read'}, full['permissions'])
        self.assertEqual({'group': 'release', 'cancel-in-progress': False}, full['concurrency'])
        self.assertEqual(STAGES, list(full['jobs']))
        self.assertEqual(NAMES, {k: v['name'] for k, v in full['jobs'].items()})
        for file, (title, action, required) in ENTRIES.items():
            entry = load(file)
            self.assertEqual(title, entry['name'])
            self.assertEqual(['workflow_dispatch'], list(entry['on']))
            spec = entry['on']['workflow_dispatch']['inputs']['release_version']
            self.assertEqual(required, spec['required']); self.assertEqual('string', spec['type'])
            job = entry['jobs']['release']
            self.assertEqual(['release'], list(entry['jobs']))
            self.assertEqual('./.github/workflows/release.yml', job['uses'])
            self.assertEqual({'action': action, 'release_version': '${{ inputs.release_version }}'}, job['with'])
            self.assertEqual('inherit', job['secrets'])
            for key, value in {'contents': 'write', 'actions': 'read', 'issues': 'write', 'pull-requests': 'read', 'id-token': 'write'}.items():
                self.assertEqual(value, entry['permissions'][key], file + ' ' + key)
            self.assertIn('${{ inputs.release_version', entry['run-name'])
        self.assertFalse((ROOT / '.github/workflows/pipeline.yml').exists())
        self.assertFalse((ROOT / '.github/workflows/release-image.yml').exists())

    def test_every_job_is_valid_for_github_and_pinned(self):
        full = load('release.yml')
        for name, job in full['jobs'].items():
            self.assertNotIn('runner.', json.dumps(job.get('env', {})), name)  # runner.* is step-only
            for step in job.get('steps', []):
                if 'uses' in step:
                    self.assertIn(step['uses'], PINNED, name + ': ' + step['uses'])
                if 'run' in step and name != 'deploy-prod':
                    self.assertFalse(step['run'].startswith('echo') and 'no PROD' in step['run'])
            if name not in ('release',):
                self.assertNotEqual('write', job.get('permissions', {}).get('contents'), name)
            if name not in ('publish', 'deploy-dev', 'keep-dev', 'rollback-dev'):
                self.assertNotIn('id-token', job.get('permissions', {}), name)
        text = json.dumps(full)
        self.assertNotIn('$GITHUB_SHA', text)
        self.assertEqual(text.count('github.sha'), text.count('"ref": "${{ github.sha }}"'))  # no typed commit anywhere
        self.assertNotIn('INFRA_DISPATCH_TOKEN', text); self.assertNotIn('workflow run', text)  # one run, never a dispatch of another repository
        helper = (ROOT / 'tools/release/pipeline_tag.py').read_text()
        self.assertNotIn('deploy-request', helper); self.assertNotIn('DEPLOY_WORKFLOW', helper)


class StageContractTests(unittest.TestCase):
    def setUp(self):
        self.full = load('release.yml'); self.jobs = self.full['jobs']

    def test_security_gate_then_release_tag_cut_on_full_or_resolved_from_the_input(self):
        validate, release = self.jobs['validate'], self.jobs['release']
        self.assertEqual({'contents': 'read'}, validate['permissions'])
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', runs(validate)[0])
        self.assertIn('--fail-under=85', runs(validate)[-1]); self.assertIn('*/candidate.py', runs(validate)[-1]); self.assertIn('*/owner_release.py', runs(validate)[-1])
        self.assertEqual('validate', release['needs'])
        self.assertEqual({'contents': 'write'}, release['permissions'])
        self.assertEqual({'release-version': '${{ steps.source.outputs.release_version }}', 'source-sha': '${{ steps.source.outputs.source_sha }}'}, release['outputs'])
        steps = {s['name']: s for s in release['steps'] if 'name' in s}
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', steps['Refuse any run that is not on main']['run'])
        need = steps['Require an existing release tag for publish and deploy-dev']
        self.assertEqual(A + " == 'publish' || " + A + " == 'deploy-dev'", need['if']); self.assertEqual('test -n "$RELEASE_VERSION"', need['run'])
        cut = steps['Cut the next release tag at the run commit, or reuse the one that already names it']
        self.assertEqual(('cut', A + " == 'full' || (" + A + " == 'build' && inputs.release_version == '')"), (cut['id'], cut['if']))
        self.assertEqual('python3 tools/release/pipeline_tag.py cut --app ' + APP + ' --record "$RUNNER_TEMP/release-tag.json"', cut['run'])
        source = steps['Resolve the release tag once and freeze its main commit']
        self.assertEqual('source', source['id'])
        self.assertEqual('${{ inputs.release_version || steps.cut.outputs.release_version }}', source['env']['RELEASE_VERSION'])
        self.assertEqual('python3 tools/release/release_version.py resolve --app ' + APP + ' --record "$RUNNER_TEMP/release-source.json"', source['run'])
        uploads = {s['with']['name']: s for s in uses(release, 'actions/upload-artifact@')}
        self.assertEqual({'release-source', 'release-tag'}, set(uploads))
        self.assertEqual("steps.cut.outcome == 'success'", uploads['release-tag']['if'])
        self.assertEqual(21, uploads['release-source']['with']['retention-days'])

    def test_build_is_the_reusable_ci_with_image_export_from_the_tag_resolved_source(self):
        build = self.jobs['build']
        self.assertEqual('release', build['needs'])
        self.assertEqual(A + " == 'full' || " + A + " == 'build' || " + A + " == 'publish'", build['if'])
        self.assertEqual('./.github/workflows/ci.yml', build['uses'])
        self.assertEqual({'export-image': True, 'release-version': '${{ needs.release.outputs.release-version }}', 'source-sha': '${{ needs.release.outputs.source-sha }}'}, build['with'])
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('${{ inputs.source-sha || github.sha }}', ci)
        self.assertIn('release-image-${{ inputs.source-sha || github.sha }}', ci)
        self.assertIn('SOURCE_REVISION=$SOURCE_SHA', ci); self.assertNotIn('SOURCE_REVISION=$GITHUB_SHA', ci)
        # The history secret scan refuses a shallow clone; the binding commit is fetched in full (Domain run #3, 2026-10-28).
        self.assertIn('git fetch origin \\"$GITHUB_SHA\\"' if ci.startswith('{') else 'git fetch origin "$GITHUB_SHA"', ci)
        self.assertNotIn('--depth', ci)
        self.assertIn('--fail-under=85', ci)
        if APP == 'web':  # the called CI shares the caller's ref; the group carries the workflow name so neither cancels the other
            self.assertIn('group: learning-web-ci-${{ github.workflow }}-${{ github.ref }}', ci)

    def test_approval_is_credential_free_gist_first_and_bound_to_the_same_run_image(self):
        approve = self.jobs['approve']
        self.assertEqual(['release', 'build'], approve['needs'])
        self.assertEqual("always() && needs.build.result == 'success' && (" + A + " == 'full' || " + A + " == 'publish')", approve['if'])
        self.assertEqual({'contents': 'read', 'actions': 'read', 'issues': 'write', 'pull-requests': 'read'}, approve['permissions'])
        self.assertNotIn('environment', approve)
        self.assertEqual([], uses(approve, 'aws-actions/'))
        self.assertEqual({'RELEASE_VERSION': '${{ needs.release.outputs.release-version }}', 'SOURCE_SHA': '${{ needs.release.outputs.source-sha }}', 'GH_TOKEN': '${{ github.token }}'}, approve['env'])
        downloads = {s['with']['name']: s['with']['path'] for s in uses(approve, 'actions/download-artifact@')}
        self.assertEqual({'release-source': '${{ runner.temp }}', 'release-image-${{ needs.release.outputs.source-sha }}': '${{ runner.temp }}/release-image'}, downloads)
        checkouts = [(s['with']['ref'], s['with'].get('path'), s['with']['persist-credentials']) for s in uses(approve, 'actions/checkout@')]
        self.assertEqual([('${{ github.sha }}', None, False), ('${{ needs.release.outputs.source-sha }}', 'release-source', False)], checkouts)
        self.assertEqual(['echo "RELEASE_SOURCE_RECORD=$RUNNER_TEMP/release-source.json" >> "$GITHUB_ENV"',
                          'python3 tools/release/owner_release.py gate --directory "$RUNNER_TEMP/release-image" --receipt "$RUNNER_TEMP/owner-approval.json"',
                          'python3 tools/release/owner_release.py approve --directory "$RUNNER_TEMP/release-image" --receipt "$RUNNER_TEMP/owner-approval.json" --source-root release-source'], runs(approve))
        self.assertEqual(('owner-approval', '${{ runner.temp }}/owner-plan.json\n${{ runner.temp }}/owner-approval.json'),
                         next((s['with']['name'], s['with']['path']) for s in uses(approve, 'actions/upload-artifact@')))

    def test_publish_rechecks_approval_and_tag_before_credentials_and_reads_the_digest_back(self):
        publish = self.jobs['publish']
        self.assertEqual(['release', 'approve'], publish['needs'])
        self.assertEqual("always() && needs.approve.result == 'success'", publish['if'])
        self.assertEqual('dev', publish['environment'])
        self.assertEqual({'contents': 'read', 'actions': 'read', 'issues': 'read', 'pull-requests': 'read', 'id-token': 'write'}, publish['permissions'])
        self.assertEqual('1', publish['env']['AWS_MAX_ATTEMPTS'])
        commands = runs(publish)
        oidc = next(i for i, s in enumerate(publish['steps']) if str(s.get('uses', '')).startswith('aws-actions/configure-aws-credentials@'))
        order = names(publish)
        for earlier in ('Restore the approval plan and receipt', 'Verify Git release tag and shared repository', 'Recheck live owner approval before upload credentials',
                        'Load and verify scanned image without rebuilding', 'Recheck release tag before upload credentials'):
            self.assertLess(order.index(earlier), oidc, earlier)
        self.assertIn('owner_release.py check --directory "$RUNNER_TEMP/release-image" --receipt "$RUNNER_TEMP/owner-approval.json" --source-root release-source', '\n'.join(commands))
        self.assertIn('release_version.py recheck --app ' + APP + ' --record "$RUNNER_TEMP/release-source.json"', '\n'.join(commands))
        self.assertIn('image.py import --directory "$RUNNER_TEMP/release-image" --source-root release-source', '\n'.join(commands))
        self.assertNotIn('docker build', '\n'.join(commands)); self.assertNotIn('docker push', '\n'.join(commands))  # the tool pushes once and reads back
        self.assertIn('owner_release.py publish --directory "$RUNNER_TEMP/release-image" --receipt "$RUNNER_TEMP/owner-approval.json" --source-root release-source --loaded', '\n'.join(commands))
        creds = publish['steps'][oidc]['with']
        self.assertEqual(("${{ vars.DEV_PUBLISH_ROLE_ARN || 'arn:aws:iam::509614632283:role/lookahead-dev-deploy' }}", 900, 'image-publish-${{ github.run_id }}-1', True),
                         (creds['role-to-assume'], creds['role-duration-seconds'], creds['role-session-name'], creds['disable-retry']))
        policy = json.loads(creds['inline-session-policy'])
        self.assertEqual('arn:aws:ecr:us-east-2:509614632283:repository/lookahead-dev-ecr', policy['Statement'][1]['Resource'])
        uploads = {s['with']['name']: s for s in uses(publish, 'actions/upload-artifact@')}
        self.assertEqual('${{ runner.temp }}/registry-image.json\n${{ runner.temp }}/release-source.json', uploads['registry-image-${{ needs.release.outputs.source-sha }}']['with']['path'])
        self.assertIn('publication-outcome.json', uploads['image-publication-outcome']['with']['path'])
        self.assertEqual("always() && (steps.publish.outcome == 'success' || steps.publish.outcome == 'failure')", uploads['image-publication-outcome']['if'])
        self.assertEqual('docker logout "${ECR_REPOSITORY%%/*}"', commands[-1])

    def test_deploy_dev_runs_infra_tooling_at_its_release_tag_inside_this_run_behind_the_flag(self):
        deploy = self.jobs['deploy-dev']
        self.assertEqual(['release', 'publish'], deploy['needs'])
        self.assertEqual("always() && needs.release.result == 'success' && vars.DEV_DEPLOY_ENABLED == 'true' && ((" + A + " == 'full' && needs.publish.result == 'success') || " + A + " == 'deploy-dev')", deploy['if'])
        self.assertEqual('dev', deploy['environment'])
        self.assertEqual({'contents': 'read', 'actions': 'read', 'issues': 'write', 'pull-requests': 'read', 'id-token': 'write'}, deploy['permissions'])
        self.assertEqual((APP if APP != 'gateway-bff' else 'gateway', '${{ needs.release.outputs.release-version }}'), (deploy['env']['DEPLOY_APP'], deploy['env']['DEPLOY_RELEASE_VERSION']))
        steps = {s['name']: s for s in deploy['steps'] if 'name' in s}
        self.assertEqual(('infra', 'python3 tools/release/candidate.py infra --output "$RUNNER_TEMP/infra-tooling.json"'),
                         (steps['Select the Infra tooling release (newest Infra calendar tag)']['id'], steps['Select the Infra tooling release (newest Infra calendar tag)']['run']))
        infra = steps['Checkout Infra tooling at its release tag']['with']
        self.assertEqual(('lookahead99ms/lookahead-learning-infra', '${{ steps.infra.outputs.infra_sha }}', 'infra-tooling', '${{ secrets.CROSS_REPO_READ_TOKEN }}', False),
                         (infra['repository'], infra['ref'], infra['path'], infra['token'], infra['persist-credentials']))
        chain = [s['run'] for s in deploy['steps'] if s.get('working-directory') == 'infra-tooling']
        self.assertEqual(['python3 -m github.workflows.application_workflow config',
                          'python3 -m github.workflows.application_inputs --config app-inputs/workflow-config.json --runs app-inputs/runs.json --output app-inputs',
                          'python3 -m github.workflows.application_workflow plan',
                          'python3 -m github.approvals.application_approval approve --plan app-plan/plan.json --receipt app-approval/approval.json',
                          'python3 -m github.approvals.application_approval verify --plan app-plan/plan.json --receipt app-approval/approval.json',
                          'python3 -m github.workflows.application_workflow execute'], chain)
        creds = [s['with'] for s in uses(deploy, 'aws-actions/configure-aws-credentials@')]
        self.assertEqual([('${{ steps.config.outputs.planRoleArn }}', 'app-deploy-plan-${{ github.run_id }}-1'), ('${{ steps.config.outputs.deployRoleArn }}', 'app-deploy-${{ github.run_id }}-1')],
                         [(c['role-to-assume'], c['role-session-name']) for c in creds])
        order = names(deploy)
        self.assertLess(order.index('Wait for the owner approval of this exact deployment (issue with gist first)'), order.index('Use short-lived DEV OIDC credentials for the deployment'))
        self.assertLess(order.index('Recheck the deployment approval before AWS credentials'), order.index('Use short-lived DEV OIDC credentials for the deployment'))
        uploads = {s['with']['name']: s['with']['path'] for s in uses(deploy, 'actions/upload-artifact@')}
        self.assertEqual('infra-tooling/app-result/', uploads['application-candidate-result'])
        self.assertIn('infra-tooling/app-inputs/', uploads['deploy-dev-receipts'])

    def test_verify_decide_keep_rollback_and_grey_prod(self):
        verify, decide, keep, back, prod = (self.jobs[k] for k in ('verify-dev', 'decide-dev', 'keep-dev', 'rollback-dev', 'deploy-prod'))
        self.assertEqual("always() && needs.deploy-dev.result == 'success'", verify['if'])
        self.assertEqual([], uses(verify, 'aws-actions/')); self.assertNotIn('id-token', verify['permissions'])
        self.assertIn('candidate.py verify --stage dev --result-dir "$RUNNER_TEMP/app-result" --registry-image "$REGISTRY_IMAGE" --url "$DEV_VERIFY_URL" --output "$RUNNER_TEMP/candidate-verify.json"', '\n'.join(runs(verify)))
        self.assertEqual("always() && needs.verify-dev.result == 'success'", decide['if'])
        self.assertEqual({'decision': '${{ steps.decide.outputs.decision }}'}, decide['outputs'])
        self.assertEqual('write', decide['permissions']['issues']); self.assertNotIn('environment', decide); self.assertNotIn('id-token', decide['permissions'])
        self.assertIn('candidate.py decide --stage dev --verification "$RUNNER_TEMP/candidate-verify.json" --output "$RUNNER_TEMP/candidate-decision.json"', '\n'.join(runs(decide)))
        self.assertEqual("always() && needs.decide-dev.result == 'success' && needs.decide-dev.outputs.decision == 'keep'", keep['if'])
        self.assertIn('candidate.py cutover --stage dev', '\n'.join(runs(keep)))
        # Failed verification rolls back without a question; a ROLLBACK decision rolls back too (user decision 2026-10-28).
        self.assertEqual("always() && needs.deploy-dev.result == 'success' && (needs.verify-dev.result == 'failure' || needs.decide-dev.outputs.decision == 'rollback')", back['if'])
        self.assertIn('candidate.py rollback --stage dev', '\n'.join(runs(back)))
        for job in (keep, back):
            self.assertEqual('dev', job['environment'])
        # PROD is visible as a stage and skipped (grey) until PROD exists; it never pretends to deploy.
        self.assertEqual("always() && needs.keep-dev.result == 'success' && vars.PROD_ENABLED == 'true'", prod['if'])
        self.assertEqual({}, prod['permissions']); self.assertTrue(runs(prod)[0].endswith('exit 1'))

    def test_record_runs_whenever_a_release_exists_and_writes_one_record_per_run(self):
        record = self.jobs['record']
        self.assertEqual(STAGES[1:-1], record['needs'])
        self.assertEqual("always() && needs.release.result == 'success'", record['if'])
        self.assertEqual({'contents': 'read', 'actions': 'read'}, record['permissions'])
        optional = next(s for s in uses(record, 'actions/download-artifact@') if 'pattern' in s['with'])
        self.assertTrue(optional['continue-on-error']); self.assertTrue(optional['with']['merge-multiple'])
        for name in ('registry-image-*', 'owner-approval', 'candidate-verify', 'candidate-decision', 'deploy-dev-receipts'):
            self.assertIn(name, optional['with']['pattern'])
        self.assertIn('candidate.py record --registry-image "$r/registry-image.json" --approval "$r/owner-approval.json" --verification "$r/candidate-verify.json" --decision "$r/candidate-decision.json" --infra "$r/infra-tooling.json" --output "$RUNNER_TEMP/release-record.json"', '\n'.join(runs(record)))
        upload = next(s['with'] for s in uses(record, 'actions/upload-artifact@'))
        self.assertEqual(('release-record-${{ needs.release.outputs.release-version }}', 90), (upload['name'], upload['retention-days']))


if __name__ == '__main__':
    unittest.main()
