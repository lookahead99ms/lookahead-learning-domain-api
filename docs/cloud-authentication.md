# Cloud authentication and durable sign-ins

DEV/PROD use Cognito managed login through Gateway. Local retains the Identity
application and its database. This is implemented application code and an
inactive deployment candidate; local controlled-provider tests do not establish
live Cognito, RDS, ECS, IAM or network readiness.

## Ownership and verification

Gateway owns the browser's HTTP session, OAuth state/PKCE, access and refresh
tokens, CSRF protection, and an unguessable server-side sign-in proof. Tokens,
proofs, the internal Gateway secret, AWS credentials and private content are not
frontend configuration. Domain owns application account mapping, enabled/admitted
state, explicit author capability, course grants, and durable logical sign-ins in
its PostgreSQL database. Cognito owns credentials and provider identity.

Domain checks JWT signature against the configured Cognito JWKS, exact regional
issuer, access-token use, configured client ID, explicit issue/expiry times,
`origin_jti`, `auth_time`, and all configured account/content/support scopes plus
`aws.cognito.signin.user.admin`. Resource-bound access tokens carrying `aud` are
rejected: this candidate does not configure Cognito resource binding. Every
protected request also calls Cognito `GetUser`; no positive verification cache
can outlive provider revocation. Provider failures fail closed with sanitized503;
invalid or revoked credentials return401. `GetUser`, `ChangePassword` and
`GlobalSignOut` use the access token, not application IAM credentials. The
Cognito SDK bean uses anonymous credentials and a fixed regional HTTPS endpoint.
Domain's S3 publication client separately uses ECS task credentials. Local creates
neither SDK client.

Accounts map `(issuer, subject)` to an application UUID. Email/username is never
a linking key and Local UUIDs are never inferred from Cognito claims. Admission
is explicit; managed login by itself cannot grant application or curriculum
access. Current enabled/admitted/author state and session state are read on each
request. The runtime SQL role cannot create cloud accounts or change admission,
enabled state or author capability.

## Durable sign-in policy

A per-account PostgreSQL row lock serializes admission, replacement, revocation
and operator changes across Domain instances. At most two sign-ins are active.
A third login receives a five-minute restricted replacement challenge; it cannot
call ordinary authenticated APIs until it replaces one of its own sign-ins.
Challenges and Gateway proofs are stored as SHA-256 digests, bound to the provider
identity/token family and server-side browser session. Challenge issuance is
limited to five per account in fifteen minutes. An unacknowledged admission
challenge is not recoverable from its digest; start a fresh login after losing it.

Sign-ins expire after thirty minutes idle or seven days absolute. Admission and
revocation controls require provider authentication within five minutes. Refresh
within the same token family preserves a sign-in; reauthentication with a new
family replaces the current browser's sign-in and retains the old family's
revocation tombstone. Restarting Domain does not reset limits or revocations.
Tombstones have no automatic cleanup in this candidate; retention planning is
required before sustained production traffic. Gateway browser sessions remain
process-local, so seamless Gateway replica/restart continuity is not claimed.

Profile edits change Domain display text only. Password changes first commit a
durable pending guard that blocks authentication, admission and challenge use.
They verify the supplied current password with Cognito, commit revocation of all
Domain sign-ins/challenges and clear the guard, then request Cognito global
sign-out. A known wrong-password or password-policy rejection clears the guard
without revoking sessions. An uncertain provider outcome revokes them. Failure
of global sign-out returns503 without undoing Domain revocation. A process crash
or failed completion transaction leaves the durable guard blocking access until
explicit operator recovery. The current-password check is fresh credential proof;
the separate five-minute login-authentication rule applies to admission/revocation.
These systems have no shared atomic transaction or automatic replay.
Application logout likewise requires confirmed Domain termination; provider
logout uncertainty is reported separately by Gateway.

## Explicit account administration

Apply migrations through Infra's separately approved Flyway Utils flow first;
the Domain migration/bootstrap executable entrypoints remain retired.
`V4__cloud_identity_and_sign_ins.sql` adds cloud tables without changing Local
identity ownership. Use a verified exact provider issuer/subject, dedicated
migration-role connection and externally injected secret. The application runtime
role must not run this command. From the built executable JAR:

```sh
java -Dloader.main=com.lookahead.domain.cloud.CloudAccountAdministration \
  -cp target/lookahead-domain-api-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  admit https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example \
  synthetic-provider-subject learner@example.invalid 'Example Learner'
```

That filename is the default local build. For a release artifact, use its exact
`lookahead-domain-api-<version>.jar` filename.

Set `LOOKAHEAD_ENVIRONMENT=dev` or `prod` and `AWS_REGION` for cloud operator
commands. Runtime, migrations and account administration require the same regional
RDS endpoint, database `lookahead_platform`, `sslmode=verify-full` and absolute
readable CA bundle. Both the initial role check and subsequent JDBC connections
use bounded connect/socket/cancellation timeouts. Unknown or empty environment
names fail; an omitted environment retains the existing Local CLI default.

Supply `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, and exactly one of
`SPRING_FLYWAY_PASSWORD` or `LOOKAHEAD_MIGRATION_PASSWORD_FILE` as documented for
migration. The example contains synthetic identifiers, not a deployment target.
Other actions are `disable`, `enable`, `author-on`, and `author-off`, followed by
issuer and subject only. `disable` also revokes every durable sign-in and cancels
pending challenges; `enable` never revives them. Repeated admission preserves the
application UUID. Admission leaves content grants and author capability unchanged.
This is an operator CLI; there is no public account-admission endpoint or automatic
cloud self-registration workflow.

To recover a stuck password-change guard: disable the account, investigate the
provider outcome and confirm no password operation remains in flight, then run
`resolve-password-change <issuer> <subject>` through the same CLI. Resolution
requires a disabled account, revokes all old sign-ins/challenges and clears the
guard in one transaction. Enable only after review; require a fresh login. Normal
restart and `enable` alone never clear the guard. Recovery is not automatic.

## Admission (DLV-969)

`V17__dev_access_admission.sql` turns the sign-in admission into a request-and-approve
flow. The operator CLI above still works and the Gateway contract is unchanged: an
account that is not admitted still receives `403 ACCOUNT_ADMISSION_REQUIRED`.

First contact. When `POST /internal/v1/cloud-sign-ins/admit` finds no `cloud_accounts`
row for the verified issuer and subject, `CloudSignInRegistry.admit` inserts a
`platform_subjects` row and a waiting `cloud_accounts` row (`admitted=false`,
`enabled=true`, `author_access=false`, `requested_at=clock_timestamp()`, username,
display name and the provider-verified email when present), records a `request` event
in `cloud_admission_events`, commits, and then answers the 403. A later sign-in before
admission refreshes username, display name and verified email on the waiting row and
answers the same 403. A per-identity transaction advisory lock makes two concurrent
first contacts produce one row. Only the sign-in admission path does this; `authenticate`,
`inventory` and the other actions keep failing without creating rows, and a disabled
account keeps answering 401.

Owner endpoints, proxied by the Gateway with the other `/api/v1/admin/access` routes
and refused with `403 OWNER_REQUIRED` for anyone else, before any query:

- `GET /api/v1/admin/access/waiting` answers
  `{waiting:[{accountId, username, displayName, email, requestedAt, expiresAt}], expiryHours:48}`,
  oldest request first; `email` is the verified email or null; `expiresAt` is
  `requestedAt` plus 48 hours. `Cache-Control: no-store`.
- `POST /api/v1/admin/access/admit` with `{accountId, roles}` locks the row, sets
  `admitted=true`, `admitted_at`, `admitted_by`, saves the staff assignment through the
  existing owner `StaffAccessService.save` when `roles` is not empty (creating one, or
  updating the account's existing assignment with its id and revision), records an
  `admit` event with the roles, and answers `{accountId, username, displayName, admittedAt, roles}`
  in one transaction. Errors: `422 INVALID_ADMISSION` (not a canonical UUID, or a role
  outside co-author/admin/reviewer/tester), `404 ACCOUNT_NOT_FOUND`,
  `409 ACCOUNT_ALREADY_ADMITTED`. Owner access (`author_access`) is never granted here.

Cleanup. In cloud mode only (`CloudSchedulingConfiguration` enables scheduling under the
same `dev|prod` condition), `CloudAdmissionCleanup.expire` runs five minutes after start
and then hourly. It takes `pg_try_advisory_xact_lock(hashtextextended('cloud-admission-cleanup',0))`
and returns 0 when another task holds it; otherwise it selects waiting rows older than
48 hours `FOR UPDATE SKIP LOCKED`, writes an `expire` event, deletes the `cloud_accounts`
row (only while `admitted=false`) and its `platform_subjects` row, and returns the count.
Failures are logged as one WARN line and never stop the scheduler. The 48 hours are the
single constant `CloudAdmissionCleanup.WAITING_EXPIRY`, also used for `expiresAt`. The
Cognito user is not touched; the person can sign in again and a new waiting row appears.

V17 grants to `lookahead_platform_app`: `INSERT` and `DELETE` on `cloud_accounts`,
`UPDATE` on `admitted`, `admitted_at`, `admitted_by`, `requested_at` and `verified_email`
(in addition to the V4 `username`, `display_name`, `credential_change_pending`), and
`SELECT`, `INSERT` on `cloud_admission_events`. `enabled`, `author_access`, `issuer` and
`subject` stay operator-only; `cloud_admission_events` is append-only by trigger and
revoke; a `BEFORE DELETE` trigger refuses to delete an admitted `cloud_accounts` row.
`CloudSignInRegistryDatabaseTest` applies V4, V6, V7 and V17 and covers first contact,
concurrent first contacts, the runtime-role grants, owner admission with roles and
rollback, and the cleanup under a held lock.

Test accounts in DEV (phase C, Domain side). `LOOKAHEAD_TEST_ACCOUNT_IDENTITY`
(`app.test-accounts.identity`, `local` by default) set to `cognito` together with
`LOOKAHEAD_TEST_ACCOUNT_POOL_ID` (`app.test-accounts.pool-id`, the sign-in pool of
`LOOKAHEAD_COGNITO_ISSUER`, validated like the issuer) turns on created test learners in
DEV only: `CloudTestAccountSettings` refuses to start in `prod`, and `TestAccountPolicy.cloud()`
is true only for `dev`. Local behaviour is unchanged; the fixture roster (`seed`, `reset`,
the legacy preset, the Local real-account deletion) answers `403 TEST_ACCOUNTS_DISABLED` in
cloud mode and `GET /api/v1/admin/test-accounts` lists only learners with `initialization='created'`.
`CognitoTestIdentityOperator` writes the pool with the task role (`ContainerCredentialsProvider`,
a client separate from the anonymous verifier client, same timeouts, no retries); the task role
needs exactly five actions on the pool ARN: `cognito-idp:AdminCreateUser` (message suppressed,
no attributes), `AdminSetUserPassword` (permanent), `AdminGetUser` (reads the `sub`),
`AdminDisableUser` and `AdminDeleteUser` (see `shared/iam-transition-proposal.md` in the
DLV-969 evidence folder). The completing transaction of `POST /api/v1/admin/test-accounts`
also inserts the admitted `cloud_accounts` row (`admitted=true`, `enabled=true`,
`author_access=false`, username = display name = the generated username, `admitted_by` = the
creator) and an `admit` event with roles `[]`, so the Waiting list and the audit stay
consistent; an existing Cognito user from an interrupted attempt is reused on retry with the
same mutation key, and the lifetime allowance is unchanged. `DELETE` disables and deletes the
Cognito user, revokes the account's `cloud_sign_ins`, erases its data and marks the registry
row deleted; the admitted `cloud_accounts` row stays, because the runtime role cannot change
`enabled` and V17 never deletes an admitted row. `CloudTestAccountsDatabaseTest` (cloud-auth
database, full schema plus V17) covers create, retry, delete and the roster refusal.

## Configuration and validation

Cloud requires `LOOKAHEAD_COGNITO_ISSUER`, `AWS_REGION`,
`APP_OAUTH_CLIENT_ID`, `LOOKAHEAD_COGNITO_SCOPE_PREFIX`, and the separately
injected `LOOKAHEAD_DOMAIN_GATEWAY_SECRET` value (32–4096 characters; secret ARNs, unresolved placeholders and control characters are rejected). See `src/main/resources/application.yml`
and the application service manifest for authoritative bindings. Cloud database
configuration requires injected runtime credentials, regional RDS host,
`sslmode=verify-full` and an installed trusted CA file. Cloud readiness includes
migrationV4 and restricted cloud identity/session privileges in addition to the
existing database and publication gates.

Run Java21 `./mvnw test package`. Database suites opt in through
`CLOUD_AUTH_TEST_DATABASE_URL` and `CLOUD_AUTH_TEST_PASSWORD_FILE` and reject all
URLs except a loopback `lookahead_domain_cloud_auth` database. Infra owns the
isolated tmpfs PostgreSQL helper `scripts/cloud_auth_test_database.py`; never point
these tests at an application database. They cover concurrent admission across
registry instances, challenge binding/replay, revocation/expiry, explicit account
administration, runtime grants, provider outage handling, and real signed JWTs
through Spring security/controllers into PostgreSQL. Provider calls are controlled
fixtures. Remaining deployment gates include real client-secret transfer, SQL
role/CA bootstrap, private Gateway-to-Domain TLS, live Cognito flows, and
cross-service deployment evidence. No local result authorizes cloud activation.
