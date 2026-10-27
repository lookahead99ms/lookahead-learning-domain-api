from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import owner_release as m
from test_owner_approval import Github
import test_image


class Candidate:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.github = Github('lookahead99ms/lookahead-learning-domain-api')
        self.github.now = datetime.now(timezone.utc)
        self.env = {**self.github.env, 'APPROVED_SHA': 'a'*40, 'DEPLOY_ROLE': self.github.plan['role'], 'ECR_REPOSITORY': m.REPOSITORY}
        self.expected = {'revision': 'a'*40, 'dockerfileSha256': 'd'*64, 'workflowSha256': 'e'*64}
        config = test_image.ImmutableImageTests().archive(self.directory/'image.tar', labels={'org.opencontainers.image.version': 'v2026.10.26.20.00', 'org.opencontainers.image.revision': 'a'*40, 'com.lookahead.app': 'domain'})
        self.image = {**m.approval.versions.metadata('domain', 'v2026.10.26.20.00'), 'schemaVersion':'lookahead-scanned-image/v2', **self.expected, 'imageId':config,
                      'archiveSha256':m.image.digest(self.directory/'image.tar'),'platform':'linux/amd64'}
        m.write(self.directory/'image.json',self.image,new=True)
        self.receiptPath=self.directory/'approval.json';self.reads=0;self.calls=[]

    def approve(self):
        self.github.plan=m.plan(self.directory,self.env,self.github.get)
        m.write(self.receiptPath,self.github.approved(),new=True)

    def provider(self,*args):
        self.calls.append(args)
        if args[0]=='sts':return {'Account':m.approval.ACCOUNT}
        if args[1]=='describe-repositories':return {'repositories':[{'repositoryUri':m.REPOSITORY,'imageTagMutability':'IMMUTABLE'}]}
        self.reads+=1
        if self.reads==1:return {'images':[],'failures':[{'failureCode':'ImageNotFound'}]}
        raw=json.dumps({'schemaVersion':2,'config':{'digest':self.image['imageId']}})
        return {'images':[{'imageManifest':raw,'imageId':{'imageTag':'domain-v2026.10.26.20.00','imageDigest':'sha256:'+hashlib.sha256(raw.encode()).hexdigest()}}]}

    def publish(self,provider=None):
        return m.publish(self.directory,self.receiptPath,self.env,'loaded-image',self.github.get,provider or self.provider)


class OwnerReleaseTests(unittest.TestCase):
    def test_exact_tested_image_is_approved_pushed_once_and_read_back(self):
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp)
            with patch.object(m.image,'inputs',return_value=c.expected),patch.object(m.image,'verifyLoaded') as verified,patch.object(m.subprocess,'run') as call:
                c.approve();result=c.publish()
                self.assertEqual(result['imageId'],c.image['imageId'])
                verified.assert_called_once_with('loaded-image',c.image['imageId'])
                self.assertEqual([r.args[0][1] for r in call.call_args_list],['tag','push'])
                self.assertTrue(json.loads((Path(temp)/'publication-outcome.json').read_text())['readbackVerified'])

    def test_changed_archive_or_absent_approval_never_reaches_aws(self):
        for mode in ('archive','approval','source','target','expected'):
            with tempfile.TemporaryDirectory() as temp:
                c=Candidate(temp)
                with patch.object(m.image,'inputs',return_value=c.expected):
                    c.approve()
                    if mode=='archive':(Path(temp)/'image.tar').write_bytes(b'changed')
                    elif mode=='approval':c.github.comments=[]
                    elif mode=='source':c.expected['revision']='b'*40
                    elif mode=='target':c.env['ECR_REPOSITORY']=m.REPOSITORY+'-other'
                    else:c.env['APPROVED_SHA']='b'*40
                    with self.assertRaises(ValueError):c.publish()
                    self.assertEqual(c.calls,[])

    def test_existing_tag_mutability_account_loaded_image_and_late_rejection_block(self):
        for mode in ('tag','mutability','account','loaded','late'):
            with tempfile.TemporaryDirectory() as temp:
                c=Candidate(temp)
                with patch.object(m.image,'inputs',return_value=c.expected),patch.object(m.subprocess,'run') as call:
                    c.approve()
                    def provider(*a):
                        r=c.provider(*a)
                        if mode=='tag' and a[1]=='batch-get-image':r={'images':[{}]}
                        if mode=='mutability' and a[1]=='describe-repositories':r['repositories'][0]['imageTagMutability']='MUTABLE'
                        if mode=='account' and a[0]=='sts':r['Account']='000000000000'
                        return r
                    def loaded(*a):
                        if mode=='loaded':raise ValueError('loaded config changed')
                        if mode=='late':c.github.comments.append({**c.github.comment(),'body':'REJECT'})
                    with patch.object(m.image,'verifyLoaded',side_effect=loaded),self.assertRaises(ValueError):c.publish(provider)
                    self.assertFalse(any(r.args[0][1]=='push' for r in call.call_args_list))
                    self.assertFalse((Path(temp)/'publication-outcome.json').exists())

    def test_ambiguous_upload_retains_intent_without_retry_or_success(self):
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp)
            def run(args,**kwargs):
                if args[1]=='push':raise TimeoutError('upload response lost')
            with patch.object(m.image,'inputs',return_value=c.expected),patch.object(m.image,'verifyLoaded'),patch.object(m.subprocess,'run',side_effect=run) as call:
                c.approve()
                with self.assertRaises(TimeoutError):c.publish()
                outcome=json.loads((Path(temp)/'publication-outcome.json').read_text())
                self.assertTrue(outcome['pushAttempted']);self.assertFalse(outcome['readbackVerified'])
                self.assertEqual(sum(r.args[0][1]=='push' for r in call.call_args_list),1)
                self.assertFalse((Path(temp)/'registry-image.json').exists())

    def test_symlink_inputs_and_duplicate_receipt_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp);p=Path(temp)/'image.json';p.rename(Path(temp)/'other.json');p.symlink_to('other.json')
            with patch.object(m.image,'inputs',return_value=c.expected),self.assertRaises(ValueError):m.plan(Path(temp).resolve(),c.env,c.github.get)
            p.unlink();p.write_text('{"same":1,"same":2}')
            with patch.object(m.image,'inputs',return_value=c.expected),self.assertRaises(ValueError):m.plan(Path(temp).resolve(),c.env,c.github.get)

    def test_transport_has_no_retry_and_redacts_provider_errors(self):
        with patch.object(m.subprocess,'run',return_value=Mock(returncode=0,stdout='{}')) as call:
            self.assertEqual(m.aws('ecr','batch-get-image'),{})
            self.assertEqual(call.call_args.kwargs['timeout'],40)
        with patch.object(m.subprocess,'run',return_value=Mock(returncode=1,stdout='secret',stderr='secret')):
            with self.assertRaises(ValueError) as caught:m.aws('ecr','batch-get-image')
            self.assertNotIn('secret',str(caught.exception))

    def test_cli_uses_same_plan_and_live_decision_before_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt=Path(temp)/'approval.json';args=['--directory',temp,'--receipt',str(receipt)]
            with patch.object(m,'gate') as gate,patch.object(m,'plan',return_value={'tested':True}),patch.object(m.approval,'approve',return_value={'commentId':7}),patch.object(m.approval,'verify') as verify,patch.object(m,'publish') as publish:
                m.main(['gate',*args]);gate.assert_called_once()
                m.main(['approve',*args]);self.assertEqual(json.loads(receipt.read_text())['commentId'],7)
                m.main(['check',*args]);verify.assert_called_once()
                with self.assertRaises(ValueError):m.main(['publish',*args])
                m.main(['publish',*args,'--loaded','verified']);publish.assert_called_once()


if __name__=='__main__':unittest.main()
