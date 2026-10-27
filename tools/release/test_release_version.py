from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import release_version as m

VERSION = 'v2026.10.26.20.00'


def archive(path, labels=None, mode='valid'):
    raw = json.dumps({'os': 'linux', 'architecture': 'amd64', 'config': {'Labels': labels or {}}}).encode()
    digest = hashlib.sha256(raw).hexdigest(); name = digest + '.json'
    if mode == 'path': name = 'f'*64 + '.json'
    rows = [{'Config': name, 'Layers': []}]
    if mode == 'multiple': rows *= 2
    contents = [('manifest.json', json.dumps(rows).encode()), (name, raw)]
    if mode == 'duplicate': contents.append(contents[1])
    with tarfile.open(path, 'w') as package:
        for key, value in contents:
            item = tarfile.TarInfo(key); item.size = len(value)
            package.addfile(item, io.BytesIO(value))
    return 'sha256:' + digest


class VersionTests(unittest.TestCase):
    def test_utc_reset_independent_app_counter_and_growth_past_99(self):
        now = datetime(2026, 10, 26, 16, tzinfo=timezone(timedelta(hours=-4)))
        self.assertEqual(m.nextVersion('domain', [], now), VERSION)
        tags = [VERSION, 'v2026.10.26.19.99', 'unrelated']
        self.assertEqual(m.nextVersion('domain', tags, now), 'v2026.10.26.20.01')
        self.assertEqual(m.nextVersion('web', [], now), VERSION)
        self.assertEqual(m.nextVersion('domain', ['v2026.10.26.20.99'], now), 'v2026.10.26.20.100')
        self.assertEqual(m.nextVersion('domain', tags, now+timedelta(hours=1)), 'v2026.10.26.21.00')
        self.assertEqual(m.nextVersion('web', [], datetime(2026, 12, 31, 23, tzinfo=timezone.utc)+timedelta(hours=1)), 'v2027.01.01.00.00')
        self.assertTrue(m.nextVersion('web', []).startswith('v'))
        with self.assertRaises(ValueError): m.nextVersion('web', [], datetime(2026, 1, 1))

    def test_exact_calendar_format_and_cross_app_binding(self):
        for app in m.APPS:
            value=m.metadata(app, VERSION)
            self.assertEqual(m.validate(value, app), value)
            self.assertEqual(value['imageTag'], app+'-'+VERSION)
            self.assertEqual(value['buildVersion'], VERSION[1:])
        for version in [None, 'latest', 'v2026.02.30.12.00', 'v2026.10.26.24.00', 'v2026.10.26.20.0', 'v2026.10.26.20.001', 'v2026.10.26.20.００']:
            with self.subTest(version=version), self.assertRaises(ValueError): m.metadata('web', version)
        with self.assertRaises(ValueError): m.metadata('unknown', VERSION)
        for key in m.FIELDS:
            value=m.metadata('web', VERSION);value[key]='wrong'
            with self.assertRaises(ValueError): m.validate(value, 'web')

    def test_lightweight_annotated_changed_missing_and_cyclic_git_tags(self):
        commit={'type':'commit','sha':'a'*40}; tag={'type':'tag','sha':'b'*40}
        self.assertEqual(m.gitTag(VERSION, 'a'*40, 'web', lambda p:{'object':commit})['releaseVersion'], VERSION)
        self.assertEqual(m.gitTag(VERSION, 'a'*40, 'web', lambda p:{'object':tag if '/ref/' in p else commit})['app'], 'web')
        for obj in [tag, {}, {'type':'tree','sha':'a'*40}, {'type':'commit','sha':'c'*40}]:
            with self.assertRaises(ValueError): m.gitTag(VERSION, 'a'*40, 'web', lambda p:{'object':obj})
        with self.assertRaises(ValueError): m.gitTag(VERSION, 'main', 'web', lambda p:{'object':commit})

    def test_built_config_labels_prevent_retagging_another_version_source_or_app(self):
        release={**m.metadata('web', VERSION), 'revision':'a'*40}
        labels={'org.opencontainers.image.version':VERSION,'org.opencontainers.image.revision':'a'*40,'com.lookahead.app':'web'}
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'image.tar';archive(path,labels);m.archiveLabels(path,release)
            for key in labels:
                archive(path,{**labels,key:'wrong'})
                with self.assertRaises(ValueError):m.archiveLabels(path,release)
            for mode in ['path','multiple','duplicate']:
                archive(path,labels,mode)
                with self.assertRaises(ValueError):m.archiveLabels(path,release)

    def test_read_only_cli_inventory_and_remote_tag_gate(self):
        env={'RELEASE_VERSION':VERSION,'GITHUB_SHA':'a'*40,'GITHUB_REPOSITORY':'owner/repo','GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch'}
        with patch.object(m.subprocess,'check_output',return_value='a\trefs/tags/'+VERSION+'\na\trefs/tags/'+VERSION+'^{}\n') as call, patch('builtins.print'):
            m.main(['next','--app','web']);self.assertEqual(call.call_args.args[0],['git','ls-remote','--tags','origin'])
        with patch.dict(os.environ,env,clear=True),patch.object(m.subprocess,'check_output',return_value=json.dumps({'object':{'type':'commit','sha':'a'*40}})),patch('builtins.print'):
            m.main(['check','--app','web'])
            for key,value in [('GITHUB_REF','refs/tags/'+VERSION),('GITHUB_EVENT_NAME','push'),('ECR_REPOSITORY','other')]:
                with patch.dict(os.environ,{key:value}),self.assertRaises(ValueError):m.main(['check','--app','web'])

    def test_resolve_freezes_commit_tag_object_and_main_and_recheck_detects_movement(self):
        main = 'c'*40
        data = {'git/ref/tags/'+VERSION: {'object': {'type': 'tag', 'sha': 'b'*40}}, 'git/tags/'+'b'*40: {'object': {'type': 'commit', 'sha': 'a'*40}},
                'compare/'+'a'*40+'...'+main: {'status': 'ahead', 'merge_base_commit': {'sha': 'a'*40}}}
        def get(p):
            if p not in data: raise ValueError('gh api failed: ' + p)
            return data[p]
        record = m.resolve(VERSION, get, main, 'web')
        self.assertEqual(record, {**m.metadata('web', VERSION), 'schema': 'release-source/v1', 'selector': VERSION, 'sourceSha': 'a'*40,
                                  'tagObjectSha': 'b'*40, 'mainSha': main})
        self.assertEqual(m.resolve(VERSION, get, main)['sourceSha'], 'a'*40)
        self.assertEqual(m.recheck(record, get, main, 'web')['sourceSha'], 'a'*40)
        data['compare/'+'a'*40+'...'+main] = {'status': 'identical', 'merge_base_commit': {'sha': 'a'*40}}
        self.assertEqual(m.recheck(record, get, main, 'web')['sourceSha'], 'a'*40)
        for compare in [{'status': 'diverged', 'merge_base_commit': {'sha': 'd'*40}}, {'status': 'behind', 'merge_base_commit': {'sha': main}},
                        {'status': 'ahead', 'merge_base_commit': {'sha': 'd'*40}}, {}]:
            data['compare/'+'a'*40+'...'+main] = compare
            with self.subTest(compare=compare), self.assertRaisesRegex(ValueError, 'on main'): m.resolve(VERSION, get, main, 'web')
        data['compare/'+'a'*40+'...'+main] = {'status': 'ahead', 'merge_base_commit': {'sha': 'a'*40}}
        with self.assertRaises(ValueError): m.resolve(VERSION, get, 'main', 'web')
        with self.assertRaises(ValueError): m.resolve('latest', get, main, 'web')
        # Moved lightweight tag, re-pointed annotated tag and a record from another tag all fail recheck.
        data['git/tags/'+'b'*40] = {'object': {'type': 'commit', 'sha': 'e'*40}}; data['compare/'+'e'*40+'...'+main] = {'status': 'ahead', 'merge_base_commit': {'sha': 'e'*40}}
        with self.assertRaisesRegex(ValueError, 'changed after resolution'): m.recheck(record, get, main, 'web')
        data['git/ref/tags/'+VERSION] = {'object': {'type': 'commit', 'sha': 'a'*40}}
        with self.assertRaisesRegex(ValueError, 'changed after resolution'): m.recheck(record, get, main, 'web')
        for bad in [{}, {**record, 'schema': 'other'}, {**record, 'selector': 'v2026.10.26.20.01'}]:
            with self.assertRaises(ValueError): m.recheck(bad, get, main, 'web')
        for obj in [{'type': 'tag', 'sha': 'b'*40}, {'type': 'tree', 'sha': 'a'*40}, {'type': 'commit', 'sha': 'short'}]:
            data['git/ref/tags/'+VERSION] = {'object': obj}; data['git/tags/'+'b'*40] = {'object': obj}
            with self.assertRaises(ValueError): m.peel(VERSION, get)

    def test_cli_resolve_writes_frozen_record_and_recheck_requires_same_workflow_run(self):
        main = 'a'*40
        env = {'RELEASE_VERSION': VERSION, 'GITHUB_SHA': main, 'GITHUB_REPOSITORY': 'owner/repo', 'GITHUB_REF': 'refs/heads/main',
               'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_RUN_ID': '5'}
        data = {'git/ref/tags/'+VERSION: {'object': {'type': 'commit', 'sha': 'b'*40}}, 'commits/main': {'sha': main},
                'compare/'+'b'*40+'...'+main: {'status': 'ahead', 'merge_base_commit': {'sha': 'b'*40}}}
        def api(args, text, timeout): return json.dumps(data[args[2].split('/', 3)[3]])
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp)/'release-source.json'; output = Path(temp)/'outputs'
            with patch.dict(os.environ, {**env, 'GITHUB_OUTPUT': str(output)}, clear=True), patch.object(m.subprocess, 'check_output', side_effect=api), patch('builtins.print'):
                m.main(['resolve', '--app', 'web', '--record', str(record)])
                saved = json.loads(record.read_text())
                self.assertEqual((saved['sourceSha'], saved['workflowSha'], saved['mainSha'], saved['runId']), ('b'*40, main, main, '5'))
                self.assertIn('source_sha='+'b'*40, output.read_text())
                with self.assertRaises(ValueError): m.main(['resolve', '--app', 'web', '--record', str(record)])
                m.main(['recheck', '--app', 'web', '--record', str(record)])
                with self.assertRaises(ValueError): m.main(['resolve', '--app', 'web'])
                data['git/ref/tags/'+VERSION]['object']['sha'] = 'c'*40; data['compare/'+'c'*40+'...'+main] = {'status': 'ahead', 'merge_base_commit': {'sha': 'c'*40}}
                with self.assertRaisesRegex(ValueError, 'changed after resolution'): m.main(['recheck', '--app', 'web', '--record', str(record)])
                data['commits/main'] = {'sha': 'f'*40}
                with self.assertRaisesRegex(ValueError, 'Main moved'): m.main(['recheck', '--app', 'web', '--record', str(record)])
            with patch.dict(os.environ, {**env, 'GITHUB_SHA': 'f'*40}, clear=True), patch.object(m.subprocess, 'check_output', side_effect=api):
                with self.assertRaisesRegex(ValueError, 'another workflow run'): m.main(['recheck', '--app', 'web', '--record', str(record)])
            data['git/ref/tags/'+VERSION]['object']['sha'] = 'b'*40; data['commits/main'] = {'sha': main}
            with patch.dict(os.environ, {**env, 'SOURCE_SHA': 'b'*40}, clear=True), patch.object(m.subprocess, 'check_output', side_effect=api), patch('builtins.print'):
                m.main(['check', '--app', 'web'])
            with patch.dict(os.environ, env, clear=True), patch.object(m.subprocess, 'check_output', side_effect=api):
                with self.assertRaises(ValueError): m.main(['check', '--app', 'web'])


if __name__ == '__main__':
    unittest.main()
