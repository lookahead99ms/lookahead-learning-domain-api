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
    def archive(self, path, architecture='amd64', multiple=False, wrongPath=False, link=False):
        raw=json.dumps({'os':'linux','architecture':architecture}).encode()
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
