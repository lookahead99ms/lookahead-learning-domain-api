#!/usr/bin/env python3
"""One-shot CI tooling. No credentials, private checkout or runtime service required."""
import argparse
from datetime import date
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tarfile
import urllib.request
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.codex-scratch/security'
PINS = Path(__file__).with_name('tools.json')
SAST_EXCEPTIONS = Path(__file__).with_name('sast-exceptions.json')


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def run(args, **kwargs):
    subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def install(name):
    pins = read(PINS)[name]
    key = platform.system() + '-' + platform.machine().lower()
    aliases = {'Linux-x86_64': 'linux-amd64', 'Darwin-arm64': 'darwin-arm64'}
    target = pins['archives'][aliases[key]]
    directory = OUT / 'bin'
    directory.mkdir(parents=True, exist_ok=True)
    archive = OUT / (name + '.tar.gz')
    with urllib.request.urlopen(target['url'], timeout=60) as response:
        payload = response.read(200 * 1024 * 1024 + 1)
    if len(payload) > 200 * 1024 * 1024 or hashlib.sha256(payload).hexdigest() != target['sha256']:
        raise ValueError('Tool archive checksum/size mismatch')
    archive.write_bytes(payload)
    with tarfile.open(archive, 'r:gz') as package:
        entry = package.getmember(name)
        if not entry.isfile() or entry.size > 200 * 1024 * 1024:
            raise ValueError('Invalid tool archive entry')
        executable = directory / name
        executable.write_bytes(package.extractfile(entry).read())
        executable.chmod(0o700)
    print(name + ' downloaded and checksum verified')


def java_bom(components):
    result = {}
    for item in components:
        if not all(isinstance(item.get(k), str) and item[k] for k in ('group', 'name', 'version')):
            raise ValueError('Unresolved dependency coordinates')
        purl = 'pkg:maven/' + quote(item['group'], safe='.') + '/' + quote(item['name'], safe='') + '@' + quote(item['version'], safe='')
        result[purl] = {'type': 'library', **item, 'purl': purl, 'bom-ref': purl}
    if not result:
        raise ValueError('Empty dependency inventory')
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.5', 'version': 1, 'components': list(result.values())}


def dependencies(system):
    components = []
    if system == 'maven':
        tree = OUT / 'maven-tree.json'
        run(['./mvnw', '--batch-mode', '--no-transfer-progress',
             'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree',
             '-DoutputType=json', '-DoutputFile=' + str(tree)])
        def visit(node):
            for child in node.get('children', []):
                components.append({'group': child['groupId'], 'name': child['artifactId'], 'version': child['version']})
                visit(child)
        visit(read(tree))
    else:
        output = OUT / 'gradle-dependencies.json'
        run(['./gradlew', '--no-daemon', '--console=plain', '-I', 'tools/security/resolved-dependencies.gradle',
             'securityResolvedDependencies'], env={**os.environ, 'SECURITY_DEPENDENCY_OUTPUT': str(output)})
        resolved = read(output)
        if not resolved.get('configurations'):
            raise ValueError('No resolved Gradle configurations')
        components = resolved['components']
    write(OUT / 'dependencies.cdx.json', java_bom(components))
    print('Resolved dependency inventory: ' + str(len(components)) + ' coordinates')


def trivy_gate(document, kind, require_java=False):
    results = document.get('Results')
    if not isinstance(results, list):
        raise ValueError('Missing scanner results')
    def has_packages(result):
        packages = result.get('Packages')
        return isinstance(packages, list) and bool(packages) and all(
            isinstance(p, dict) and isinstance(p.get('Name'), str) and p['Name']
            and isinstance(p.get('Version'), str) and p['Version'] for p in packages)
    if kind in ('image', 'sbom') and not any(has_packages(result) for result in results):
        raise ValueError('Package coverage absent')
    if kind == 'image':
        os_info = document.get('Metadata', {}).get('OS', {})
        if not os_info.get('Family') or not any(r.get('Class') == 'os-pkgs' and has_packages(r) for r in results):
            raise ValueError('OS package coverage absent')
        if os_info.get('EOSL'):
            raise ValueError('Unsupported OS lifecycle')
    if (kind == 'image' and require_java) or kind == 'sbom':
        if not any(r.get('Class') == 'lang-pkgs' and r.get('Type') in ('jar', 'maven', 'gradle') and has_packages(r) for r in results):
            raise ValueError('Java package coverage absent')
    if kind == 'config' and not any(
            r.get('Class') == 'config' and r.get('Target')
            and sum(r.get('MisconfSummary', {}).get(k, 0) for k in ('Successes', 'Failures')) > 0
            for r in results):
        raise ValueError('Configuration check coverage absent')
    counts = {'vulnerabilities': 0, 'misconfigurations': 0, 'secrets': 0}
    for result in results:
        for field, counter in [('Vulnerabilities', 'vulnerabilities'), ('Misconfigurations', 'misconfigurations'), ('Secrets', 'secrets')]:
            for finding in result.get(field) or []:
                if field != 'Secrets' and finding.get('Severity') not in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN'):
                    raise ValueError('Missing or invalid finding severity')
                if field == 'Secrets' or finding.get('Severity') in ('HIGH', 'CRITICAL', 'UNKNOWN'):
                    counts[counter] += 1
    return counts


def scan(kind, target=None, require_java=False):
    trivy = str(OUT / 'bin/trivy')
    report = OUT / (kind + '-' + hashlib.sha256((target or '.').encode()).hexdigest()[:12] + '.json')
    controlled = OUT / 'scanner-config'
    controlled.mkdir(parents=True, exist_ok=True)
    (controlled / 'trivy.yaml').write_text('{}\n')
    (controlled / 'ignore').write_text('')
    clean_env = {k: v for k, v in os.environ.items() if not k.startswith('TRIVY_')}
    common = ['--config', str(controlled / 'trivy.yaml'), '--ignorefile', str(controlled / 'ignore'), '--format', 'json', '--output', str(report), '--exit-code', '0', '--timeout', '15m', '--quiet']
    if kind == 'sbom':
        args = ['sbom', '--scanners', 'vuln', '--list-all-pkgs', *common, str(OUT / 'dependencies.cdx.json')]
    elif kind == 'image':
        args = ['image', '--scanners', 'vuln', '--list-all-pkgs', *common, target]
    else:
        args = ['fs', '--scanners', 'misconfig,secret', '--skip-dirs', '.git,.codex-scratch,build,target', *common, '.']
    run([trivy, *args], env=clean_env)
    counts = trivy_gate(read(report), kind, require_java)
    # Never print secret matches, source excerpts or raw reports into public logs.
    print(json.dumps({'scan': kind, 'countsRequiringAction': counts}))
    if any(counts.values()):
        raise ValueError('Security findings require review')


def base_images(text):
    images = re.findall(r'^FROM\s+(\S+)', text, re.M | re.I)
    if len(images) != 2 or any(not re.fullmatch(r'eclipse-temurin:21-(?:jdk|jre)@sha256:[a-f0-9]{64}', image) for image in images):
        raise ValueError('Both Java 21 builder/runtime bases must be digest pinned')
    if ':21-jdk@' not in images[0] or ':21-jre@' not in images[1]:
        raise ValueError('Expected JDK builder followed by JRE runtime')
    return images


def load_sast_exceptions(path=SAST_EXCEPTIONS, root=ROOT, today=None):
    if not Path(path).exists():
        return {}
    document = read(path)
    if document.get('version') != 1 or not isinstance(document.get('exceptions'), list):
        raise ValueError('Invalid SAST exception contract')
    today = today or date.today()
    accepted = {}
    for item in document['exceptions']:
        required = ('ruleId', 'file', 'sourceSha256', 'expiresAt', 'ticket', 'rationale')
        if not all(isinstance(item.get(field), str) and item[field].strip() for field in required):
            raise ValueError('Incomplete SAST exception')
        relative = Path(item['file'])
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('SAST exception file must be repository-relative')
        try:
            expiry = date.fromisoformat(item['expiresAt'])
        except ValueError as error:
            raise ValueError('Invalid SAST exception expiry') from error
        if expiry < today:
            raise ValueError('Expired SAST exception')
        source = root / relative
        if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != item['sourceSha256']:
            raise ValueError('SAST exception source changed or is missing')
        key = (item['ruleId'], relative.as_posix())
        if key in accepted:
            raise ValueError('Duplicate SAST exception')
        accepted[key] = item
    return accepted


def sarif_path(uri):
    if not isinstance(uri, str) or not uri:
        return None
    value = uri.replace('\\', '/')
    marker = '/github/workspace/'
    if marker in value:
        value = value.split(marker, 1)[1]
    return value.removeprefix('file://').lstrip('/') if value.startswith('file:///github/workspace/') else value.lstrip('./')


def require_all_exceptions_used(exceptions, used):
    if set(exceptions) != used:
        raise ValueError('SAST exception is stale or was not exercised')


def sarif_gate(documents, actionable=None, exceptions=None, used=None):
    count = 0
    exceptions = exceptions or {}
    used = used if used is not None else set()
    runs = [run for doc in documents for run in doc.get('runs', [])]
    if not runs:
        raise ValueError('CodeQL report missing runs')
    for item in runs:
        if item.get('tool', {}).get('driver', {}).get('name') != 'CodeQL':
            raise ValueError('Unexpected SAST producer')
        for invocation in item.get('invocations', []):
            if invocation.get('executionSuccessful') is False or any(n.get('level') == 'error' for n in invocation.get('toolExecutionNotifications', [])):
                raise ValueError('SAST execution incomplete')
        components = [item['tool']['driver'], *item['tool'].get('extensions', [])]
        rules = {rule['id']: rule for component in components for rule in component.get('rules', [])}
        if not rules:
            raise ValueError('SAST rule inventory absent')
        if not isinstance(item.get('results'), list):
            raise ValueError('SAST result inventory absent')
        for finding in item['results']:
            rule_id = finding.get('ruleId') or finding.get('rule', {}).get('id')
            rule = rules.get(rule_id)
            if rule is None:
                raise ValueError('Unknown SAST result rule')
            score = rule.get('properties', {}).get('security-severity')
            requires_action = False
            if score is not None:
                numeric_score = float(score)
                if not math.isfinite(numeric_score) or not 0 <= numeric_score <= 10:
                    raise ValueError('Invalid SAST security severity')
                requires_action = numeric_score >= 7
                severity = str(score)
            else:
                severity = finding.get('level', rule.get('defaultConfiguration', {}).get('level', 'warning'))
                requires_action = severity in ('error', 'warning')
            if requires_action:
                location = (finding.get('locations') or [{}])[0].get('physicalLocation', {})
                artifact = sarif_path(location.get('artifactLocation', {}).get('uri'))
                line = location.get('region', {}).get('startLine')
                exception = exceptions.get((rule_id, artifact))
                if exception is None:
                    count += 1
                else:
                    used.add((rule_id, artifact))
                if actionable is not None:
                    actionable.append({'ruleId': rule_id, 'severity': severity, 'file': artifact, 'line': line,
                                       'disposition': 'reviewed-exception' if exception else 'requires-action',
                                       'ticket': exception.get('ticket') if exception else None})
    return count


def history():
    if subprocess.check_output(['git', 'rev-parse', '--is-shallow-repository'], cwd=ROOT, text=True).strip() != 'false':
        raise ValueError('History scanning requires a full fetched repository')
    controlled = OUT / 'scanner-config'
    controlled.mkdir(parents=True, exist_ok=True)
    (controlled / 'gitleaks.toml').write_text('[extend]\nuseDefault = true\n')
    (controlled / 'gitleaksignore').write_text('')
    clean_env = {k: v for k, v in os.environ.items() if not k.startswith('GITLEAKS_')}
    run([str(OUT / 'bin/gitleaks'), 'git', '--config', str(controlled / 'gitleaks.toml'),
         '--gitleaks-ignore-path', str(controlled / 'gitleaksignore'), '--ignore-gitleaks-allow', '--redact=100', '--no-banner', '--log-opts=--all',
         '--report-format', 'json', '--report-path', str(OUT / 'history-redacted.json'), '.'], env=clean_env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install', 'dependencies', 'sbom', 'config', 'images', 'sarif', 'history'])
    parser.add_argument('--tool', choices=['trivy', 'gitleaks'], default='trivy')
    parser.add_argument('--build-system', choices=['gradle', 'maven'])
    parser.add_argument('--image')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.action == 'install': install(args.tool)
    elif args.action == 'dependencies': dependencies(args.build_system)
    elif args.action in ('sbom', 'config'): scan(args.action)
    elif args.action == 'images':
        if not args.image: raise ValueError('Built application image is required')
        targets = [(image, False) for image in base_images((ROOT / 'Dockerfile').read_text())]
        targets.append((args.image, True))
        failed = False
        for target, require_java in targets:
            try:
                scan('image', target, require_java=require_java)
            except (ValueError, KeyError, OSError, subprocess.CalledProcessError):
                failed = True
                print('Image scan requires attention: ' + target, file=sys.stderr)
        if failed: raise ValueError('Image findings or coverage failures require review')
    elif args.action == 'history': history()
    elif args.action == 'sarif':
        actionable = []
        exceptions = load_sast_exceptions()
        used = set()
        count = sarif_gate([read(p) for p in (OUT / 'codeql').glob('*.sarif')], actionable, exceptions, used)
        require_all_exceptions_used(exceptions, used)
        write(OUT / 'sarif-summary.json', {'findingsRequiringAction': actionable})
        print(json.dumps({'sastFindingsRequiringAction': count, 'findings': actionable}))
        if count: raise ValueError('SAST findings require review')

if __name__ == '__main__':
    try: main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print('Security check failed: ' + type(error).__name__ + '; inspect the step and local report.', file=sys.stderr)
        sys.exit(1)
