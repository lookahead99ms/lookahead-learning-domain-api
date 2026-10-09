# Look Ahead Learning Domain API

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
The executable artifact is `target/lookahead-domain-api.jar`; its main class is
`com.lookahead.domain.DomainApiApplication`. The one-shot migration entry point
is `com.lookahead.domain.DomainApiMigration`, using `SPRING_FLYWAY_URL`,
`SPRING_FLYWAY_USER` set to the adapter's dedicated migration role and either
`SPRING_FLYWAY_PASSWORD` or `LOOKAHEAD_MIGRATION_PASSWORD_FILE`. Migration scripts
live in `db/domain`; runtime Flyway is disabled. The initial script's historical
filename and bytes remain unchanged to preserve installed migration history.

The local folder and GitHub repository are `lookahead-learning-domain-api`:
[lookahead99ms/lookahead-learning-domain-api](https://github.com/lookahead99ms/lookahead-learning-domain-api).
Java packages, artifact
names, health identifiers and environment variables now use Domain API naming.
Existing database objects and grants remain unchanged; no data migration or
runtime deployment is implied by the source rename.

## Independent build and container

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

DEV/PROD now select a verified S3 startup publication; Local retains mounted files and creates no AWS client beans. Domain alone uses the pinned S3 SDK with ECS task credentials. A bounded byte/entry cache preserves per-request authorization. Readiness includes publication initialization and existing database checks; cloud startup requires injected database secret values and verified RDS TLS. See [cloud publication configuration and validation](docs/cloud-publication.md). Local fixture checks do not certify AWS/IAM/ECS behavior. Cognito consumers and Domain durable sign-ins are implemented and locally tested. The inactive cloud candidate removes Identity and retains one Domain RDS; Local Identity is preserved. See [cloud authentication, database admission and remaining activation gates](docs/cloud-authentication.md).

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
