import importlib.util
import hashlib
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('registry', Path(__file__).with_name('registry.py'))
registry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(registry)


class RegistryReceiptTests(unittest.TestCase):
    def test_cli_binds_versioned_readback_and_refuses_overwriting_receipt(self):
        import os
        import sys
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);revision='9'*40;workflow='a'*40
            image={**registry.versions.metadata('web','v2026.10.26.20.00'),'schemaVersion':'lookahead-scanned-image/v3','revision':revision,'workflowSha':workflow,'imageId':'sha256:'+'b'*64,'platform':'linux/amd64','archiveSha256':'c'*64,'workflowSha256':'d'*64,'dockerfileSha256':'e'*64}
            raw=json.dumps({'schemaVersion':2,'config':{'digest':image['imageId']}})
            response={'images':[{'imageManifest':raw,'imageId':{'imageTag':image['imageTag'],'imageDigest':'sha256:'+hashlib.sha256(raw.encode()).hexdigest()}}]}
            (root/'image.json').write_text(json.dumps(image));(root/'read.json').write_text(json.dumps(response))
            argv=['registry.py','bind','--input',str(root/'read.json'),'--revision',revision,'--repository',registry.versions.REPOSITORY,'--image-receipt',str(root/'image.json'),'--output',str(root/'bound.json')]
            with patch.object(sys,'argv',argv):
                registry.main()
                bound=json.loads((root/'bound.json').read_text())
                self.assertEqual((bound['imageTag'],bound['revision'],bound['workflowSha'],bound['schemaVersion']),(image['imageTag'],revision,workflow,'lookahead-registry-image/v3'))
                with self.assertRaises(ValueError):registry.main()
            document={'protection_rules':[{'type':'required_reviewers','reviewers':[{'id':1}],'prevent_self_review':True}],'deployment_branch_policy':{'protected_branches':True}}
            (root/'read.json').write_text(json.dumps(document))
            record={'schema':'release-source/v1','selector':'v2026.10.26.20.00','sourceSha':revision,'workflowSha':workflow,'mainSha':workflow}
            (root/'release-source.json').write_text(json.dumps(record))
            base=['registry.py','approval','--input',str(root/'read.json'),'--revision',revision,'--role','arn:aws:iam::509614632283:role/scoped-publisher','--repository',registry.versions.REPOSITORY]
            with patch.object(sys,'argv',[*base,'--expected-revision',revision]):registry.main()
            # The frozen record supplies the expected source; it must belong to the executed workflow commit.
            with patch.dict(os.environ,{'GITHUB_SHA':workflow}):
                with patch.object(sys,'argv',[*base,'--record',str(root/'release-source.json')]):registry.main()
                with patch.object(sys,'argv',[*base,'--record',str(root/'release-source.json'),'--expected-revision',revision]):registry.main()
                with patch.object(sys,'argv',[*base,'--record',str(root/'release-source.json'),'--expected-revision',workflow]),self.assertRaises(ValueError):registry.main()
                with patch.object(sys,'argv',[*base,'--revision',workflow,'--record',str(root/'release-source.json')]),self.assertRaises(ValueError):registry.main()
                with patch.object(sys,'argv',base),self.assertRaises(ValueError):registry.main()
            with patch.dict(os.environ,{'GITHUB_SHA':'f'*40}),patch.object(sys,'argv',[*base,'--record',str(root/'release-source.json')]),self.assertRaises(ValueError):registry.main()
            with patch.dict(os.environ,{},clear=True),patch.object(sys,'argv',[*base,'--record',str(root/'release-source.json')]),self.assertRaises(ValueError):registry.main()

    def test_frozen_record_source_requires_schema_exact_commit_and_same_workflow_run(self):
        import os
        import tempfile
        from unittest.mock import patch
        record={'schema':'release-source/v1','selector':'v2026.10.26.20.00','sourceSha':'9'*40,'workflowSha':'a'*40}
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'release-source.json'
            with patch.dict(os.environ,{'GITHUB_SHA':'a'*40}):
                path.write_text(json.dumps(record));self.assertEqual(registry.recordSource(path,os.environ),'9'*40)
                for bad in [{**record,'schema':'other'},{**record,'sourceSha':'main'},{**record,'workflowSha':'9'*40},[record]]:
                    path.write_text(json.dumps(bad))
                    with self.subTest(bad=bad),self.assertRaises(ValueError):registry.recordSource(path,os.environ)
                path.write_text(json.dumps(record))
            with self.assertRaises(ValueError):registry.recordSource(path,{})

    def test_shared_repository_requires_versioned_receipt_and_exact_prefixed_tag(self):
        revision = 'a'*40
        meta = registry.versions.metadata('domain', 'v2026.10.26.20.00')
        image = {**meta, 'schemaVersion': 'lookahead-scanned-image/v2', 'revision': revision,
                 'imageId': 'sha256:'+'b'*64, 'platform': 'linux/amd64', 'archiveSha256': 'c'*64,
                 'workflowSha256': 'd'*64, 'dockerfileSha256': 'e'*64}
        raw = json.dumps({'schemaVersion': 2, 'config': {'digest': image['imageId']}})
        entry = {'imageManifest': raw, 'imageId': {'imageTag': meta['imageTag'], 'imageDigest': 'sha256:'+hashlib.sha256(raw.encode()).hexdigest()}}
        result = registry.bind(image, {'images':[entry]}, registry.versions.REPOSITORY, revision)
        self.assertEqual(result['schemaVersion'], 'lookahead-registry-image/v3')
        self.assertEqual({k:result[k] for k in meta},meta)
        self.assertNotIn('workflowSha', result)
        # v3 scanned receipts carry the executed workflow commit (M) beside the built source (S); a rollback has S != M.
        rollback = {**image, 'schemaVersion': 'lookahead-scanned-image/v3', 'revision': '9'*40, 'workflowSha': revision}
        bound = registry.bind(rollback, {'images':[entry]}, registry.versions.REPOSITORY, '9'*40)
        self.assertEqual((bound['revision'], bound['workflowSha'], bound['schemaVersion']), ('9'*40, revision, 'lookahead-registry-image/v3'))
        with self.assertRaises(ValueError):registry.bind(rollback, {'images':[entry]}, registry.versions.REPOSITORY, revision)
        for bad in ['main', '', None]:
            with self.assertRaises(ValueError):registry.bind({**rollback, 'workflowSha': bad}, {'images':[entry]}, registry.versions.REPOSITORY, '9'*40)
        with self.assertRaises(ValueError):registry.bind({k: v for k, v in rollback.items() if k != 'workflowSha'}, {'images':[entry]}, registry.versions.REPOSITORY, '9'*40)
        for tag in [revision, 'web-v2026.10.26.20.00', 'domain-v2026.10.26.20.01']:
            altered = {**entry, 'imageId': {**entry['imageId'], 'imageTag':tag}}
            with self.assertRaises(ValueError):registry.bind(image, {'images':[altered]}, registry.versions.REPOSITORY, revision)
        with self.assertRaises(ValueError):registry.bind({**image, 'schemaVersion':'lookahead-scanned-image/v1'}, {'images':[entry]}, registry.versions.REPOSITORY, revision)

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
