"""Domain owner approval and single-attempt publication of the same-run tested archive."""
import argparse
import json
import os
from pathlib import Path
import subprocess

import image
import owner_approval as approval
import registry

REPOSITORY = '509614632283.dkr.ecr.us-east-2.amazonaws.com/lookahead-dev-ecr'


def gate(env, get=approval.api):
    identity = approval.provenance(env, get)
    approval.require(identity['repository'] == 'lookahead99ms/lookahead-learning-domain-api'
                     and env.get('APPROVED_SHA') == identity['sourceSha']
                     and env.get('ECR_REPOSITORY') == REPOSITORY, 'Exact Domain source and ECR target required')
    return identity


def plan(directory, env, get=approval.api):
    identity = gate(env, get)
    directory = Path(directory)
    archive, receiptPath = directory / 'image.tar', directory / 'image.json'
    approval.require(not any(p.is_symlink() for p in [directory, *directory.parents, archive, receiptPath]),
                     'Image artifact symlinks rejected')
    expected = image.inputs(Path.cwd())
    approval.require(expected['revision'] == identity['sourceSha'], 'Checkout differs from reviewed source')
    receipt = json.loads(receiptPath.read_text(), object_pairs_hook=approval.unique)
    approval.versions.validate(receipt, identity['app'])
    approval.require(receipt.get('releaseVersion') == identity['releaseVersion'], 'Built release differs from approved tag')
    imageId = image.validate(receipt, archive, {**expected, **{k: identity[k] for k in approval.versions.FIELDS}})
    result = {'schema': 'image-owner-approval/v1', **identity, 'role': env.get('DEPLOY_ROLE', ''),
              'sourceChanges': approval.sourceChanges(identity['repository'], identity['sourceSha'], get),
              'imageId': imageId, 'files': {p.name: image.digest(p) for p in (archive, receiptPath)}}
    approval.validatePlan(result, env, get)
    return result


def aws(*args):
    result = subprocess.run(['aws', *args, '--region', 'us-east-2', '--output', 'json', '--no-cli-pager',
                             '--cli-connect-timeout', '10', '--cli-read-timeout', '25'],
                            capture_output=True, text=True, timeout=40)
    approval.require(result.returncode == 0, 'ECR request failed; preserve intent and reconcile before another run')
    return json.loads(result.stdout, object_pairs_hook=approval.unique)


def write(path, value, *, new=False):
    with Path(path).open('x' if new else 'w') as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write('\n')


def publish(directory, receiptPath, env, loaded, get=approval.api, provider=aws):
    directory, receiptPath = Path(directory), Path(receiptPath)
    selected = plan(directory, env, get)
    decision = json.loads(receiptPath.read_text(), object_pairs_hook=approval.unique)
    approval.verify(selected, decision, env, get)
    approval.require(provider('sts', 'get-caller-identity').get('Account') == approval.ACCOUNT, 'Wrong AWS account')
    repos = provider('ecr', 'describe-repositories', '--repository-names', 'lookahead-dev-ecr').get('repositories', [])
    approval.require(len(repos) == 1 and repos[0].get('repositoryUri') == REPOSITORY
                     and repos[0].get('imageTagMutability') == 'IMMUTABLE', 'Exact immutable Domain repository required')
    readArgs = ('ecr', 'batch-get-image', '--repository-name', 'lookahead-dev-ecr',
                '--image-ids', 'imageTag=' + selected['imageTag'])
    existing = provider(*readArgs)
    approval.require(existing.get('images') == [] and len(existing.get('failures', [])) == 1
                     and existing['failures'][0].get('failureCode') == 'ImageNotFound',
                     'Tag exists or state unknown; reconcile without retry')
    image.verifyLoaded(loaded, selected['imageId'])
    subprocess.run(['docker', 'tag', loaded, selected['target']], check=True, timeout=30)
    approval.verify(selected, decision, env, get)
    outcome = {'sourceSha': selected['sourceSha'], 'runId': selected['runId'], 'target': selected['target'],
               'imageId': selected['imageId'], 'approvalDigest': approval.digest(selected),
               'pushAttempted': True, 'pushReturned': False, 'readbackVerified': False}
    outcomePath = receiptPath.parent / 'publication-outcome.json'
    write(outcomePath, outcome, new=True)
    subprocess.run(['docker', 'push', '--platform', 'linux/amd64', selected['target']], check=True, timeout=900)
    outcome['pushReturned'] = True
    write(outcomePath, outcome)
    receipt = json.loads((directory / 'image.json').read_text(), object_pairs_hook=approval.unique)
    bound = registry.bind(receipt, provider(*readArgs), REPOSITORY, selected['sourceSha'])
    write(receiptPath.parent / 'registry-image.json', bound, new=True)
    write(outcomePath, {**outcome, 'readbackVerified': True, 'image': bound['image']})
    return bound


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['gate', 'approve', 'check', 'publish'])
    parser.add_argument('--directory', required=True)
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--loaded')
    args = parser.parse_args(argv)
    if args.mode == 'gate':
        gate(os.environ)
        return
    selected = plan(args.directory, os.environ)
    receiptPath = Path(args.receipt)
    if args.mode == 'approve':
        write(receiptPath.parent / 'owner-plan.json', selected, new=True)
        write(receiptPath, approval.approve(selected, os.environ), new=True)
    elif args.mode == 'check':
        approval.verify(selected, json.loads(receiptPath.read_text(), object_pairs_hook=approval.unique), os.environ)
    else:
        approval.require(bool(args.loaded), 'Exact verified loaded image required')
        publish(args.directory, receiptPath, os.environ, args.loaded)


if __name__ == '__main__':
    main()
