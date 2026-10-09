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

Apply migrations with the existing one-shot migration entry point first.
`V4__cloud_identity_and_sign_ins.sql` adds cloud tables without changing Local
identity ownership. Use a verified exact provider issuer/subject, dedicated
migration-role connection and externally injected secret. The application runtime
role must not run this command. From the built executable JAR:

```sh
java -Dloader.main=com.lookahead.domain.cloud.CloudAccountAdministration \
  -cp target/lookahead-domain-api.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  admit https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Example \
  synthetic-provider-subject learner@example.invalid 'Example Learner'
```

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
