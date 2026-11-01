"""The numbered pipeline of this repository against the platform standard (Content `01 · Content Full`, Infra `00 · Infra Pipeline`).

`release.yml` (`01 · Full`) runs on merge push and on dispatch and stops at image publication: Security gate, Release tag,
Build, Approve ECR publish, Publish to ECR, Record release. `02 · Build` and `03 · Publish` are thin callers that pass
`action`. `04 · Deploy DEV` (deploy-dev.yml, DLV-968) is its own dispatch-only workflow: Security gate, Plan DEV deploy,
Approve DEV deploy, Deploy to DEV, Record deploy, driven by `tools/release/deploy.py` against this repository's own slot
stacks. Identical test in Domain, Web and Gateway/BFF apart from APP.
"""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
# The hosted checkout folder is the repository name (lookahead-learning-web, not web-public): detect the app from the build file.
APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'
A = "(inputs.action || 'full')"
STAGES = ['validate', 'release', 'build', 'approve', 'publish', 'record']
NAMES = {'validate': 'Security gate', 'release': 'Release tag', 'build': 'Build', 'approve': 'Approve ECR publish', 'publish': 'Publish to ECR', 'record': 'Record release'}
DEPLOY_STAGES = ['validate', 'plan-dev', 'approve-deploy', 'deploy-dev', 'record']
DEPLOY_NAMES = {'validate': 'Security gate', 'plan-dev': 'Plan DEV deploy', 'approve-deploy': 'Approve DEV deploy', 'deploy-dev': 'Deploy to DEV', 'record': 'Record deploy'}
ENTRIES = {'build.yml': ('02 · Build', 'build'), 'publish.yml': ('03 · Publish', 'publish')}
PINNED = {'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1', 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a',
          'actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333', 'actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97',
          'aws-actions/configure-aws-credentials@e1253824e5c10ff9df46874f81ed3ec929e19cfd'}
GATE = "--include='*/release_version.py,*/pipeline_tag.py,*/image.py,*/registry.py,*/issue_template.py,*/owner_approval.py,*/owner_release.py,*/candidate.py,*/issue_close.py,*/deploy.py' --fail-under=85"


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
        self.assertEqual('${{ github.workflow }} | ${{ github.ref_name }} | ${{ github.event_name }} | #${{ github.run_number }}', full['run-name'])
        self.assertEqual({'push', 'workflow_dispatch', 'workflow_call'}, set(full['on']))
        self.assertEqual({'branches': ['main']}, full['on']['push'])
        # A manual Full takes no inputs: it does what a merge does, from the main head (user decision 2026-10-28). The
        # numbered entries pass `action` through workflow_call. No version is ever typed into Full (deploys live in 04).
        self.assertEqual({}, full['on']['workflow_dispatch'])
        call = full['on']['workflow_call']['inputs']
        self.assertEqual(['action'], list(call))
        self.assertEqual(('string', True), (call['action']['type'], call['action']['required']))
        self.assertFalse('default' in call['action'])
        self.assertEqual({'contents': 'read'}, full['permissions'])
        self.assertEqual({'group': 'release', 'cancel-in-progress': False}, full['concurrency'])
        self.assertEqual(STAGES, list(full['jobs']))
        self.assertEqual(NAMES, {k: v['name'] for k, v in full['jobs'].items()})
        for file, (title, action) in ENTRIES.items():
            entry = load(file)
            self.assertEqual(title, entry['name'])
            self.assertEqual(['workflow_dispatch'], list(entry['on']))
            self.assertEqual({}, entry['on']['workflow_dispatch'])  # Build and Publish cut the next tag at the main head; no version to type
            job = entry['jobs']['release']
            self.assertEqual(['release'], list(entry['jobs']))
            self.assertEqual('./.github/workflows/release.yml', job['uses'])
            self.assertEqual({'action': action}, job['with'])
            self.assertEqual('inherit', job['secrets'])
            for key, value in {'contents': 'write', 'actions': 'read', 'issues': 'write', 'pull-requests': 'read', 'id-token': 'write'}.items():
                self.assertEqual(value, entry['permissions'][key], file + ' ' + key)
        self.assertFalse((ROOT / '.github/workflows/pipeline.yml').exists())
        self.assertFalse((ROOT / '.github/workflows/release-image.yml').exists())
        text = json.dumps(full)
        for retired in ('deploy-dev', 'DEV_DEPLOY_ENABLED', 'DEV_DEPLOYMENT_CONFIG_JSON', 'DEV_VERIFY_URL', 'PROD_ENABLED', 'application_workflow', 'inputs.release_version'):
            self.assertNotIn(retired, text, retired)

    def test_merge_pause_variable_skips_the_whole_merge_run_but_never_a_dispatch(self):
        # RELEASE_ON_MERGE=false (user 2026-10-31): a merge push leaves every job skipped, so no tag, build, approval wait or ECR push.
        validate = load('release.yml')['jobs']['validate']
        self.assertEqual("github.event_name != 'push' || vars.RELEASE_ON_MERGE != 'false'", validate['if'])
        self.assertNotIn('if', load('deploy-dev.yml')['jobs']['validate'])

    def test_every_issue_close_step_is_fail_safe(self):
        # Closing a consumed approval issue must never fail the stage: a hand-closed or hand-edited issue, or a GitHub
        # error, is a warning and the job stays green (user decision 2026-10-29). The helper exits 0 for a closed issue.
        for file, expected in (('release.yml', 2), ('deploy-dev.yml', 2)):
            jobs = load(file)['jobs']
            closers = [(name, s) for name, job in jobs.items() for s in job.get('steps', []) if 'issue_close.py --stage' in s.get('run', '')]
            self.assertEqual(expected, len(closers), file)
            for name, step in closers:
                self.assertIs(True, step.get('continue-on-error'), (file, name, step['name']))
                self.assertIn(step['if'].split(' ')[0], ('failure()', 'always()'), (file, name, step['name']))
                self.assertEqual('write', jobs[name]['permissions'].get('issues'), (file, name))

    def test_every_job_is_valid_for_github_and_pinned(self):
        for file, credentialed in (('release.yml', ('publish',)), ('deploy-dev.yml', ('plan-dev', 'deploy-dev'))):
            full = load(file)
            for name, job in full['jobs'].items():
                self.assertNotIn('runner.', json.dumps(job.get('env', {})), (file, name))  # runner.* is step-only
                for step in job.get('steps', []):
                    if 'uses' in step:
                        self.assertIn(step['uses'], PINNED, file + ' ' + name + ': ' + step['uses'])
                if name not in ('release',):
                    self.assertNotEqual('write', job.get('permissions', {}).get('contents'), (file, name))
                if name not in credentialed:
                    self.assertNotIn('id-token', job.get('permissions', {}), (file, name))
                else:
                    self.assertEqual('dev', job['environment'], (file, name))
            text = json.dumps(full)
            self.assertNotIn('$GITHUB_SHA', text)
            self.assertEqual(text.count('github.sha'), text.count('"ref": "${{ github.sha }}"'), file)  # no typed commit anywhere
            self.assertNotIn('INFRA_DISPATCH_TOKEN', text); self.assertNotIn('workflow run', text)  # one run, never a dispatch of another repository
        helper = (ROOT / 'tools/release/pipeline_tag.py').read_text()
        self.assertNotIn('deploy-request', helper); self.assertNotIn('DEPLOY_WORKFLOW', helper)


class StageContractTests(unittest.TestCase):
    def setUp(self):
        self.full = load('release.yml'); self.jobs = self.full['jobs']

    def test_security_gate_then_release_tag_cut_at_the_run_commit(self):
        validate, release = self.jobs['validate'], self.jobs['release']
        self.assertEqual({'contents': 'read'}, validate['permissions'])
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', runs(validate)[0])
        self.assertTrue(runs(validate)[-1].endswith(GATE)); self.assertIn('*/deploy.py', runs(validate)[-1])
        self.assertEqual('validate', release['needs'])
        self.assertEqual({'contents': 'write'}, release['permissions'])
        self.assertEqual({'release-version': '${{ steps.source.outputs.release_version }}', 'source-sha': '${{ steps.source.outputs.source_sha }}'}, release['outputs'])
        steps = {s['name']: s for s in release['steps'] if 'name' in s}
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', steps['Refuse any run that is not on main']['run'])
        cut = steps['Cut the next release tag at the run commit, or reuse the one that already names it']
        self.assertEqual('cut', cut['id']); self.assertNotIn('if', cut)
        self.assertEqual('python3 tools/release/pipeline_tag.py cut --app ' + APP + ' --record "$RUNNER_TEMP/release-tag.json"', cut['run'])
        source = steps['Resolve the release tag once and freeze its main commit']
        self.assertEqual('source', source['id'])
        self.assertEqual('${{ steps.cut.outputs.release_version }}', source['env']['RELEASE_VERSION'])
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
                          'python3 tools/release/owner_release.py approve --directory "$RUNNER_TEMP/release-image" --receipt "$RUNNER_TEMP/owner-approval.json" --source-root release-source'], runs(approve)[:3])
        self.assertEqual(('owner-approval', '${{ runner.temp }}/owner-plan.json\n${{ runner.temp }}/owner-approval.json'),
                         next((s['with']['name'], s['with']['path']) for s in uses(approve, 'actions/upload-artifact@')))

    def test_publish_rechecks_approval_and_tag_before_credentials_and_reads_the_digest_back(self):
        publish = self.jobs['publish']
        self.assertEqual(['release', 'approve'], publish['needs'])
        self.assertEqual("always() && needs.approve.result == 'success'", publish['if'])
        self.assertEqual('dev', publish['environment'])
        self.assertEqual({'contents': 'read', 'actions': 'read', 'issues': 'write', 'pull-requests': 'read', 'id-token': 'write'}, publish['permissions'])
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
        self.assertEqual('docker logout "${ECR_REPOSITORY%%/*}"', commands[-2])
        # The consumed approval issue is closed with the outcome as the last step, whatever happened (user decision 2026-10-28).
        last = publish['steps'][-1]
        self.assertEqual(('always()', '${{ steps.publish.outcome }}'), (last['if'], last['env']['OUTCOME']))
        self.assertIn('issue_close.py --stage "Publish to ECR" --outcome "$OUTCOME" --detail "$detail" --receipt "$RUNNER_TEMP/owner-approval.json"', last['run'])
        approve = self.jobs['approve']
        self.assertEqual(('failure()', True), (approve['steps'][-1]['if'], '--match "Publish image"' in approve['steps'][-1]['run']))

    def test_record_runs_whenever_a_release_exists_and_writes_one_record_per_run(self):
        record = self.jobs['record']
        self.assertEqual(STAGES[1:-1], record['needs'])
        self.assertEqual("always() && needs.release.result == 'success'", record['if'])
        self.assertEqual({'contents': 'read', 'actions': 'read'}, record['permissions'])
        optional = next(s for s in uses(record, 'actions/download-artifact@') if 'pattern' in s['with'])
        self.assertTrue(optional['continue-on-error']); self.assertTrue(optional['with']['merge-multiple'])
        self.assertEqual('{registry-image-*,owner-approval}', optional['with']['pattern'])
        self.assertIn('candidate.py record --registry-image "$r/registry-image.json" --approval "$r/owner-approval.json" --output "$RUNNER_TEMP/release-record.json"', '\n'.join(runs(record)))
        upload = next(s['with'] for s in uses(record, 'actions/upload-artifact@'))
        self.assertEqual(('release-record-${{ needs.release.outputs.release-version }}', 90), (upload['name'], upload['retention-days']))


class DeployWorkflowTests(unittest.TestCase):
    """`04 · Deploy DEV` (DLV-968): dispatch only, one published tag in, three change sets on this repository's slot stacks."""

    def setUp(self):
        self.wf = load('deploy-dev.yml'); self.jobs = self.wf['jobs']
        self.app = {'web': 'web', 'domain': 'domain', 'gateway-bff': 'gateway'}[APP]

    def test_shape_trigger_and_stage_order(self):
        self.assertEqual('04 · Deploy DEV', self.wf['name'])
        self.assertEqual(['workflow_dispatch'], list(self.wf['on']))
        spec = self.wf['on']['workflow_dispatch']['inputs']['release_version']
        self.assertEqual((True, 'string'), (spec['required'], spec['type']))
        self.assertEqual({'contents': 'read'}, self.wf['permissions'])
        self.assertEqual({'group': 'deploy-dev', 'cancel-in-progress': False}, self.wf['concurrency'])
        self.assertEqual("${{ vars.DEV_DEPLOY_ROLE_ARN || 'arn:aws:iam::509614632283:role/lookahead-dev-deploy' }}", self.wf['env']['DEPLOY_ROLE'])
        self.assertEqual('${{ inputs.release_version }}', self.wf['env']['RELEASE_VERSION'])
        self.assertEqual(DEPLOY_STAGES, list(self.jobs))
        self.assertEqual(DEPLOY_NAMES, {k: v['name'] for k, v in self.jobs.items()})
        self.assertFalse('uses' in json.dumps(self.jobs['plan-dev']['needs']))  # no reusable-workflow hop: the deploy is this file

    def test_security_gate_covers_the_deploy_tool(self):
        validate = self.jobs['validate']
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', runs(validate)[0])
        self.assertTrue(runs(validate)[-1].endswith(GATE))

    def test_plan_resolves_the_tag_assumes_the_plan_session_and_reads_nothing_from_other_repositories(self):
        # User decision 2026-11-01: the six-role IAM check is an Infra concern and runs in Infra workflows only; application
        # deploys read no other repository and need no cross-repository token.
        plan = self.jobs['plan-dev']
        self.assertEqual(('validate', 'dev', {'contents': 'read', 'actions': 'read', 'id-token': 'write'}), (plan['needs'], plan['environment'], plan['permissions']))
        self.assertEqual({'candidate-slot': '${{ steps.plan.outputs.candidate_slot }}', 'plan-digest': '${{ steps.plan.outputs.plan_digest }}'}, plan['outputs'])
        steps = {s['name']: s for s in plan['steps'] if 'name' in s}
        self.assertIn('release_version.py resolve --app ' + APP + ' --record "$RUNNER_TEMP/deploy-plan/release-source.json"', steps['Resolve the dispatched release tag once and freeze its main commit']['run'])
        creds = [c['with'] for c in uses(plan, 'aws-actions/configure-aws-credentials@')]
        self.assertEqual([('${{ env.DEPLOY_ROLE }}', self.app + '-deploy-plan-${{ github.run_id }}-1', 900, '509614632283')],
                         [(c['role-to-assume'], c['role-session-name'], c['role-duration-seconds'], c['allowed-account-ids']) for c in creds])
        order = names(plan)
        oidc = order.index(next(s['name'] for s in plan['steps'] if str(s.get('uses', '')).startswith('aws-actions/')))
        self.assertLess(order.index('Point the release-source record at the runner temp directory'), oidc)
        self.assertEqual('python3 tools/release/deploy.py plan --output "$RUNNER_TEMP/deploy-plan/plan.json"', steps['Plan the deploy: published digest, idle slot, three change sets, resource preview']['run'])
        text = json.dumps(self.wf)
        for foreign in ('lookahead-learning-infra', 'CROSS_REPO_READ_TOKEN', 'six_role_verify', 'infra-tooling', 'candidate.py infra'):
            self.assertNotIn(foreign, text, foreign)
        self.assertEqual({'deploy-plan': '${{ runner.temp }}/deploy-plan/'}, {s['with']['name']: s['with']['path'] for s in uses(plan, 'actions/upload-artifact@')})

    def test_approval_is_credential_free_and_its_own_stage(self):
        approve = self.jobs['approve-deploy']
        self.assertEqual((['plan-dev'], "always() && needs.plan-dev.result == 'success'"), (approve['needs'], approve['if']))
        self.assertEqual({'contents': 'read', 'actions': 'read', 'issues': 'write', 'pull-requests': 'read'}, approve['permissions'])
        self.assertNotIn('environment', approve); self.assertEqual([], uses(approve, 'aws-actions/'))
        self.assertIn('deploy.py approve --plan "$RUNNER_TEMP/deploy-plan/plan.json" --receipt "$RUNNER_TEMP/deploy-approval/approval.json"', '\n'.join(runs(approve)))
        self.assertEqual({'deploy-plan': '${{ runner.temp }}/deploy-plan'}, {s['with']['name']: s['with']['path'] for s in uses(approve, 'actions/download-artifact@')})
        self.assertEqual({'deploy-approval': '${{ runner.temp }}/deploy-approval/'}, {s['with']['name']: s['with']['path'] for s in uses(approve, 'actions/upload-artifact@')})
        self.assertEqual(('failure()', True), (approve['steps'][-1]['if'], '--match "Deploy"' in approve['steps'][-1]['run']))

    def test_deploy_rechecks_approval_before_credentials_then_executes_once_and_records_the_outcome(self):
        deploy = self.jobs['deploy-dev']
        self.assertEqual((['plan-dev', 'approve-deploy'], "always() && needs.approve-deploy.result == 'success'", 'dev'), (deploy['needs'], deploy['if'], deploy['environment']))
        self.assertEqual({'contents': 'read', 'actions': 'read', 'issues': 'write', 'id-token': 'write'}, deploy['permissions'])
        order = names(deploy)
        oidc = order.index(next(s['name'] for s in deploy['steps'] if str(s.get('uses', '')).startswith('aws-actions/')))
        self.assertLess(order.index('Recheck the deployment approval before AWS credentials'), oidc)
        self.assertEqual(oidc + 1, order.index('Execute the approved plan: candidate change set, verification, flip, observation, retire'))
        creds = [c['with'] for c in uses(deploy, 'aws-actions/configure-aws-credentials@')]
        self.assertEqual([('${{ env.DEPLOY_ROLE }}', self.app + '-deploy-${{ github.run_id }}-1', 3600)], [(c['role-to-assume'], c['role-session-name'], c['role-duration-seconds']) for c in creds])
        self.assertEqual([('${{ github.sha }}', None)], [(s['with']['ref'], s['with'].get('path')) for s in uses(deploy, 'actions/checkout@')])
        execute = next(s for s in deploy['steps'] if s.get('id') == 'execute')
        self.assertEqual('python3 tools/release/deploy.py execute --plan "$RUNNER_TEMP/deploy-plan/plan.json" --receipt "$RUNNER_TEMP/deploy-approval/approval.json" --output "$RUNNER_TEMP/deploy-result/result.json"', execute['run'])
        result = next(s for s in uses(deploy, 'actions/upload-artifact@') if s['with']['name'] == 'deploy-result')
        self.assertEqual(('always()', 90), (result['if'], result['with']['retention-days']))
        last = deploy['steps'][-1]
        self.assertEqual(('always()', '${{ steps.execute.outcome }}'), (last['if'], last['env']['OUTCOME']))
        self.assertIn('issue_close.py --stage "Deploy to DEV" --outcome "$OUTCOME"', last['run'])
        text = json.dumps(self.wf)
        for forbidden in ('update-service', 'delete-stack', 'ModifyListener', 'docker '):
            self.assertNotIn(forbidden, text, forbidden)  # every AWS change goes through deploy.py change sets

    def test_record_bundles_the_three_receipts_per_run(self):
        record = self.jobs['record']
        self.assertEqual((['plan-dev', 'approve-deploy', 'deploy-dev'], "always() && needs.plan-dev.result == 'success'"), (record['needs'], record['if']))
        self.assertEqual({'contents': 'read', 'actions': 'read'}, record['permissions'])
        optional = next(s for s in uses(record, 'actions/download-artifact@'))
        self.assertEqual(('{deploy-plan,deploy-approval,deploy-result}', True), (optional['with']['pattern'], optional['continue-on-error']))
        upload = next(s['with'] for s in uses(record, 'actions/upload-artifact@'))
        self.assertEqual(('deploy-record-${{ inputs.release_version }}-${{ github.run_id }}', 90), (upload['name'], upload['retention-days']))

    def test_deployment_files_name_this_application(self):
        config = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())
        template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        self.assertEqual((APP, self.app, self.app + '-deploy'), (config['app'], config['service'], config['sessionPrefix']))
        self.assertEqual({'lookahead-dev-' + self.app + '-blue', 'lookahead-dev-' + self.app + '-green'}, {s['stack'] for s in config['slots'].values()})
        self.assertEqual('lookahead-dev-' + self.app + '-${Slot}', template['Resources']['Service']['Properties']['ServiceName']['Fn::Sub'])
        self.assertEqual({'Ref': 'DesiredCount'}, template['Resources']['Service']['Properties']['DesiredCount'])
        self.assertEqual('Retain', template['Resources']['Service']['DeletionPolicy'])
        self.assertEqual({'Task', 'Service'}, set(template['Resources']))
        self.assertNotIn('ActivationAuthorized', json.dumps(template.get('Metadata', {})))


if __name__ == '__main__':
    unittest.main()
