import importlib.util
import hashlib
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('registry', Path(__file__).with_name('registry.py'))
registry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(registry)


class RegistryReceiptTests(unittest.TestCase):
    def test_approval_requires_independent_review_and_exact_dev_source(self):
        document = {'protection_rules': [{'type': 'required_reviewers', 'reviewers': [{'id': 1}],
                                         'prevent_self_review': True}],
                    'deployment_branch_policy': {'protected_branches': True}}
        args = ['a' * 40, 'a' * 40, 'arn:aws:iam::509614632283:role/scoped-publisher',
                '509614632283.dkr.ecr.us-east-2.amazonaws.com/approved-domain']
        registry.validateApproval(document, *args)
        for bad in [{}, {**document, 'protection_rules': []},
                    {**document, 'deployment_branch_policy': {'protected_branches': False}},
                    {**document, 'protection_rules': [{'type': 'required_reviewers', 'reviewers': [{'id': 1}], 'prevent_self_review': False}]}]:
            with self.assertRaises(ValueError):
                registry.validateApproval(bad, *args)
        for index, value in [(0, 'b' * 40), (2, 'arn:aws:iam::111111111111:role/admin'),
                             (3, '509614632283.dkr.ecr.us-west-2.amazonaws.com/approved-domain')]:
            altered = list(args)
            altered[index] = value
            with self.assertRaises(ValueError):
                registry.validateApproval(document, *altered)

    def test_registry_receipt_rejects_remote_substitution_and_missing_manifest(self):
        revision = 'a' * 40
        image = {'revision': revision, 'imageId': 'sha256:' + 'b' * 64, 'platform': 'linux/amd64',
                 'archiveSha256': 'c' * 64, 'workflowSha256': 'd' * 64, 'dockerfileSha256': 'e' * 64}
        raw = json.dumps({'schemaVersion': 2, 'config': {'digest': image['imageId']}})
        entry = {'imageManifest': raw, 'imageId': {'imageTag': revision,
                  'imageDigest': 'sha256:' + hashlib.sha256(raw.encode()).hexdigest()}}
        repository = '509614632283.dkr.ecr.us-east-2.amazonaws.com/approved-domain'
        result = registry.bind(image, {'images': [entry]}, repository, revision)
        self.assertEqual(repository + '@' + entry['imageId']['imageDigest'], result['image'])
        for response in [{}, {'images': [entry, entry]}, {'images': [entry], 'failures': [{}]},
                         {'images': [{**entry, 'imageId': {**entry['imageId'], 'imageDigest': 'sha256:' + 'f' * 64}}]},
                         {'images': [{**entry, 'imageId': {**entry['imageId'], 'imageTag': 'latest'}}]}]:
            with self.assertRaises(ValueError):
                registry.bind(image, response, repository, revision)
        for altered in [{**image, 'imageId':'sha256:'+'f'*64}, {**image, 'platform':'linux/arm64'}, {**image, 'imageId':'local:tag'}]:
            with self.assertRaises(ValueError):
                registry.bind(altered, {'images':[entry]}, repository, revision)


if __name__ == '__main__':
    unittest.main()
