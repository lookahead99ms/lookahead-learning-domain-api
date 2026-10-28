#!/usr/bin/env python3
"""Close an approval issue once the stage that consumed it has finished, with the outcome as the last comment.

Approval issues must stay open while a stage is deciding (the rechecks require it). Once the stage has ended,
success or not, the approval is consumed: a later run always opens a new issue. Leaving the old issue open
suggests something can still be approved, so the workflow closes it as its last step (`if: always()`).

  issue_close.py --stage "Publish to ECR" --outcome success --detail "Published; digest read back." \\
                 --receipt "$RUNNER_TEMP/owner-approval.json"
  issue_close.py --stage "Approve ECR publish" --outcome failure --detail "Rejected, expired or failed." \\
                 --match "Publish image"

`--receipt` reads the issue number from the stage's receipt (`issue`). When the approval never produced a
receipt (REJECT, timeout, failure before the wait), `--match` finds the one open issue of this run whose title
contains the text and whose body links this run. Only issues opened by github-actions[bot] and still open are
touched; comments are never edited; nothing else is changed. Identical copy in Infra, Content, Domain, Web and
Gateway/BFF. Never calls AWS.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

BOT = 'github-actions[bot]'
OUTCOMES = {'success': ('completed', 'finished successfully'), 'failure': ('not_planned', 'failed'),
            'cancelled': ('not_planned', 'was cancelled'), 'skipped': ('not_planned', 'was skipped')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def gh(args, payload=None):
    result = subprocess.run(['gh', 'api', *args], input=json.dumps(payload) if payload is not None else None, capture_output=True, text=True, timeout=60)
    require(result.returncode == 0, 'GitHub request failed; the issue was left as it is')
    require(len(result.stdout) <= 4 * 1024 * 1024, 'GitHub response exceeds bound')
    return json.loads(result.stdout) if result.stdout.strip() else {}


def fromReceipt(path):
    path = Path(path)
    if not path.is_file() or path.stat().st_size > 64 * 1024:
        return None
    number = json.loads(path.read_text()).get('issue')
    return number if type(number) is int and number > 0 else None


def fromMatch(repository, runId, match, call=gh):
    rows = call(['repos/' + repository + '/issues?state=open&creator=' + BOT.replace('[', '%5B').replace(']', '%5D') + '&per_page=50'])
    require(isinstance(rows, list), 'Issue inventory missing')
    found = [r for r in rows if match in str(r.get('title', '')) and '/actions/runs/' + str(runId) in str(r.get('body', ''))]
    require(len(found) <= 1, 'Several open issues match; closing none')
    return found[0]['number'] if found else None


def close(repository, runId, serverUrl, number, stage, outcome, detail, call=gh):
    """Comment the outcome and close; returns what was done, or None when there is nothing to close."""
    require(outcome in OUTCOMES, 'Unknown outcome')
    issue = call(['repos/' + repository + '/issues/' + str(number)])
    if issue.get('state') != 'open' or issue.get('user', {}).get('login') != BOT or issue.get('pull_request'):
        return None
    reason, words = OUTCOMES[outcome]
    body = ('**' + stage + ' ' + words + '.** ' + detail.strip() + '\n\n'
            + 'This approval is consumed by [run ' + str(runId) + '](' + serverUrl + '/' + repository + '/actions/runs/' + str(runId)
            + '); a later run opens a new issue. Closed by the workflow.')
    call(['repos/' + repository + '/issues/' + str(number) + '/comments', '--method', 'POST', '--input', '-'], {'body': body})
    call(['repos/' + repository + '/issues/' + str(number), '--method', 'PATCH', '--input', '-'], {'state': 'closed', 'state_reason': reason})
    return {'issue': number, 'state': 'closed', 'state_reason': reason, 'outcome': outcome}


def main(argv=None, env=None, call=gh):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', required=True)
    parser.add_argument('--outcome', required=True, choices=sorted(OUTCOMES))
    parser.add_argument('--detail', required=True)
    parser.add_argument('--receipt', help='receipt JSON holding the issue number under `issue`')
    parser.add_argument('--match', help='title text of this run\'s open approval issue when no receipt exists')
    args = parser.parse_args(argv)
    repository, runId = env.get('GITHUB_REPOSITORY', ''), env.get('GITHUB_RUN_ID', '')
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) and re.fullmatch('[1-9][0-9]*', runId), 'Exact repository and run required')
    number = fromReceipt(args.receipt) if args.receipt else None
    if number is None and args.match:
        number = fromMatch(repository, runId, args.match, call)
    if number is None:
        print('No approval issue to close for this stage.', flush=True)
        return None
    result = close(repository, runId, env.get('GITHUB_SERVER_URL', 'https://github.com'), number, args.stage, args.outcome, args.detail, call)
    print(json.dumps(result) if result else 'Issue #' + str(number) + ' is already closed or not an approval issue; left as it is.', flush=True)
    return result


if __name__ == '__main__':
    main()
