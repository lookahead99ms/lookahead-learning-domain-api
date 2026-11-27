import importlib.util
import json
import hashlib
import io
import tarfile
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('immutable_image', Path(__file__).with_name('image.py'))
image = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image)


class ImmutableImageTests(unittest.TestCase):
    def test_cli_exports_and_imports_same_archive_without_building(self):
        import shutil
        import sys
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve();source=root/'source.tar';configId=self.archive(source)
            directory=root/'release';expected={'revision':'a'*40,'workflowSha':'a'*40,'dockerfileSha256':'b'*64,'workflowSha256':'c'*64}
            def run(args,**kwargs):
                self.assertEqual(args[:3],['docker','image','save'])
                shutil.copyfile(source,args[4])
            with patch.object(image,'inputs',return_value=expected),patch.object(image,'identity',return_value=configId),patch.object(image.subprocess,'run',side_effect=run),patch('builtins.print'):
                with patch.object(sys,'argv',['image.py','export','--image','scanned','--directory',str(directory)]):image.main()
                receipt=json.loads((directory/'image.json').read_text());self.assertEqual(receipt['imageId'],configId)
                self.assertEqual(receipt['schemaVersion'],'lookahead-scanned-image/v1');self.assertNotIn('workflowSha',receipt)
                with patch.object(sys,'argv',['image.py','export','--image','scanned','--directory',str(directory)]),self.assertRaises(ValueError):image.main()
                with patch.object(sys,'argv',['image.py','import','--directory',str(directory)]),patch.object(image,'command',return_value='Loaded image: scanned'),patch.object(image,'verifyLoaded',return_value='scanned') as verify:
                    image.main();verify.assert_called_once_with('scanned',configId)
                source=root/'release-source';source.mkdir();sourceLink=root/'source-link';sourceLink.symlink_to(source,target_is_directory=True)
                with patch.object(image,'command',return_value='Loaded image: scanned'),patch.object(image,'verifyLoaded',return_value='scanned'),patch.object(image,'inputs',return_value=expected) as inputs:
                    with patch.object(sys,'argv',['image.py','import','--directory',str(directory),'--source-root',str(source)]):image.main()
                    inputs.assert_called_once_with(Path.cwd(),source)
                    for bad in (sourceLink,root/'missing'):
                        with patch.object(sys,'argv',['image.py','import','--directory',str(directory),'--source-root',str(bad)]),self.assertRaises(ValueError):image.main()
                link=root/'link';link.symlink_to(directory,target_is_directory=True)
                with patch.object(sys,'argv',['image.py','import','--directory',str(link)]),self.assertRaises(ValueError):image.main()

    def test_versioned_archive_rejects_other_build_app_source_version_and_workflow_commit(self):
        from test_release_version import archive as fixture
        import os
        app = 'domain'
        meta = image.versions.metadata(app, 'v2026.10.26.20.00')
        # Rollback shape: the image source (S) is an older main commit than the executed workflow (M).
        expected = {**meta, 'revision':'9'*40,'workflowSha':'a'*40,'dockerfileSha256':'b'*64,'workflowSha256':'c'*64}
        labels = {'org.opencontainers.image.version':meta['releaseVersion'], 'org.opencontainers.image.revision':'9'*40,'com.lookahead.app':app}
        with tempfile.TemporaryDirectory() as temp:
            archive=Path(temp)/'image.tar'; configId=fixture(archive,labels)
            receipt={**expected,'schemaVersion':'lookahead-scanned-image/v3','imageId':configId,'archiveSha256':image.digest(archive),'platform':'linux/amd64'}
            self.assertEqual(image.validate(receipt,archive,expected),configId)
            for key in (*meta,'revision','workflowSha'):
                with self.subTest(key=key),self.assertRaises(ValueError):image.validate({**receipt,key:'wrong'},archive,expected)
            with self.assertRaises(ValueError):image.validate({**receipt,'workflowSha':'f'*40},archive,expected)
            with self.assertRaises(ValueError):image.validate(receipt,archive,{**expected,'workflowSha':'f'*40})
            with self.assertRaises(ValueError):image.validate({**receipt,'workflowSha':'main'},archive,{**expected,'workflowSha':'main'})
            # Receipts from the older v2 contract (no executed-workflow commit) fail safely.
            old={k:v for k,v in receipt.items() if k!='workflowSha'}
            with self.assertRaises(ValueError):image.validate({**old,'schemaVersion':'lookahead-scanned-image/v2'},archive,expected)
            with self.assertRaises(ValueError):image.validate({**receipt,'schemaVersion':'lookahead-scanned-image/v2'},archive,expected)
            for key in labels:
                changed=fixture(archive,{**labels,key:'wrong'})
                with self.assertRaises(ValueError):image.validate({**receipt,'imageId':changed,'archiveSha256':image.digest(archive)},archive,expected)
        env={'RELEASE_VERSION':meta['releaseVersion'],'GITHUB_SHA':'a'*40,'SOURCE_SHA':'9'*40}
        with patch.dict(os.environ,env,clear=True),patch.object(image,'command',side_effect=['','','9'*40]),patch.object(image,'digest',return_value='b'*64),patch.object(image,'workflowFile',return_value=b'main ci') as workflow:
            result=image.inputs(Path('root'),Path('source'))
            workflow.assert_called_once_with(Path('root'),'a'*40)
            self.assertEqual((result['imageTag'],result['revision'],result['workflowSha'],result['workflowSha256']),(meta['imageTag'],'9'*40,'a'*40,hashlib.sha256(b'main ci').hexdigest()))
        with patch.dict(os.environ,{**env,'SOURCE_SHA':'a'*40},clear=True),patch.object(image,'command',side_effect=['','','9'*40]),patch.object(image,'digest',return_value='b'*64),self.assertRaisesRegex(ValueError,'resolved release source'):
            image.inputs(Path('root'),Path('source'))
        with patch.dict(os.environ,{**env,'GITHUB_SHA':'main'},clear=True),patch.object(image,'command',side_effect=['','','9'*40]),patch.object(image,'digest',return_value='b'*64),self.assertRaises(ValueError):
            image.inputs(Path('root'),Path('source'))
        # Without GITHUB_SHA (local use) both commits are the checkout and the working-tree ci.yml is hashed.
        with patch.dict(os.environ,{},clear=True),patch.object(image,'command',side_effect=['','','9'*40]),patch.object(image,'digest',return_value='b'*64):
            result=image.inputs(Path('root'))
            self.assertEqual((result['revision'],result['workflowSha'],result['workflowSha256']),('9'*40,'9'*40,'b'*64));self.assertNotIn('imageTag',result)

    def test_inputs_bind_tag_resolved_checkout_and_executed_workflow_commit_from_real_git(self):
        import os
        import subprocess
        def git(cwd,*args):
            return subprocess.run(['git','-c','user.name=t','-c','user.email=t@example.invalid','-c','commit.gpgsign=false',*args],cwd=cwd,check=True,capture_output=True,text=True).stdout.strip()
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve()/'main';root.mkdir();(root/'.github/workflows').mkdir(parents=True)
            git(root,'init','-q','-b','main');(root/'Dockerfile').write_text('FROM scratch\n');(root/'.github/workflows/ci.yml').write_text('ci: one\n')
            git(root,'add','.');git(root,'commit','-q','-m','S');S=git(root,'rev-parse','HEAD')
            (root/'.github/workflows/ci.yml').write_text('ci: two\n');(root/'Dockerfile').write_text('FROM scratch\nLABEL later=1\n')
            git(root,'add','.');git(root,'commit','-q','-m','M');M=git(root,'rev-parse','HEAD')
            source=root.parent/'release-source';git(root.parent,'clone','-q',str(root),str(source));git(source,'checkout','-q',S)
            with patch.dict(os.environ,{'GITHUB_SHA':M,'SOURCE_SHA':S},clear=True):
                result=image.inputs(root,source)
                self.assertEqual(result,{'revision':S,'workflowSha':M,'dockerfileSha256':hashlib.sha256(b'FROM scratch\n').hexdigest(),'workflowSha256':hashlib.sha256(b'ci: two\n').hexdigest()})
                # Same tooling checkout, wrong application source: a checkout at M is not the resolved S.
                with self.assertRaisesRegex(ValueError,'resolved release source'):image.inputs(root)
                # Dirty source substitution and an unknown executed-workflow commit fail.
                (source/'Dockerfile').write_text('FROM other\n')
                with self.assertRaisesRegex(ValueError,'committed and clean'):image.inputs(root,source)
                git(source,'checkout','-q','--','Dockerfile')
            with patch.dict(os.environ,{'GITHUB_SHA':'f'*40,'SOURCE_SHA':S},clear=True),self.assertRaises(subprocess.CalledProcessError):image.inputs(root,source)
            with patch.dict(os.environ,{},clear=True):
                self.assertEqual(image.inputs(source),{'revision':S,'workflowSha':S,'dockerfileSha256':hashlib.sha256(b'FROM scratch\n').hexdigest(),'workflowSha256':hashlib.sha256(b'ci: one\n').hexdigest()})

    def archive(self, path, architecture='amd64', multiple=False, wrongPath=False, link=False, labels=None):
        raw=json.dumps({'os':'linux','architecture':architecture, 'config': {'Labels': labels or {}}}).encode()
        digest=hashlib.sha256(raw).hexdigest();name=('f'*64 if wrongPath else digest)+'.json'
        entries=[{'Config':name,'Layers':[]}]
        if multiple:entries.append(dict(entries[0]))
        with tarfile.open(path,'w') as tar:
            for memberName,content in [('manifest.json',json.dumps(entries).encode()),(name,raw)]:
                member=tarfile.TarInfo(memberName);member.size=len(content)
                if link and memberName==name:member.type=tarfile.SYMTYPE;member.linkname='/outside'
                tar.addfile(member,io.BytesIO(content) if member.isfile() else None)
        return 'sha256:'+digest

    def test_archive_config_identity_is_distinct_from_containerd_manifest_id(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'image.tar';config=self.archive(archive)
            info=json.dumps([{'Os':'linux','Architecture':'amd64','Id':'sha256:'+'d'*64}])
            with patch.object(image,'command',return_value=info):
                self.assertNotEqual(image.identity('local-image'),image.archiveIdentity(archive))
            self.assertEqual(config,image.archiveIdentity(archive))
            for options in [{'architecture':'arm64'},{'multiple':True},{'wrongPath':True},{'link':True}]:
                self.archive(archive,**options)
                with self.assertRaises(ValueError):image.archiveIdentity(archive)

    def test_oci_index_rejects_multiple_runnable_platforms_and_unbound_attestations(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'image.tar'
            for variant in ['valid','arm64','duplicate','wrong-config','unbound-attestation']:
                configId=self.archive(archive)
                blobs={}
                def descriptor(document,**fields):
                    raw=json.dumps(document).encode();value=hashlib.sha256(raw).hexdigest();blobs['blobs/sha256/'+value]=raw
                    return {'digest':'sha256:'+value,**fields}
                runtime=descriptor({'schemaVersion':2,'config':{'digest':configId if variant!='wrong-config' else 'sha256:'+'f'*64}},platform={'os':'linux','architecture':'arm64' if variant=='arm64' else 'amd64'})
                children=[runtime]
                if variant=='duplicate':children.append(dict(runtime))
                if variant=='unbound-attestation':
                    children.append(descriptor({'schemaVersion':2},platform={'os':'unknown','architecture':'unknown'},annotations={'vnd.docker.reference.type':'attestation-manifest','vnd.docker.reference.digest':'sha256:'+'f'*64}))
                parent=descriptor({'schemaVersion':2,'manifests':children})
                blobs['index.json']=json.dumps({'schemaVersion':2,'manifests':[parent]}).encode()
                with tarfile.open(archive,'a') as tar:
                    for name,raw in blobs.items():
                        member=tarfile.TarInfo(name);member.size=len(raw);tar.addfile(member,io.BytesIO(raw))
                if variant=='valid':self.assertEqual(configId,image.archiveIdentity(archive))
                else:
                    with self.assertRaises(ValueError):image.archiveIdentity(archive)

    def test_loaded_reference_requires_one_result_and_verified_config(self):
        for output in ['', 'Loaded image ID: sha256:aaa\nLoaded image ID: sha256:bbb']:
            with self.assertRaises(ValueError):image.loadedReference(output)
        self.assertEqual('sha256:aaa',image.loadedReference('Loaded image ID: sha256:aaa\n'))
        self.assertEqual('example:tag',image.loadedReference('Loaded image: example:tag\n'))
        with patch.object(image,'identity'),patch.object(image.subprocess,'run'),patch.object(image,'archiveIdentity',return_value='sha256:'+'a'*64):
            self.assertEqual('local-reference',image.verifyLoaded('local-reference','sha256:'+'a'*64))
            with self.assertRaises(ValueError):image.verifyLoaded('local-reference','sha256:'+'b'*64)

    def test_dirty_or_untracked_release_source_is_rejected(self):
        for replies in [['changed.py'], ['', 'extra.py']]:
            with self.subTest(replies=replies), patch.object(image, 'command', side_effect=replies), self.assertRaises(ValueError):
                image.inputs(Path('.'))

    def test_archive_source_platform_and_identity_bind_independently(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'image.tar'
            configId = self.archive(archive)
            expected = {'revision': 'a' * 40, 'dockerfileSha256': 'b' * 64, 'workflowSha256': 'c' * 64}
            receipt = {'schemaVersion': 'lookahead-scanned-image/v1', **expected,
                       'imageId': configId, 'archiveSha256': image.digest(archive), 'platform': 'linux/amd64'}
            self.assertEqual(receipt['imageId'], image.validate(receipt, archive, expected))
            for key, value in [('revision', 'e' * 40), ('dockerfileSha256', 'e' * 64),
                               ('workflowSha256', 'e' * 64), ('archiveSha256', 'e' * 64),
                               ('platform', 'linux/arm64'), ('imageId', 'untrusted:latest'),
                               ('schemaVersion', 'untrusted')]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    image.validate({**receipt, key: value}, archive, expected)
            with self.assertRaises(ValueError):
                image.validate({**receipt, 'deploymentReady': True}, archive, expected)
            archive.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                image.validate(receipt, archive, expected)


if __name__ == '__main__':
    unittest.main()
