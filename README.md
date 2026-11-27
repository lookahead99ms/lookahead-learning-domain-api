# Look Ahead Learning Domain API

## DynamoDB persistence (DLV-970)

The Domain runs on one store: DynamoDB, four tables (**User Account Details**, **Administrator
Audit Events**, **User Study Plan Details**, **Protected Owner Identity**) read and written through
the store classes under `db/store` with PartiQL transactions fenced on the owner's `ACCOUNT#id / META` revision.
Accounts, identities, sessions, admission, the e-mailed account merge, staff access and labels,
plans/versions/progress/receipts/lifecycle, memberships, test accounts, author reviews, record
numbers and the sweeps all live there. Study-plan snapshots stay complete, account-owned copies
(≤ 200 KiB inline, larger ones as verified chunked documents with atomic publication, retirement
and bounded cleanup). Data changes are numbered steps applied by the migration entrypoint before a
deploy that needs them (`DomainDynamoCatalog`); readiness refuses to serve a store that is behind.
See [docs/dynamodb-persistence.md](docs/dynamodb-persistence.md) (design, item families,
libraries) and [docs/dynamodb-runtime.md](docs/dynamodb-runtime.md) (settings, local run,
migration entrypoint, sweeps). The protected owner identity admits itself at first contact (or at
its next sign-in, when its `OWNER / ROOT` binding was written after that contact) and every other
account action is an owner endpoint; there is no command-line account administration.

PostgreSQL (history): the Domain served Local and DEV from a PostgreSQL database (RDS `lookahead-dev-db`
on DEV) until 2026-11-17. That day the real accounts were imported into DynamoDB (data step 5, run 44162520740),
a final manual snapshot of the instance was kept, and PostgreSQL was retired under DLV-970 (owner: "delete all
Postgres things"); nothing of it is left in this repository. The RDS instance, its stacks, secrets, subnets and
IAM rows are retired by Infra and the owner's console steps (Content evidence
`DLV-970-dynamodb-migration-20261102`, `postgresql-retirement-plan.md`).

Audit items (purchases, author reviews, staff access, account maintenance) are insert-only on
DynamoDB: the application role holds `PartiQLInsert` on the audit table and nothing that changes
or removes an item. Nothing purges the audit and nothing needs to.

This independently packaged Java/Spring Boot application owns learning business
rules: content access, current course grants, plans, progress, support receipts
and local author capability.
Local uses the separate Identity application. DEV/PROD use Cognito and Domain-owned
durable sign-ins; see [cloud authentication](docs/cloud-authentication.md). Domain
has no browser login controller, authorization-server library, OAuth state
repository, signing private key, or Identity database connection.

Build from this repository with Java21 and `./mvnw verify` (Windows:
`mvnw.cmd verify`). The Maven wrapper downloads the pinned Maven distribution;
dependencies come from Maven Central. No sibling repository, private curriculum,
Toolkit library or preinstalled local artifact is needed to build and test.
On Unix, install `unzip` so the wrapper uses the checksum-pinned ZIP distribution.
The application Dockerfile supplies it in the build stage.
On Unix, a fresh wrapper download tries `wget` up to three times, then `curl`
up to three times when available, with timeouts and a two-second retry delay.
The pinned SHA-256 is still required before extraction; checksum failures and
Maven build failures are not retried. To diagnose a download failure, run
`MVNW_VERBOSE=true ./mvnw --version`. Offline bootstrap regression checks run with
`python3 -m unittest discover -s .github/pipeline/build -p 'test_maven_wrapper.py'`.
The Boot parent still owns the dependency baseline; temporary Jackson 2/3 patch
BOM overrides are documented in [Security CI](docs/security-ci.md).
The executable service artifact is `target/lookahead-domain-api-<version>.jar`;
its main class remains `com.lookahead.domain.DomainApiApplication`. The data
migration entrypoint ships in the same jar (`DomainDynamoMigration`, below).

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
- The DynamoDB items were written after the rename and carry `blaze` ids. `CatalogIdsTest` and `StudyPlanServiceLegacyCatalogTest` cover the request rules.
- The platform review page "Look Ahead Catalog" is now "Blaze Catalog"; an old tab's value
  is accepted and stored items read with the new name.

The local folder and GitHub repository are `lookahead-learning-domain-api`:
[lookahead99ms/lookahead-learning-domain-api](https://github.com/lookahead99ms/lookahead-learning-domain-api).
Java packages, artifact
names, health identifiers and environment variables now use Domain API naming.
No data migration or runtime deployment is implied by the source rename.

## Independent build and container

The [DEV activation contract](docs/dev-activation.md) inventories the application
configuration, immutable injected-secret selectors, mTLS/health ports and exact
runtime versus execution-role requirements. It separates locally checked source
behavior from pending cloud startup and cross-service verification.

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
docker build -t lookahead-domain-api:local .
```

The Docker build runs the default test suite before packaging; the DynamoDB suites
run only with the disposable DynamoDB Local fixture (below) and skip in the image build. Its context is this repository only. The image runs Java as UID10001, serves on port8080 and checks
`/actuator/health/readiness`. Configuration and data are mounted or supplied at
runtime; the image contains no curriculum or credentials. Transport records are
owned by this application, with JSON contract tests preserving their API shape.

An independent build is not a store-free service. To run the JAR or container,
provide the DynamoDB store (the four tables, migrated to `DomainDynamoCatalog.REQUIRED`),
the Local Identity verification/JWKS endpoint or cloud Cognito settings, secrets and a validated account catalog
using the configuration below. The redistributable synthetic catalog is in
`src/test/resources/accounts/`; it is sufficient for tests, not a production
curriculum. Integrated local orchestration remains infrastructure-owned.

## Required release coverage gate

DynamoDB test-account fixtures share one controllable clock across scenario creation,
membership evaluation and store timestamps. `TestAccountService` defaults to the UTC
system clock in production; database tests inject their fixture clock and verify creation,
expiry and reset at fixed dates, so memberships do not become future-dated as CI ages.

CI and release validation run `./mvnw -Pcoverage clean verify` against one disposable,
in-memory DynamoDB Local. JaCoCo 0.8.15 measures every production class, without source
exclusions, and fails verification below **85% line coverage**. Branch coverage is reported
separately. Reports are written to `target/site/jacoco/index.html` and
`target/site/jacoco/jacoco.xml`.

```sh
docker run -d --rm --name dynamodb-local -p 127.0.0.1:4445:8000 amazon/dynamodb-local:2.6.1 -jar DynamoDBLocal.jar -inMemory -sharedDb
DLV970_DYNAMODB_TEST_ENDPOINT=http://127.0.0.1:4445 DLV970_LOCAL_DYNAMODB_ENDPOINT=http://127.0.0.1:4445 \
./mvnw --batch-mode --no-transfer-progress -Pcoverage clean verify
```

Both variables must name a loopback endpoint; the coverage profile fails
(`CoverageFixtureAvailabilityTest`) when either is absent, rather than silently skipping
persistence validation. Every `*DatabaseTest` creates the four tables when absent and purges the
key prefixes it writes; never point the variables at the persistent laptop store. GitHub CI starts
the same pinned image as a step. The ordinary `./mvnw verify` and Docker build remain
independently executable, but do not certify the required coverage gate.

The suite checks Local provider preservation, DEV/PROD Cognito and publication
bean selection, invalid configuration rejection, and the real sign-in, review and plan
lifecycle behaviour on DynamoDB Local. AWS provider calls remain mocked; coverage and
local store passes do not certify live Cognito, S3 IAM, DynamoDB IAM or ECS secret injection.

## Configuration

| Property | Environment convenience | Purpose |
| --- | --- | --- |
| `app.deployment-environment` | `LOOKAHEAD_ENVIRONMENT` | Explicit `local`, `dev` or `prod` |
| `app.dynamodb.*` | `AWS_REGION`, `LOOKAHEAD_DYNAMODB_*_TABLE`, `LOOKAHEAD_DYNAMODB_ENDPOINT` (local only) | The DynamoDB store; see [docs/dynamodb-runtime.md](docs/dynamodb-runtime.md) |
| `app.identity.issuer` | `LOOKAHEAD_OAUTH_ISSUER` | Expected public token issuer |
| `app.identity.upstream` | `LOOKAHEAD_IDENTITY_UPSTREAM` | Fixed internal Identity origin |
| `app.identity.gateway-client-id` | `APP_OAUTH_CLIENT_ID` | Expected originating OAuth client |
| `app.identity.verifier-secret` | `LOOKAHEAD_IDENTITY_VERIFIER_SECRET` | Separate service-verification secret, at least 32 characters |
| `app.accounts.catalog-path` | `LOOKAHEAD_ACCOUNT_CATALOG` | Trusted account catalog file |
| `app.content.publication-path` | `LOOKAHEAD_CONTENT_PUBLICATION` | Immutable publication manifest |
| `app.content.root` | `LOOKAHEAD_CONTENT_ROOT` | Read-only published content directory |
| `app.publication.live-prefix` | `LOOKAHEAD_PUBLICATION_LIVE_PREFIX` | DLV-971: the prefix holding exactly one publication tar (DEV `release/live/` after the AWS lane's cutover; empty keeps today's `live/<tag>/` discovery) |
| `app.reviews.prefix`, `app.support-requests.prefix`, `app.delivery.prefix` | `LOOKAHEAD_REVIEWS_PREFIX`, `LOOKAHEAD_SUPPORT_REQUESTS_PREFIX`, `LOOKAHEAD_DELIVERY_PREFIX` | DLV-971 runtime store roots in the assets bucket (`reviews/`, `support-requests/`, `delivery/`) |

Configuration-tree files can supply the corresponding dotted property names;
`LOOKAHEAD_SECRETS_DIRECTORY` selects their directory. Never mount Identity DB
credentials, password fixtures or signing private keys in this application.
DEV and PROD require the Cognito configuration in the cloud guide. Explicit local mode permits loopback
HTTP and fixed internal names `identity`, `identity-api`, `lookahead-identity`.
Public issuer HTTP remains loopback-only. `accounts` and `resource` roles are
always activated; mixed gateway/authorization-server roles fail startup.

The store settings are checked once at startup (`DynamoConnection`): Local needs a loopback
`LOOKAHEAD_DYNAMODB_ENDPOINT`; dev and prod need `AWS_REGION` and the four approved table names
and refuse an endpoint override. Every DynamoDB call is bounded (2 s per call, 1 s per attempt on
readiness) and readiness checks the data-version watermark against the build's requirement.

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
access. Existing account UUIDs were preserved by the DEV import of 2026-11-17. Every
product item is keyed by the account (`ACCOUNT#id / …`); nothing joins another store.
Domain API does not persist a stale copy of Identity's enabled state.

An account disable blocks newly verified requests, but cannot atomically cancel
an already admitted Domain API transaction. Plan/version/activity/
mutation-receipt writes remain one Domain API transaction. Support receipt
reservation and per-account limits commit under the owner's `ACCOUNT#id / META` revision,
answering 409 after three lost fences; uncertain
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
Tests on DynamoDB Local do not certify the IAM grants of the real tables, point-in-time
recovery or the three-application login flow. Those require the isolated infrastructure integration gate before cutover.

## Contracts with other repositories

No repository clones another at build or deploy time, and every cross-repository contract lives in
one place only: the Content repository, under `contracts/` (`registry.json`,
`web-content-paths.json`, `release-layout.json`, `domain-api-v1.json`; owner decisions 2026-11-05
and 2026-11-06). This repository keeps no copy. `.github/pipeline/build/fetch-contracts.sh` fetches the two files
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
| `contracts/domain-api-v1.json` (`gateway-domain-contract/v1`: every Domain call the Gateway makes, its method, path, success statuses and the response fields the Gateway reads) | Gateway; the Gateway edits the master on Content first | `DomainProviderContractTest`: every call is a real controller mapping, deferred routes stay unmapped, and the calls whose answer the Gateway interprets are driven through the real controllers on stubbed services | Content master first (the Gateway's change), then this repository provides the route, status or field, then the Gateway's own pull request, then Web | A listed route is missing from Domain or answers an unlisted status or field: Domain is behind the master on Content |
| `contracts/release-layout.json` (`release-layout/v1`: the `live/<tag>/` layout, the keys of `manifest.json` and `publication.json`, and the keys and record fields of `account-catalog.json`) | Content | `ReleaseLayoutContractTest`: builds a minimal release from the contract alone and loads it through `PublicationArchiveLoadService`, `AccountCatalogService` and `StudyPlanSnapshotValidator`; a release or catalog missing a required key is refused the way the readers refuse, an absent optional key is accepted, and an unknown record field is shown to be ignored | Content first for a new optional key (with a bumped `schemaVersion`), then this repository adopts it. A required key or a removed key needs the Domain change merged before the Content release that carries it | The release Content would publish cannot be loaded by Domain, or the master no longer says what Domain reads |

A test that fails with "Contract file absent" has nothing to do with the contract: the fetch did
not run (locally: run `.github/pipeline/build/fetch-contracts.sh`; CI: the fetch step failed or the secret was not
passed, and the step's own message names the fix). Content's `registry.json` lists both files with
this repository and these two tests as consumers; Content compares nothing with this repository.

## Author review decisions

The local DLV-921 candidate records immutable, version-bound author review events. Identical concurrent retries return the original receipt; changed payloads and stale decisions retain their distinct conflict codes. See [API, manifest configuration, migration and verification](docs/author-review-api.md) and [OpenAPI source](docs/author-review-openapi.json). Recording a decision does not update delivery status or Git.

### Study-plan naming

Plans carry names and stable per-account plan numbers (legacy display labels came from
`goal`). The account's `META` item holds the last allocated number (`lastPlanNumber`); the
owner-fenced transaction serializes concurrent creates and deletion does not reuse a number. Create accepts an optional `name`; omission
generates `Study plan #N_DDMMYYYY_HXDays` using UTC creation date and configured
`dailyHours`/`days`. Names are independent of goals and immutable schedule versions.

`POST /api/v1/plans/{id}/name` accepts `{expectedRevision, name}` and the existing
idempotency key/owner protections. It changes only name, revision, update time and
an audit event; schedule, progress and creation number are preserved. Trimmed names
are1–160 characters with no control characters. List/detail return `name` and
`planNumber`; list also returns advisory `nextPlanNumber`. The account catalog
advertises `planNamingPolicies: ["plan-name-v1"]`.


## Service deployment definition

This application deploys itself to DEV. Its deployment definition is
`.github/pipeline/deploy/dev/service.template.json` (the ECS task definition and service of the stack
`lookahead-dev-domain`) with `.github/pipeline/deploy/dev/deploy-config.json` (cluster, stack, log group, Cloud Map
service, role and secret-version bindings, the four DynamoDB table names, observation window), applied by
`.github/pipeline/deploy/deploy.py` through `04 · Deploy DEV`; `.github/pipeline/shared/README.md` describes the
stages. The DEV data migration (`07 · Migrate DEV data`, `.github/pipeline/deploy/migrate.py`) reads the same two
files. Infra owns the shared AWS resources the service runs on (network, cluster, IAM roles, secrets, registry). Since
the owner decision of November 27, 2026 no tool or workflow here reads a file of the Infra repository and Infra reads
no file of this one; the earlier service contract file in the root `deployment/` folder (`lookahead-service/v2`, the
input of an offline planner Infra used to have) was removed that day.

## Cloud publication application candidate

DEV/PROD now select a verified S3 startup publication; Local retains mounted files and creates no AWS client beans. Domain alone uses the pinned S3 SDK with ECS task credentials. A bounded byte/entry cache preserves per-request authorization. Readiness includes publication initialization and the DynamoDB store check; cloud startup requires the ECS task role and the default AWS endpoints. Since 2026-10-27 a content release is identified by its Content calendar release tag `vYYYY.MM.DD.HH.NN` (S3 keys `releases/<tag>/…` and `live/<tag>/…`, pin `LOOKAHEAD_PUBLICATION_RELEASE_ID=<tag>`), not by a content hash; live mode serves the complete `live/` folder with the highest tag. See [cloud publication configuration and validation](docs/cloud-publication.md). Local fixture checks do not certify AWS/IAM/ECS behavior. Cognito consumers and Domain durable sign-ins are implemented and locally tested. The cloud candidate removes Identity; Local Identity is preserved. See [cloud authentication, database admission and remaining activation gates](docs/cloud-authentication.md).

The image carries no database CA bundle; it trusts the JVM's CA set for AWS endpoints.

The container readiness probe is `com.lookahead.domain.health.ContainerHealthcheck`
under `src/main/java`, included in the normal production coverage inventory.
Its tests cover real loopback success/failure responses, strict readiness payloads,
request timeout configuration and interruption. Docker compiles that same measured
source for the standalone JVM probe.
Wrapper distribution setup has a source-independent Docker layer; dependency
versions and checksum verification are unchanged.

### Publication from the live tar (DLV-971)

The approved bucket layout serves the publication from one tar: at start, with
`app.publication.live-prefix` set (DEV `release/live/` once the AWS lane has copied the tar there;
`.github/pipeline/deploy/dev/deploy-config.json` keeps `PublicationLivePrefix` empty until that cutover), the Domain
lists the prefix, expects exactly one `v*.tar` (the publication as today, plain or gzip tar, with
`publication.json` and `account-catalog.json` inside and no manifest beside it), downloads and
unpacks it into the private publication folder (`PublicationLiveLoadService`), and reports the version
(the file name) in the readiness detail `release` and in one `publication.load` line. Zero or more
than one tar, a stray object under the prefix, or a tar that fails its checks (unsafe member, an
asset whose digest differs from `publication.json`, an unlisted file) leaves the content unloaded:
readiness is DOWN with the reason (`LIVE_RELEASE_MISSING`, `LIVE_RELEASE_AMBIGUOUS`,
`LIVE_RELEASE_UNEXPECTED`, `LIVE_RELEASE_INVALID`, `LIVE_RELEASE_UNAVAILABLE`) and the task stays up
with an empty trusted catalog until the next start finds one good tar; nothing restarts on its own
beyond the container health check. With the setting empty, today's `live/<tag>/` discovery
(`PublicationArchiveLoadService`) is unchanged, so the current DEV keeps running until the cutover.
Tests: `PublicationLiveLoadServiceTest`.

### Reading the log (plain lines, owner decision 2026-11-17)

The application log is for one person troubleshooting in CloudWatch, not for dashboards. Every
line is one plain sentence with no schema to learn: no JSON, no key=value block, no logger name,
thread, PID or service name. The same pattern serves every profile (set once as
`logging.pattern.console` in `application.yml`; `EventLogPlainLoggingTest` renders it and asserts the
exact lines):

```
<UTC timestamp with millis> <LEVEL padded to 5> <sentence>  [request <first 8 chars of the request id>]
```

```
2026-11-17T20:41:18.144Z INFO  POST /api/v1/plans -> 200 in 371 ms  [request 5e17c0aa]
2026-11-17T20:44:12.300Z WARN  Free plan limit hit for account faaac829-…: at most 2 active free plans at once  [request 7a51d9e0]
2026-11-17T18:52:30.004Z ERROR Readiness DOWN for dynamodb: the DynamoDB data is at version 4 but this release requires 5 (DATA_VERSION_BEHIND); run 07 · Migrate DEV data with this release, then deploy again; the container health check fails until then
2026-11-17T21:03:14.221Z ERROR POST /api/v1/plans failed after 41 ms: DynamoDB ExecuteTransaction on lookahead-dev-user-study-plan-details was refused (DynamoDbException: User arn:aws:sts::…:assumed-role/lookahead-dev-app-task/… is not authorized to perform: dynamodb:PartiQLInsert …); answered 503 ACCOUNT_STORAGE_UNAVAILABLE; reference e2b8f14c-…  [request e2b8f14c]
java.lang.… (the stack trace follows an ERROR line of the unexpected-failure handlers, and only those)
```

- The request bracket is there only while a request is being served; lines written outside a
  request (startup, readiness, sweeps, the migration task) end after the sentence.
- Levels: INFO is normal progress. WARN is an expected refusal (wrong password, limit hit,
  locked content, waiting account, validation) in short wording, never with a stack trace.
  ERROR is something an operator must act on: misconfiguration, permission denied, dependency
  down, a bug. The word `ERROR` appears on a line only as the level of an ERROR line; the deploy
  and restart tools scan the log group for it (`.github/pipeline/deploy/dev/deploy-config.json`
  `logFailurePatterns`, also `startup denied`, `AccessDeniedException`, `Unable to create tempDir`).
- Every ERROR line answers five questions in one sentence: what was being done (request or
  job), which dependency and operation on which resource failed (table, bucket/key, host), why
  (exception type and the dependency's own message, HTTP status, AWS error code), what the
  caller got (status and code), and the reference of the stack trace that follows. A hint is
  added where the code knows the cure: readiness `DATA_VERSION_BEHIND` → run 07 · Migrate DEV
  data; connection refused / connect timeout → the VPC endpoint and security group; a permission
  refusal → the IAM policy; a missing table → the Infra stack. On ERROR lines the dependency's
  message IS quoted (IAM denials and AWS request ids help); secrets, tokens, passwords, cookies,
  Authorization headers and request bodies never are (`EventLog.scrub`). Startup refusals name the
  setting and the problem but do not quote the validation exception, which could echo the value.
- Owner and admin actions name the actor in the sentence: `Owner 1a2b3c4d downgraded account …`,
  `Admin 2b7d9e44 …`, or `Account 3c8f1a2b …` when the role is not known at the call site.
  Counts and codes a reader needs are in the sentence too (delivery plan import counts, legacy
  review import counts, the readiness component and reason code, lifecycle reason codes).
- The `EventLog`/`addKeyValue` calls stay in the code (tests assert on them with `LogCapture`,
  and a log pipeline could render them again); they do not appear on the line.

How to search in CloudWatch Logs Insights (log group `/lookahead/dev/domain`):

- One request end to end, in Domain and in the Gateway (the same id travels in `X-Request-Id`):
  `fields @timestamp, @message | filter @message like /\[request 5e17c0aa\]/ | sort @timestamp asc`.
  The reference a caller quotes from a 500 answer is the full request id; search its first 8
  characters.
- Everything an operator must act on: `filter @message like / ERROR /` (the level word with its
  spaces, so a code such as `UNEXPECTED_ERROR` in a WARN summary does not match).
- One route: `filter @message like /POST \/api\/v1\/plans /`; refusals of one kind:
  `filter @message like /-> 403 /` or `/limit hit/`.
- Why a task failed to start or stay healthy: `filter @message like /Readiness DOWN|Startup refused|Started:|Stopping:/`.
  `Readiness DOWN for <component>: … (<REASON>)` repeats every minute while it lasts and
  `Readiness UP for <component>` is written once on recovery; `Startup refused: <setting> <what
  is wrong>; the app stops before it takes traffic` names the environment variable, property or
  file; `Started: version … (release tag …, revision …)` and `Stopping: shutdown requested …
  after N s; readiness before the stop …; last readiness checks: …` explain an exit code 143.
- The migration task: `filter @message like /Data step|Migration /`; its one JSON report line
  on stdout is parsed by `.github/pipeline/deploy/migrate.py` and is not a log line.

## SAST gate diagnostics

`python3 .github/pipeline/build/check.py sarif` requires completed CodeQL invocations,
valid rule/result inventories and exercised, source-bound exceptions. A failure
prints a reviewed constant reason (for example, missing invocation inventory or
unexercised exception) while leaving untrusted error text and SARIF messages out
of public logs. Warning/error notifications still block; this diagnostic change
does not waive findings or weaken the security gate. Raw SARIF and source
databases remain unpublished. Run the tooling regressions with
`python3 -m unittest discover -s .github/pipeline/build -p 'test_checks.py'`.

SAST exception hashes are also checked in the first tooling-test step; review
the security boundary before updating a hash after a source change. Expiry and
mandatory exception usage stay enforced. SARIF trace notifications (`none`)
are informational alongside `note`; warnings, errors and unknown levels block.
A rejected notification logs only its level and a bounded Java diagnostic ID,
never its message, source snippet, locations or properties.


The cloud admission CSRF disposition in `.github/pipeline/build/sast-exceptions.json`
is bound to the entire `SignInSecurityConfiguration.java` source SHA and expires
on 2026-10-19. The internal chain accepts explicit bearer and Gateway-secret
headers, never browser cookies; missing/wrong Gateway credentials fail closed,
and every successful authentication still verifies the token against Cognito.
`SignInSecurityConfigurationChainTest` exercises both DEV and PROD filter chains with
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

The November 16 base refresh includes Ubuntu's APT fix and removes the expired
vulnerability exception; see [Security CI](docs/security-ci.md#apt-exception-removal-november-16-2026).

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

The `release.yml` workflow (`01 · Full`; `02 · Build` and `03 · Publish` call it)
reuses the full CI job (`ci.yml`), then exports its exact scanned image as a one-day archive. Publication loads that
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
See [image publication and recovery](.github/pipeline/shared/README.md). No workflow was dispatched or
image published locally. Configure approved settings and action allowlists before
activation; private content and AWS credentials are never bundled in the image.


Publication explicitly pushes only the verified `linux/amd64` manifest (Docker API
1.46 or newer). The registry receipt checks its config digest against the saved
archive; Docker Desktop index IDs are kept separate from that config identity.

## Platform reviews (DLV-928)

`/api/v1/platform-reviews` (packages `com.lookahead.domain.controller`,
`com.lookahead.domain.service.review` and `com.lookahead.domain.db.store.review`) stores reviewer
feedback without a database table. DLV-971 (owner, 2026-11-06): one JSON record per item in two
runtime stores of the assets bucket, `reviews/records/YYYY/MM/DD/REV-000001.json` and
`support-requests/records/YYYY/MM/DD/SUP-000001.json` (the day is the record's creation day, UTC),
with the images beside each record under `<store>/attachments/<ID>/NN-name.ext`. Ids come from two
DynamoDB counters (`SEQUENCE#review-record` and `SEQUENCE#support-request / COUNTER`, data step 4; `PlatformReviewRecordNumberStore`), unique and increasing, not gapless. `PlatformReviewObjects` is the
storage boundary, one instance per store root; implementations are the Local filesystem store (the
roots are sub-folders of `LOOKAHEAD_PLATFORM_REVIEW_DIRECTORY`) and the S3 store (GetObject,
conditional PutObject, ListObjectsV2 with and without a delimiter, DeleteObject, SSE-KMS with the
publication key). `PlatformReviewRecordStore` reads a record, applies the change and writes it back only if it is
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
| `LOOKAHEAD_PLATFORM_REVIEW_DIRECTORY` | Local private directory; the stores are its `reviews/`, `support-requests/` and `delivery/` sub-folders. Unconfigured storage answers 503; cloud uses the S3 settings |
| `LOOKAHEAD_PLATFORM_REVIEWS_BUCKET`, `LOOKAHEAD_PLATFORM_REVIEWS_KMS_KEY` | The assets bucket and the publication KMS key, shared by every store (`app.platform-reviews.bucket`, `.kms-key`) |
| `LOOKAHEAD_REVIEWS_PREFIX` | `app.reviews.prefix`, the review store root (DEV `reviews/`) |
| `LOOKAHEAD_SUPPORT_REQUESTS_PREFIX` | `app.support-requests.prefix`, the support request store root (DEV `support-requests/`) |
| `LOOKAHEAD_DELIVERY_PREFIX` | `app.delivery.prefix`, the delivery plan root (DEV `delivery/`) |
| `LOOKAHEAD_PLATFORM_REVIEWERS` | Legacy compatibility setting; ignored by the wired runtime. Current authorization uses Domain staff assignments. The owner is the author capability |

Limits: 6 images per item, 5 MB each, 20 MB per item, PNG/JPEG/WebP checked by file signature,
EXIF/XMP/IPTC/PNG-text metadata removed without re-encoding; multipart request at most 21 MB;
100 items per reviewer per day. The container image creates
`/var/lib/lookahead/platform-reviews` owned by the app user so a new named volume mounted
there is writable. Tests: `PlatformReviewServiceTest`, `PlatformReviewControllerTest`,
`PlatformReviewFileSystemObjectStoreTest`, `PlatformReviewS3ObjectStoreTest`, `PlatformReviewS3ClientTest`, `PlatformReviewRecordNumberStoreDatabaseTest`.

Kinds and support requests (DLV-971, owner 2026-11-06). Every item carries `kind`: `"review"` for an
item written on Write Review, `"request"` for a learner's support request (Report a problem, Share
feedback, Ask a question). A request also carries `requestType` and `reference` (the
support receipt's reference, so the owner can match it to the email thread); on a review both are
`null`. `SupportFeedbackService.submit` records the request through
`PlatformReviewService.recordRequest` into the support-requests store before it emails the support
inbox: category `problem` for a problem and `idea` for feedback or a question, page `Support`, no page
URL, priority `medium`, status `new`, the learner's feedback role (`user`) as the submitter role, the
screenshots stored through the review image pipeline; the reply address stays in the email and the
receipt only. The receipt reference is the dedupe key: the same request is never recorded twice.
Who sees and does what: the owner and admins list every item; a reviewer lists the reviews they
wrote, never a request; a learner has no Team Reviews access. The owner sets the status of any
item, an admin only of a request (`403 PLATFORM_REVIEW_ACCESS_DENIED` on a review); a review is
edited or deleted by its writer while New (the owner's status locks it); a request is never
edited through the review API and only the owner deletes it.

Statuses are `new`, `accepted`, `planned`, `done`, `declined` and `closed`. `closed` is set by hand
(`PUT .../triage`, `closedBy` the actor's role) or by the sweep; `closed` to any status is a reopen
under the same rights and clears `closedAt`/`closedBy`. Every status change of a record or a ticket
is one insert-only item in Administrator Audit Events (`PlatformReviewStatusHistoryStore`:
`RECORD#<kind>#<recordId> / STATUS#<time>#<id>` with the kind (review|request|ticket), the actor
(`system` for the sweep), the two statuses and whether a note was set; the application role can only
insert there). A history failure is logged (`platform-review.history` / `HISTORY_NOT_WRITTEN`) and
never undoes the change.

Promote (`POST /api/v1/platform-reviews/{id}/promote`, 201): a work item is added to the delivery
plan (title and summary from the record's title and details, type `bug` for a problem, `task` for a
content review or a question, else `story`; priority high/medium/low to P1/P2/P3; the workflow's
first status; the plan's current stage; `origin {id, kind}`; the environment stamps) and
`ticketId` is set on the record; the answer is `{data: {ticketId, item}}`. The owner promotes any
record, an admin a learner's request only; a record already promoted answers
`409 PLATFORM_REVIEW_ALREADY_PROMOTED`; 404 for an unknown record; 503 when the plan storage is
unavailable (the record stays unlinked).

## Delivery plan (DLV-971)

`/api/v1/delivery/plan` (packages `com.lookahead.domain.controller`,
`com.lookahead.domain.service.delivery` and `com.lookahead.domain.db.store.delivery`) serves and edits
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

Published plan at start (DLV-998, owner 2026-11-09; `DeliveryPlanReleaseImportJob`): the publication tar carries the
Content repository's `src/delivery-plan/delivery-plan.json` as `content/staff/delivery-plan.json` (a local Domain serving a
content tree reads `delivery/delivery-plan.json` under its content root). After the legacy review import and before
the sweeps, `SweepRunner.atStart` runs the import: no `plan.json` yet, the released file becomes the first plan as it
is (`DeliveryPlanStore.seed`, If-None-Match, its own version); otherwise it becomes the inbox and the sync above merges it
at once, its `baseVersion` (else `version`) having to be the DEV plan or an archived version. The SHA-256 of the imported
bytes is kept in `delivery/inbox/.release-import.json` with the release tag and the plan version, so a restart on the same
release imports nothing and the version does not climb; a file already in the inbox (an owner's upload) is never
replaced and the release is imported at the next start. DEV and local only (`DevOnly.allowed`); in prod nothing runs.
One `delivery.release-import` line: `seeded`, `merged` (with the counts) or the reason it was left (`BASE_VERSION_UNKNOWN`,
`INBOX_OCCUPIED`, `RELEASE_PLAN_INVALID`, `RELEASE_PLAN_UNREADABLE`); a storage failure is logged by the start guard
and never stops the task. Tests: `DeliveryPlanReleaseImportJobTest`, `SweepRunnerTest`, `DeliveryPlanStoreTest`.

Upload from the page (owner, 2026-11-07): `PUT /api/v1/delivery/plan/inbox` takes the local
`delivery-plan.json` as the request body (the Delivery Plan page's Upload plan file), checks it
(`DeliveryPlanFileValidator.plan`: 422 `DELIVERY_INBOX_INVALID` unless it is a plan object with a
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

`SweepRunner` is the one scheduled component: at `ApplicationReadyEvent` (before the task takes
traffic) the legacy review import runs once, then the three sweeps, and the sweeps repeat every 4
hours (`PT4H`, cloud mode, where scheduling is enabled; a local Domain sweeps at each start): support
requests still `new` or `accepted` with `updatedAt` older than 7 days are closed with the note "No
update in 7 days; closed automatically. Reply or reopen to continue.", `closedBy` `system` and a
history row (reviews are never swept; any status change, note or reply resets the clock); the plan
inbox sync; and the 48-hour admission cleanup (`DevAccessCleanupJob`, moved from hourly to this
cadence). Each sweep is isolated: a failure is logged (`sweep` / `UNEXPECTED`) and the next still
runs. Tests: `SweepRunnerTest`, the sweep clock in `PlatformReviewServiceTest`.

Run now (`POST /api/v1/sweeps/run` `{"job": "support-requests" | "delivery-inbox" | "admissions" |
"all"}`, `SweepController`, owner 2026-11-06): the owner's Jobs buttons on Support tickets and feedback
(owner, 2026-11-07: Close idle tickets on Support tickets and feedback, Clean up admissions on
People & Access; the plan inbox has its own routes above) run one sweep, or all three, at
once through `SweepRunner.runNow`, the method the start and the schedule share
(`SweepRunner.sweep(Job)`). The owner alone (`PlatformReviewService.requireOwner`: the account
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
session is refused with 401). Tests: `SweepControllerTest`, `SweepRunnerTest`.

## Staff access and cloud profile candidates (DLV-930/933)

`StaffAccessStore` keeps the staff assignments (`STAFFACCESS / ASSIGNMENT#id`) and their insert-only
events; account preferences and profile links are items of the account (`ACCOUNT#id / PREFERENCES`,
`ACCOUNT#id / LINKS`). Owner-only access
assignment APIs are `/api/v1/admin/access`, `/catalog`, `/lookup`, `/waiting` and `/admit` (DLV-969); current
permissions are `/api/v1/account/permissions` and additive `auth/me.permissions`.
Roles are admin/reviewer/tester, independent of selected/full curriculum grants.
Only the existing owner capability manages access. Testers receive full courses
and own feedback; no admin/board access. The owner triages all feedback; admins read every item (DLV-971, 2026-11-06).

Pending email assignments claim only from provider-verified email on an admitted
Cognito identity, within 14 days after save. Local signup does not verify email.
No accounts merge by email. Cognito signup/admission automation is still a cloud
activation follow-up. Owner-owned plan review artifacts remain owner-only.

`PlatformReviewS3ObjectStore` (it decides; `PlatformReviewS3Client` only makes the S3 calls) is a tested cloud candidate, using conditional ETags,
expected bucket owner and SSE-KMS. Set all three cloud values together:
`LOOKAHEAD_PLATFORM_REVIEWS_BUCKET`, `LOOKAHEAD_PLATFORM_REVIEWS_PREFIX` (trailing
slash), `LOOKAHEAD_PLATFORM_REVIEWS_KMS_KEY` (key ARN); the older singular names
`LOOKAHEAD_PLATFORM_REVIEW_*` are still read as a fallback. The prefix/key are explicit;
there is no Local emulator or cloud disk fallback. Runtime uses the existing
container task credentials and regional S3 client. In DEV the task definition
(`.github/pipeline/deploy/dev/service.template.json`) binds the three variables to the template
parameters `PlatformReviewsBucket`, `PlatformReviewsPrefix` and `PlatformReviewsKmsKeyArn`;
`.github/pipeline/deploy/dev/deploy-config.json` carries `PENDING-S3-REVIEWS` placeholders until Infra
supplies the bucket and key, and the deploy tool refuses to plan while a placeholder remains
(DLV-968). Infra must supply least-privilege S3/KMS permissions before deployment.

Cloud account preference/link routes use the current Cognito sign-in proof and
Domain account lock, preserving Local request shapes. `SignInStoreDatabaseTest`
checks persistence, account isolation and revocation against the disposable DynamoDB Local
fixture; `PlatformReviewS3ClientTest` checks concurrent
conditional writes with a stubbed SDK client. Neither is live AWS certification.

### Lower-cost DEV candidate

The `lower-cost-dev` profile serves application mTLS on8443 and loopback-only
management readiness on8081. TLS validates the peer chain; the application filter
also requires the Gateway client identity `gateway.lookahead-dev.internal` and
clientAuth usage. OAuth and Domain account/ownership checks remain required.
Application credentials now use immutable-version ECS injection. Private keys must be one complete
unencrypted PKCS8 PEM block with line-separated boundaries; incomplete envelopes,
extra blocks and surrounding material are rejected before startup. `InjectedCredentialInitializer`
validates certificate validity, remaining lifetime, SAN, EKU, chain and matching
PKCS8 keys before context refresh, then installs inline Spring PEM and scalar
properties. Inputs are `LOOKAHEAD_TLS_{SCHEMA,SERVER_CERTIFICATE,SERVER_KEY,TRUST}` and
`LOOKAHEAD_DOMAIN_GATEWAY_SECRET` (`LOOKAHEAD_DOMAIN_DB_PASSWORD` left with PostgreSQL on
2026-11-17 and is neither required nor read); `LOOKAHEAD_TLS_SERVER_NAME` is public
configuration. Validation errors reveal no input values and deny startup.

This is local candidate code, not AWS deployment evidence. The standard Local
configuration and preserved Service Connect mode remain separate. Infra owns
orchestration, runtime templates and operation runbooks; the data steps stay here.

Lower-cost DEV security events use the dedicated `lookahead.security.audit` logger
with fixed outcome names and random event IDs. The filter preserves HTTP responses
and emits no request paths, tokens or bodies. Infra owns the separately scoped
audit relay and90-day archive; local tests do not verify AWS delivery.

The Docker context explicitly includes only `.github/pipeline/build/container/dns.security`
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
Manage access → DEV gate access by email is a pass through the DEV gate. `SignInService`
claims it inside the first-contact transaction (`StaffAccessService.claimPrepared`) and admits
the new account at once (`admitted`, `admittedAt`, an `admit` event carrying the invitation's
roles and no actor), so the person never appears under Review requests and
`/auth/invitations/activate` then finds nothing to claim. An address with nothing prepared, or
an invitation with nothing ticked (a cancelled one), leaves the first contact waiting as before.

## Local membership purchases (DLV-209)

Domain owns `/api/v1/membership/plans`, `/api/v1/sales/active`, account purchase/quote/access-record endpoints and staff upgrades/sales/price endpoints. Saved study plans retain `/api/v1/plans`. Purchases require `Idempotency-Key`; `expectedPriceCents` optionally rejects a stale quote. Effective catalog access is computed dynamically with server-time expiry. Nothing renews automatically.

Local and DEV accept only mock payments (`APP_PAYMENTS_PROVIDER=mock`, default). Select `APP_PAYMENTS_MOCK_SCENARIO`: `success` (default), `decline`, `requires-action`, `network`, `rate-limit`, `server-error`, or `timeout-after-success`. Only change this on an isolated test deployment; pending outcomes never grant access. At most three attempts per purchase, jittered exponential delay and a three-second retry-loop budget. Unknown outcomes need investigation; real settlement/refunds/webhooks are not enabled. The Stripe SDK adapter is unregistered and never used in Local/DEV; do not supply keys. Student/regional discount rules and Course Pass duration remain pending.

`./mvnw test` runs unit/contract checks. `MembershipStoreDatabaseTest` runs against DynamoDB Local when `DLV970_DYNAMODB_TEST_ENDPOINT` is set; it writes and removes only its own synthetic items. Never point it at application tables.

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

Saved-plan mutation transactions are fenced on the owner's `ACCOUNT#id / META` revision,
including retries. This serializes distinct mutation keys for the same account across
instances, while other owners remain independent. `StudyPlanStoreDatabaseTest` and
`StudyPlanLifecycleStoreDatabaseTest` check the fence and the lost-fence 409 against the
disposable DynamoDB Local fixture. This source change is not yet activated and does not
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

The DLV-938 rules enforce free-account allowances inside the owner-fenced
plan transactions.
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
by this browser route. `AccountMaintenanceStore` writes the maintenance audit.

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

### Calendar image releases

DEV image releases use one shared `lookahead-dev-ecr` repository and `domain-vyyyy.mm.dd.hh.<00-nn>` tags. The source Git tag omits `domain-`. UTC counters start at `00` each hour independently per app. Run `python3 .github/pipeline/shared/release_version.py next --app domain` to suggest the next version from remote Git tags; this does not create or reserve a tag. Publish the exact source tag through the user-owned Git cycle, then supply it as `release-version` to the manual release workflow on main. CI verifies the tag/source before building and retains app/version/source/config/archive/manifest provenance. Deployment still selects an immutable digest. Prefixes organize images; IAM upload scope covers the shared repository.
Maven builds accept `-Drevision=yyyy.mm.dd.hh.NN`; local builds retain the snapshot default.


DEV image publication uses the existing shared deployment role with an
`image-publish-*` session name required by its trust policy. The role explicitly
restricts these sessions to ECR publication operations; the workflow retains its
additional repository-scoped session policy, existing approval and scan gates.
Infra owns the reviewed policy update and registry provisioning. These local
workflow changes do not install IAM policies or publish an image.

## DynamoDB store and DEV data migration

DLV-970. `07 · Migrate DEV data` runs the migration
entrypoint of a release's own image as a one-off Fargate task, then `04 · Deploy DEV` serves that release
("data first, then app"). DEV has served DynamoDB since the cutover of 2026-11-17; there is no store
switch, so a deploy never names a store.

### The task definition

`.github/pipeline/deploy/dev/service.template.json` has four table parameters pinned by `AllowedPattern` to the DEV names
(`UserAccountDetailsTable` = `lookahead-dev-user-account-details`, `AdministratorAuditEventsTable`,
`UserStudyPlanDetailsTable`, `ProtectedOwnerIdentityTable`). The container reads them as the four
`LOOKAHEAD_DYNAMODB_*_TABLE` variables beside `AWS_REGION` and `LOOKAHEAD_ENVIRONMENT=dev`; it carries no
database URL, user or secret. `.github/pipeline/deploy/dev/deploy-config.json` carries the four names;
`test_dev_persistence_store.py` pins both files.

### Order: migrate, then deploy

The migration entrypoint ships in the application image
(`java -cp /opt/lookahead/app.jar -Dloader.main=com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoMigration org.springframework.boot.loader.launch.PropertiesLauncher migrate|verify|status`)
and must run under `lookahead-dev-migration-task` before the application of the same release is
deployed when the release adds a data step ("data first, then app"). There is no pipeline gate: the running
release keeps serving while the data moves ahead; a release deployed before its migration reports
`DATA_VERSION_BEHIND` in readiness and never becomes healthy, and the ECS circuit breaker rolls it back.

Data step 5 (`account-access-import`, owner decision 2026-11-17) read the PostgreSQL platform database once on
DEV (run 44162520740) and wrote every real account with its PostgreSQL id into the store; its result is the
`IMPORT#account-access / RESULT` marker. The source was retired the same day: the step keeps its number, name
and definition (the DEV ledger holds that checksum), leaves a store that carries the marker as it is, writes
the empty marker on a store without one (a fresh local store, PROD) and `verify` still checks every account
the marker names. A `migrate` task carries no secret and reads nothing but the four tables.
See [docs/dynamodb-runtime.md](docs/dynamodb-runtime.md).

### Dispatching `07 · Migrate DEV data` (one step at a time)

1. Make sure the release is published: `01 · Full` or `03 · Publish` finished with the tag `vYYYY.MM.DD.HH.NN` in ECR as `domain-vYYYY.MM.DD.HH.NN`.
2. On GitHub, open Actions → `07 · Migrate DEV data` → Run workflow.
3. Enter `release_version` (the tag) and leave `command` at `migrate` (`verify` re-checks every recorded step, `status` only reports; both write nothing).
4. Wait for the Security gate and "Plan DEV data migration" to finish; the plan artifact `migrate-plan` holds `plan.json`.
5. Open the approval issue the run created (its link is the last line of "Approve DEV data migration") and read it.
6. Post one new comment with exactly the command the issue shows: `APPROVE MIGRATE domain-vYYYY.MM.DD.HH.NN` (`APPROVE VERIFY …` / `APPROVE STATUS …` for the other commands), within 10 minutes of the issue opening (`APPROVAL_WAIT_MINUTES`), or `REJECT`.
7. Wait for "Migrate DEV data" to finish; it closes the issue with the outcome and keeps `migrate-result/result.json` and one `migrate-record-<tag>-<run>` artifact.
8. On success, dispatch `04 · Deploy DEV` with the same `release_version`.

The workflow shares the `deploy-dev` concurrency group, so it never runs beside a deploy, recovery or
restart. The approval is the same owner mechanism as a deploy (v2 issue, `owner_approval` windows, a
live recheck before any credential, `issue_close.py` at the end).

### What the approval issue shows

A v2 issue titled `DEV: Migrate domain data domain-v… — one Fargate task lookahead-dev-domain-migrate
applies the data steps of this release to the four DynamoDB tables; the service is not touched · run N`
(`Verify domain data` / `Read domain data status` for the other commands). "What this run does" lists
the five steps in order; "Affected AWS resources" has one row per resource with its type and physical
name: the task definition family `lookahead-dev-domain-migrate` (CREATE, a new revision), the one-off
`AWS::ECS::Task` on `lookahead-dev-cluster` (RUN ONCE), the service `lookahead-dev-domain` (NO CHANGE,
with the DesiredCount it serves now), the four tables (MODIFY for `migrate`, READ ONLY
otherwise) and the three ledger items in `lookahead-dev-protected-owner-identity` under
`DATA_VERSION#DOMAIN`: `STEP#<ten-digit version>`, `CURRENT`, `LOCK`. The "Migration task" table names
the image digest, roles, session and source commits; "Tables the task reads" maps each variable to its
table; the validation table shows the tag resolution, the digest read back from ECR, the configuration
hash and the command; the payload is the whole plan.

### Reading the result

`migrate-result/result.json` (schema `app-migrate-result/v1`): `outcome` (`success` / `failure`),
`error`, `command`, `taskDefinition` (the revision registered), `task` (the ARN), `exitCode`,
`stopCode`, `stoppedReason`, `report` (the entrypoint's JSON line) and `steps` (`identity`,
`registered`, `started`, `stopped`, `report` or `no-report`, `ahead`). The report line is
`{"command":"migrate","environment":"dev","runId":"github-<run>","before":N,"after":M,"required":R,"applied":[…],"verified":[…],"ahead":false}`.
A run succeeds only with exit code 0, a report line without `failed`, the command and run identity it
names, and for `migrate` `after` at `required`; `verify` and `status` must report an empty `applied`
list (no ledger change). Exit code 1 means the entrypoint failed and the ledger is unchanged past the
last recorded step: fix the cause and run again with the same release (it continues from `CURRENT`).
Exit code 2 is a usage error of the entrypoint (a tooling bug). A stop code other than
`EssentialContainerExited` (for example `TaskFailedToStart`) is reported with ECS's reason; the log
stream of the task is `migrate/migrate/<task id>` in `/lookahead/dev/domain`.

### Rollback rule

A recorded data step is never undone and there is no other store to fall back to: the way back from a
data step is a new step, reviewed and recorded like any other, not a deploy input. A release that must not serve
is rolled back by deploying the previous tag; its data steps stay recorded and are backward compatible.

### AWS actions of the workflow and the role each needs (IAM candidate for Infra)

The workflow runs as `lookahead-dev-deploy` in the sessions `domain-deploy-migrate-plan-<run>-1`
(plan, 15 minutes) and `domain-deploy-migrate-<run>-1` (execute, 60 minutes); the deploy trust must
admit them as it admits `domain-deploy-*`. Calls, in order:

| Stage | AWS API call | Role that makes it | Resource |
| --- | --- | --- | --- |
| plan | `ecr:BatchGetImage` | `lookahead-dev-deploy` (already granted) | `lookahead-dev-ecr` |
| plan | `cloudformation:DescribeStacks` | `lookahead-dev-deploy` (already granted) | stack `lookahead-dev-domain` (read: current DesiredCount and image) |
| execute | `sts:GetCallerIdentity` | `lookahead-dev-deploy` | — |
| execute | `ecs:RegisterTaskDefinition` | `lookahead-dev-deploy` (new) | family `lookahead-dev-domain-migrate` |
| execute | `iam:PassRole` | `lookahead-dev-deploy` (new) | `lookahead-dev-migration-task` and `lookahead-dev-ecs-execution`, to `ecs-tasks.amazonaws.com` |
| execute | `ecs:RunTask` | `lookahead-dev-deploy` (new) | task definition `lookahead-dev-domain-migrate:*` on `lookahead-dev-cluster`, Fargate, the service's subnets and security group |
| execute | `ecs:DescribeTasks` | `lookahead-dev-deploy` (already granted for the service's tasks; needs the migrate tasks too) | tasks of `lookahead-dev-cluster` |
| execute | `logs:GetLogEvents` | `lookahead-dev-deploy` (new; the deploy has `logs:FilterLogEvents` only) | log group `/lookahead/dev/domain`, streams `migrate/migrate/*` |
| the task | `ecr:GetAuthorizationToken`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`, `logs:CreateLogStream`, `logs:PutLogEvents` | `lookahead-dev-ecs-execution` (already granted for the service) | the image and the log group above |
| the task | reads `dynamodb:DescribeTable` (all four tables), `GetItem` (also authorises `TransactGetItems`) and `Query` on the account and audit tables, `Scan` on the audit table only (the bootstrap's empty-namespace check); PartiQL writes through `ExecuteTransaction`: `dynamodb:PartiQLInsert` on the account and audit tables (staff counters and markers, `CATALOG / PLAN#<id>` + `META`, `SEQUENCE#* / COUNTER`, the imported account, access and audit items of step 5), `dynamodb:PartiQLUpdate` and `dynamodb:PartiQLDelete` on the account table only (step 5 raises the counters and removes the pre-import waiting rows; the audit table stays append-only); the ledger on the protected table, partition `DATA_VERSION#DOMAIN` only: `GetItem`, `Query`, `PartiQLInsert`, `PartiQLUpdate` (corrected 2026-11-17, transition `dev-dynamodb-ledger-partiql`). Nothing on `OWNER/ROOT`. A `migrate` task reads nothing outside the four tables (the PostgreSQL import source of step 5 was retired on 2026-11-17). The running application's role: `ExecuteTransaction` (PartiQL `INSERT` / `UPDATE` / `DELETE`; `PartiQLInsert` only on the audit table) plus `GetItem`, `Query`, `Scan`, `BatchGetItem`, `TransactGetItems`, `DescribeTable`; no native write anywhere | `lookahead-dev-migration-task` | the four `lookahead-dev-*` tables |

The task definition is registered without tags (no `ecs:TagResource`), with a read-only root file
system, `/tmp` as a volume and blocking `awslogs` (the report line is never dropped). The service's
task role `lookahead-dev-app-task` reads the ledger only; the migrate task is the single writer.
Local check: `for step in shared publish deploy; do python3 -m unittest discover -s ".github/pipeline/$step" -p 'test_*.py' || break; done` (the `07` gate also
requires 85 percent coverage over `migrate.py`).
