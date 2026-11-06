"""`06 · Restart DEV` (user decision 2026-11-05): one owner approval, one forced rollout of the revision this application is serving,
then the new task is verified like a deploy verifies its task. No image, stack or configuration change; nothing created.
Identical copy in the Web, Domain and Gateway/BFF repositories; the application differs only through `deployment/dev/deploy-config.json`.

Same rules as the Content release's Domain restart and the deploys: refused in the overnight blackout, and refused while the service
is busy (a rollout in progress, or a rollout younger than a deploy's 15-minute watch, which holds the service to the task it verified;
Domain deploy run 42106678466 failed on such a collision). Sessions are `<app>-deploy-restart-plan-<run>-1` (reads) and
`<app>-deploy-restart-<run>-1` (the one `ecs update-service --force-new-deployment`), the `<app>-deploy-*` sessions the deploy
role already admits and the installed policies limit to this application's stack and service.
"""
import argparse
import json
import re
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import deploy
import issue_template as issueTemplate
import owner_approval as owner

WORKFLOW = '.github/workflows/restart-dev.yml'
SCHEMA_PLAN = 'app-restart-plan/v1'
SCHEMA_RECEIPT = 'app-restart-approval/v1'
SCHEMA_RESULT = 'app-restart-result/v1'
WATCH_SECONDS = 15 * 60  # a deploy holds the service to the task it verified this long after the rollout completed
ROLLOUT_SECONDS = 600
WAIT = ('APPROVAL_WAIT_MINUTES', 10, 2, 15)
VALID = ('APPROVAL_VALID_MINUTES', 15, 5, 60)
require = owner.require


def minutes(env, setting):
    name, default, low, high = setting
    raw = str(env.get(name, '') or '').strip()
    if not raw:
        return default
    require(re.fullmatch('[0-9]{1,3}', raw) is not None and low <= int(raw) <= high, name + ' must be a whole number of minutes between %d and %d' % (low, high))
    return int(raw)


def parseTime(value):
    """ECS timestamps as the CLI prints them (ISO 8601) or as epoch seconds."""
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value), timezone.utc)
    return datetime.fromisoformat(str(value).replace('Z', '+00:00')).astimezone(timezone.utc)


def provenance(env, get=owner.api):
    """A fresh owner dispatch of this workflow on main, first attempt, in the application's own repository."""
    repository = env.get('GITHUB_REPOSITORY')
    require(repository in owner.PUBLISHERS, 'Unknown application repository')
    app = owner.PUBLISHERS[repository][1]
    expected = {'GITHUB_ACTOR': owner.OWNER, 'GITHUB_TRIGGERING_ACTOR': owner.OWNER, 'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '1',
                'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_WORKFLOW_REF': repository + '/' + WORKFLOW + '@refs/heads/main'}
    require(all(env.get(k) == v for k, v in expected.items()), 'Fresh owner dispatch of 06 · Restart DEV from main required')
    sha, runId = env.get('GITHUB_SHA', ''), env.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) is not None and re.fullmatch('[1-9][0-9]*', runId) is not None, 'Exact source/run required')
    metadata = get(repository, '')
    require(metadata.get('full_name') == repository and metadata.get('owner', {}).get('login') == owner.OWNER
            and str(metadata.get('id')) == env.get('GITHUB_REPOSITORY_ID'), 'Repository identity changed')
    require(get(repository, 'commits/main').get('sha') == sha, 'Main moved since this run started')
    run = get(repository, 'actions/runs/' + runId)
    fields = {'id': int(runId), 'path': WORKFLOW, 'head_sha': sha, 'head_branch': 'main', 'event': 'workflow_dispatch', 'run_attempt': 1, 'status': 'in_progress'}
    require(all(run.get(k) == v for k, v in fields.items())
            and all(run.get(k, {}).get('login') == owner.OWNER for k in ('actor', 'triggering_actor')), 'Run provenance changed')
    return {'app': app, 'repository': repository, 'workflowSha': sha, 'runId': runId}


def primaryDeployment(call, config):
    rows = call('ecs', 'describe-services', '--cluster', config['cluster'], '--services', config['stack'])
    service = next((s for s in rows.get('services', []) if s.get('status') == 'ACTIVE'), None)
    require(service is not None, 'Service ' + config['stack'] + ' is not active')
    deployments = service.get('deployments', [])
    primary = next((d for d in deployments if d.get('status') == 'PRIMARY'), {})
    return {'desired': service.get('desiredCount', 0), 'running': service.get('runningCount', 0), 'deployments': len(deployments),
            'rolloutState': primary.get('rolloutState'), 'taskDefinition': primary.get('taskDefinition'), 'updatedAt': primary.get('updatedAt'), 'id': primary.get('id')}


def requireFree(state, now):
    """Serving one task on one settled rollout that is older than a deploy's watch; otherwise the restart would collide with a deploy."""
    require(state['desired'] == 1 and state['running'] == 1 and state['deployments'] == 1 and state['rolloutState'] == 'COMPLETED',
            'Service is busy: a rollout is in progress or it is not serving one task (desired %s, running %s, rollout %s); restart later'
            % (state['desired'], state['running'], state['rolloutState']))
    age = None if state.get('updatedAt') is None else max(0.0, (now - parseTime(state['updatedAt'])).total_seconds())
    require(age is None or age >= WATCH_SECONDS, 'Service is busy: its last rollout completed %d s ago and a deploy may be watching it until %s; restart after that'
            % (age or 0, (now + timedelta(seconds=WATCH_SECONDS - (age or 0))).strftime('%H:%M UTC')))
    return age


def runningTask(call, config):
    arns = call('ecs', 'list-tasks', '--cluster', config['cluster'], '--service-name', config['stack'], '--desired-status', 'RUNNING').get('taskArns', [])
    require(len(arns) == 1, 'Exactly one running task expected')
    task = call('ecs', 'describe-tasks', '--cluster', config['cluster'], '--tasks', arns[0])['tasks'][0]
    container = next((c for c in task.get('containers', []) if c.get('name') == config['service']), None)
    require(container is not None, 'Container ' + config['service'] + ' not found on the running task')
    digest = str(container.get('imageDigest') or '')
    require(re.fullmatch('sha256:[a-f0-9]{64}', digest) is not None, 'Running container reports no image digest')
    return arns[0], task, digest


def plan(env, call, root, get=owner.api, now=None):
    identity = provenance(env, get)
    config = deploy.loadConfig(root)
    require(identity['app'] == config['app'], 'Configuration belongs to another application')
    now = deploy.utc(now)
    deploy.requireOutsideBlackout(1, now, 'at planning')
    state = primaryDeployment(call, config)
    age = requireFree(state, now)
    arn, task, digest = runningTask(call, config)
    require(task.get('taskDefinitionArn') == state['taskDefinition'], "The running task is not on the service's current revision")
    result = {'schema': SCHEMA_PLAN, **identity, 'stack': config['stack'], 'cluster': config['cluster'], 'service': config['service'],
              'account': config['account'], 'region': config['region'], 'deployRoleArn': config['deployRoleArn'],
              'sessionName': config['sessionPrefix'] + '-restart-' + identity['runId'] + '-1', 'revision': state['taskDefinition'],
              'imageDigest': digest, 'task': arn, 'rolloutAgeSeconds': None if age is None else int(age),
              'observationMinutes': config['refreshObservationMinutes'], 'logGroup': config['logGroup'], 'logFailurePatterns': config['logFailurePatterns'],
              'configSha256': deploy.hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest(), 'plannedAt': now.isoformat()}
    result['planDigest'] = owner.digest({k: v for k, v in result.items() if k != 'planDigest'})
    return result


def validatePlan(planned, env, root, get=owner.api):
    identity = provenance(env, get)
    require(planned.get('schema') == SCHEMA_PLAN and all(planned.get(k) == v for k, v in identity.items()), 'Restart plan identity changed')
    require(planned.get('planDigest') == owner.digest({k: v for k, v in planned.items() if k != 'planDigest'}), 'Restart plan digest changed')
    require(planned.get('configSha256') == deploy.hashlib.sha256(json.dumps(deploy.loadConfig(root), sort_keys=True).encode()).hexdigest(), 'Configuration changed since the plan')


def revisionNumber(planned):
    return planned['revision'].rsplit(':', 1)[-1]


def command(planned):
    """The issue template admits `APPROVE <WORDS> <plan digest>`; the digest binds the exact revision, task and image of the plan."""
    return 'APPROVE RESTART ' + planned['planDigest']


def headline(planned):
    return {'env': 'DEV', 'action': 'Restart ' + planned['app'], 'subject': 'revision ' + revisionNumber(planned),
            'change': 'forced rollout of the running revision; no image, stack or configuration change'}


def body(planned, receipt):
    t = issueTemplate.text
    age = planned.get('rolloutAgeSeconds')
    resources = [{'action': 'Restart (no change)', 'type': 'AWS::ECS::Service', 'consoleName': planned['stack'],
                  'note': 'force new deployment of revision ' + revisionNumber(planned) + '; same task definition and image; nothing created, updated or replaced'}]
    validation = [('Running task', '`' + t(planned['task'].rsplit('/', 1)[-1]) + '` on `' + t(planned['revision'].rsplit('/', 1)[-1]) + '`'),
                  ('Image digest', '`' + t(planned['imageDigest'][:19]) + '…`'),
                  ('Last rollout', 'completed ' + str(age) + ' s before the plan' if age is not None else 'age not reported by ECS'),
                  ('Approval window', 'Answer within ' + str(receipt['waitMinutes']) + ' minutes of this issue opening; the answer stays valid for '
                   + str(receipt['validMinutes']) + ' minutes from your comment, rechecked before credentials'),
                  ('Execution', 'Recheck the blackout and that the service is still free; one `aws ecs update-service --force-new-deployment` (never retried); '
                   'wait up to ' + str(ROLLOUT_SECONDS // 60) + ' minutes for the rollout; verify the new task (HEALTHY, same image, clean log); watch it for '
                   + str(planned['observationMinutes']) + ' minutes')]
    overview = ('## Decision summary: DEV ' + planned['app'] + ' restart\n\nApproving forces a new deployment of `' + t(planned['stack'])
                + '` with no change: the same revision and image serve again from a fresh task. Use it to make a running application pick up '
                'what it reads at start (a content release, a rotated secret). Nothing is deleted; if the new task fails its health checks ECS keeps the old one.\n')
    scope = ('**Application:** `' + t(planned['app']) + '` · **Service:** `' + t(planned['stack']) + '` · **Revision:** `' + t(revisionNumber(planned)) + '`\n\n'
             + issueTemplate.table(('Step', 'Detail'), [('Approve', 'owner comment below'),
                                                        ('Restart', '`aws ecs update-service --force-new-deployment` on the service above, once'),
                                                        ('Verify', 'rollout COMPLETED on the same revision; the new task HEALTHY with the same image and a clean log; a short watch')]).rstrip())
    return issueTemplate.render(headline=headline(planned), resources=resources, validation=validation, overview=overview, scope=scope,
                                evidence='The plan of this run (restart-plan artifact) names the task, revision and image digest it binds to.',
                                risks='Rolling replacement only; ECS keeps the old task if the new one fails health checks. No deletes, no rollback, no change.',
                                repository=planned['repository'], runId=receipt['runId'], owner=owner.OWNER, expires=receipt['expiresAt'],
                                permissionDeadline='Routine authority until revoked; this approval still expires.', command=command(planned), payload=planned)


def decision(planned, receipt, env, get, now):
    """(comment id, comment time) of the exact owner answer, or None while there is none; raises when the approval cannot stand."""
    provenance(env, get)
    repository = planned['repository']
    issue = get(repository, 'issues/' + str(receipt['issue']))
    require(not issue.get('pull_request') and issue.get('state') == 'open' and issue.get('user', {}).get('login') == 'github-actions[bot]'
            and issue.get('body') == body(planned, receipt), 'Approval issue changed or closed')
    comments = get(repository, 'issues/' + str(receipt['issue']) + '/comments?per_page=100')
    require(len(comments) < 100, 'Comment limit exceeded')
    created, expires = parseTime(receipt['createdAt']), parseTime(receipt['expiresAt'])
    selected = None
    for comment in comments:
        if comment['user']['login'] != owner.OWNER or comment['created_at'] != comment['updated_at']:
            continue
        when = parseTime(comment['created_at'])
        if not created <= when <= expires:
            continue
        stripped = comment['body'].strip()
        require(stripped != 'REJECT', 'Owner rejected the restart')
        if stripped == command(planned):
            selected = (comment['id'], comment['created_at'])
    return selected


def approve(planned, env, root, get=owner.api, now=lambda: deploy.utc(), sleep=time.sleep):
    validatePlan(planned, env, root, get)
    wait, valid = minutes(env, WAIT), minutes(env, VALID)
    start = now().replace(microsecond=0)
    receipt = {'schema': SCHEMA_RECEIPT, 'runId': planned['runId'], 'planDigest': planned['planDigest'], 'createdAt': start.isoformat(),
               'expiresAt': (start + timedelta(minutes=wait)).isoformat(), 'waitMinutes': wait, 'validMinutes': valid}
    issue = get(planned['repository'], 'issues', {'title': issueTemplate.title(headline(planned), planned['runId']), 'body': body(planned, receipt)})
    receipt['issue'] = issue['number']
    print('https://github.com/' + planned['repository'] + '/issues/' + str(receipt['issue']), flush=True)
    for _ in range(wait * 60 // 20):
        selected = decision(planned, receipt, env, get, now())
        if selected is not None:
            return {**receipt, 'commentId': selected[0], 'approvedAt': selected[1]}
        sleep(20)
    raise TimeoutError('Restart approval timed out; start a new run')


def verify(planned, receipt, env, root, get=owner.api, now=None):
    now = deploy.utc(now)
    validatePlan(planned, env, root, get)
    require(receipt.get('schema') == SCHEMA_RECEIPT and receipt.get('runId') == env.get('GITHUB_RUN_ID') and receipt.get('planDigest') == planned['planDigest'],
            'Receipt belongs to another plan')
    require((minutes(env, WAIT), minutes(env, VALID)) == (receipt.get('waitMinutes'), receipt.get('validMinutes')), 'Approval windows changed since the issue opened')
    require(receipt.get('approvedAt') and now < parseTime(receipt['approvedAt']) + timedelta(minutes=receipt['validMinutes']), 'Approval expired')
    selected = decision(planned, receipt, env, get, now)
    require(selected is not None and selected == (receipt.get('commentId'), receipt.get('approvedAt')), 'Exact live owner approval required')


class Restarter:
    def __init__(self, planned, config, call, sleep=time.sleep, now=None, log=print):
        self.plan, self.config, self.call, self.sleep, self.now, self.log = planned, config, call, sleep, now or (lambda: deploy.utc()), log
        self.steps = []
        self.outcome = None

    def note(self, step, **detail):
        self.steps.append({'step': step, 'at': self.now().isoformat(), **detail})
        self.log(step + ': ' + json.dumps(detail, sort_keys=True, default=str)[:300], flush=True)

    def waitFor(self, label, check, seconds, every=15):
        for _ in range(max(1, seconds // every)):
            state = check()
            if state is not None:
                return state
            self.sleep(every)
        raise ValueError(label + ' did not complete within ' + str(seconds) + ' seconds')

    def settled(self):
        state = primaryDeployment(self.call, self.config)
        require(state['taskDefinition'] == self.plan['revision'], 'Another deployment replaced the revision during the restart')
        return state if state['desired'] == 1 and state['running'] == 1 and state['deployments'] == 1 and state['rolloutState'] == 'COMPLETED' else None

    def healthyTask(self, expected):
        cfg = self.config
        arns = self.call('ecs', 'list-tasks', '--cluster', cfg['cluster'], '--service-name', self.plan['stack'], '--desired-status', 'RUNNING').get('taskArns', [])
        require(arns == [expected], 'The restarted task ' + expected.rsplit('/', 1)[-1] + ' was replaced or stopped')
        task = self.call('ecs', 'describe-tasks', '--cluster', cfg['cluster'], '--tasks', expected)['tasks'][0]
        require(task.get('lastStatus') == 'RUNNING' and task.get('healthStatus') == 'HEALTHY', 'Task became ' + str(task.get('lastStatus')) + '/' + str(task.get('healthStatus')))
        return task

    def run(self):
        p, cfg = self.plan, self.config
        outcome = {'schema': SCHEMA_RESULT, 'runId': p['runId'], 'planDigest': p['planDigest'], 'app': p['app'], 'stack': p['stack'], 'revision': p['revision'], 'imageDigest': p['imageDigest']}
        try:
            caller = self.call('sts', 'get-caller-identity')
            expected = 'arn:aws:sts::' + p['account'] + ':assumed-role/' + p['deployRoleArn'].rsplit('/', 1)[-1] + '/' + p['sessionName']
            require(caller.get('Arn') == expected and caller.get('Account') == p['account'], 'Restart session identity mismatch')
            self.note('identity', arn=expected)
            deploy.requireOutsideBlackout(1, self.now(), 'at execution')
            state = primaryDeployment(self.call, cfg)
            require(state['taskDefinition'] == p['revision'], 'The service moved to another revision since the plan; plan again')
            requireFree(state, self.now())
            self.call('ecs', 'update-service', '--cluster', cfg['cluster'], '--service', p['stack'], '--force-new-deployment')
            self.note('restarted', stack=p['stack'], revision=revisionNumber(p))
            counts = self.waitFor('rollout of ' + p['stack'], self.settled, ROLLOUT_SECONDS)
            arn, task, digest = runningTask(self.call, cfg)
            require(arn != p['task'], 'The restart did not replace the task')
            require(digest == p['imageDigest'], 'The new task does not carry the planned image')
            task = self.waitFor('task health', lambda: (lambda t: t if t.get('healthStatus') == 'HEALTHY' else None)(
                self.call('ecs', 'describe-tasks', '--cluster', cfg['cluster'], '--tasks', arn)['tasks'][0]), 180)
            startedAt = owner.timestamp(task['startedAt']) if task.get('startedAt') else self.now() - timedelta(minutes=10)
            pattern = ' '.join('?"' + word + '"' for word in cfg['logFailurePatterns'])
            events = self.call('logs', 'filter-log-events', '--log-group-name', cfg['logGroup'], '--log-stream-name-prefix',
                               cfg['service'] + '/' + cfg['service'] + '/' + arn.rsplit('/', 1)[-1], '--start-time', str(int(startedAt.timestamp() * 1000)),
                               '--filter-pattern', pattern).get('events', [])
            require(not events, 'Log window contains failure markers: ' + (events[0].get('message', '')[:200] if events else ''))
            self.note('verified', task=arn, previousTask=p['task'], counts={k: counts[k] for k in ('desired', 'running', 'rolloutState')})
            rounds = p['observationMinutes']
            for minute in range(rounds):
                state = primaryDeployment(self.call, cfg)
                require(state['desired'] == 1 and state['running'] == 1 and state['rolloutState'] == 'COMPLETED', 'Service stopped serving during the watch')
                self.healthyTask(arn)
                self.log('watch ' + str(minute + 1) + '/' + str(rounds) + ': running 1, task healthy', flush=True)
                if minute < rounds - 1:
                    self.sleep(60)
            self.note('observed', minutes=rounds)
            outcome['outcome'] = 'success'
        except Exception as error:
            outcome.update({'outcome': 'failure', 'error': str(error)[:500]})
            raise
        finally:
            outcome.update({'steps': self.steps, 'finishedAt': self.now().isoformat()})
            self.outcome = outcome
        return outcome


def main(argv=None, env=None, call=None, get=owner.api, sleep=time.sleep):
    env = deploy.os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'approve', 'verify', 'execute'])
    parser.add_argument('--root', default='.', help='repository root holding deployment/dev')
    parser.add_argument('--plan', help='plan.json written by plan')
    parser.add_argument('--receipt', help='approval receipt (approve writes it, verify and execute read it)')
    parser.add_argument('--output', help='plan or result file to write (must not exist)')
    args = parser.parse_args(argv)
    call = call or deploy.client()
    if args.mode == 'plan':
        require(bool(args.output), '--output required')
        result = plan(env, call, args.root, get)
        deploy.write(args.output, result)
        deploy.output(env, plan_digest=result['planDigest'])
        print(json.dumps({k: result[k] for k in ('app', 'stack', 'revision', 'imageDigest', 'rolloutAgeSeconds', 'planDigest')}, sort_keys=True))
        return result
    require(bool(args.plan and args.receipt), '--plan and --receipt required')
    planned = deploy.readJson(args.plan)
    if args.mode == 'approve':
        receipt = approve(planned, env, args.root, get, sleep=sleep)
        deploy.write(args.receipt, receipt)
        return receipt
    receipt = deploy.readJson(args.receipt)
    verify(planned, receipt, env, args.root, get)
    if args.mode == 'verify':
        print('approval verified for restart plan ' + planned['planDigest'][:16])
        return receipt
    require(bool(args.output), '--output required')
    restarter = Restarter(planned, deploy.loadConfig(args.root), call, sleep=sleep)
    try:
        return restarter.run()
    finally:
        try:
            deploy.write(args.output, restarter.outcome)
        except OSError as error:
            print('result not written: ' + str(error)[:200])
        print(json.dumps({k: restarter.outcome.get(k) for k in ('outcome', 'revision', 'error')}, sort_keys=True))


if __name__ == '__main__':
    main()
