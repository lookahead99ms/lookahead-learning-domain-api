# Platform application

This independently packaged resource application owns content access, current
course grants, plans, progress, support receipts and local author capability.
It has no password-login controller, authorization-server library, OAuth state
repository, signing private key, or Identity database connection.

The candidate does not migrate an existing combined database automatically.
Legacy migrations and the running application remain separate. Infrastructure
provisions `lookahead_platform_app` and `lookahead_platform_migrator` with access
only to the Platform database. Runtime credentials must not own tables, create
schemas, or connect to the Identity database.

Build from the sibling `lookahead-learning-backend` directory with
`./mvnw -pl :platform-app -am verify`. The executable artifact is
`../lookahead-learning-platform/target/lookahead-platform.jar`; its main class is
`com.lookahead.platform.PlatformApplication`. The one-shot migration entry point
is `com.lookahead.platform.PlatformMigration`, using `SPRING_FLYWAY_URL`,
`SPRING_FLYWAY_USER=lookahead_platform_migrator` and either
`SPRING_FLYWAY_PASSWORD` or `LOOKAHEAD_MIGRATION_PASSWORD_FILE`. Migration scripts
live in `db/platform`; runtime Flyway is disabled.

## Configuration

| Property | Environment convenience | Purpose |
| --- | --- | --- |
| `app.deployment-environment` | `LOOKAHEAD_ENVIRONMENT` | Explicit `local`, `dev` or `prod` |
| `spring.datasource.url` | `LOOKAHEAD_PLATFORM_JDBC_URL` | Platform PostgreSQL URL |
| `spring.datasource.username` | Fixed default | `lookahead_platform_app` only |
| `spring.datasource.password` | `LOOKAHEAD_PLATFORM_DB_PASSWORD` | Platform runtime credential |
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
DEV and PROD Identity URLs require HTTPS. Explicit local mode permits loopback
HTTP and fixed internal names `identity`, `identity-api`, `lookahead-identity`.
Public issuer HTTP remains loopback-only. `accounts` and `resource` roles are
always activated; mixed gateway/authorization-server roles fail startup.

Database URLs require an explicit single host and permit only `sslmode` and an
absolute `sslrootcert` path as URL parameters. Credentials, session options,
schema and timeout overrides are rejected. Runtime connections verify their
actual restricted role before being lent to application code. Migration jobs
verify their separate role before Flyway. Readiness checks each required DML
privilege individually; retaining SELECT alone does not mark a broken writer UP.

## Request and data contracts

JWT signature, issuer and lifetime checks use public JWKS. Audience, originating
client and canonical UUID subject are checked before the internal call. Every
authenticated request then POSTs the exact access token as form field `token`
to `/internal/v1/tokens/verify` using Basic user `lookahead-platform-verifier`
and the dedicated verification secret. Redirects and positive response caching
are disabled; connect/read timeouts and response sizes are bounded.

Identity returns `{ "active": false }` for an invalid/revoked/disabled identity,
or an active result with `subject`, `username`, `displayName`, and `clientId`.
The latter must match the signed token. Invalid credentials yield 401;
unavailable or malformed Identity results yield 503. No response is treated as
permission to bypass current Platform grants.

Only a freshly verified subject may create its credential-free
`platform_subjects` reference. This operation is idempotent and never grants
access. Existing account UUIDs must be preserved during data transfer. Product
foreign keys point to this local subject table; no cross-database SQL exists.
Platform does not persist a stale copy of Identity's enabled state.

An account disable blocks newly verified requests, but cannot atomically cancel
an already admitted Platform transaction across databases. Plan/version/activity/
mutation-receipt writes remain one Platform transaction. Support receipt
reservation and per-account limits lock the Platform subject row; uncertain
SMTP delivery is never automatically resent by replaying a request key.

Local fixtures require `local-test`, explicit local mode and
`LOOKAHEAD_PLATFORM_SEED_ENABLED=true`. They create only deterministic subject
references and synthetic grants. Optional `LOOKAHEAD_PLATFORM_AUTHOR_ENABLED`
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
