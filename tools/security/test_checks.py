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
    def test_resolved_bom_keeps_distinct_versions_and_deduplicates(self):
        a={'group':'org.example','name':'fixture','version':'1'}
        bom=checks.java_bom([a,a,{**a,'version':'2'}])
        self.assertEqual(2,len(bom['components']))
        with self.assertRaises(ValueError):checks.java_bom([])

    def test_sarif_clean_and_high_security_finding(self):
        run={'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture','properties':{'security-severity':'8.1'}}]}},'results':[]}
        self.assertEqual(0,checks.sarif_gate([{'runs':[run]}]))
        run['results']=[{'ruleId':'fixture'}]
        self.assertEqual(1,checks.sarif_gate([{'runs':[run]}]))

    def test_sarif_missing_or_failed_analysis_is_not_clean(self):
        with self.assertRaises(ValueError):checks.sarif_gate([])
        run={'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture'}]}},'invocations':[{'executionSuccessful':False}]}
        with self.assertRaises(ValueError):checks.sarif_gate([{'runs':[run]}])
        run.pop('invocations');run['results']=[{'ruleId':'unrecognized'}]
        with self.assertRaises(ValueError):checks.sarif_gate([{'runs':[run]}])

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
            run={'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture','properties':{'security-severity':value}}]}},'results':[{'ruleId':'fixture'}]}
            with self.assertRaises(ValueError): checks.sarif_gate([{'runs':[run]}])

    def test_application_image_needs_java_and_os(self):
        os_result={'Class':'os-pkgs','Packages':[{'Name':'libc','Version':'1'}]}
        java_result={'Class':'lang-pkgs','Type':'jar','Packages':[{'Name':'spring-core','Version':'1'}]}
        for results in ([os_result], [java_result]):
            with self.assertRaises(ValueError):checks.trivy_gate({'Metadata':{'OS':{'Family':'ubuntu'}},'Results':results},'image',True)
        self.assertFalse(any(checks.trivy_gate({'Metadata':{'OS':{'Family':'ubuntu'}},'Results':[os_result,java_result]},'image',True).values()))

    def test_grouped_sarif_rules_are_resolved(self):
        run={'tool':{'driver':{'name':'CodeQL'},'extensions':[{'rules':[{'id':'fixture','properties':{'security-severity':'8'}}]}]},'results':[{'rule':{'id':'fixture'}}]}
        self.assertEqual(1,checks.sarif_gate([{'runs':[run]}]))

    def test_sast_exception_is_exact_expiring_and_hash_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); source=root/'src/Security.java'; source.parent.mkdir(); source.write_text('fixture')
            policy=root/'exceptions.json'
            item={'ruleId':'fixture','file':'src/Security.java','sourceSha256':checks.hashlib.sha256(source.read_bytes()).hexdigest(),
                  'expiresAt':'2026-10-19','ticket':'DLV-804','rationale':'Reviewed synthetic fixture.'}
            policy.write_text(checks.json.dumps({'version':1,'exceptions':[item]}))
            exceptions=checks.load_sast_exceptions(policy,root,date(2026,9,19))
            run={'tool':{'driver':{'name':'CodeQL','rules':[{'id':'fixture','properties':{'security-severity':'8'}}]}},
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
        for bad in ['FROM eclipse-temurin:21-jre', valid.splitlines()[0], valid.replace('21-jdk@sha256:'+64*'a','21-jdk')]:
            with self.assertRaises(ValueError):checks.base_images(bad)

if __name__=='__main__':unittest.main()
