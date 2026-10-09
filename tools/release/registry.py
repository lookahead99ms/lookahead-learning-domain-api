#!/usr/bin/env python3
"""Validate operator release inputs and bind ECR manifest to a scanned config digest."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


def validateApproval(document, revision, expectedRevision, role, repository):
    if revision != expectedRevision or not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('Approve the exact release source SHA')
    if not re.fullmatch(r'arn:aws:iam::509614632283:role/[A-Za-z0-9+=,.@_/-]+', role):
        raise ValueError('Require scoped DEV OIDC role')
    if not re.fullmatch(r'509614632283\.dkr\.ecr\.us-east-2\.amazonaws\.com/[a-z0-9]+(?:[._/-][a-z0-9]+)*', repository):
        raise ValueError('Require exact DEV registry repository')
    rules = document.get('protection_rules', [])
    reviews = [r for r in rules if r.get('type') == 'required_reviewers']
    if not reviews or not reviews[0].get('reviewers') or reviews[0].get('prevent_self_review') is not True:
        raise ValueError('Protected DEV environment with independent review required')
    if document.get('deployment_branch_policy', {}).get('protected_branches') is not True:
        raise ValueError('DEV environment must restrict deployment to protected branches')


def bind(image, response, repository, revision):
    # imageId is the archive config digest, not Docker containerd's manifest/index Id.
    if image.get('platform') != 'linux/amd64' or not re.fullmatch(r'sha256:[a-f0-9]{64}', image.get('imageId', '')):
        raise ValueError('Require scanned linux/amd64 config identity')
    entries = response.get('images', [])
    if response.get('failures') or len(entries) != 1:
        raise ValueError('Require exactly one complete ECR manifest')
    entry = entries[0]
    raw = entry['imageManifest']
    manifest = json.loads(raw)
    remoteDigest = entry['imageId']['imageDigest']
    if remoteDigest != 'sha256:' + hashlib.sha256(raw.encode()).hexdigest() or manifest.get('schemaVersion') != 2:
        raise ValueError('Registry manifest identity changed')
    if manifest.get('config', {}).get('digest') != image['imageId'] or image['revision'] != revision or entry['imageId'].get('imageTag') != revision:
        raise ValueError('Registry image differs from the scanned release')
    return {'schemaVersion': 'lookahead-registry-image/v1', 'revision': revision,
            'image': repository + '@' + remoteDigest, 'imageId': image['imageId'],
            'platform': image['platform'], 'scannedArchiveSha256': image['archiveSha256'],
            'buildWorkflowSha256': image['workflowSha256'], 'dockerfileSha256': image['dockerfileSha256']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['approval', 'bind'])
    parser.add_argument('--input', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--expected-revision')
    parser.add_argument('--role')
    parser.add_argument('--image-receipt')
    parser.add_argument('--output')
    args = parser.parse_args()
    data = json.loads(Path(args.input).read_text())
    if args.action == 'approval':
        validateApproval(data, args.revision, args.expected_revision, args.role, args.repository)
    else:
        image = json.loads(Path(args.image_receipt).read_text())
        receipt = bind(image, data, args.repository, args.revision)
        path = Path(args.output)
        if path.exists():
            raise ValueError('Release receipt output must be new')
        path.write_text(json.dumps(receipt, sort_keys=True) + '\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError):
        print('Registry receipt or approval invalid; publication blocked.', file=sys.stderr)
        sys.exit(1)
