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
| `app.publication.live-prefix` | `LOOKAHEAD_PUBLICATION_LIVE_PREFIX` | DLV-971: the prefix holding exactly one publication tar (DEV `release/live/` after the AWS lane's cutover; empty keeps today's `live/<tag>/` discovery) |
| `app.reviews.prefix`, `app.support-requests.prefix`, `app.delivery.prefix` | `LOOKAHEAD_REVIEWS_PREFIX`, `LOOKAHEAD_SUPPORT_REQUESTS_PREFIX`, `LOOKAHEAD_DELIVERY_PREFIX` | DLV-971 runtime store roots in the assets bucket (`reviews/`, `support-requests/`, `delivery/`) |
| `app.platform-reviews.legacy-prefix` | `LOOKAHEAD_PLATFORM_REVIEWS_LEGACY_PREFIX` | DLV-971: the retired day-file prefix imported once at start; empty on DEV since 2026-11-09 (DLV-976 transition B removed the grant; the prefix held no day files) |

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
DLV-971: a support request is recorded for Team Reviews first (see Platform reviews),
then emailed. Receipt statuses: `accepted` (email sent, 202), `recorded` (recorded for
Team Reviews, mail not configured, 202; `EMAIL_NOT_SENT` names the missing setting),
`unconfirmed` (reserved, email not confirmed, 503). A failing or unconfigured review store
is logged as `support.record` / `REQUEST_NOT_RECORDED` and the email still goes; when
neither the store nor mail is available the request key is released and the answer is
`503 SUPPORT_UNAVAILABLE`, so a retry starts over. A replay of a `recorded` request returns
the stored receipt (`unconfirmed`; the table's statuses are unchanged).

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

## Contracts with other repositories

No repository clones another at build or deploy time, and every cross-repository contract lives in
one place only: the Content repository, under `docs/contracts/` (`registry.json`,
`web-content-paths.json`, `release-layout.json`, `domain-api-v1.json`; owner decisions 2026-11-05
and 2026-11-06). This repository keeps no copy. `scripts/fetch-contracts.sh` fetches the two files
this repository tests against from Content main, read-only, into the git-ignored `.contracts/`
folder, and the tests read them from there (`-Dcontracts.dir=<folder>` points elsewhere). CI runs
the script in the step "Fetch contracts from Content" on every pull request and push to main, with
the Actions secret `CONTENT_READ_TOKEN` (a fine-grained token with Contents: read on the Content
repository alone; a workflow that calls `ci.yml` passes it with `secrets: inherit`); a developer
runs it locally with their own `gh auth login`. The image build (`Dockerfile`) carries no fetched
file and runs the test suite with `-DexcludedGroups=fetched-contract`; the same commit has already
passed those tests in the CI Maven step. Contracts are public by nature; anything that would need
to stay private is not a contract but configuration, and it belongs to Infra.

| Contract file on Content | Owner | Read by, on this repo's PR | Change order | What a failure means |
| --- | --- | --- | --- | --- |
| `docs/contracts/domain-api-v1.json` (`gateway-domain-contract/v1`: every Domain call the Gateway makes, its method, path, success statuses and the response fields the Gateway reads) | Gateway; the Gateway edits the master on Content first | `DomainProviderContractTest`: every call is a real controller mapping, deferred routes stay unmapped, and the calls whose answer the Gateway interprets are driven through the real controllers on stubbed services | Content master first (the Gateway's change), then this repository provides the route, status or field, then the Gateway's own pull request, then Web | A listed route is missing from Domain or answers an unlisted status or field: Domain is behind the master on Content |
| `docs/contracts/release-layout.json` (`release-layout/v1`: the `live/<tag>/` layout, the keys of `manifest.json` and `publication.json`, and the keys and record fields of `account-catalog.json`) | Content | `ReleaseLayoutContractTest`: builds a minimal release from the contract alone and loads it through `PublicationBootstrap` and `SnapshotValidator`; a release or catalog missing a required key is refused the way the readers refuse, an absent optional key is accepted, and an unknown record field is shown to be ignored | Content first for a new optional key (with a bumped `schemaVersion`), then this repository adopts it. A required key or a removed key needs the Domain change merged before the Content release that carries it | The release Content would publish cannot be loaded by Domain, or the master no longer says what Domain reads |

A test that fails with "Contract file absent" has nothing to do with the contract: the fetch did
not run (locally: run `scripts/fetch-contracts.sh`; CI: the fetch step failed or the secret was not
passed, and the step's own message names the fix). Content's `registry.json` lists both files with
this repository and these two tests as consumers; Content compares nothing with this repository.

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

### Publication from the live tar (DLV-971)

The approved bucket layout serves the publication from one tar: at start, with
`app.publication.live-prefix` set (DEV `release/live/` once the AWS lane has copied the tar there;
`deployment/dev/deploy-config.json` keeps `PublicationLivePrefix` empty until that cutover), the Domain
lists the prefix, expects exactly one `v*.tar` (the publication as today, plain or gzip tar, with
`publication.json` and `account-catalog.json` inside and no manifest beside it), downloads and
unpacks it into the private publication folder (`LivePublicationLoader`), and reports the version
(the file name) in the readiness detail `release` and in one `publication.load` line. Zero or more
than one tar, a stray object under the prefix, or a tar that fails its checks (unsafe member, an
asset whose digest differs from `publication.json`, an unlisted file) leaves the content unloaded:
readiness is DOWN with the reason (`LIVE_RELEASE_MISSING`, `LIVE_RELEASE_AMBIGUOUS`,
`LIVE_RELEASE_UNEXPECTED`, `LIVE_RELEASE_INVALID`, `LIVE_RELEASE_UNAVAILABLE`) and the task stays up
with an empty trusted catalog until the next start finds one good tar; nothing restarts on its own
beyond the container health check. With the setting empty, today's `live/<tag>/` discovery
(`PublicationBootstrap`) is unchanged, so the current DEV keeps running until the cutover.
Tests: `LivePublicationLoaderTest`.

### Why a task failed: the log explains it (DLV-969)

The health answer carries no details, so the reason is written to the log instead
(names, codes and exception types only; never values, hosts, tokens or exception
messages). Search these loggers in CloudWatch:

- `lookahead.health`: `Readiness DOWN: …` with a `reason` (for example
  `MIGRATIONS_MISSING`, `GRANTS_DIFFER`, `CATALOG_MISSING`) and `health.component`
  (`database` or `content publication`) when a readiness check fails, again every
  minute while it lasts, and `Readiness UP: …` once on recovery.
- `lookahead.startup`: `Startup refused: <setting> <what is wrong>` names the
  environment variable, property or file that stopped startup (for example
  `LOOKAHEAD_TLS_SERVER_KEY does not belong to LOOKAHEAD_TLS_SERVER_CERTIFICATE`), and
  `Content release <tag> loaded …` or `… could not be loaded while <step>: …` for the
  S3 content release.
- `lookahead.lifecycle`: every liveness/readiness state change, one `Started: version …`
  line when the task first accepts traffic, and one `Stopping: shutdown requested …`
  line with the uptime, the readiness before the stop and the last readiness checks, so
  an exit code 143 (SIGTERM after a failed container health check) is explained.
- `lookahead.event`: fail-closed and fallback paths on hot request paths (content 503s,
  storage and sign-in check outages, refused bearer tokens by reason code such as
  `ISSUER_MISMATCH` or `GATEWAY_SECRET_MISMATCH`, refused Gateway client certificates)
  are logged once per reason when they start and at most once a minute while they last.

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
bound to the tag, both commits, run, image, target and role within 10 minutes of the
issue being created; a manual trigger alone is insufficient. The posted approval is valid
for 15 minutes from its comment and is rechecked before the 15-minute upload credentials
and again before pushing (both windows are defaults, tunable by the repository variables
`APPROVAL_WAIT_MINUTES` and `APPROVAL_VALID_MINUTES` described in the release tooling README). This is workflow-enforced owner approval,
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
feedback without a database table. DLV-971 (owner, 2026-11-06): one JSON record per item in two
runtime stores of the assets bucket, `reviews/records/YYYY/MM/DD/REV-000001.json` and
`support-requests/records/YYYY/MM/DD/SUP-000001.json` (the day is the record's creation day, UTC),
with the images beside each record under `<store>/attachments/<ID>/NN-name.ext`. Ids come from two
Postgres sequences (`review_record_seq`, `support_request_seq`, V22). Sequence selection uses a bound PostgreSQL `regclass` parameter; the sequence name is never concatenated into SQL. `PlatformReviewObjects` is the
storage boundary, one instance per store root; implementations are the Local filesystem store (the
roots are sub-folders of `LOOKAHEAD_PLATFORM_REVIEW_DIRECTORY`) and the S3 store (GetObject,
conditional PutObject, ListObjectsV2 with and without a delimiter, DeleteObject, SSE-KMS with the
publication key). `RecordStore` reads a record, applies the change and writes it back only if it is
unchanged (If-Match on the ETag), so concurrent saves keep every item; a create uses a fresh id and
never conflicts. The list merges both stores newest first, paged by day (30 days a page) as before.

Record shape (the approved layout's): `id`, `kind` (`review` | `request`), `requestType` (`problem` |
`feedback` | `question` | null), `status`, `submitter {accountId, role, displayName}`, `category`,
`title`, `details`, `page`, `pageUrl`, `priority`, `attachments[]`, `reference` (requests), `ticketId`
(null until promoted), `ownerNote`, `createdAt`, `updatedAt`, `closedAt`, `closedBy`, plus the
write-control fields `createKey`, `createHash`, `lastWriteKey`. The API keeps the Web's `Item` and
`Summary` fields (`reviewerId` is derived from the account id, never stored) and adds `ticketId`
and `ticketStatus` (the ticket's `statusId` read from the delivery plan when `ticketId` is set;
`Item` also answers `closedAt` and `closedBy`).

| Setting | Meaning |
| --- | --- |
| `LOOKAHEAD_PLATFORM_REVIEW_DIRECTORY` | Local private directory; the stores are its `reviews/`, `support-requests/` and `delivery/` sub-folders and the directory itself is the legacy day-file store. Unconfigured storage answers 503; cloud uses the S3 settings |
| `LOOKAHEAD_PLATFORM_REVIEWS_BUCKET`, `LOOKAHEAD_PLATFORM_REVIEWS_KMS_KEY` | The assets bucket and the publication KMS key, shared by every store (`app.platform-reviews.bucket`, `.kms-key`) |
| `LOOKAHEAD_REVIEWS_PREFIX` | `app.reviews.prefix`, the review store root (DEV `reviews/`) |
| `LOOKAHEAD_SUPPORT_REQUESTS_PREFIX` | `app.support-requests.prefix`, the support request store root (DEV `support-requests/`) |
| `LOOKAHEAD_DELIVERY_PREFIX` | `app.delivery.prefix`, the delivery plan root (DEV `delivery/`) |
| `LOOKAHEAD_PLATFORM_REVIEWS_LEGACY_PREFIX` | `app.platform-reviews.legacy-prefix`, the retired day-file prefix read once by the import; empty skips it, and DEV passes empty since 2026-11-09 (DLV-976). `LOOKAHEAD_PLATFORM_REVIEWS_PREFIX` is no longer read for records |
| `LOOKAHEAD_PLATFORM_REVIEWERS` | Legacy compatibility setting; ignored by the wired runtime. Current authorization uses Domain staff assignments. The owner is the author capability |

Limits: 6 images per item, 5 MB each, 20 MB per item, PNG/JPEG/WebP checked by file signature,
EXIF/XMP/IPTC/PNG-text metadata removed without re-encoding; multipart request at most 21 MB;
100 items per reviewer per day. The container image creates
`/var/lib/lookahead/platform-reviews` owned by the app user so a new named volume mounted
there is writable. Tests: `PlatformReviewServiceTest`, `PlatformReviewControllerTest`,
`FileSystemPlatformReviewObjectsTest`, `S3PlatformReviewObjectsTest`, `ReviewRecordsDatabaseTest`.

Kinds and support requests (DLV-971, owner 2026-11-06). Every item carries `kind`: `"review"` for an
item written on Write Review, `"request"` for a learner's support request (Report a problem, Share
feedback, Ask a question). A request also carries `requestType` and `reference` (the
`support_receipts` reference, so the owner can match it to the email thread); on a review both are
`null`. `SupportFeedbackService.submit` records the request through
`PlatformReviewService.recordRequest` into the support-requests store before it emails the support
inbox: category `problem` for a problem and `idea` for feedback or a question, page `Support`, no page
URL, priority `medium`, status `new`, the learner's feedback role (`user`) as the submitter role, the
screenshots stored through the review image pipeline; the reply address stays in the email and the
receipt row only. The receipt reference is the dedupe key: the same request is never recorded twice.
Who sees and does what: the owner and admins list every item; a reviewer lists the reviews they
wrote, never a request; a learner has no Team Reviews access. The owner sets the status of any
item, an admin only of a request (`403 PLATFORM_REVIEW_ACCESS_DENIED` on a review); a review is
edited or deleted by its writer while New (the owner's status locks it); a request is never
edited through the review API and only the owner deletes it.

Statuses are `new`, `accepted`, `planned`, `done`, `declined` and `closed`. `closed` is set by hand
(`PUT .../triage`, `closedBy` the actor's role) or by the sweep; `closed` to any status is a reopen
under the same rights and clears `closedAt`/`closedBy`. Every status change of a record or a ticket
is one row of the append-only `review_status_events` table (V22: `id, record_id, kind
(review|request|ticket), actor_id (null for the sweep), from_status, to_status, note_set,
recorded_at`; the runtime role may INSERT and SELECT only, the trigger refuses UPDATE and DELETE for
every role). A history failure is logged (`platform-review.history` / `HISTORY_NOT_WRITTEN`) and
never undoes the change.

Promote (`POST /api/v1/platform-reviews/{id}/promote`, 201): a work item is added to the delivery
plan (title and summary from the record's title and details, type `bug` for a problem, `task` for a
content review or a question, else `story`; priority high/medium/low to P1/P2/P3; the workflow's
first status; the plan's current stage; `origin {id, kind}`; the environment stamps) and
`ticketId` is set on the record; the answer is `{data: {ticketId, item}}`. The owner promotes any
record, an admin a learner's request only; a record already promoted answers
`409 PLATFORM_REVIEW_ALREADY_PROMOTED`; 404 for an unknown record; 503 when the plan storage is
unavailable (the record stays unlinked).

Legacy import (`LegacyReviewImport`): at start, while no marker exists and the legacy prefix has
`reviews/<day>.json` files, every item is rewritten as a record in the right store (reviews to
`reviews/`, kind request to `support-requests/`, oldest first so the ids follow the original order),
images are copied from `review-images/<itemId>/` to `attachments/<ID>/NN-name.ext`, the counts are
logged (`platform-review.import`) and a marker `reviews/.imported.json` keeps it from running twice.
Legacy objects are never deleted here; the AWS lane removes the prefix after the counts match.

The import resumes after a partial failure (DLV-971/F9). Before anything is written for an item, its
destination is fixed in `reviews/.import-progress/<day>/<itemId>.json` (legacy day and item id, kind,
the record id drawn from the sequence, the legacy `createdAt` that decides the record key), created
if-none-match so the first writer wins. A restarted task, or a second task starting at the same
moment, reads the progress back and uses the same id and attachment keys: an attachment put is
idempotent, a record create is if-none-match (`RecordStore.createIfAbsent`), and a record already in
place is verified (id and kind), its images checked and re-copied from the legacy object if lost,
and counted instead of written. A number drawn before a failed progress write is a gap in the
sequence, never a second record. The marker is written only after every legacy item has a verified
record; its counts come from what is in the stores, not from the attempt: `reviews`, `requests`,
`images`, `days`, `legacyRecords`, `migratedRecords`, `legacyAttachments`, `migratedAttachments`,
`missingAttachments` (legacy image objects that do not exist, listed under `missing`, never
invented), `resumedRecords`, `domainSourceSha` (the deployed source revision from build info,
"unknown" without it), `importedAt`/`verifiedAt`. Transition B requires `legacyAttachments ==
migratedAttachments`, so a missing legacy image blocks B until the owner has read the list.
A storage failure stops the run (`IMPORT_FAILED`), keeps the progress and is retried at the next
start. Records in the stores without any progress object block the import (`IMPORT_BLOCKED`,
nothing written) because their origin is unknown; the owner reads them and either removes them or
writes the marker by hand. A progress object that does not describe its item, or a record under
the fixed id of another kind, stops the run (`IMPORT_PROGRESS_MISMATCH`). Tests:
`LegacyReviewImportTest`, `LegacyReviewImportRetryTest` (failure injection at each step, two tasks
at once), `LegacyReviewImportStrandingTest` (the regression, which fails against the import before
the fix).

## Delivery plan (DLV-971)

`/api/v1/delivery/plan` (package `com.lookahead.domain.delivery`) serves and edits
`delivery/plan.json` in the assets bucket (`DeliveryPlanStore`). The file keeps the Content shape
(`schemaVersion`, `version`, workflow, priorities, stages, `workItems` with `createdIn`, `updatedIn`,
`updatedAt`, and the new `origin {id, kind} | null` and `pendingLocal` object | null). Every write
reads the current file, checks the caller's `expectedVersion` (`409 VERSION_CONFLICT` with
`currentRevision` when stale), raises `version`, copies the previous file to
`delivery/archive/plan-v<N>.json` and writes with If-Match on the ETag, so two edits cannot lose
each other. The stamps are `dev` when the cloud policy is on, else `local`; a ticket without a stamp
reads as `local`, never an error. Until a plan exists `GET` answers `404 DELIVERY_PLAN_MISSING`; the
published plan seeds it at the next start (below), or the owner uploads it by hand.

| Call | Right | Answer |
| --- | --- | --- |
| `GET /api/v1/delivery/plan` | delivery read (owner, co-author, admin, reviewer) | 200 `{data: plan}`; 404 `DELIVERY_PLAN_MISSING` |
| `POST /api/v1/delivery/plan/work-items` `{expectedVersion, item}` | delivery create and `permitsDeliveryWrite(true, null, statusId)` | 201 `{data: {version, item}}`; 409 `VERSION_CONFLICT` / `DELIVERY_ITEM_EXISTS`; 422 `DELIVERY_ITEM_INVALID` |
| `PUT /api/v1/delivery/plan/work-items/{id}` `{expectedVersion, item}` | `StaffPermissions.permitsDeliveryWrite(false, previous, next)` for a status change; delivery create or review for a text-only edit | 200 `{data: {version, item}}`; 404 `DELIVERY_ITEM_NOT_FOUND`; 409 `VERSION_CONFLICT` |
| `POST /api/v1/delivery/plan/work-items/{id}/resolve-conflict` `{expectedVersion, keep: "dev" \| "local"}` | owner or admin | 200 `{data: {version, item}}`; 409 `DELIVERY_NO_CONFLICT`; 404; 409 `VERSION_CONFLICT` |

`createdIn`, `updatedIn`, `updatedAt`, `origin` and `pendingLocal` are server-set: a body's values
are ignored. The Gateway forwards these routes with the same session, CSRF check and bearer
(`SCOPE_account`) as platform reviews. Tests: `DeliveryPlanServiceTest`, `DeliveryPlanControllerTest`,
`DeliveryPlanStoreTest`.

Inbox sync (`DeliveryPlanMerge`): the owner downloads `delivery/plan.json`, edits it locally and
uploads it to `delivery/inbox/plan.json`. The sync (at start, then every 4 hours) resolves the inbox's
`baseVersion` (its `version` when absent) against the archive (or the current plan when equal) and
merges ticket by ticket: changed locally and unchanged on DEV since the base, take local (stamped
`updatedIn: local`); changed on DEV and unchanged locally, keep DEV; changed in both (or new on both
sides with different content), keep DEV and attach the local version as `pendingLocal` for the owner
to resolve on the page; new locally, add; new on DEV, keep; missing from the inbox is not a deletion,
and a ticket deleted on DEV stays deleted. Stamps never count as a change. Plan-level fields follow
the same rule field by field. The DEV plan is written as a new version, the previous one archived,
the inbox file deleted, and one `delivery.inbox-sync` line carries the counts. A base the archive
lacks, or a missing plan, keeps the inbox for the owner (one WARN per run). The same code runs for a
local Domain against a local inbox. Tests: `DeliveryPlanMergeTest` (the rule table).

Published plan at start (DLV-998, owner 2026-11-09; `ReleasePlanImport`): the publication tar carries the
repository's `runtime/delivery/delivery-plan.json` as `content/staff/delivery-plan.json` (a local Domain serving a
content tree reads `delivery/delivery-plan.json` under its content root). After the legacy review import and before
the sweeps, `RuntimeSweeps.atStart` runs the import: no `plan.json` yet, the released file becomes the first plan as it
is (`DeliveryPlanStore.seed`, If-None-Match, its own version); otherwise it becomes the inbox and the sync above merges it
at once, its `baseVersion` (else `version`) having to be the DEV plan or an archived version. The SHA-256 of the imported
bytes is kept in `delivery/inbox/.release-import.json` with the release tag and the plan version, so a restart on the same
release imports nothing and the version does not climb; a file already in the inbox (an owner's upload) is never
replaced and the release is imported at the next start. DEV and local only (`DevOnly.allowed`); in prod nothing runs.
One `delivery.release-import` line: `seeded`, `merged` (with the counts) or the reason it was left (`BASE_VERSION_UNKNOWN`,
`INBOX_OCCUPIED`, `RELEASE_PLAN_INVALID`, `RELEASE_PLAN_UNREADABLE`); a storage failure is logged by the start guard
and never stops the task. Tests: `ReleasePlanImportTest`, `RuntimeSweepsTest`, `DeliveryPlanStoreTest`.

Upload from the page (owner, 2026-11-07): `PUT /api/v1/delivery/plan/inbox` takes the local
`delivery-plan.json` as the request body (the Delivery Plan page's Upload plan file), checks it
(`DeliveryPlanStore.inboxCandidate`: 422 `DELIVERY_INBOX_INVALID` unless it is a plan object with a
numeric `version` and a `workItems` array, 413 over 16 MB), requires its base (`baseVersion`, else
`version`) to be the DEV plan or an archived version (422 `DELIVERY_INBOX_BASE_UNKNOWN` otherwise,
nothing written), stores it as `delivery/inbox/plan.json` and merges it at once with the sync above.
`POST /api/v1/delivery/plan/inbox/sync` (Sync plan inbox) merges a file placed in the inbox by hand.
Both answer the `Sync` record `{synced, counts, version, reason}`; `reason` is `no inbox file` when
nothing waited. The owner or a co-author (`manageAccess`, or `feedbackRole` co-author); 403
`DELIVERY_ACCESS_DENIED` for everyone else, admins included. Lines: `delivery.inbox-upload`
(account, bytes, items, base) and `delivery.inbox-sync-now` (account, merged, plan.version), then the
sync's own. The Gateway forwards both like the other delivery routes (8 MB body limit). DEV and
local only (owner, 2026-11-07): in `prod` (`app.deployment-environment`) every delivery plan write
(tickets, conflicts, the inbox routes), promoting a review to a ticket and the manual sweep run answer
404 `DEV_ONLY` before any other check (`DevOnly`); PROD reads the plan only, and the 4-hour schedule
itself runs everywhere. Tests: `DeliveryPlanControllerTest`, `DeliveryPlanServiceTest`, `SweepControllerTest`.

## Sweeps (DLV-971)

`RuntimeSweeps` is the one scheduled component: at `ApplicationReadyEvent` (before the task takes
traffic) the legacy review import runs once, then the three sweeps, and the sweeps repeat every 4
hours (`PT4H`, cloud mode, where scheduling is enabled; a local Domain sweeps at each start): support
requests still `new` or `accepted` with `updatedAt` older than 7 days are closed with the note "No
update in 7 days; closed automatically. Reply or reopen to continue.", `closedBy` `system` and a
history row (reviews are never swept; any status change, note or reply resets the clock); the plan
inbox sync; and the 48-hour admission cleanup (`CloudAdmissionCleanup`, moved from hourly to this
cadence). Each sweep is isolated: a failure is logged (`sweep` / `UNEXPECTED`) and the next still
runs. Tests: `RuntimeSweepsTest`, the sweep clock in `PlatformReviewServiceTest`.

Run now (`POST /api/v1/sweeps/run` `{"job": "support-requests" | "delivery-inbox" | "admissions" |
"all"}`, `SweepController`, owner 2026-11-06): the owner's Jobs buttons on Support tickets and feedback
(owner, 2026-11-07: Close idle tickets on Support tickets and feedback, Clean up admissions on
People & Access; the plan inbox has its own routes above) run one sweep, or all three, at
once through `RuntimeSweeps.runNow`, the method the start and the schedule share
(`RuntimeSweeps.sweep(Job)`). The owner alone (`PlatformReviewService.requireOwner`: the account
that manages access, the author capability without staff access); 401 without a sign-in,
`403 PLATFORM_REVIEW_ACCESS_DENIED` for everyone else, admins included; `400 SWEEP_JOB_INVALID` for
any other body; `409 SWEEP_NOT_AVAILABLE` for `admissions` where there is no cleanup (a local Domain;
`all` there simply leaves `admissionsRemoved` null). Answer 200 `{data: {job, ranAt,
supportRequestsClosed, inboxMerged, inboxResult, admissionsRemoved}}` with the requested job's counts
filled and the others null: `ranAt` the ISO instant, `inboxResult` the plan version written when
`inboxMerged` is true, else the reason the inbox was left alone (`no inbox file`,
`delivery/plan.json missing`, `base version N not in the archive`, or `did not complete
(ExceptionType)`). A sweep that did not complete counts 0 and keeps its `sweep` / `UNEXPECTED` line.
One `sweep` line per manual run carries `manual` true, `account.id` (the owner), `job` and the
counts. The Gateway forwards the route like the delivery plan (staff sign-ins only; a DEV test
session is refused with 401). Tests: `SweepControllerTest`, `RuntimeSweepsTest`.

## Staff access and cloud profile candidates (DLV-930/933)

Domain V6 owns `staff_access` and immutable `staff_access_events`; V7 owns
`cloud_account_preferences` and `cloud_account_profile_links`. Owner-only access
assignment APIs are `/api/v1/admin/access`, `/catalog`, `/lookup`, `/waiting` and `/admit` (DLV-969); current
permissions are `/api/v1/account/permissions` and additive `auth/me.permissions`.
Roles are admin/reviewer/tester, independent of selected/full curriculum grants.
Only the existing owner capability manages access. Testers receive full courses
and own feedback; no admin/board access. The owner triages all feedback; admins read every item (DLV-971, 2026-11-06).

Pending email assignments claim only from provider-verified email on an admitted
Cognito identity, within 14 days after save. Local signup does not verify email.
No accounts merge by email. Cognito signup/admission automation is still a cloud
activation follow-up. Owner-owned plan review artifacts remain owner-only.

`S3PlatformReviewObjects` is a tested cloud candidate, using conditional ETags,
expected bucket owner and SSE-KMS. Set all three cloud values together:
`LOOKAHEAD_PLATFORM_REVIEWS_BUCKET`, `LOOKAHEAD_PLATFORM_REVIEWS_PREFIX` (trailing
slash), `LOOKAHEAD_PLATFORM_REVIEWS_KMS_KEY` (key ARN); the older singular names
`LOOKAHEAD_PLATFORM_REVIEW_*` are still read as a fallback. The prefix/key are explicit;
there is no Local emulator or cloud disk fallback. Runtime uses the existing
container task credentials and regional S3 client. In DEV the task definition
(`deployment/dev/service.template.json`) binds the three variables to the template
parameters `PlatformReviewsBucket`, `PlatformReviewsPrefix` and `PlatformReviewsKmsKeyArn`;
`deployment/dev/deploy-config.json` carries `PENDING-S3-REVIEWS` placeholders until Infra
supplies the bucket and key, and the deploy tool refuses to plan while a placeholder remains
(DLV-968). Infra must supply least-privilege S3/KMS permissions before deployment.

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

DLV-971 prepared access (owner, 2026-11-07): an invitation saved on People & Access →
Manage access → DEV gate access by email is a pass through the DEV gate. `CloudSignInRegistry`
claims it inside the first-contact transaction (`StaffAccessService.claimPrepared`) and admits
the new account at once (`admitted`, `admitted_at`, an `admit` event carrying the invitation's
roles and no actor), so the person never appears under Review requests and
`/auth/invitations/activate` then finds nothing to claim. An address with nothing prepared, or
an invitation with nothing ticked (a cancelled one), leaves the first contact waiting as before.

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
