# Look Ahead Learning Domain API

## Audit retention and Local fixture provisioning

The local source candidate adds forward migration V15; V1–V14 remain immutable.
Runtime UPDATE, DELETE and TRUNCATE are revoked on purchase, author-review, staff
and account-maintenance audit tables, including PUBLIC grants. Unexpected effective
inherited or column-level audit mutation rights fail the migration. Cloud connection
initialization also rejects audit mutation privileges and executable public-schema
SECURITY DEFINER functions; the existing runtime role/ownership checks remain.

Managed-account data erasure can retain purchase audit without attempting audit
DELETE, and preserves subject/lifetime-creation records. Local synthetic purge
requires an explicit migrator job setting `LOOKAHEAD_LOCAL_AUDIT_PURGE=true` with
`LOOKAHEAD_ENVIRONMENT=local`, the `lookahead_platform` database, and an allowed
loopback or `domain-api-db` host. Infra's generated Local migration job supplies
this setting; cloud templates do not. The provisioner restores only membership
audit DELETE, still limited by the existing synthetic-account trigger. A Local
job without that opt-in retains audit and account cleanup still succeeds.

DEV managed identities and owner deletion remain disabled pending the reviewed
Cognito operator/permissions and cross-system recovery work. This SQL preparation
does not activate them, implement PROD self-deletion, or change HTTP payloads.
Run the documented database-backed coverage gate below; new audit tests exercise
V14→V15, unchanged checksums, denied audit mutation, retained audit during cleanup,
retry, purchase dependencies and inherited/function privilege bypasses.

This independently packaged Java/Spring Boot application owns learning business
rules: content access, current course grants, plans, progress, support receipts
and local author capability.
Local uses the separate Identity application. DEV/PROD use Cognito and Domain-owned
durable sign-ins; see [cloud authentication](docs/cloud-authentication.md). Domain
has no browser login controller, authorization-server library, OAuth state
repository, signing private key, or Identity database connection.

The candidate does not migrate an existing combined database automatically.
Legacy migrations and the running application remain separate. Infrastructure
provisions separate runtime and migration roles with access only to the Domain
API database. Runtime credentials must not own tables, create schemas, or connect
to the Identity database. Installed storage identifiers are retained through the
explicit [legacy storage adapter](docs/storage-compatibility.md).

Build from this repository with Java21 and `./mvnw verify` (Windows:
`mvnw.cmd verify`). The Maven wrapper downloads the pinned Maven distribution;
dependencies come from Maven Central. No sibling repository, private curriculum,
Toolkit library or preinstalled local artifact is needed to build and test.
On Unix, install `unzip` so the wrapper uses the checksum-pinned ZIP distribution.
The application Dockerfile supplies it in the build stage.
The Boot parent still owns the dependency baseline; temporary Jackson 2/3 patch
BOM overrides are documented in [Security CI](docs/security-ci.md).
The executable service artifact is `target/lookahead-domain-api-<version>.jar`;
its main class remains `com.lookahead.domain.DomainApiApplication`. The old
`DomainApiMigration` and `DomainApiBootstrap` executable entrypoints are retired:
they reject invocation before reading credentials or connecting to PostgreSQL.
Use Infra's separately approved **Flyway Utils · Run** workflow for DEV, and its
explicit Flyway utility selection for Local orchestration. Runtime Flyway remains
disabled in the application service.

Domain still owns the unchanged SQL sources in `db/domain` and the Java V5 migration.
Retiring a launcher does not move/delete migration sources or change installed
history. `DomainApiMigration.configure`, role/target validation and bootstrap
fixture helpers remain for existing tests and account administration. Their presence
is not a supported standalone migration command. Historical cutover fixtures use a
retained pre-retirement Domain JAR; new artifacts cannot run the old launcher.

## Blaze catalog ID (DLV-931)

The catalog whose data ID was `look-ahead` is now `blaze`: topic and course scopes
`blaze:<courseId>`, content IDs `blaze:<courseId>:<lesson>`, topic `path` `blaze`, routes
`/blaze/...` and content assets under `/content/blaze/`. The platform keeps its Look Ahead
name: packages, headers, environment variables, database objects and browser storage keys
such as `look-ahead.study-plan.v1` are unchanged.

- `CatalogIds` holds the one rule set. Requests from old browsers, saved local plans and a
  publication built before the rename are read with legacy `look-ahead` IDs mapped to
  `blaze`: the trusted account catalog, protected-content manifest scopes and content IDs,
  ready-made templates (after their digest is checked against the published bytes), account
  grants, and plan create, import, activity and version bodies. Prose such as plan names,
  goals, titles and notes is never rewritten. Retry receipts still hash the request exactly
  as sent, so an old tab retrying the same request gets its original response.
- `V5__blaze_catalog_id` (Java, `com.lookahead.domain.migration`) moves stored
  `account_grants` (an account holding both forms keeps the longer-lasting grant) and
  rewrites `plans.progress`, `plan_versions.snapshot/membership/provenance/recovery`,
  `plan_activity.payload` and `mutation_receipts.response`. A rewritten snapshot gets its
  `snapshot_digest` recomputed with `PlanJson`, so the stored digest still matches. Rows
  without catalog IDs are untouched; `request_hash`, `catalogVersion` pins and the
  append-only author review events are left as they are.
- `BlazeCatalogMigrationDatabaseTest` runs the production Flyway configuration on the
  disposable review fixture: V1–V4, legacy data, then V5, and checks the migrated plan through
  `PlanService`. `CatalogIdsTest` and `LegacyCatalogPlanTest` cover the request rules.
- The platform review page "Look Ahead Catalog" is now "Blaze Catalog"; an old tab's value
  is accepted and stored items read with the new name.

The local folder and GitHub repository are `lookahead-learning-domain-api`:
[lookahead99ms/lookahead-learning-domain-api](https://github.com/lookahead99ms/lookahead-learning-domain-api).
Java packages, artifact
names, health identifiers and environment variables now use Domain API naming.
Existing database objects and grants remain unchanged; no data migration or
runtime deployment is implied by the source rename.

## Independent build and container

The [DEV activation contract](docs/dev-activation.md) inventories the application
configuration, immutable injected-secret selectors, mTLS/health ports and exact
runtime versus execution-role requirements. It separates locally checked source
behavior from pending cloud startup and cross-service verification.

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
docker build -t lookahead-domain-api:local .
```

The Docker build runs the default test suite before packaging; database suites
require their explicit disposable fixtures. Its context is this repository only. The image runs Java as UID10001, serves on port8080 and checks
`/actuator/health/readiness`. Configuration and data are mounted or supplied at
runtime; the image contains no curriculum or credentials. Transport records are
owned by this application, with JSON contract tests preserving their API shape.

An independent build is not a database-free service. To run the JAR or container,
provide the Domain PostgreSQL database with its migrated schema/restricted role,
the Local Identity verification/JWKS endpoint or cloud Cognito settings, secrets and a validated account catalog
using the configuration below. The redistributable synthetic catalog is in
`src/test/resources/accounts/`; it is sufficient for tests, not a production
curriculum. Integrated local orchestration remains infrastructure-owned.

## Required release coverage gate

Database fixture maintenance: the Blaze migration test explicitly targets V5
(after seeding V4 data), so later migrations do not change its one-migration
assertion. The author-review HTTP fixture installs V6 and imports the real staff
access repository/service required by account and preview authorization. Keep
these test contexts aligned with application dependencies; do not bypass staff
checks to make isolated contexts start.


CI and release validation run `./mvnw -Pcoverage clean verify` with both disposable
PostgreSQL fixtures configured. JaCoCo 0.8.15 measures every production class,
without source exclusions, and fails verification below **85% line coverage**.
Branch coverage is reported separately. Reports are written to
`target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`.

Infrastructure owns fixture startup and shutdown. Use its
`scripts/cloud_auth_test_database.py` and
`scripts/author_document_review_test_database.py` helpers to prepare the isolated
loopback databases before running:

```sh
CLOUD_AUTH_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:4393/lookahead_domain_cloud_auth \
CLOUD_AUTH_TEST_PASSWORD_FILE=/absolute/path/to/cloud-auth-test/postgres-password \
DLV921_DATABASE_URL=jdbc:postgresql://127.0.0.1:4392/lookahead_domain_review \
DLV921_DATABASE_PASSWORD_FILE=/absolute/path/to/author-document-review-test/postgres-password \
./mvnw --batch-mode --no-transfer-progress -Pcoverage clean verify
```

The file paths above are placeholders for the helper-created password files;
never use application or production database credentials. The coverage profile
fails when either fixture URL or password file is absent, rather than silently
skipping persistence validation. Tests use unique schemas and remove their own
schema after execution. GitHub CI supplies two pinned PostgreSQL services with
synthetic disposable credentials. The ordinary `./mvnw verify` and Docker build
remain independently executable, but do not certify the required coverage gate.

The suite checks Local provider preservation, DEV/PROD Cognito and publication
bean selection, invalid configuration rejection, and real PostgreSQL sign-in,
review and plan lifecycle behavior. AWS provider calls remain mocked; coverage
and local database passes do not certify live Cognito, S3 IAM, ECS secret injection
or RDS TLS behavior.

## Configuration

| Property | Environment convenience | Purpose |
| --- | --- | --- |
| `app.deployment-environment` | `LOOKAHEAD_ENVIRONMENT` | Explicit `local`, `dev` or `prod` |
| `spring.datasource.url` | `LOOKAHEAD_DOMAIN_JDBC_URL` | Domain API PostgreSQL URL |
| `spring.datasource.username` | Adapter default | Only the exact runtime role in `LegacyStorageNames` |
| `spring.datasource.password` | `LOOKAHEAD_DOMAIN_DB_PASSWORD` | Domain API runtime credential |
| `app.identity.issuer` | `LOOKAHEAD_OAUTH_ISSUER` | Expected public token issuer |
| `app.identity.upstream` | `LOOKAHEAD_IDENTITY_UPSTREAM` | Fixed internal Identity origin |
| `app.identity.gateway-client-id` | `APP_OAUTH_CLIENT_ID` | Expected originating OAuth client |
| `app.identity.verifier-secret` | `LOOKAHEAD_IDENTITY_VERIFIER_SECRET` | Separate service-verification secret, at least 32 characters |
| `app.accounts.catalog-path` | `LOOKAHEAD_ACCOUNT_CATALOG` | Trusted account catalog file |
| `app.content.publication-path` | `LOOKAHEAD_CONTENT_PUBLICATION` | Immutable publication manifest |
| `app.content.root` | `LOOKAHEAD_CONTENT_ROOT` | Read-only published content directory |

Configuration-tree files can supply the corresponding dotted property names;
`LOOKAHEAD_SECRETS_DIRECTORY` selects their directory. Never mount Identity DB
credentials, password fixtures or signing private keys in this application.
DEV and PROD require the Cognito configuration in the cloud guide. Explicit local mode permits loopback
HTTP and fixed internal names `identity`, `identity-api`, `lookahead-identity`.
Public issuer HTTP remains loopback-only. `accounts` and `resource` roles are
always activated; mixed gateway/authorization-server roles fail startup.

Database URLs require an explicit single host and permit only `sslmode` and an
absolute `sslrootcert` path as URL parameters. Credentials, session options,
schema and timeout overrides are rejected. Runtime connections verify their
actual restricted role before being lent to application code. Migration jobs
verify their separate role before Flyway. Readiness checks each required DML
privilege individually; retaining SELECT alone does not mark a broken writer UP.

Runtime, migration and account-administration cloud connections share the same
JDBC transport validator. DEV/PROD require database `lookahead_platform`, an RDS
hostname in `AWS_REGION`, `sslmode=verify-full`, and an absolute readable,
non-symlink CA bundle containing current CA certificates. The CA bundle's trusted
provenance and image packaging are separate release checks; accepting its file
format does not establish an AWS handshake. No application AWS SDK fetches the CA.

For migration and account-administration commands, explicitly set
`LOOKAHEAD_ENVIRONMENT=dev` or `prod` and `AWS_REGION` alongside the Flyway
variables above. Existing Local commands retain `local` when
`LOOKAHEAD_ENVIRONMENT` is absent; explicitly empty or unknown modes fail.
Local JDBC URLs and password-file defaults are preserved. Every runtime,
migration and administration connection uses three-second connect,
five-second socket and two-second cancellation timeouts, including connections
opened after the initial migration-role check. URL parameters cannot override
these bounds.

DEV/PROD also require `server.address=127.0.0.1`
(`LOOKAHEAD_BIND_ADDRESS=127.0.0.1`). The Service Connect proxy terminates encrypted
private ingress on port 8443 and forwards inside the task to Domain HTTP on
loopback port 8080. Startup rejects wildcard, task-interface and ambiguous host
bindings so plain HTTP cannot be exposed outside the task. Local binding behavior
is unchanged, and the container healthcheck continues to use loopback HTTP.
The deployed Service Connect TLS boundary still requires exact-image and live
AWS verification.

## Local request and data contracts

The following Identity protocol applies to Local. DEV/PROD use the separate
[Cognito and durable sign-in contract](docs/cloud-authentication.md).

JWT signature, issuer and lifetime checks use public JWKS. Audience, originating
client and canonical UUID subject are checked before the internal call. Every
authenticated request then POSTs the exact access token as form field `token`
to `/internal/v1/tokens/verify` using Basic user `lookahead-domain-verifier`
and the dedicated verification secret. Redirects and positive response caching
are disabled; connect/read timeouts and response sizes are bounded.

Identity returns `{ "active": false }` for an invalid/revoked/disabled identity,
or an active result with `subject`, `username`, `displayName`, and `clientId`.
The latter must match the signed token. Invalid credentials yield 401;
unavailable or malformed Identity results yield 503. No response is treated as
permission to bypass current Domain API grants.

Only a freshly verified subject may create its credential-free
local subject reference. This operation is idempotent and never grants
access. Existing account UUIDs must be preserved during data transfer. Product
foreign keys point to this local subject table; no cross-database SQL exists.
Domain API does not persist a stale copy of Identity's enabled state.

An account disable blocks newly verified requests, but cannot atomically cancel
an already admitted Domain API transaction across databases. Plan/version/activity/
mutation-receipt writes remain one Domain API transaction. Support receipt
reservation and per-account limits lock the Domain API subject row; uncertain
SMTP delivery is never automatically resent by replaying a request key.

Local fixtures require `local-test`, explicit local mode and
`LOOKAHEAD_DOMAIN_SEED_ENABLED=true`. They create only deterministic subject
references and synthetic grants. Optional `LOOKAHEAD_DOMAIN_AUTHOR_ENABLED`
retains the guarded local author capability. Ordinary restart does not restore
revoked learner grants. These fixtures must never activate in DEV or PROD.

The execution runner remains dormant and is not packaged into this candidate.
Payments, Google account linking, production staff permissions, public comments
and commercial/age policies are not introduced by this boundary change.

## Validation boundary

Module tests cover product validation and access regressions, configuration
guards, authoritative verification, response rejection and account ownership.
Mocked JDBC tests do not certify real PostgreSQL permissions, migration transfer,
cross-replica transaction behavior, backup/restore or the three-application login
flow. Those require the isolated infrastructure integration gate before cutover.

## Author review decisions

The local DLV-921 candidate records immutable, version-bound author review events. See [API, manifest configuration, migration and verification](docs/author-review-api.md) and [OpenAPI source](docs/author-review-openapi.json). Recording a decision does not update delivery status or Git.

### Study-plan naming

Migration `V3__plan_names.sql` adds names and stable per-account plan numbers,
preserving legacy display labels from `goal`. The subject row holds the last
allocated number; transactional increments serialize concurrent creates and
deletion does not reuse a number. Create accepts an optional `name`; omission
generates `Study plan #N_DDMMYYYY_HXDays` using UTC creation date and configured
`dailyHours`/`days`. Names are independent of goals and immutable schedule versions.

`POST /api/v1/plans/{id}/name` accepts `{expectedRevision, name}` and the existing
idempotency key/owner protections. It changes only name, revision, update time and
an audit event; schedule, progress and creation number are preserved. Trimmed names
are1–160 characters with no control characters. List/detail return `name` and
`planNumber`; list also returns advisory `nextPlanNumber`. The account catalog
advertises `planNamingPolicies: ["plan-name-v1"]`.


## AWS DEV deployment candidate (DLV-810)

`deployment/service.yaml` is the application-owned service contract for the local AWS candidate. It declares health, capacity, immutable image/release inputs and symbolic approved resource/secret references. Shared DEV/PROD values, IAM, S3, networking, CloudFormation and tooling belong to Infra. YAML uses JSON syntax. Current image/evidence values are unresolved and desired count is zero; this is not an activated cloud profile. Local configuration and authorization are preserved. See the sibling Infra `aws/devprod/README.md` for local planning commands and remaining application/identity/bootstrap gates. No resource creation, upload or GitHub activation has been performed.

Deployment direction: this application owns its service YAML and future thin caller to an immutable-pinned Infra reusable workflow. The current v1 manifest still requires migration to shared + DEV/PROD sections. Infra owns bootstrap/IAM/security groups/templates/orchestration; see workspace Infra `docs/deployment/README.md`. Build-once digest promotion, required scans and separate DEV/PROD approvals remain gates, not enabled deployment behavior.

## Service deployment configuration

`deployment/service.yaml` uses `lookahead-service/v2`: `shared` owns service port, health, settings and approved resource/secret/Infra output references; `environments.dev` and `.prod` select capacity, image digest, content release and activation evidence. Both candidates retain zero tasks. Shared account/region values and all infrastructure remain Infra-owned. Infra's maintained `aws/devprod/parameter-bindings.yaml` validates every template parameter; `scripts/aws_deployment.py plan --environment dev|prod` generates an offline plan. See Infra `docs/deployment/README.md` for commands and activation gates. No application contract or Local Docker behavior changed; AWS deployment remains blocked.

October8 candidate service configuration adds explicit Fargate runtime/private networking, ingress/target-group references, optional alarm references, and per-environment scaling min/max, CPU targets, cooldowns and alarm thresholds. Desired/min tasks stay0 and scaling/alarms disabled. Fargate uses CPU/memory, not an EC2 instance type. Infra owns conditional scaling/IAM/CloudWatch resources; see Infra docs/deployment/README.md.

Deployment `service.yaml` now declares task startup, health probes, temporary disk, logging/rollout settings and approved environment/Secrets Manager bindings. Java DEV/PROD sections declare Xms128 MiB / Xmx512 MiB and approved JVM args for provisional 1 GiB tasks, leaving explicit native/probe budget; load testing remains required. Infra shared values and maintained templates remain authoritative; see Infra `docs/deployment/README.md` → JVM and task configuration. Local Docker/runtime behavior is unchanged and AWS activation remains blocked.

AWS candidate `deployment/service.yaml` now references the shared application task role and separate shared Fargate execution role from its owning environment foundation. All three cloud apps reuse this pair; DEV/PROD have separate role resources/scopes. Infra owns policies, trust and validation; service-specific secret mappings and existing authorization are preserved. See Infra `docs/deployment/README.md` → Two shared IAM roles per environment. No cloud activation.

## Cloud publication application candidate

DEV/PROD now select a verified S3 startup publication; Local retains mounted files and creates no AWS client beans. Domain alone uses the pinned S3 SDK with ECS task credentials. A bounded byte/entry cache preserves per-request authorization. Readiness includes publication initialization and existing database checks; cloud startup requires injected database secret values and verified RDS TLS. Since 2026-10-27 a content release is identified by its Content calendar release tag `vYYYY.MM.DD.HH.NN` (S3 keys `releases/<tag>/…` and `live/<tag>/…`, pin `LOOKAHEAD_PUBLICATION_RELEASE_ID=<tag>`), not by a content hash; live mode serves the complete `live/` folder with the highest tag. See [cloud publication configuration and validation](docs/cloud-publication.md). Local fixture checks do not certify AWS/IAM/ECS behavior. Cognito consumers and Domain durable sign-ins are implemented and locally tested. The inactive cloud candidate removes Identity and retains one Domain RDS; Local Identity is preserved. See [cloud authentication, database admission and remaining activation gates](docs/cloud-authentication.md).

The container now includes [checksum-pinned public RDS trust roots](tools/container/trust/README.md) at `/opt/lookahead/trust/rds-global-bundle.pem`. The build validates certificates and excludes private key material. This supplies the cloud JDBC trust file; real RDS connectivity/restore and ECS Service Connect encryption still need provider verification.

The container readiness probe is `com.lookahead.domain.health.ContainerHealthcheck`
under `src/main/java`, included in the normal production coverage inventory.
Its tests cover real loopback success/failure responses, strict readiness payloads,
request timeout configuration and interruption. Docker compiles that same measured
source for the standalone JVM probe. The RDS trust verifier remains build-only.
Wrapper distribution setup has a source-independent Docker layer; dependency
versions and checksum verification are unchanged.

## SAST gate diagnostics

`python3 tools/security/check.py sarif` requires completed CodeQL invocations,
valid rule/result inventories and exercised, source-bound exceptions. A failure
prints a reviewed constant reason (for example, missing invocation inventory or
unexercised exception) while leaving untrusted error text and SARIF messages out
of public logs. Warning/error notifications still block; this diagnostic change
does not waive findings or weaken the security gate. Raw SARIF and source
databases remain unpublished. Run the tooling regressions with
`python3 -m unittest discover -s tools/security -p 'test_*.py'`.

SAST exception hashes are also checked in the first tooling-test step; review
the security boundary before updating a hash after a source change. Expiry and
mandatory exception usage stay enforced. SARIF trace notifications (`none`)
are informational alongside `note`; warnings, errors and unknown levels block.
A rejected notification logs only its level and a bounded Java diagnostic ID,
never its message, source snippet, locations or properties.


The cloud admission CSRF disposition in `tools/security/sast-exceptions.json`
is bound to the entire `CloudSecurityConfiguration.java` source SHA and expires
on 2026-10-19. The internal chain accepts explicit bearer and Gateway-secret
headers, never browser cookies; missing/wrong Gateway credentials fail closed,
and every successful authentication still verifies the token against Cognito.
`CloudAdmissionSecurityTest` exercises both DEV and PROD filter chains with
controlled JWT decoding/provider responses, including rejected tokens and
provider revocation. It does not certify real Cognito/IAM/network behavior;
the separate signed-JWT/database integration test remains required for that
boundary's local integration coverage. Source changes or expiry require review.


The Docker builder uses digest-pinned Eclipse Temurin Java 21 Ubuntu 24.04
(`21-jdk-noble`); the runtime uses a separately pinned Java 21 Ubuntu 24.04 image
(`21-jre-noble`). The runtime uses glibc and Ubuntu account-creation
commands, retains numeric user/group 10001 and the existing Java health probe,
and needs no added curl or application SDK. Distroless Java 21 Debian candidates
were investigated but rejected because their complete package scans still found
blocking vulnerabilities. No image exception is used.

The scanner blocks High, Critical and Unknown severities, including unfixed
findings, and requires complete OS/Java package coverage for built images.
A passing base scan does not certify the application: CI scans both exact pinned
bases and the final image. Local Docker smoke checks are separate from AWS
activation, network, IAM and cloud profile verification.

On October 9, 2026, the pinned AMD64 candidates passed complete base/final
package scans and the isolated three-application Local authentication contract
with fresh synthetic PostgreSQL databases. All applications ran as 10001:10001
with a read-only root filesystem, capped temporary storage and healthy Java
readiness probes. This does not certify Cognito or RDS connectivity in AWS.

When a SAST exception is not exercised, the gate writes and prints bounded finding
metadata and `unusedSastExceptions` before failing. Review the exact rule/file
against the completed scan before retiring an exception; absence is not automatic
approval. Missing analysis, warnings, expired or changed-source exceptions and
unreviewed findings still block. SARIF messages and source snippets are not printed.

Earlier October 9 PR analysis omitted the cloud admission bypass finding, but
main analysis reported it again. The bypass exception remains removed; the
authentication fix and full-scan configuration below require new hosted proof.
Both CSRF dispositions remain source-bound and expiring.

PR and main SAST gates require full repository analysis. Diff-informed and
overlay analysis are explicitly disabled in the pinned action; incremental SARIF
is rejected. An alert disappearing from changed-lines-only analysis is not proof
that its exception is obsolete. New authentication control flow always terminates
fresh OAuth before reuse, and Domain compares missing credentials unconditionally
before denying authentication. Hosted full-scan proof remains required.

The dormant `release-image.yml` manual workflow reuses the full CI job, then
exports its exact scanned image as a one-day archive. Publication loads that
archive without rebuilding, validates source/Dockerfile/workflow/archive/config
digests, and uses a separately reviewed DEV OIDC role. Its only source selector is
an existing calendar release tag: a `resolve` job resolves the tag to its commit once,
requires that commit to be on `main`, freezes the record, CI builds that commit while
the workflow and tooling run from `main`, and the tag is re-checked before credentials.
Rollback is a new tag at an older `main` commit. It requires an immutable DEV ECR
repository and an exact main-only `dev` environment. The owner must approve an issue
bound to the tag, both commits, run, image, target and role within 40 minutes;
a manual trigger alone is insufficient. Approval is rechecked before the 15-minute
upload credentials and again before pushing. This is workflow-enforced owner approval,
not independent review. Registry receipt verifies remote manifest/config identity.
Existing tags and uncertain registry state block retries; failed uploads retain intent.
See [image publication and recovery](tools/release/README.md). No workflow was dispatched or
image published locally. Configure approved settings and action allowlists before
activation; private content and AWS credentials are never bundled in the image.


The old Domain bootstrap launcher is retired. Running it returns a fixed message
pointing to Infra Flyway Utils `prepare`, without inspecting arguments, secrets or
SQL state. The legacy bootstrap helper and its PostgreSQL/credential-file fixtures
remain as compatibility evidence. Supported database preparation is owned by Infra;
its separately approved utility prepares roles/grants without starting the Domain
server or automatically applying migrations. Old image/task revisions remain
historical artifacts and are not deleted by this source change.

Publication explicitly pushes only the verified `linux/amd64` manifest (Docker API
1.46 or newer). The registry receipt checks its config digest against the saved
archive; Docker Desktop index IDs are kept separate from that config identity.

## Platform reviews (DLV-928)

`/api/v1/platform-reviews` (package `com.lookahead.domain.review.platform`) stores reviewer
feedback without a database table: one JSON file per UTC day, `reviews/yyyy-MM-dd.json`,
holds every item written that day, and images are stored under `review-images/{itemId}/`.
`PlatformReviewObjects` is the storage boundary; implementations are the Local filesystem store and the S3 cloud candidate. A change reads the day file, applies the change and writes it back only if
it is unchanged (serialized in-process as well), so concurrent saves keep every item. The S3
store (DLV-929) maps the same operations to GetObject, conditional PutObject, ListObjectsV2
and DeleteObject.

| Setting | Meaning |
| --- | --- |
| `LOOKAHEAD_PLATFORM_REVIEW_DIRECTORY` | Local private directory for day files and images. Unconfigured storage answers 503; cloud uses separate S3 settings |
| `LOOKAHEAD_PLATFORM_REVIEWERS` | Legacy compatibility setting; ignored by the wired runtime. No automatic reviewer migration. Current authorization uses Domain staff assignments. The owner is the author capability |

Limits: 6 images per item, 5 MB each, 20 MB per item, PNG/JPEG/WebP checked by file signature,
EXIF/XMP/IPTC/PNG-text metadata removed without re-encoding; multipart request at most 21 MB;
100 items per reviewer per day. The container image creates
`/var/lib/lookahead/platform-reviews` owned by the app user so a new named volume mounted
there is writable. Tests: `PlatformReviewServiceTest`, `PlatformReviewControllerTest`.


## Staff access and cloud profile candidates (DLV-930/933)

Domain V6 owns `staff_access` and immutable `staff_access_events`; V7 owns
`cloud_account_preferences` and `cloud_account_profile_links`. Owner-only access
assignment APIs are `/api/v1/admin/access`, `/catalog` and `/lookup`; current
permissions are `/api/v1/account/permissions` and additive `auth/me.permissions`.
Roles are admin/reviewer/tester, independent of selected/full curriculum grants.
Only the existing owner capability manages access. Testers receive full courses
and own feedback; no admin/board access. Owner/admin triage all feedback.

Pending email assignments claim only from provider-verified email on an admitted
Cognito identity, within 14 days after save. Local signup does not verify email.
No accounts merge by email. Cognito signup/admission automation is still a cloud
activation follow-up. Owner-owned plan review artifacts remain owner-only.

`S3PlatformReviewObjects` is a tested cloud candidate, using conditional ETags,
expected bucket owner and SSE-KMS. Set all three cloud values together:
`LOOKAHEAD_PLATFORM_REVIEW_BUCKET`, `LOOKAHEAD_PLATFORM_REVIEW_PREFIX` (trailing
slash), `LOOKAHEAD_PLATFORM_REVIEW_KMS_KEY` (key ARN). The prefix/key are explicit;
there is no Local emulator or cloud disk fallback. Runtime uses the existing
container task credentials and regional S3 client. Infra must supply least-privilege
S3/KMS permissions before deployment. No AWS resources have been provisioned.

Cloud account preference/link routes use the current Cognito sign-in proof and
Domain account lock, preserving Local request shapes. `CloudSignInRegistryDatabaseTest`
checks persistence, account isolation and revocation against the documented
disposable PostgreSQL fixture; `S3PlatformReviewObjectsTest` checks concurrent
conditional writes with a stubbed SDK client. Neither is live AWS certification.

### Lower-cost DEV candidate

The `lower-cost-dev` profile serves application mTLS on8443 and loopback-only
management readiness on8081. TLS validates the peer chain; the application filter
also requires the Gateway client identity `gateway.lookahead-dev.internal` and
clientAuth usage. OAuth and Domain account/ownership checks remain required.
Application credentials now use immutable-version ECS injection. Private keys must be one complete
unencrypted PKCS8 PEM block with line-separated boundaries; incomplete envelopes,
extra blocks and surrounding material are rejected before startup. `InjectedCredentials`
validates certificate validity, remaining lifetime, SAN, EKU, chain and matching
PKCS8 keys before context refresh, then installs inline Spring PEM and scalar
properties. Inputs are `LOOKAHEAD_TLS_{SCHEMA,SERVER_CERTIFICATE,SERVER_KEY,TRUST}`,
`LOOKAHEAD_DOMAIN_DB_PASSWORD` and `LOOKAHEAD_DOMAIN_GATEWAY_SECRET`;
`LOOKAHEAD_TLS_SERVER_NAME` is public configuration. Validation errors reveal no
input values and deny startup. Database connections retain `sslmode=verify-full`.
Local jobs and the reviewed DEV database operations use Flyway Utils. The legacy
Domain launcher rejects execution even if an old credential directory is supplied.

This is local candidate code, not AWS deployment evidence. The standard Local
configuration and preserved Service Connect mode remain separate. Infra owns
orchestration, runtime templates and operation runbooks; SQL migrations stay here.

The retained historical bootstrap fixture helper requires an absolute, private credential directory
(mode0700). Its three fixed credential files must be regular, non-symlink,
mode0400 files, matching the initializer's output. Reads are bounded to128bytes;
duplicate environment/file sources and invalid password bytes fail before any
connection. `BootstrapCredentialFilesTest` preserves these helper checks; the retired launcher does not call the helper.

Lower-cost DEV security events use the dedicated `lookahead.security.audit` logger
with fixed outcome names and random event IDs. The filter preserves HTTP responses
and emits no request paths, tokens or bodies. Infra owns the separately scoped
audit relay and90-day archive; local tests do not verify AWS delivery.

The Docker context explicitly includes only `deployment/container/dns.security`
from that configuration directory. Keep this exact allowance when changing the
runtime COPY; credential/private-file exclusions remain in effect.


DLV-930 invitation activation: `GET /api/v1/auth/me` does not claim staff access.
Managed sign-in restoration first reads the account, then submits the bodyless
`POST /bff/api/v1/auth/invitations/activate` with the session CSRF token, and loads
current grants/plans from the returned account. Gateway forwards only POST without
query parameters to Domain `/api/v1/auth/invitations/activate` using its server-held
bearer. Domain requires account scope and uses only provider-verified principal
email. No eligible invitation is a no-op; retries/concurrent claims append one
claim event. Conflicting existing assignments return 409. The flow does not change
the sign-in return URL or enable first-time cloud admission. Local signup still
cannot prove mailbox ownership. Deploy Domain and Gateway before the updated Web
client; do not use the old mutating GET as fallback if POST fails.

## Local membership purchases (DLV-209)

Domain owns `/api/v1/membership/plans`, `/api/v1/sales/active`, account purchase/quote/access-record endpoints and staff upgrades/sales/price endpoints. Saved study plans retain `/api/v1/plans`. Migration V8 adds only membership tables. Purchases require `Idempotency-Key`; `expectedPriceCents` optionally rejects a stale quote. Effective catalog access is computed dynamically with server-time expiry. Nothing renews automatically.

Local and DEV accept only mock payments (`APP_PAYMENTS_PROVIDER=mock`, default). Select `APP_PAYMENTS_MOCK_SCENARIO`: `success` (default), `decline`, `requires-action`, `network`, `rate-limit`, `server-error`, or `timeout-after-success`. Only change this on an isolated test deployment; pending outcomes never grant access. At most three attempts per purchase, jittered exponential delay and a three-second retry-loop budget. Unknown outcomes need investigation; real settlement/refunds/webhooks are not enabled. The Stripe SDK adapter is unregistered and never used in Local/DEV; do not supply keys. Student/regional discount rules and Course Pass duration remain pending.

`./mvnw test` runs unit/contract checks. `MembershipDatabaseTest` requires the existing isolated `CLOUD_AUTH_TEST_DATABASE_URL` and password-file environment variables from Infra’s cloud-auth test database helper; it creates and removes only its synthetic test schema. Never point it at application databases.

## DLV-961 co-author policy (source work, not activated)

The assignable `co-author` role permits document/preview reads, delivery reads,
People and access reads/lookup, and membership-record reads. It does not grant
owner access management, delivery writes, sale/price writes or staff upgrades.
Admin and co-author roles imply full curriculum in effective-grant computation;
other staff assignments and commercial purchases remain separate. The final
permission set and the complete UI/publishing boundary still need review before
merge or activation. DLV-930 owns that role verification.

DLV-961's managed Local seed/reset integration is implemented in Domain and
active behind working4301; DEV provisioning remains deferred.
Ready-made account persistence remains the DLV-705 dependency: the existing
preparation mapper is read-only and must not be treated as an enabled save
capability. Existing author and learner records have not been reset by this work.

Saved-plan mutation transactions now lock the owning `platform_subjects` row before
request-key and plan locks, including retries. This serializes distinct mutation
keys for the same account across instances, while other owners remain independent.
`PlanLifecycleDatabaseTest` checks lock contention and rollback against the documented
disposable PostgreSQL fixture. This source change is not yet activated and does not
advertise ready-made adoption or enforce the unfinished DLV-705 reservation policy.


DLV-961 source now includes the fixed 27-row scenario catalog and a guarded Local
purchase-fixture writer. It uses normal membership purchases, replacement links,
mock-payment receipts and staff-upgrade audit rows. Student/regional/sale categories
are explicit synthetic metadata with zero configured discount amount; no commercial
eligibility or rate is inferred. The writer is not wired into startup or live resets
yet. Plan seeding and lifecycle integration remain unfinished.

Authenticated `GET /api/v1/account/access-summary` and the staff-readable
`GET /api/v1/admin/accounts/{id}/access-summary` return derived current access labels.
Only owner, admin and co-author may read another account. These read projections
exclude future, expired and superseded purchases; manual course grants are labeled
`Granted`. They neither grant access nor create stored roles. Local activation and
frontend integration remain pending the DLV-961 batch.

### Dated account plans and managed Local fixtures

DLV-705 adds `POST /api/v1/plans/{id}/lifecycle` with revision/idempotency protection.
New plans require an explicit UTC `startDate`; legacy imports may remain unactivated.
Activation validates current access, four active plans, exclusive courses and overlapping
1080-minute daily capacity. Archive/completion retains versions and progress. Automatic
archive is derived 90 days after the latest saved exclusive end, without read-side writes.
Ready-made saves compare the complete client snapshot against a hash-verified server mapping.
Explicit `compatibleTemplateCatalogVersions` permits verified historical pins while every
reference continues to be checked against the current catalog.

The integrated DLV-938 Domain candidate adds migration V11 and enforces
free-account allowances inside the existing owner-locked plan transactions.
Current server-owned topic grants decide whether an account is free; published
free content IDs decide whether a plan has free scope. Free accounts can keep
two active free-content plans, create 15 free plans across their lifetime
(including imports), and make three successful deletions in any rolling
21-day window. Durable allowance rows survive plan deletion, and exact
idempotency retries do not count twice. Existing paid-scope plans remain
owner-readable after access loss, while protected content remains grant-gated.
The proposed 30-day access-loss archive is not yet implemented.

Test maintenance requires `accounts,local-test`, `app.deployment-environment=local`,
`app.test-accounts.enabled=true`, a separate operator secret and prepared private custom-plan
fixtures (`app.test-accounts.plan-fixtures`). Internal seed is never exposed by Gateway.

DLV-962 adds Local-only staff creation of independent learner test accounts.
Domain checks the current owner/co-author/admin assignment, reserves a creator
quota under a database lock, asks Identity to provision a disabled login, records
the managed learner, then activates login. Owner creations are unlimited;
co-authors get 20 and admins 10 lifetime successful creations (a dual role uses
10). Same-key retries complete interrupted work. The creator may delete their
learner; owner and co-author may delete any learner. An unfinished reservation
holds capacity for 15 minutes, then ceases to block new requests; completion
rechecks the lifetime count under the creator lock. A pending Identity login
remains disabled until successful same-key completion or operator recovery.
Identity disables login
before Domain erases plans, progress, access, synthetic payment fixtures and
purchase audit details; minimal tombstones preserve the lifetime count. Ordinary
membership audit remains append-only. `GET /api/v1/admin/test-accounts/allowance`, `POST
/api/v1/admin/test-accounts` and `DELETE .../{id}` are active on the working
Local stack behind 4301 as of 2026-10-16. The follow-up deletion policy
allows the owner to delete any other managed test account, co-authors to delete
admins and learners, and admins to delete learners. Domain returns `canDelete`
per row; Identity disables the target before Domain clears its roles and data.
The Local author account remains protected.
Migration V14 grants the runtime database role the three remaining synthetic
purchase-table deletes needed after sign-in is disabled. The preceding grant
gap caused deletion to return `ACCOUNT_STORAGE_UNAVAILABLE` on stable Local
even though privileged database tests passed; the database suite now checks
those runtime privileges directly.
Owner/admin may explicitly reset a registered fixture; co-author reads only. Disable the
legacy startup seeder when using managed maintenance to preserve existing author grants.
Do not include fixture publications or secret files in this repository or its image.

## People account directory

Owner-only `GET /api/v1/admin/accounts?query=&page=0` returns `{data:{accounts,page,hasNext}}`. Identity provides enabled account identity fields; Domain adds roles, `testAccount`, `fullCurriculum` and effective `access`. The owner check precedes enumeration. Other staff use their existing restricted sources. Fixed page size 50; literal search, stable username/id ordering, no-store and no account writes.
The follow-up adds `canDowngrade` and `canDelete` action flags. An owner in Local
or DEV may `POST /api/v1/admin/accounts/{id}/downgrade` with confirmation
`DOWNGRADE`, clearing roles, grants and effective purchases while retaining
identity, plans and immutable purchase history. An already-free real learner
may then be separately deleted in Local with `POST .../{id}/delete` and
confirmation `DELETE`: Identity first disables sign-in and scrubs profile and
credentials, then Domain erases plans and progress while retaining purchase
and minimal maintenance audit. The owner and managed test accounts are
excluded. DEV provider-owned deletion remains operator-only and is not exposed
by this browser route. Domain migration V13 owns the maintenance audit.

Local internal test-account seeding requires a non-browser JSON POST with the
dedicated operator credential. Requests carrying Origin or Sec-Fetch-Site,
non-JSON POSTs and cross-origin preflight are rejected before seeding. Existing
Infra Python tooling already uses the supported request shape. See
[security maintenance](docs/security-ci.md) for the source-bound review.


### Complete archive and Domain catalog

Content Release generates `account-catalog.json` from the same staged content and
pinned Web planner. This is curriculum metadata (IDs, routes, topics and version
pins), not user accounts. It is hash-bound backend-only archive metadata, excluded
from served assets. Domain verifies and extracts the complete archive before readiness;
Angular requests individual authorized content files. Older content-only archives must
be rebuilt and published as a new release; never bypass the catalog requirement.


### CSRF protection and Local operator seeding

Security chains retain Spring CSRF protection with cookie-backed token storage (no
server authentication session). Bearer resource-server requests use Spring's standard
Bearer-token handling; cookie-only unsafe requests are rejected with 403. Local
operator clients must GET `/internal/v1/test-accounts/csrf` with the dedicated Basic
credential, retain the HttpOnly `XSRF-TOKEN` cookie, then POST JSON to the seed endpoint
with that cookie, the returned `X-XSRF-TOKEN` token and the same Basic credential.
Origin and Sec-Fetch-Site requests remain rejected. Use the updated Infra seed client.
This change is locally tested; shared-stack activation and hosted CodeQL are pending.
The three expired CSRF exceptions have been removed rather than extended.

Membership access summaries evaluate purchase and grant validity at one application UTC instant per request. Tests inject the same clock used for scenario purchases; start is inclusive and expiry exclusive. This changes no response shape or access policy.

### Workflow run identification and approval context

Actions run titles include the workflow name, branch, event and run number so
parallel workflows are distinguishable in All workflows. Existing run history
is unchanged. Approval issues, where produced, show exact source commit links
and associated PRs merged into main. Push-triggered approvals include the
verified push range when available; manual/pinned sources are explicitly scoped
to their exact revision, not claimed as a delta since the previous deployment.
The central Content/Infra approval flows retain this snapshot with the approval
receipt and disclose lookup failures or bounded/truncated results. Runtime APIs,
workflow triggers, AWS grants and existing approval commands are unchanged.

### Initial DEV migration guard

The retained `InitialMigrationGuard` and database tests document the original DEV
empty-schema/session-lock boundary. They remain compatibility helpers; the retired
Domain launcher cannot activate them. Infra Flyway Utils owns supported execution,
including exact expected/target versions, empty-schema initial installation,
verification and rejection of stale state. No historical SQL migration was edited.


### Calendar image releases

DEV image releases use one shared `lookahead-dev-ecr` repository and `domain-vyyyy.mm.dd.hh.<00-nn>` tags. The source Git tag omits `domain-`. UTC counters start at `00` each hour independently per app. Run `python3 tools/release/release_version.py next --app domain` to suggest the next version from remote Git tags; this does not create or reserve a tag. Publish the exact source tag through the user-owned Git cycle, then supply it as `release-version` to the manual release workflow on main. CI verifies the tag/source before building and retains app/version/source/config/archive/manifest provenance. Deployment still selects an immutable digest. Prefixes organize images; IAM upload scope covers the shared repository.
Maven builds accept `-Drevision=yyyy.mm.dd.hh.NN`; local builds retain the snapshot default.


DEV image publication uses the existing shared deployment role with an
`image-publish-*` session name required by its trust policy. The role explicitly
restricts these sessions to ECR publication operations; the workflow retains its
additional repository-scoped session policy, existing approval and scan gates.
Infra owns the reviewed policy update and registry provisioning. These local
workflow changes do not install IAM policies or publish an image.
