#!/usr/bin/env python3
"""Fetch the Gateway's consumer contract at the Gateway's newest calendar release tag (DLV-968 decision T13).

The Gateway/BFF owns `src/test/resources/contracts/domain-api-v1.json`: every Domain call it makes and the Web paths it
forwards. Domain's `DomainProviderContractTest` and Web's `scripts/release/check-gateway-contract.mjs` read the copy this
script writes, so each provider is checked against the Gateway as released, never against a typed commit.

Selection is the one `candidate.infraTooling` uses for Infra: lightweight `v*` tags of the Gateway repository through
`gh api repos/<gateway>/git/matching-refs/tags/v --paginate`, newest by calendar (`vYYYY.MM.DD.HH.NN`), then the file at
that tag's commit through the contents API.

  python3 tools/release/consumer_contract.py fetch --output src/test/resources/contracts/domain-api-v1.json [--receipt <json>]

Without a token (`CROSS_REPO_READ_TOKEN`, or `GH_TOKEN` as the workflow step sets it) the command prints why it skipped,
writes nothing and exits 0: local runs keep the committed copy. With a token every failure is an error.
Identical copy in the Domain and Web repositories. Never calls AWS.
"""
import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess

GATEWAY = 'lookahead99ms/lookahead-learning-gateway-bff'
CONTRACT = 'src/test/resources/contracts/domain-api-v1.json'
SCHEMA = 'consumer-contract/v1'
CONTRACT_SCHEMA = 'gateway-domain-contract/v1'
PATTERN = r'v\d{4}\.\d{2}\.\d{2}\.\d{2}\.(?:\d{2}|[1-9]\d{2,})'
MAX_BODY = 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def token(env):
    return env.get('CROSS_REPO_READ_TOKEN') or env.get('GH_TOKEN') or ''


class Missing(Exception):
    """The contract file does not exist at the selected commit (HTTP 404): the Gateway tag predates the contract."""


def gh(args, env=None):
    result = subprocess.run(['gh', *args], capture_output=True, text=True, timeout=60, env=env)
    if result.returncode != 0 and 'HTTP 404' in (result.stderr or ''):
        raise Missing(' '.join(args[:2]))
    require(result.returncode == 0, 'GitHub request failed; nothing was written')
    require(len(result.stdout) <= 4 * MAX_BODY, 'GitHub response exceeds bound')
    return result.stdout


def newestTag(rows):
    """Lightweight calendar tags only (an annotated tag names a tag object, not the commit); newest by date, then sequence."""
    tags = {}
    for line in rows.splitlines():
        parts = line.split('\t')
        if len(parts) == 3 and parts[0].startswith('refs/tags/') and parts[1] == 'commit':
            name = parts[0][len('refs/tags/'):]
            if re.fullmatch(PATTERN, name, re.ASCII) and re.fullmatch('[a-f0-9]{40}', parts[2]):
                tags[name] = parts[2]
    require(bool(tags), GATEWAY + ' has no calendar release tag; the merge-driven Gateway pipeline cuts one per main merge')
    newest = max(tags, key=lambda tag: (tag[:14], int(tag.rsplit('.', 1)[1])))
    return newest, tags[newest]


def select(env, run=None, now=None):
    """Newest Gateway calendar tag and the commit it names."""
    run = run or gh
    secret = token(env)
    require(bool(secret), 'Secret CROSS_REPO_READ_TOKEN is missing: the Gateway is private and its contract is read at its release tag')
    rows = run(['api', 'repos/' + GATEWAY + '/git/matching-refs/tags/v', '--paginate', '-q', '.[] | [.ref, .object.type, .object.sha] | @tsv'],
               env={**os.environ, 'GH_TOKEN': secret})
    newest, sha = newestTag(rows)
    created = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
    return {'schema': SCHEMA, 'repository': GATEWAY, 'path': CONTRACT, 'releaseVersion': newest, 'sourceSha': sha, 'selectedAt': created,
            'selection': 'newest calendar tag of ' + GATEWAY + ' at selection time; contract read at that commit'}


def download(sha, secret, run=None):
    """The raw contract at `sha`, or None when that commit predates the contract (publishing may be paused, so the newest
    Gateway tag can lag the Gateway main branch; the committed copy then stays in force)."""
    run = run or gh
    try:
        return run(['api', 'repos/' + GATEWAY + '/contents/' + CONTRACT + '?ref=' + sha, '-H', 'Accept: application/vnd.github.raw+json'],
                   env={**os.environ, 'GH_TOKEN': secret})
    except Missing:
        return None


def validate(text):
    require(len(text) <= MAX_BODY, 'Contract exceeds bound')
    try:
        contract = json.loads(text)
    except ValueError:
        raise ValueError('Contract is not JSON')
    require(isinstance(contract, dict) and contract.get('schema') == CONTRACT_SCHEMA, 'Contract schema is not ' + CONTRACT_SCHEMA)
    require(isinstance(contract.get('calls'), list) and bool(contract['calls']), 'Contract lists no calls')
    require(isinstance(contract.get('webPaths'), list) and all(isinstance(p, str) and p.startswith('/') for p in contract['webPaths']),
            'Contract webPaths must be absolute paths')
    return contract


def fetch(env, output, run=None, get=None, now=None):
    """Write the Gateway's contract at its newest tag to `output` (replacing a committed copy) and return the receipt."""
    chosen = select(env, run, now)
    text = (get or download)(chosen['sourceSha'], token(env), run)
    if text is None:
        print('Consumer contract not fetched: absent at Gateway ' + chosen['releaseVersion'] + ' (' + chosen['sourceSha'][:12] + '); the committed copy at ' + str(output) + ' is used', flush=True)
        return {**chosen, 'output': str(output), 'skipped': 'contract absent at the newest Gateway tag'}
    contract = validate(text)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text if text.endswith('\n') else text + '\n')
    return {**chosen, 'output': str(output), 'calls': len(contract['calls']), 'webPaths': len(contract['webPaths'])}


def main(argv=None, env=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('mode', choices=['fetch'])
    parser.add_argument('--output', required=True, help='where the contract is written; an existing committed copy is replaced')
    parser.add_argument('--receipt', help='optional selection receipt to write (must not exist)')
    args = parser.parse_args(argv)
    if not token(env):
        print('Consumer contract not fetched: CROSS_REPO_READ_TOKEN is unset; the committed copy at ' + args.output + ' is used', flush=True)
        return None
    result = fetch(env, args.output)
    if args.receipt:
        receipt = Path(args.receipt)
        receipt.parent.mkdir(parents=True, exist_ok=True)
        with receipt.open('x') as stream:
            json.dump(result, stream, sort_keys=True, indent=2)
            stream.write('\n')
    print(json.dumps(result, sort_keys=True))
    return result


if __name__ == '__main__':
    main()
