import copy
from datetime import date
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile

spec = importlib.util.spec_from_file_location('security_checks', Path(__file__).with_name('check.py'))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)

class SecurityGateTests(unittest.TestCase):
    def test_reviewed_cloud_findings_are_bound_and_other_findings_still_block(self):
        exceptions = checks.load_sast_exceptions()
        rules = [{'id': rule, 'properties': {'security-severity': '8.8'}}
                 for rule in sorted({key[0] for key in exceptions})]
        results = [{'ruleId': rule, 'locations': [{'physicalLocation': {
            'artifactLocation': {'uri': file}, 'region': {'startLine': 54}}}]}
            for rule, file in exceptions]
        document = {'runs': [{'tool': {'driver': {'name': 'CodeQL', 'rules': rules}},
            'invocations': [{'executionSuccessful': True}], 'results': results}]}
        used = set()
        self.assertEqual(checks.sarif_gate([document], exceptions=exceptions, used=used), 0)
        checks.require_all_exceptions_used(exceptions, used)
        changed = copy.deepcopy(document)
        changed['runs'][0]['results'][0]['locations'][0]['physicalLocation']['artifactLocation']['uri'] = 'src/main/java/Unreviewed.java'
        self.assertEqual(checks.sarif_gate([changed], exceptions=exceptions), 1)

    def test_unused_exception_reports_metadata_before_failing_closed(self):
        exceptions = checks.load_sast_exceptions()
        document = {'runs': [{'tool': {'driver': {'name': 'CodeQL', 'rules': [
            {'id': 'fixture', 'properties': {'security-severity': '8'}}]}},
            'invocations': [{'executionSuccessful': True}], 'results': []}]}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / 'codeql').mkdir()
            (output / 'codeql/java.sarif').write_text(checks.json.dumps(document))
            with patch.object(checks, 'OUT', output), patch.object(checks.sys, 'argv', ['check.py', 'sarif']), patch('builtins.print') as printed:
                with self.assertRaisesRegex(ValueError, '^SAST exception is stale or was not exercised$'):
                    checks.main()
            summary = checks.json.loads((output / 'sarif-summary.json').read_text())
            self.assertEqual(0, summary['sastFindingsRequiringAction'])
            self.assertEqual(len(exceptions), len(summary['unusedSastExceptions']))
            self.assertEqual(summary, checks.json.loads(printed.call_args.args[0]))
            for item in summary['unusedSastExceptions']:
                self.assertEqual({'ruleId', 'file', 'ticket'}, set(item))
            self.assertNotIn('rationale', checks.json.dumps(summary))
            self.assertNotIn('sourceSha256', checks.json.dumps(summary))

    def test_public_failure_reason_exposes_only_reviewed_constants(self):
        for reason in checks.SAFE_SAST_FAILURES:
            self.assertIn(reason, checks.failure_message(ValueError(reason)))
        for error in (ValueError('synthetic-secret'), ValueError('Expired SAST exception synthetic-secret'),
                      KeyError('synthetic-secret'), OSError('synthetic-secret')):
            self.assertNotIn('synthetic-secret', checks.failure_message(error))
            self.assertIn('inspect the step', checks.failure_message(error))

    def test_notification_rejection_has_safe_distinct_reason(self):
        run = {'tool': {'driver': {'name': 'CodeQL', 'rules': [{'id': 'fixture'}]}},
               'results': [], 'invocations': [{'executionSuccessful': True,
                   'toolExecutionNotifications': [{'level': 'warning', 'message': {'text': 'synthetic-secret'}}]}]}
        with self.assertRaisesRegex(ValueError, '^SAST notification requires review$'):
            checks.sarif_gate([{'runs': [run]}])
        run['invocations'][0]['toolExecutionNotifications'] = 'synthetic-secret'
        with self.assertRaisesRegex(ValueError, '^SAST notification inventory invalid$'):
            checks.sarif_gate([{'runs': [run]}])

    def test_repository_sast_exception_binding_is_current(self):
        checks.load_sast_exceptions()

    def test_trace_notifications_are_informational_but_warnings_still_block(self):
        run = {'tool': {'driver': {'name': 'CodeQL', 'rules': [{'id': 'fixture'}]}},
               'results': [], 'invocations': [{'executionSuccessful': True,
                   'toolExecutionNotifications': [{'level': 'none'}]}]}
        self.assertEqual(0, checks.sarif_gate([{'runs': [run]}]))
        run['invocations'][0]['toolExecutionNotifications'][0]['level'] = 'warning'
        with self.assertRaises(ValueError): checks.sarif_gate([{'runs': [run]}])

    def test_notification_summary_never_includes_messages_or_unknown_ids(self):
        summary = checks.notification_summary([
            {'level': 'warning', 'descriptor': {'id': 'java/diagnostics/extraction-warnings'},
             'message': {'text': 'synthetic-secret'}, 'properties': {'secret': 'synthetic-secret'}},
            {'level': 'synthetic-secret', 'descriptor': {'id': 'synthetic-secret'}},
            None])
        self.assertNotIn('synthetic-secret', str(summary))
        self.assertEqual('java/diagnostics/extraction-warnings', summary[0]['id'])
        self.assertEqual('invalid', summary[1]['level'])

    def test_resolved_bom_keeps_distinct_versions_and_deduplicates(self):
        a={'group':'org.example','name':'fixture','version':'1'}
        bom=checks.java_bom([a,a,{**a,'version':'2'}])
        self.assertEqual(2,len(bom['components']))
        with self.assertRaises(ValueError):checks.java_bom([])

    def test_sarif_clean_and_high_security_finding(self):
        run={'invocations':[{'executionSuccessful':True}],'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture','properties':{'security-severity':'8.1'}}]}},'results':[]}
        self.assertEqual(0,checks.sarif_gate([{'runs':[run]}]))
        run['results']=[{'ruleId':'fixture'}]
        self.assertEqual(1,checks.sarif_gate([{'runs':[run]}]))

    def test_sarif_missing_or_failed_analysis_is_not_clean(self):
        with self.assertRaises(ValueError):checks.sarif_gate([])
        run={'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture'}]}},'invocations':[{'executionSuccessful':False}]}
        with self.assertRaises(ValueError):checks.sarif_gate([{'runs':[run]}])
        run['invocations']=[{'executionSuccessful':True}];run['results']=[{'ruleId':'unrecognized'}]
        with self.assertRaises(ValueError):checks.sarif_gate([{'runs':[run]}])

    def test_sarif_requires_success_and_rejects_incomplete_analysis_notifications(self):
        clean={'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture'}]}},'results':[]}
        for invocations in (None, [], [{}], [{'executionSuccessful':False}], [{'executionSuccessful':'true'}], [None]):
            run={**clean,'invocations':invocations}
            with self.subTest(invocations=invocations), self.assertRaises(ValueError):
                checks.sarif_gate([{'runs':[run]}])
        with self.assertRaises(ValueError): checks.sarif_gate([{'runs':[clean]}])
        for field in ('toolExecutionNotifications','toolConfigurationNotifications'):
            for notification in ({'level':'error'},{'level':'warning'},{},{'level':'invalid'},None):
                run={**clean,'invocations':[{'executionSuccessful':True,field:[notification]}]}
                with self.subTest(field=field,notification=notification), self.assertRaises(ValueError):
                    checks.sarif_gate([{'runs':[run]}])
            run={**clean,'invocations':[{'executionSuccessful':True,field:[{'level':'note'}]}]}
            self.assertEqual(0,checks.sarif_gate([{'runs':[run]}]))
            run['invocations'].append({'executionSuccessful':False})
            with self.assertRaises(ValueError): checks.sarif_gate([{'runs':[run]}])

    def test_image_needs_os_and_package_coverage(self):
        report={'Metadata':{'OS':{'Family':'ubuntu'}},'Results':[{'Class':'os-pkgs','Packages':[{'Name':'fixture','Version':'1'}]}]}
        self.assertFalse(any(checks.trivy_gate(report,'image').values()))
        for bad in [{}, {'Results':[]}, {'Results':[{'Packages':[{}]}]}, {'Metadata':{'OS':{'Family':'ubuntu','EOSL':True}},'Results':[{'Packages':[{}]}]}]:
            with self.assertRaises(ValueError):checks.trivy_gate(bad,'image')

    def test_vulnerability_gate_keeps_unfixed_high_findings(self):
        report={'Results':[{'Class':'lang-pkgs','Type':'jar','Packages':[{'Name':'fixture','Version':'1'}],'Vulnerabilities':[{'Severity':'HIGH','FixedVersion':''},{'Severity':'LOW'}]}]}
        self.assertEqual(1,checks.trivy_gate(report,'sbom')['vulnerabilities'])

    def test_secret_always_fails_even_low_severity(self):
        report={'Results':[{'Class':'config','Target':'Dockerfile','MisconfSummary':{'Successes':1},'Secrets':[{'Severity':'LOW','Match':'synthetic'}]}]}
        counts=checks.trivy_gate(report,'config')
        self.assertEqual(1,counts['secrets'])
        self.assertNotIn('synthetic',str(counts))

    def test_config_coverage_and_high_misconfiguration(self):
        with self.assertRaises(ValueError):checks.trivy_gate({'Results':[]},'config')
        counts=checks.trivy_gate({'Results':[{'Class':'config','Target':'Dockerfile','MisconfSummary':{'Failures':1},'Misconfigurations':[{'Severity':'CRITICAL'}]}]},'config')
        self.assertEqual(1,counts['misconfigurations'])

    def test_sarif_nonfinite_severity_fails(self):
        for value in ('NaN', 'Infinity', '-1', '11'):
            run={'invocations':[{'executionSuccessful':True}],'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture','properties':{'security-severity':value}}]}},'results':[{'ruleId':'fixture'}]}
            with self.assertRaises(ValueError): checks.sarif_gate([{'runs':[run]}])

    def test_application_image_needs_java_and_os(self):
        os_result={'Class':'os-pkgs','Packages':[{'Name':'libc','Version':'1'}]}
        java_result={'Class':'lang-pkgs','Type':'jar','Packages':[{'Name':'spring-core','Version':'1'}]}
        for results in ([os_result], [java_result]):
            with self.assertRaises(ValueError):checks.trivy_gate({'Metadata':{'OS':{'Family':'ubuntu'}},'Results':results},'image',True)
        self.assertFalse(any(checks.trivy_gate({'Metadata':{'OS':{'Family':'ubuntu'}},'Results':[os_result,java_result]},'image',True).values()))

    def test_grouped_sarif_rules_are_resolved(self):
        run={'invocations':[{'executionSuccessful':True}],'tool':{'driver':{'name':'CodeQL'},'extensions':[{'rules':[{'id':'fixture','properties':{'security-severity':'8'}}]}]},'results':[{'rule':{'id':'fixture'}}]}
        self.assertEqual(1,checks.sarif_gate([{'runs':[run]}]))

    def test_sast_exception_is_exact_expiring_and_hash_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); source=root/'src/Security.java'; source.parent.mkdir(); source.write_text('fixture')
            policy=root/'exceptions.json'
            item={'ruleId':'fixture','file':'src/Security.java','sourceSha256':checks.hashlib.sha256(source.read_bytes()).hexdigest(),
                  'expiresAt':'2026-10-19','ticket':'DLV-804','rationale':'Reviewed synthetic fixture.'}
            policy.write_text(checks.json.dumps({'version':1,'exceptions':[item]}))
            exceptions=checks.load_sast_exceptions(policy,root,date(2026,9,19))
            run={'invocations':[{'executionSuccessful':True}],'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture','properties':{'security-severity':'8'}}]}},
                 'results':[{'ruleId':'fixture','locations':[{'physicalLocation':{'artifactLocation':{'uri':'src/Security.java'},'region':{'startLine':7}}}]}]}
            details=[]; used=set()
            self.assertEqual(0,checks.sarif_gate([{'runs':[run]}],details,exceptions,used))
            self.assertEqual('reviewed-exception',details[0]['disposition'])
            checks.require_all_exceptions_used(exceptions,used)
            source.write_text('changed')
            with self.assertRaises(ValueError):checks.load_sast_exceptions(policy,root,date(2026,9,19))
            source.write_text('fixture'); item['expiresAt']='2026-09-18'; policy.write_text(checks.json.dumps({'version':1,'exceptions':[item]}))
            with self.assertRaises(ValueError):checks.load_sast_exceptions(policy,root,date(2026,9,19))

    def test_source_scan_disables_external_scanner_overrides(self):
        report={'Results':[{'Class':'config','Target':'Dockerfile','MisconfSummary':{'Successes':1}}]}
        with tempfile.TemporaryDirectory() as directory, patch.object(checks,'OUT',Path(directory)), patch.object(checks,'run') as execute, patch.object(checks,'read',return_value=report), patch.dict(checks.os.environ,{'TRIVY_IGNORE_UNFIXED':'true','TRIVY_CONFIG':'untrusted.yml'}):
            checks.scan('config')
            command=execute.call_args.args[0]
            self.assertIn('--ignorefile',command)
            self.assertIn('--config',command)
            self.assertFalse(any(k.startswith('TRIVY_') for k in execute.call_args.kwargs['env']))

    def test_history_disables_inline_and_repository_suppressions(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(checks,'OUT',Path(directory)), patch.object(checks,'run') as execute, patch.object(checks.subprocess,'check_output',return_value='false'), patch.dict(checks.os.environ,{'GITLEAKS_CONFIG':'untrusted.toml'}):
            checks.history()
            command=execute.call_args.args[0]
            self.assertIn('--ignore-gitleaks-allow',command)
            self.assertIn('--gitleaks-ignore-path',command)
            self.assertFalse(any(k.startswith('GITLEAKS_') for k in execute.call_args.kwargs['env']))

    def test_both_base_stages_must_be_pinned(self):
        valid='FROM eclipse-temurin:21-jdk@sha256:'+64*'a'+' AS build\nFROM eclipse-temurin:21-jre@sha256:'+64*'b'+' AS runtime'
        self.assertEqual(2,len(checks.base_images(valid)))
        self.assertEqual(2,len(checks.base_images(valid.replace("21-jdk@", "21-jdk-noble@").replace("21-jre@", "21-jre-noble@"))))
        self.assertEqual(2,len(checks.base_images(valid.replace("21-jdk@", "21-jdk-noble@").replace("21-jre@", "21-jre-alpine@"))))
        for bad in [valid.replace("21-jdk@", "21-jdk-unknown@"), valid.replace("21-jdk@", "21-jdk-alpine@"), valid.replace("21-jre@", "21-jre-unknown@"), "\n".join(reversed(valid.splitlines()))]:
            with self.assertRaises(ValueError):checks.base_images(bad)
        for bad in ['FROM eclipse-temurin:21-jre', valid.splitlines()[0], valid.replace('21-jdk@sha256:'+64*'a','21-jdk'), valid.replace('21-jre@sha256:'+64*'b','21-jre-alpine'), valid.replace('eclipse-temurin:21-jre@','untrusted/java:21-jre@')]:
            with self.assertRaises(ValueError):checks.base_images(bad)

if __name__=='__main__':unittest.main()
