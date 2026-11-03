"""`05 · Recover DEV`: recover a DEV service in one approved run, with no AWS CLI (DLV-969, user decision 2026-11-02).

Identical copy in the Web, Domain and Gateway/BFF repositories. Every stage is an ordinary plan of `deploy.py`, executed by its
`Deployer` under its own deploy session (`<prefix>-<run>-<n>`), so each stage keeps the deploy checks: provenance, owner on main,
release tag, published digest, plan digest, change-set preview, session identity, verification.

Stages, in order:
  1. park        DesiredCount 0. A stack stuck in UPDATE_IN_PROGRESS / UPDATE_ROLLBACK_IN_PROGRESS is parked directly first
                 (deploy.py busy-stack park), then the park change set records DesiredCount 0.
  2. target      the chosen release with DesiredCount 1; its stack wait is bounded by `healthy_minutes`.
  3. park-again  only if the target did not serve: DesiredCount 0 again.
  4. fallback    only if the target did not serve: the fallback release with DesiredCount 1, same bound.
  5. park-final  only if the fallback did not serve either: DesiredCount 0, and the run fails.

The fallback release is the `fallback_release` input, or else the newest release whose deploy verified healthy on main: a
successful `04 · Deploy DEV` run with DesiredCount 1, or a successful `01 · Full` / `03 · Publish` run whose `Deploy to DEV` job
succeeded, read from that run's `deploy-record-<release>-<run>` artifact name. Without either the plan stops.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
from datetime import timedelta
from pathlib import Path

import deploy
import issue_template as issueTemplate
import owner_approval as owner

SCHEMA_PLAN = 'app-recover-plan/v1'
SCHEMA_STAGE = 'app-recover-stage/v1'
# (stage, release it uses, DesiredCount, deploy session number)
STAGES = (('park', 'target', 0, 1), ('target', 'target', 1, 2), ('park-again', 'target', 0, 3),
          ('fallback', 'fallback', 1, 4), ('park-final', 'fallback', 0, 5))
SERVING = ('target', 'fallback')
DEPLOY_JOBS = {'Deploy to DEV', 'Deploy to DEV (04 · Deploy DEV) / Deploy to DEV'}
RELEASE = r'v\d{4}\.\d{2}\.\d{2}\.\d{2}\.(?:\d{2}|[1-9]\d{2,})'
PARK_SECONDS = 1200
require = owner.require


def healthyMinutes(env):
    value = str(env.get('RECOVER_HEALTHY_MINUTES', '15')).strip() or '15'
    require(re.fullmatch('[0-9]{1,2}', value) and 5 <= int(value) <= 40, 'healthy_minutes must be 5..40')
    return int(value)


def releaseEnv(env, key):
    """The environment one release's plans are checked under: its tag and its frozen release-source record."""
    if key == 'target':
        return dict(env)
    return {**env, 'RELEASE_VERSION': env.get('FALLBACK_RELEASE_VERSION', ''), 'RELEASE_SOURCE_RECORD': env.get('FALLBACK_SOURCE_RECORD', '')}


def lastVerified(repository, get, exclude):
    """Newest release whose deploy on main verified healthy, other than `exclude`; None when nothing qualifies."""
    runs = []
    for workflow in ('deploy-dev.yml', 'release.yml', 'publish.yml'):
        rows = get(repository, 'actions/workflows/' + workflow + '/runs?branch=main&status=success&per_page=20') or {}
        runs += [(run, workflow) for run in rows.get('workflow_runs', [])]
    runs.sort(key=lambda row: str(row[0].get('run_started_at') or row[0].get('created_at') or ''), reverse=True)
    for run, workflow in runs[:30]:
        if workflow == 'deploy-dev.yml' and '| desired 1 |' not in str(run.get('display_title', '')):
            continue  # a park run verified zero tasks, not a serving release
        runId = str(run.get('id'))
        jobs = (get(repository, 'actions/runs/' + runId + '/jobs?per_page=100') or {}).get('jobs', [])
        if not any(job.get('name') in DEPLOY_JOBS and job.get('conclusion') == 'success' for job in jobs):
            continue
        artifacts = (get(repository, 'actions/runs/' + runId + '/artifacts?per_page=100') or {}).get('artifacts', [])
        for artifact in artifacts:
            found = re.fullmatch('deploy-record-(' + RELEASE + ')-' + re.escape(runId), str(artifact.get('name', '')))
            if found and found.group(1) != exclude:
                return {'releaseVersion': found.group(1), 'source': 'last verified deploy, run ' + runId}
    return None


def chooseFallback(env, get=owner.api):
    target = env.get('RELEASE_VERSION', '')
    require(re.fullmatch(RELEASE, target), 'Target release tag vyyyy.mm.dd.hh.nn required')
    explicit = str(env.get('FALLBACK_INPUT', '')).strip()
    if explicit:
        require(re.fullmatch(RELEASE, explicit) and explicit != target, 'fallback_release must be another release tag vyyyy.mm.dd.hh.nn')
        return {'releaseVersion': explicit, 'source': 'input'}
    found = lastVerified(env.get('GITHUB_REPOSITORY', ''), get, target)
    require(found is not None, 'No earlier release verified healthy on main was found; dispatch again with fallback_release set')
    return found


def plan(env, call, root, get=owner.api, now=None):
    minutes = healthyMinutes(env)
    envs = {key: releaseEnv(env, key) for key in ('target', 'fallback')}
    require(envs['fallback']['RELEASE_VERSION'] and envs['fallback']['RELEASE_VERSION'] != envs['target']['RELEASE_VERSION'],
            'A fallback release other than the target is required')
    stages = [deploy.plan({**envs[key], 'DEPLOY_DESIRED_COUNT': str(count)}, call, root, get, now, stage=name, session=session, recovery=True)
              for name, key, count, session in STAGES]
    target, fallback = stages[1], stages[3]
    result = {'schema': SCHEMA_PLAN, 'app': target['app'], 'repository': target['repository'], 'runId': target['runId'],
              'workflowSha': target['workflowSha'], 'stack': target['stack'], 'releaseVersion': target['releaseVersion'],
              'imageTag': target['imageTag'], 'fallbackReleaseVersion': fallback['releaseVersion'], 'fallbackImageTag': fallback['imageTag'],
              'fallbackSource': env.get('FALLBACK_SOURCE') or 'input', 'healthyMinutes': minutes, 'stages': stages,
              'plannedAt': deploy.utc(now).isoformat()}
    result['planDigest'] = deploy.digest({k: v for k, v in result.items() if k != 'planDigest'})
    return result


def stagePlan(planned, name):
    found = [s for s in planned['stages'] if s.get('stage') == name]
    require(len(found) == 1, 'Recovery plan has no single stage ' + name)
    return found[0]


def validate(planned, env, root, get=owner.api):
    require(planned.get('schema') == SCHEMA_PLAN and [s.get('stage') for s in planned.get('stages', [])] == [s[0] for s in STAGES],
            'Recovery plan shape changed')
    require(planned.get('planDigest') == deploy.digest({k: v for k, v in planned.items() if k != 'planDigest'}), 'Recovery plan digest changed')
    identities = {key: deploy.provenance(releaseEnv(env, key), get) for key in ('target', 'fallback')}
    for name, key, count, session in STAGES:
        stage = stagePlan(planned, name)
        deploy.validatePlan(stage, releaseEnv(env, key), root, get, identity=identities[key])
        require(stage['desiredCount'] == count and stage['choices'] == ['deploy'] and stage['sessionName'].endswith('-' + str(session))
                and stage['runId'] == planned['runId'], 'Recovery stage ' + name + ' changed')


def commands(planned):
    return {'deploy': 'APPROVE RECOVER ' + planned['imageTag']}


def window(planned):
    return timedelta(minutes=40)


def headline(planned):
    return {'env': 'DEV', 'action': 'Recover ' + planned['app'], 'subject': planned['imageTag'],
            'change': 'park ' + planned['stack'] + ', deploy ' + planned['releaseVersion'] + ', fall back to ' + planned['fallbackReleaseVersion']
                      + ', park again if neither serves'}


def gist(planned):
    t, stack, minutes = issueTemplate.text, issueTemplate.text(planned['stack']), str(planned['healthyMinutes'])
    return '\n'.join((
        '**Business change:** the DEV ' + t(planned['app']) + ' service `' + stack + '` is recovered in one run: parked, then served by `'
        + t(planned['imageTag']) + '`, or by the fallback `' + t(planned['fallbackImageTag']) + '` if that does not become healthy. No IAM, network, database or secret changes.',
        '',
        '1. **Park:** DesiredCount 0. If the stack is stuck (`UPDATE_IN_PROGRESS` / `UPDATE_ROLLBACK_IN_PROGRESS`) the service is scaled to zero directly, '
        'the run waits for CloudFormation to finish, then the park change set records DesiredCount 0.',
        '2. **Target:** change set with `' + t(planned['imageTag']) + '`, DesiredCount 1; the stack may take at most ' + minutes + ' minutes, then the task must be '
        'HEALTHY with a clean log window and its discovery or target registration, then a short observation.',
        '3. **Only if the target does not serve:** park again, then **fallback** `' + t(planned['fallbackImageTag']) + '` (' + t(planned['fallbackSource'])
        + ') the same way.',
        '4. **Only if the fallback does not serve either:** park at zero tasks and fail the run.',
        '',
        'Each stage assumes its own deploy session (`' + t(stagePlan(planned, 'park')['sessionName'].rsplit('-', 1)[0]) + '-1` … `-5`, at most 60 minutes each). '
        'The run summary lists every stage, release, task definition revision, outcome and time. Approval expires 40 minutes after this issue is created.'))


def body(planned, receipt):
    t = issueTemplate.text
    rows = [('**' + name + '**', '`' + t(s['releaseVersion']) + '` · DesiredCount ' + str(s['desiredCount']) + ' · change set `' + t(s['changeSet'])
             + '` · session `' + t(s['sessionName']) + '`') for name, s in ((s['stage'], s) for s in planned['stages'])]
    resources = [{'action': 'Modify', 'type': 'AWS::ECS::Service', 'consoleName': planned['stack'],
                  'note': 'DesiredCount 0, then 1 with the target release; on failure 0, then 1 with the fallback; on failure 0'},
                 {'action': 'Modify', 'type': 'AWS::CloudFormation::Stack', 'consoleName': planned['stack'],
                  'note': 'one change set per stage (app-' + planned['runId'] + '-<stage>), each previewed against its stage plan before execution'}]
    return issueTemplate.render(
        headline=headline(planned), gist=gist(planned), resources=resources,
        overview='**Recover the DEV service**\n\nAuthorize the whole recovery sequence described above inside this run. Without approval nothing changes in AWS.',
        scope='### Recovery\n\n' + issueTemplate.table(('Stage', 'Release, count, change set, session'), rows),
        validation=[('Target ' + t(planned['releaseVersion']), 'ECR tag `' + t(planned['imageTag']) + '` read back as `' + t(stagePlan(planned, 'target')['image'].rsplit('@', 1)[-1]) + '`'),
                    ('Fallback ' + t(planned['fallbackReleaseVersion']), 'ECR tag `' + t(planned['fallbackImageTag']) + '` read back as `'
                     + t(stagePlan(planned, 'fallback')['image'].rsplit('@', 1)[-1]) + '`; chosen by ' + t(planned['fallbackSource'])),
                    ('Healthy bound', t(str(planned['healthyMinutes'])) + ' minutes of stack wait per serving stage')],
        evidence='Review the plan artifact of this run before approving.',
        risks='A stage that cannot verify does not retry: the run parks the service and fails, and the summary names the last stop reasons. '
              'A rollback stuck in UPDATE_ROLLBACK_FAILED stops the run (break-glass runbook). This is owner approval enforced by workflow code, not independent review.',
        repository=planned['repository'], runId=planned['runId'], owner=owner.OWNER, expires=receipt['expiresAt'], created=receipt.get('createdAt'),
        permissionDeadline='Each stage assumes a deploy session of at most 60 minutes; IAM is not changed.',
        command=commands(planned)['deploy'], payload=planned)


def approve(planned, env, root, get=owner.api, now=lambda: deploy.utc(), sleep=time.sleep):
    validate(planned, env, root, get)
    start = now().replace(microsecond=0)
    receipt = {'schema': deploy.SCHEMA_RECEIPT, 'runId': planned['runId'], 'planDigest': planned['planDigest'],
               'createdAt': start.isoformat(), 'expiresAt': (start + window(planned)).isoformat()}
    preview = body(planned, receipt)
    require(len(preview) < 60000, 'Approval preview too large')
    issue = get(planned['repository'], 'issues', {'title': issueTemplate.title(headline(planned), planned['runId']), 'body': preview})
    receipt['issue'] = issue['number']
    print('https://github.com/' + planned['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(int(window(planned).total_seconds()) // 20):
        # Only the issue and its comments are polled; the plan was validated above and is validated again on an answer.
        selected = deploy.selectedApproval(planned, receipt, get, now(), body, commands, window)
        if selected is not None:
            validate(planned, env, root, get)
            return {**receipt, 'commentId': selected[0], 'mode': selected[1]}
        sleep(20)
    raise TimeoutError('Recovery approval timed out; start a new reviewed run')


def verify(planned, receipt, env, root, get=owner.api, now=None):
    validate(planned, env, root, get)
    selected = deploy.selectedApproval(planned, receipt, get, now, body, commands, window)
    require(selected is not None and selected == (receipt.get('commentId'), receipt.get('mode')), 'Exact live owner approval required')


def lastStops(call, config, limit=3):
    """Why the newest stopped tasks of the service stopped (read-only, best effort)."""
    try:
        arns = (call('ecs', 'list-tasks', '--cluster', config['cluster'], '--service-name', config['stack'], '--desired-status', 'STOPPED') or {}).get('taskArns', [])[:limit]
        if not arns:
            return []
        tasks = (call('ecs', 'describe-tasks', '--cluster', config['cluster'], '--tasks', *arns) or {}).get('tasks', [])
    except RuntimeError as error:
        return [{'error': str(error)[:200]}]
    return [{'taskDefinition': str(t.get('taskDefinitionArn', '')).rsplit('/', 1)[-1], 'stopCode': t.get('stopCode'), 'stoppedReason': t.get('stoppedReason'),
             'health': t.get('healthStatus'), 'exitCode': (t.get('containers') or [{}])[0].get('exitCode')} for t in tasks]


def runStage(name, planned, receipt, root, call, sleep=time.sleep, now=None, fetch=None, log=print):
    """Execute one approved stage. A serving stage that was attempted and did not verify returns outcome `unhealthy`; a park stage,
    or any failure before a change set was attempted, raises."""
    require(receipt.get('planDigest') == planned['planDigest'] and receipt.get('runId') == planned['runId'] and receipt.get('mode') == 'deploy'
            and receipt.get('commentId'), 'Approval receipt does not cover this recovery plan')
    require(planned.get('planDigest') == deploy.digest({k: v for k, v in planned.items() if k != 'planDigest'}), 'Recovery plan digest changed')
    now = now or (lambda: deploy.utc())
    stage = dict(stagePlan(planned, name))
    config, (templateText, _) = deploy.loadConfig(root), deploy.loadTemplate(root)
    require(stage['templateSha256'] == hashlib.sha256(templateText.encode()).hexdigest(), 'Template changed since the plan')
    serving = stage['desiredCount'] == 1
    if not serving:
        state = deploy.stackState(call, stage['stack'])
        status = state['status'] if state else None
        require(status in deploy.BUSY_PARKABLE | deploy.UPDATABLE, 'Stack ' + stage['stack'] + ' is ' + str(status)
                + '; it can be neither parked nor updated (break-glass runbook on the Admin page Support runbooks)')
        busy = status in deploy.BUSY_PARKABLE
        stage.update(busyStackPark=busy, busyStackStatus=status if busy else None)
    deployer = deploy.Deployer(stage, config, templateText, call, sleep=sleep, now=now, fetch=fetch, mode='deploy', log=log,
                               stackSeconds=planned['healthyMinutes'] * 60 if serving else PARK_SECONDS,
                               observeRounds=stage['refreshObservationMinutes'] if serving else None)
    result = {'schema': SCHEMA_STAGE, 'stage': name, 'runId': planned['runId'], 'releaseVersion': stage['releaseVersion'], 'imageTag': stage['imageTag'],
              'desiredCount': stage['desiredCount'], 'busyStackPark': stage['busyStackPark'], 'startedAt': now().isoformat()}
    try:
        deployer.run()
        result['outcome'] = 'healthy' if serving else 'parked'
    except Exception as error:
        attempted = any(step['step'] in ('change-set', 'change-set-empty', 'scaled-to-zero') for step in deployer.steps)
        result.update({'outcome': 'unhealthy' if serving else 'failed', 'error': str(error)[:500], 'stops': lastStops(call, config)})
        if not (serving and attempted):
            result.update({'steps': deployer.steps, 'finishedAt': now().isoformat()})
            raise StageFailed(result) from error
    finally:
        try:
            counts = deploy.serviceCounts(call, config)
        except RuntimeError:
            counts = None
        result['taskDefinition'] = str((counts or {}).get('taskDefinition') or '').rsplit('/', 1)[-1] or None
    result.update({'steps': deployer.steps, 'finishedAt': now().isoformat()})
    return result


class StageFailed(Exception):
    def __init__(self, result):
        super().__init__(result.get('error', 'stage failed'))
        self.result = result


def summary(planned, directory):
    """Markdown table of every stage that ran, and whether a release now serves."""
    rows, served = [], None
    for name, _, _, _ in STAGES:
        path = Path(directory) / (name + '.json')
        if not path.is_file():
            rows.append((name, '—', '—', 'not run', '—', '—', '—'))
            continue
        stage = deploy.readJson(path)
        note = stage.get('error', '')
        if stage.get('stops'):
            note += (' · ' if note else '') + '; '.join(str(s.get('taskDefinition')) + ': ' + str(s.get('stoppedReason')) + ' (health ' + str(s.get('health'))
                                                       + ', exit ' + str(s.get('exitCode')) + ')' for s in stage['stops'] if 'error' not in s)
        rows.append((name, stage['releaseVersion'], str(stage['desiredCount']), stage['outcome'], stage.get('taskDefinition') or '—',
                     stage.get('startedAt', '—'), (stage.get('finishedAt', '—')) + (' · ' + note if note else '')))
        if name in SERVING and stage['outcome'] == 'healthy':
            served = stage['releaseVersion']
    text = '## 05 · Recover DEV: ' + planned['stack'] + '\n\n' + issueTemplate.table(
        ('Stage', 'Release', 'DesiredCount', 'Outcome', 'Task definition', 'Started (UTC)', 'Finished (UTC) · note'), rows)
    text += '\n\n' + ('**Serving:** `' + served + '`.' if served else '**Nothing serves:** the service is parked or failed; read the notes above.')
    return text, served


def main(argv=None, env=None, call=None, get=owner.api, sleep=time.sleep, fetch=None, now=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('mode', choices=['fallback', 'plan', 'approve', 'verify', 'stage', 'summary'])
    parser.add_argument('--root', default='.', help='repository root holding deployment/dev')
    parser.add_argument('--plan', help='recovery plan written by plan')
    parser.add_argument('--receipt', help='approval receipt (approve writes it; verify and stage read it)')
    parser.add_argument('--output', help='plan file to write (must not exist)')
    parser.add_argument('--stage', choices=[s[0] for s in STAGES])
    parser.add_argument('--dir', help='directory of stage results')
    args = parser.parse_args(argv)
    if args.mode == 'fallback':
        chosen = chooseFallback(env, get)
        deploy.output(env, fallback_release=chosen['releaseVersion'], fallback_source=chosen['source'])
        print(json.dumps(chosen, sort_keys=True))
        return chosen
    call = call or deploy.client()
    if args.mode == 'plan':
        require(bool(args.output), '--output required')
        result = plan(env, call, args.root, get)
        deploy.write(args.output, result)
        deploy.output(env, plan_digest=result['planDigest'], fallback_release=result['fallbackReleaseVersion'])
        print(json.dumps({k: result[k] for k in ('releaseVersion', 'fallbackReleaseVersion', 'fallbackSource', 'healthyMinutes', 'planDigest')}, sort_keys=True))
        return result
    require(bool(args.plan), '--plan required')
    planned = deploy.readJson(args.plan, 1024 * 1024)
    if args.mode == 'summary':
        require(bool(args.dir), '--dir required')
        text, served = summary(planned, args.dir)
        if env.get('GITHUB_STEP_SUMMARY'):
            with open(env['GITHUB_STEP_SUMMARY'], 'a') as stream:
                stream.write(text + '\n')
        print(text)
        require(served is not None, 'Recovery ended without a serving release')
        return served
    require(bool(args.receipt), '--receipt required')
    if args.mode == 'approve':
        receipt = approve(planned, env, args.root, get, sleep=sleep)
        deploy.write(args.receipt, receipt)
        return receipt
    receipt = deploy.readJson(args.receipt)
    if args.mode == 'verify':
        verify(planned, receipt, env, args.root, get, now)
        print('recovery approval verified for plan ' + planned['planDigest'][:16])
        return receipt
    require(bool(args.stage and args.dir), '--stage and --dir required')
    target = Path(args.dir) / (args.stage + '.json')
    try:
        result = runStage(args.stage, planned, receipt, args.root, call, sleep=sleep, fetch=fetch)
    except StageFailed as failed:
        deploy.write(target, failed.result)
        raise
    deploy.write(target, result)
    if args.stage in SERVING:
        deploy.output(env, healthy='true' if result['outcome'] == 'healthy' else 'false')
    print(json.dumps({k: result.get(k) for k in ('stage', 'releaseVersion', 'outcome', 'taskDefinition', 'error')}, sort_keys=True))
    return result


if __name__ == '__main__':
    try:
        main()
    except Exception as error:  # one readable line in the job log; the stage file holds the details
        print('recover: ' + str(error)[:500], file=sys.stderr)
        raise
