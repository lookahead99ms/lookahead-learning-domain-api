"""Owner approval trust boundaries with a fake GitHub provider; no network calls."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import owner_approval as m


RECORDS = tempfile.TemporaryDirectory()


def record(repository, source, workflow, path=None):
    """Frozen resolution as release_version.py resolve writes it; tests may corrupt a copy."""
    path = path or Path(RECORDS.name) / (hashlib.sha256((repository + source + workflow).encode()).hexdigest() + '.json')
    path.write_text(json.dumps({'schema': 'release-source/v1', 'selector': 'v2026.10.26.20.00', 'releaseVersion': 'v2026.10.26.20.00',
                                'sourceSha': source, 'tagObjectSha': None, 'mainSha': workflow, 'workflowSha': workflow,
                                'repository': repository, 'runId': '12', **m.versions.metadata(m.PUBLISHERS[repository][1], 'v2026.10.26.20.00')}))
    return str(path)


class Github:
    def __init__(self, repository='lookahead99ms/lookahead-learning-infra', source='a'*40):
        workflow, target = m.PUBLISHERS[repository]
        self.now = datetime(2026, 10, 26, 12, tzinfo=timezone.utc)
        self.env = {'GITHUB_REPOSITORY': repository, 'GITHUB_ACTOR': m.OWNER, 'GITHUB_TRIGGERING_ACTOR': m.OWNER,
                    'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_RUN_ATTEMPT': '1',
                    'GITHUB_WORKFLOW_REF': repository + '/' + workflow + '@refs/heads/main', 'GITHUB_SHA': 'a'*40,
                    'RELEASE_VERSION': 'v2026.10.26.20.00', 'GITHUB_RUN_ID': '12', 'GITHUB_REPOSITORY_ID': '10', 'GITHUB_REPOSITORY_OWNER_ID': '20',
                    'RELEASE_SOURCE_RECORD': record(repository, source, 'a'*40)}
        self.data = {'': {'full_name': repository, 'id': 10, 'owner': {'id': 20, 'login': m.OWNER}, 'private': True},
                     'git/ref/tags/v2026.10.26.20.00': {'object': {'type': 'commit', 'sha': source}}, 'commits/main': {'sha': 'a'*40}, 'commits/'+'a'*40+'/pulls?per_page=100': [],
                     'compare/'+source+'...'+'a'*40: {'status': 'identical' if source == 'a'*40 else 'ahead', 'merge_base_commit': {'sha': source}},
                     'actions/runs/12': {'id': 12, 'path': workflow, 'head_sha': 'a'*40, 'head_branch': 'main',
                                        'event': 'workflow_dispatch', 'run_attempt': 1, 'status': 'in_progress',
                                        'repository': {'full_name': repository}, 'head_repository': {'full_name': repository},
                                        'actor': {'login': m.OWNER}, 'triggering_actor': {'login': m.OWNER}},
                     'environments/dev': {'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True}},
                     'environments/dev/deployment-branch-policies?per_page=100':
                         {'total_count': 1, 'branch_policies': [{'name': 'main', 'type': 'branch'}]}}
        self.plan = {'schema': 'image-owner-approval/v1', **m.provenance(self.env, self.get),
                     'role': 'arn:aws:iam::509614632283:role/synthetic-publisher',
                     'imageId': 'sha256:'+'b'*64, 'files': {'image.tar': 'c'*64, 'image.json': 'd'*64}}
        self.comments = []
        self.autoApprove = True

    def get(self, repository, path, payload=None):
        if repository != self.env['GITHUB_REPOSITORY']:
            raise AssertionError('Cross-repository approval request')
        if path == 'issues':
            self.data['issues/3'] = {'state': 'open', 'user': {'login': 'github-actions[bot]'}, 'body': payload['body'], 'title': payload.get('title'),
                                     'created_at': self.now.replace(microsecond=0).isoformat()}
            if self.autoApprove:
                self.comments = [self.comment()]
            return {'number': 3}
        if path == 'issues/3/comments?per_page=100':
            return deepcopy(self.comments)
        return deepcopy(self.data[path])

    def comment(self):
        return {'id': 7, 'user': {'login': m.OWNER}, 'body': 'APPROVE IMAGE '+m.digest(self.plan),
                'created_at': self.now.replace(microsecond=0).isoformat(), 'updated_at': self.now.replace(microsecond=0).isoformat()}

    def approved(self):
        return m.approve(self.plan, self.env, self.get, lambda: self.now, lambda _: None)


class ApprovalTests(unittest.TestCase):
    def test_owner_approval_rechecks_both_publishers_and_binds_exact_image(self):
        for repo in m.PUBLISHERS:
            fake = Github(repo)
            receipt = fake.approved()
            m.verify(fake.plan, receipt, fake.env, fake.get, fake.now)
            self.assertEqual(receipt['commentId'], 7)
            self.assertIn(fake.plan['imageId'], fake.data['issues/3']['body'])
            self.assertIn('does not deploy services', fake.data['issues/3']['body'])
            self.assertIn('Permission deadline', fake.data['issues/3']['body'])

    def test_github_second_precision_accepts_fractional_runner_clock_for_both_publishers(self):
        for repository in m.PUBLISHERS:
            for fraction in (1, 56527, 999999):
                with self.subTest(repository=repository, microsecond=fraction):
                    fake = Github(repository)
                    fake.now = fake.now.replace(microsecond=fraction)
                    receipt = fake.approved()
                    start = fake.now.replace(microsecond=0)
                    self.assertEqual(receipt['createdAt'], start.isoformat())
                    self.assertEqual(receipt['expiresAt'], (start + timedelta(minutes=40)).isoformat())
                    self.assertLessEqual(m.timestamp(receipt['expiresAt']) - fake.now, timedelta(minutes=40))
                    m.verify(fake.plan, receipt, fake.env, fake.get, fake.now)
                    self.assertEqual(fake.data['issues/3']['body'], m.body(fake.plan, receipt))
                    # A prior-second issue and an issue at expiry still fail closed.
                    original = fake.data['issues/3']['created_at']
                    for stamp in (start - timedelta(seconds=1), start + timedelta(minutes=40)):
                        fake.data['issues/3']['created_at'] = stamp.isoformat()
                        with self.assertRaises(ValueError):
                            m.verify(fake.plan, receipt, fake.env, fake.get, fake.now)
                    fake.data['issues/3']['created_at'] = original
                    with self.assertRaises(ValueError):
                        m.verify(fake.plan, receipt, fake.env, fake.get, start + timedelta(minutes=40))
                    fake.data['issues/3']['body'] += 'edited'
                    with self.assertRaisesRegex(ValueError, 'Approval issue changed'):
                        m.verify(fake.plan, receipt, fake.env, fake.get, fake.now)

    def test_context_rejects_other_owner_source_run_repository_and_environment(self):
        for field in Github().env:
            fake = Github(); fake.env[field] = 'invalid'
            with self.subTest(field=field), self.assertRaises(ValueError): m.provenance(fake.env, fake.get)
        edits = [lambda d:d[''].update(private=False), lambda d:d['commits/main'].update(sha='f'*40),
                 lambda d:d['actions/runs/12'].update(run_attempt=2),
                 lambda d:d['actions/runs/12']['actor'].update(login='other'),
                 lambda d:d['actions/runs/12']['head_repository'].update(full_name='other'),
                 lambda d:d['environments/dev'].update(deployment_branch_policy={}),
                 lambda d:d['environments/dev/deployment-branch-policies?per_page=100'].update(total_count=2),
                 lambda d:d['environments/dev/deployment-branch-policies?per_page=100']['branch_policies'][0].update(name='*'),
                 lambda d:d['environments/dev/deployment-branch-policies?per_page=100']['branch_policies'][0].update(type='tag')]
        for edit in edits:
            fake=Github();edit(fake.data)
            with self.assertRaises(ValueError):m.provenance(fake.env,fake.get)

    def test_plan_identity_role_files_and_config_cannot_change(self):
        for field,value in [('schema','other'),('target','other'),('sourceSha','f'*40),('runId','13'),
                            ('role','arn:aws:iam::000000000000:role/admin'),('imageId','latest'),
                            ('files',{}),('files',{'../image.tar':'a'*64}),('files',{'image.tar':'bad'})]:
            fake=Github();fake.plan[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):m.validatePlan(fake.plan,fake.env,fake.get)
        fake=Github();receipt=fake.approved();fake.plan['files']['image.tar']='f'*64
        with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now)

    def test_wrong_edited_missing_future_or_old_comments_never_approve(self):
        edits=[lambda c:c['user'].update(login='other'),lambda c:c.update(body='APPROVE IMAGE wrong'),
               lambda c:c.update(updated_at=(Github().now+timedelta(seconds=1)).isoformat()),
               lambda c:c.update(created_at=(Github().now-timedelta(seconds=1)).isoformat(),updated_at=(Github().now-timedelta(seconds=1)).isoformat()),
               lambda c:c.update(created_at=(Github().now+timedelta(seconds=1)).isoformat(),updated_at=(Github().now+timedelta(seconds=1)).isoformat())]
        for edit in edits:
            fake=Github();receipt=fake.approved();edit(fake.comments[0])
            self.assertIsNone(m.decision(fake.plan,receipt,fake.env,fake.get,fake.now))
            with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now)
        fake=Github();receipt=fake.approved();fake.comments=[]
        with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now)

    def test_rejection_changed_issue_pagination_and_comment_id_block(self):
        edits=[lambda f:f.comments.append({**f.comment(),'body':'REJECT'}),
               lambda f:f.data['issues/3'].update(body='changed'),lambda f:f.data['issues/3'].update(state='closed'),
               lambda f:f.data['issues/3'].update(pull_request={ 'url':'anything' }),
               lambda f:f.data['issues/3']['user'].update(login='other'),
               lambda f:f.comments.extend([f.comment()]*99),lambda f:f.comments[0].update(id=8)]
        for edit in edits:
            fake=Github();receipt=fake.approved();edit(fake)
            with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now)

    def test_expiry_timezone_run_and_receipt_digest_fail_closed(self):
        edits=[lambda r:r.update(schema='wrong'),lambda r:r.update(runId='13'),lambda r:r.update(planDigest='f'*64),
               lambda r:r.update(createdAt='2026-10-26T12:00:00'),
               lambda r:r.update(expiresAt='2026-10-26T13:00:00+00:00'),
               lambda r:r.update(expiresAt='2026-10-26T12:00:00+00:00')]
        for edit in edits:
            fake=Github();receipt=fake.approved();edit(receipt)
            with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now)
        fake=Github();receipt=fake.approved()
        with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now+timedelta(minutes=40))
        with self.assertRaises(ValueError):m.verify(fake.plan,receipt,fake.env,fake.get,fake.now-timedelta(seconds=1))

    def test_wait_is_bounded_and_rechecks_provenance(self):
        fake=Github();fake.autoApprove=False;sleeps=[]
        with self.assertRaises(TimeoutError):m.approve(fake.plan,fake.env,fake.get,lambda:fake.now,sleeps.append)
        self.assertEqual(sleeps,[20]*120)
        def moved(_):fake.data['commits/main']['sha']='f'*40
        with self.assertRaisesRegex(ValueError,'Main moved'):m.approve(fake.plan,fake.env,fake.get,lambda:fake.now,moved)

    def test_source_pr_context_is_exact_and_pagination_is_rejected(self):
        fake=Github();path='commits/'+'a'*40+'/pulls?per_page=100'
        fake.data[path]=[{'number':2,'title':'Reviewed source','merged_at':'2026-10-26','base':{'ref':'main'}},
                         {'number':3,'title':'Unmerged','merged_at':None}]
        result=m.sourceChanges(fake.env['GITHUB_REPOSITORY'],'a'*40,fake.get)
        self.assertEqual([r['number'] for r in result['pullRequests']],[2])
        self.assertIn('not a diff',result['meaning'])
        fake.data[path]*=50
        with self.assertRaises(ValueError):m.sourceChanges(fake.env['GITHUB_REPOSITORY'],'a'*40,fake.get)

    def test_transport_is_bounded_redacts_errors_and_rejects_duplicate_fields(self):
        for payload in (None,{'title':'synthetic'}):
            with patch.object(m.subprocess,'run',return_value=Mock(returncode=0,stdout='{"ok":true}')) as call:
                self.assertTrue(m.api('owner/repo','issues',payload)['ok'])
                self.assertEqual(call.call_args.kwargs['timeout'],30)
                if payload:self.assertEqual(call.call_args.kwargs['input'],json.dumps(payload))
        for result in [Mock(returncode=1,stdout='sensitive'),Mock(returncode=0,stdout='x'*(2*1024*1024+1)),
                       Mock(returncode=0,stdout='{"same":1,"same":2}')]:
            with patch.object(m.subprocess,'run',return_value=result),self.assertRaises(ValueError) as error:m.api('owner/repo','')
            self.assertNotIn('sensitive',str(error.exception))


    def test_rollback_tag_at_older_main_ancestor_records_image_and_workflow_sources_separately(self):
        for repo in m.PUBLISHERS:
            fake = Github(repo, source='9'*40)
            self.assertEqual(fake.plan['sourceSha'], '9'*40)
            self.assertEqual(fake.plan['workflowSha'], 'a'*40)
            receipt = fake.approved()
            m.verify(fake.plan, receipt, fake.env, fake.get, fake.now)
            body = fake.data['issues/3']['body']
            self.assertIn('/commit/' + '9'*40, body); self.assertIn('/commit/' + 'a'*40, body); self.assertIn('derived from the tag', body)

    def test_off_main_moved_or_foreign_release_record_fails_before_credentials(self):
        fake = Github(source='9'*40); fake.data['compare/'+'9'*40+'...'+'a'*40] = {'status': 'diverged', 'merge_base_commit': {'sha': '8'*40}}
        with self.assertRaisesRegex(ValueError, 'must be on main'): m.provenance(fake.env, fake.get)
        fake = Github(); fake.data['git/ref/tags/v2026.10.26.20.00']['object']['sha'] = 'e'*40
        fake.data['compare/'+'e'*40+'...'+'a'*40] = {'status': 'ahead', 'merge_base_commit': {'sha': 'e'*40}}
        with self.assertRaisesRegex(ValueError, 'changed after resolution'): m.provenance(fake.env, fake.get)
        for edit in [lambda r: r.update(workflowSha='f'*40), lambda r: r.update(repository='other/repo'), lambda r: r.update(runId='13'),
                     lambda r: r.update(schema='other'), lambda r: r.update(sourceSha='main'), lambda r: r.update(releaseVersion='v2026.10.26.20.01')]:
            fake = Github(); row = json.loads(Path(fake.env['RELEASE_SOURCE_RECORD']).read_text()); edit(row)
            path = Path(RECORDS.name) / 'edited.json'; path.write_text(json.dumps(row)); fake.env['RELEASE_SOURCE_RECORD'] = str(path)
            with self.subTest(edit=edit), self.assertRaises(ValueError): m.provenance(fake.env, fake.get)
        fake = Github(); fake.env['RELEASE_SOURCE_RECORD'] = str(Path(RECORDS.name) / 'missing.json')
        with self.assertRaises(ValueError): m.provenance(fake.env, fake.get)
        fake = Github(); fake.env['RELEASE_VERSION'] = 'v2026.10.26.20.01'
        with self.assertRaises(ValueError): m.provenance(fake.env, fake.get)
        fake = Github(); fake.plan['workflowSha'] = '9'*40
        with self.assertRaises(ValueError): m.validatePlan(fake.plan, fake.env, fake.get)


    def test_issue_title_and_body_state_the_change_and_absence_of_aws_resources(self):
        for repo in m.PUBLISHERS:
            fake = Github(repo); tag = fake.plan['imageTag']
            fake.plan['sourceChanges'] = {'meaning': 'synthetic', 'pullRequests': [
                {'number': 2, 'title': 'Older change', 'url': 'https://github.com/' + repo + '/pull/2'},
                {'number': 5, 'title': 'Newest <change>', 'url': 'https://github.com/' + repo + '/pull/5'}]}
            receipt = fake.approved(); body = fake.data['issues/3']['body']
            headline = m.headline(fake.plan)
            self.assertEqual(headline, {'env': 'DEV', 'action': 'Publish image', 'subject': tag, 'change': 'Newest <change>'})
            self.assertEqual(m.issueTemplate.title(headline, '12'), 'DEV: Publish image ' + tag + ' — Newest &lt;change&gt; · run 12')
            self.assertRegex(m.issueTemplate.title(headline, '12'), r'^DEV: Publish image \S+ — .+ · run 12$')
            self.assertTrue(body.startswith('> [!IMPORTANT]\n> **DEV · Publish image ' + tag + '** — Newest &lt;change&gt;'))
            self.assertIn('| **Affected AWS resources** | **None** created, updated or replaced. |', body)
            self.assertIn('| **imageTag** | `' + tag + '` |', body)
            self.assertIn('| [#5](https://github.com/' + repo + '/pull/5) | Newest &lt;change&gt; | into main |', body)
            self.assertIn('| Release tag v2026.10.26.20.00 | resolved to source commit [aaaaaaaaaaaa](', body)
            self.assertIn('| Source selection | commit derived from the tag, not typed |', body)
            self.assertEqual(body, m.body(fake.plan, receipt))
            m.verify(fake.plan, receipt, fake.env, fake.get, fake.now)
        fake = Github(); self.assertNotIn('sourceChanges', fake.plan)
        self.assertEqual(m.headline(fake.plan)['change'], 'no merged PR associated with this source')
        fake.approved(); self.assertIn('Merged PR context was not captured', fake.data['issues/3']['body'])
        fake = Github(); fake.plan['sourceChanges'] = {'meaning': 'synthetic', 'pullRequests': []}
        self.assertEqual(m.headline(fake.plan)['change'], 'no merged PR associated with this source')
        fake.approved(); self.assertIn('No merged pull request is associated', fake.data['issues/3']['body'])


if __name__=='__main__':unittest.main()
