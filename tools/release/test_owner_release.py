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

# One copy of this file per app repository: the app under test is the one this checkout builds (package.json = web,
# build.gradle = gateway-bff, otherwise domain), and the repository is the one that publishes that app.
ROOT = Path(__file__).resolve().parents[2]
APP = 'web' if (ROOT / 'package.json').exists() else 'gateway-bff' if (ROOT / 'build.gradle').exists() else 'domain'
REPO = next(repo for repo, (_, app) in m.approval.PUBLISHERS.items() if app == APP)


class Candidate:
    """One tested image. `source` is the tag-resolved commit S; the workflow and tooling run from main M ('a'*40)."""
    def __init__(self, directory, source='a'*40):
        self.directory = Path(directory).resolve()
        self.github = Github(REPO, source=source)
        self.github.data['commits/'+source+'/pulls?per_page=100'] = []
        self.github.now = datetime.now(timezone.utc)
        self.env = {**self.github.env, 'DEPLOY_ROLE': self.github.plan['role'], 'ECR_REPOSITORY': m.REPOSITORY}
        self.expected = {'revision': source, 'workflowSha': 'a'*40, 'dockerfileSha256': 'd'*64, 'workflowSha256': 'e'*64}
        config = test_image.ImmutableImageTests().archive(self.directory/'image.tar', labels={'org.opencontainers.image.version': 'v2026.10.26.20.00', 'org.opencontainers.image.revision': source, 'com.lookahead.app': APP})
        self.image = {**m.approval.versions.metadata(APP, 'v2026.10.26.20.00'), 'schemaVersion':'lookahead-scanned-image/v3', **self.expected, 'imageId':config,
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
        return {'images':[{'imageManifest':raw,'imageId':{'imageTag':APP+'-v2026.10.26.20.00','imageDigest':'sha256:'+hashlib.sha256(raw.encode()).hexdigest()}}]}

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
        for mode in ('archive','approval','source','target','workflow','receipt-workflow','old-receipt'):
            with tempfile.TemporaryDirectory() as temp:
                c=Candidate(temp)
                with patch.object(m.image,'inputs',return_value=c.expected):
                    c.approve()
                    if mode=='archive':(Path(temp)/'image.tar').write_bytes(b'changed')
                    elif mode=='approval':c.github.comments=[]
                    elif mode=='source':c.expected['revision']='b'*40
                    elif mode=='target':c.env['ECR_REPOSITORY']=m.REPOSITORY+'-other'
                    elif mode=='workflow':c.expected['workflowSha']='b'*40
                    elif mode=='receipt-workflow':m.write(Path(temp)/'image.json',{**c.image,'workflowSha':'b'*40})
                    else:m.write(Path(temp)/'image.json',{**{k:v for k,v in c.image.items() if k!='workflowSha'},'schemaVersion':'lookahead-scanned-image/v2'})
                    with self.subTest(mode=mode),self.assertRaises(ValueError):c.publish()
                    self.assertEqual(c.calls,[])

    def test_rollback_tag_at_older_main_commit_builds_source_s_under_workflow_m(self):
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp,source='9'*40)
            with patch.object(m.image,'inputs',return_value=c.expected) as inputs,patch.object(m.image,'verifyLoaded'),patch.object(m.subprocess,'run'):
                c.approve();plan=c.github.plan
                self.assertEqual((plan['sourceSha'],plan['workflowSha']),('9'*40,'a'*40))
                inputs.assert_called_with(Path.cwd(),None,'a'*40)
                body=c.github.data['issues/3']['body'];self.assertIn('/commit/'+'9'*40,body);self.assertIn('/commit/'+'a'*40,body)
                self.assertEqual(c.github.data['issues/3']['title'],'DEV: Publish image '+APP+'-v2026.10.26.20.00 — no merged PR associated with this source · run 12')
                self.assertIn('| **Affected AWS resources** | **None** created, updated or replaced. |',body);self.assertIn('| **imageTag** | `'+APP+'-v2026.10.26.20.00` |',body)
                bound=c.publish()
                self.assertEqual((bound['revision'],bound['workflowSha'],bound['imageTag']),('9'*40,'a'*40,APP+'-v2026.10.26.20.00'))
                outcome=json.loads((Path(temp)/'publication-outcome.json').read_text())
                self.assertEqual((outcome['sourceSha'],outcome['workflowSha'],outcome['releaseVersion']),('9'*40,'a'*40,'v2026.10.26.20.00'))
            # The source root is passed through to the image binding when the tooling runs from main.
            with patch.object(m.image,'inputs',return_value=c.expected) as inputs:
                m.plan(c.directory,c.env,c.github.get,'release-source');inputs.assert_called_once_with(Path.cwd(),'release-source','a'*40)

    def test_off_main_source_moved_tag_or_wrong_checkout_fails_before_credentials(self):
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp,source='9'*40)
            c.github.data['compare/'+'9'*40+'...'+'a'*40]={'status':'diverged','merge_base_commit':{'sha':'8'*40}}
            with patch.object(m.image,'inputs',return_value=c.expected),self.assertRaisesRegex(ValueError,'must be on main'):m.plan(c.directory,c.env,c.github.get)
            self.assertEqual(c.calls,[])
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp,source='9'*40)
            with patch.object(m.image,'inputs',return_value=c.expected):c.approve()
            c.github.data['git/ref/tags/v2026.10.26.20.00']['object']['sha']='e'*40
            c.github.data['compare/'+'e'*40+'...'+'a'*40]={'status':'ahead','merge_base_commit':{'sha':'e'*40}}
            with patch.object(m.image,'inputs',return_value=c.expected),self.assertRaisesRegex(ValueError,'changed after resolution'):c.publish()
            self.assertEqual(c.calls,[])
        with tempfile.TemporaryDirectory() as temp:
            c=Candidate(temp,source='9'*40)
            # A clean checkout at main (M) is not the tag-resolved source (S).
            with patch.object(m.image,'inputs',return_value={**c.expected,'revision':'a'*40}),self.assertRaisesRegex(ValueError,'tag-resolved source'):m.plan(c.directory,c.env,c.github.get)
            with patch.object(m.image,'inputs',return_value={**c.expected,'workflowSha':'9'*40}),self.assertRaisesRegex(ValueError,'executed main workflow'):m.plan(c.directory,c.env,c.github.get)
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
            with patch.object(m,'gate') as gate,patch.object(m,'plan',return_value={'tested':True}) as plan,patch.object(m.approval,'approve',return_value={'commentId':7}),patch.object(m.approval,'verify') as verify,patch.object(m,'publish') as publish:
                m.main(['gate',*args]);gate.assert_called_once()
                m.main(['approve',*args,'--source-root','release-source']);self.assertEqual(json.loads(receipt.read_text())['commentId'],7)
                plan.assert_called_with(temp,m.os.environ,sourceRoot='release-source')
                m.main(['check',*args]);verify.assert_called_once();plan.assert_called_with(temp,m.os.environ,sourceRoot=None)
                with self.assertRaises(ValueError):m.main(['publish',*args])
                m.main(['publish',*args,'--loaded','verified','--source-root','release-source'])
                publish.assert_called_once_with(temp,receipt,m.os.environ,'verified',sourceRoot='release-source')


if __name__=='__main__':unittest.main()
