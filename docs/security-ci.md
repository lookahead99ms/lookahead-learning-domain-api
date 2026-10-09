# Security CI

The existing `verify` job builds and tests this application, runs CodeQL security-extended analysis, checks fetched Git history for secrets, scans resolved dependencies and configuration, and scans both digest-pinned Temurin bases plus the built application image. It does not deploy anything. Python here is one-shot development tooling, not an application service.

## Local checks

From this repository with Java 21, Python 3 and Docker available:

```sh
python3 -m unittest discover -s tools/security -p 'test_*.py'
python3 tools/security/check.py install --tool trivy
python3 tools/security/check.py install --tool gitleaks
python3 tools/security/check.py history
python3 tools/security/check.py dependencies --build-system maven
python3 tools/security/check.py sbom
python3 tools/security/check.py config
docker build --pull --tag lookahead-domain-api:security-ci-local .
python3 tools/security/check.py images --image lookahead-domain-api:security-ci-local
```

The installer verifies pinned archive checksums. Supported tool hosts are Linux AMD64 and macOS ARM64. Network access is required for dependency resolution, scanner databases and rule updates. History checks reject shallow repositories; fetch the complete intended history first. They inspect fetched refs, not inaccessible remote history.

## Gate and reporting policy

HIGH/CRITICAL dependency or configuration findings, unknown severity, any secret finding, scanner errors, or missing required package/configuration coverage fail the check. Every CodeQL run must declare a successful invocation; absent or failed invocations and warning/error execution or configuration notifications fail closed. CodeQL security severity 7–10 fails; warning/error findings without severity also fail. Lower severity findings stay in local reports. Existing application test failures remain blocking. No automatic suppression or auto-merge is configured. Scanner environment overrides, repository ignore files and inline Gitleaks allow markers cannot disable these scans. A reviewed CodeQL false positive may use only the repository's explicit SAST exception contract: exact rule and repository-relative file, an unexpired review date, an owning ticket and rationale, and the SHA-256 digest of the reviewed source. Source drift, expiry, duplicate records, unmatched findings, and stale unused records all fail closed.

Reports and SBOMs are written under ignored `.codex-scratch/security/`. Raw source/secret reports are not uploaded by this workflow; CodeQL database and SARIF uploads are disabled. Secret matches are redacted in history output and omitted from summary logs. GitHub job logs and ordinary build output still follow repository visibility. Do not put actual credentials into test fixtures.

CodeQL execution occurs inside GitHub Actions around a clean build, using the CLI/query bundle linked to the pinned action. Local SARIF parser fixtures verify the gate, not analyzer completeness. CodeQL licensing permits this public repository; review licensing before applying this setup to a private repository. Rules inventory is not proof that every source file was analyzed.

## Coverage boundaries

Gradle resolution includes project/buildscript resolvable configurations. Maven scans the resolved project dependency tree across scopes; it does not claim complete Maven plugin dependency coverage. Container scans separately check OS packages and Java package inventory in the final application image. A clean report only concerns supported packages and the scanner database at scan time. It does not prove business authorization, payment safety or live AWS security. Application tests supplement SAST; real cross-service/account isolation probes need their documented private integration environment.

Dependabot proposes weekly Friday 11 AM America/New_York updates for the native build system, Docker bases and Actions. A base update requires review, rebuild, tests and rescanning; existing deployed containers do not update automatically. Enabling remote checks requires the user-owned Git cycle. Keep required status checks mapped to the actual `verify` job; do not add invented status names. Hosted execution, branch protection and failure-email delivery need their own verification.

## Temporary Tomcat override (DLV-916)

On 2026-09-19 Maven Central lists Boot 4.1.1 as the latest stable 4.1 release; its BOM manages Tomcat 11.0.24. This application retains Boot 4.1.1 and overrides embedded Tomcat to 11.0.25, the Apache-published fixed release. Gradle uses a group-scoped resolution rule; Maven uses the Boot parent `tomcat.version` property. Remove the override when a compatible stable Boot patch manages 11.0.25 or newer, then repeat dependency, security, contract and image checks. Do not switch to a milestone release solely to remove this override.

Sources: [Boot Maven metadata](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml), [Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom), [Apache fixes](https://tomcat.apache.org/security-11.html).

## Temporary Jackson override (DLV-916)

On 2026-09-29 the resolved SBOM gate reported high-severity CVE-2026-68497.
Boot 4.1.1 is still the latest stable 4.1 patch and manages affected Jackson
versions. The Maven parent manages Jackson 2.21.5 and 3.1.5. The `jackson-2-bom.version` and `jackson-bom.version` properties now select the corresponding 2.21.7 and 3.1.7 patch BOMs after the October 8 scan. Both Jackson lines are present because Springdoc/Swagger uses Jackson 2 while Boot/Flyway uses Jackson 3.
Remove these overrides when a stable Boot patch manages fixed Jackson versions;
then repeat complete dependency, native test, protocol, package and image checks.
Do not narrow the SBOM to runtime dependencies: build tooling is in scope.

Sources: [Jackson 2.21.6 release](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.6),
[Jackson 3.1.6 release](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.6),
[Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom).

## Maven plugin classpath patches (DLV-918)

Project BOMs do not constrain Maven plugin dependencies. The ordinary
`clean verify` plugins therefore carry narrow dependency overrides: Boot uses
Jackson 3.1.7, Commons Lang 3.18.0 and Plexus Utils 3.6.1; JaCoCo and resources
use Plexus Utils 3.6.1; clean and compiler use Plexus Utils 4.0.3; compiler uses
Commons IO 2.14.0. Plugin versions and the existing Tomcat override are retained.

Infra separately resolves the project tree (all scopes) and Maven's plugin
inventory, including inherited plugin management. The latter includes optional
plugins that `clean verify` does not execute. Findings in those tools remain
visible and must be reviewed before using their goals; they are not reported as
runtime libraries. The private project coordinate and local artifact paths are
excluded from OSV input. Application tests, complete dependency scans and final
image checks are separate gates.

References: [Jackson core advisory](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf),
[Jackson databind advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24),
[Apache Commons IO fix](https://commons.apache.org/proper/commons-io/security.html),
[Plexus Utils advisory](https://github.com/advisories/GHSA-6fmv-xxpf-w3cw).
