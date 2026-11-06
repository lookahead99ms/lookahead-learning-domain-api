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

One access hierarchy for DEV test accounts (DLV-969, owner 2026-11-04). `TestAccountTemplates.allows` is the
single rule for the test-session picker and start (`DevTestSessions`), the test-account list
(`GET /api/v1/admin/test-accounts`), Reset, creation scenarios and the People rows of `GET /api/v1/admin/access`.
The acting person's role is the lower of their roles (`TestAccountTemplates.role`):

| Acting role | May see or act on |
| --- | --- |
| owner | every account |
| co-author | every account except the owner's |
| admin (also co-author + admin) | admins and learners only |
| learner, reviewer or tester | nothing: they never switch (owner 2026-11-04) |
| not admitted, disabled or deleted | nothing |

Who may open each page is unchanged (`requireAccessReader`, the staff reader of the test-account list, owner/admin
Reset). A test session acts as the test account, so its course access is the test account's own. "Create test
account" makes learner test accounts only, for every role; the allowance's `scenarios` lists the 27 learner scenarios,
and any other scenario id answers `422 UNKNOWN_SCENARIO`. DLV-971 (owner, 2026-11-06): the roster has learner test
accounts only. The co-author and admin test accounts it used to carry (`coauthor01`, `coauthor02`,
`admin01`–`admin06`, in DEV with the `@lookahead.dev` suffix) are gone from the roster and retired by migration V21
where a database already holds them: their `staff_access` row is cleared, their registry row is marked deleted by
`V21` (a creation-ledger row too), and their `platform_subjects` and admitted `cloud_accounts` rows stay (V17). They
are never created or restored again. A test account never carries a staff role; the hierarchy above still decides who
sees or acts on an account, because it also governs the People list.

Restore of deleted roster accounts (owner, DEV only), on the route the Gateway already forwards:
`POST /api/v1/admin/test-accounts` with `{"restoreRoster": true, "confirm": "RESTORE"}` answers
`{data:{restored:N}}`. Each deleted roster entry (`creation_state='deleted'`, owner-created, never a created test
account) goes back to active in its own transaction and is rebuilt from its scenario like Reset; the admitted row is
re-created if missing, the registry revision is bumped, a `test_account_resets` row with preset `restore` records the
owner, and one `test-account.restore` event names the ids. Nothing to restore answers `restored: 0`. Refusals:
`403 OWNER_REQUIRED`, `422 ACCOUNT_CONFIRMATION_REQUIRED`, `403 TEST_ACCOUNTS_DISABLED` outside DEV. The roster
card's `roster.deleted` (allowance) is the restorable count.

Roster status (owner, DEV only): `GET /api/v1/admin/test-accounts/allowance` carries `roster` for the owner (null for
everyone else and outside DEV), and `POST /api/v1/admin/test-accounts` with `{"roster": true}` answers the same
object. It is computed by one SQL query (`DevTestRoster.STATUS`) on every read:

| Field | Meaning |
| --- | --- |
| `total` | roster entries (27) |
| `ready` | active owner-created roster accounts |
| `deleted` | roster accounts deleted before they became permanent; what Restore brings back |
| `withoutPlan` | ready learners whose scenario has a study plan they do not have (S37) |
| `changed` | ready accounts that differ from their scenario start; the card greys out "Reset all" at 0 (owner 2026-11-04) |

An account's scenario start is its latest `test_account_resets` row: Reset (`scenario`), Restore (`restore`) or, since
2026-11-04, the `create` row roster creation writes; each is the last write of its transaction. An account counts in
`changed` when, after that row, its registry row was stamped (the V10 triggers set `last_changed_at` on every insert,
update or delete of its `plans`, `account_grants`, `membership_purchases` and `staff_access`, which also covers plan
deletion and the timestamp-less `account_grants`), or any of these is newer: `membership_purchases.created_at` or
`updated_at`, `plans.created_at` or `updated_at`, `plan_versions.created_at`, `plan_activity.recorded_at`,
`staff_access.updated_at`. `plan_lifecycle` has no timestamp and is not read; a lifecycle change through the API also
updates its plan and writes plan activity. Every account in `withoutPlan` also counts in `changed` (once). A roster
account created before the `create` row existed has no start recorded and counts as changed until it is reset once.
No migration.

Every action on `POST /api/v1/admin/test-accounts` sends only its own fields (`{"roster":true}`,
`{"restoreRoster":true,"confirm":"RESTORE"}`, `{"purchaseDecision":{...}}`, `{"key":...,"scenario":...}`); the flags
are optional booleans, so an omitted flag is false rather than `400 MALFORMED_JSON`. A Reset body without
`expectedRevision` answers `428 REVISION_REQUIRED` (it was a 400), and a price body without `priceCents`
(`POST /api/v1/admin/membership/plans/{id}/price`) the existing `422 MEMBERSHIP_INVALID`. A body that still cannot
be read logs one `request.json` line naming the field (never its value). `TestAccountsControllerTest` sends these
exact bodies through the controller with Spring Boot's JSON mapper.

### Owner-approved purchases for DEV test accounts (DLV-969, owner 2026-11-04)

In DEV (`app.deployment-environment=dev`) payments stay simulated, and only a test account staff created can
purchase: an active `test_account_registry` row with a `creator_id` and a `@lookahead.dev` username (the 27 roster
accounts and "Create test account" learners), used through a test session. `PaymentConfiguration` wraps the mock
provider in `OwnerApprovalPaymentGateway`, so a checkout is a request: no payment, no access, no migration. Local
pays the mock at once as before; PROD has no payment provider.

A request is an ordinary `membership_purchases` row: `status='pending'`, `source='checkout'`,
`provider_status='awaiting_owner_approval'`, no `provider_payment_id`. The existing idempotency key, fingerprint,
plan rules (new, renewal, upgrade at the prorated difference, change in the final seven days, promo sale) and
overlap checks apply when it is created. Retrying with the same `Idempotency-Key` answers the same request and never
asks the provider; nothing processes pending purchases automatically. Its `purchase-created` audit row also records
`requestedBy`: the gate holder of the test session (`DevTestSessions.REQUESTER_ATTRIBUTE`, set by the test-session
filter), because the purchase's own actor is the test account.

Routes (all already forwarded by the Gateway):

- `POST /api/v1/account/purchases` (test session). Answer: the purchase, e.g.
  `{data:{id, planId, plan, pricePaidCents, currency:"USD", status:"pending", providerStatus:"awaiting_owner_approval",
  providerPaymentId:null, startsAt, endsAt, source:"checkout", replacesId, ...}}`. The Web shows
  "Request sent: the owner approves purchases in DEV".
- `POST /api/v1/account/purchases/quote` answers the usual quote plus `ownerApproval: true` in DEV.
- `GET /api/v1/admin/test-accounts`: for owner and admin viewers in DEV each row carries `pendingPurchase` (null
  when nothing waits; a request a Reset superseded no longer counts):
  `{purchaseId, kind: "new"|"renewal"|"upgrade"|"change", fromPlan:{code,name} ("free"/"Free" for a new plan),
  toPlan:{code,name}, priceCents, currency, priceDifferenceCents (upgrade only: full difference before proration),
  startsAt, endsAt, requestedAt, requestedBy}`. `requestedBy` is `{self:true}` for the viewer's own request, otherwise
  `{self:false, label:"Admin 2"}` (the permanent `StaffAliases` label) and, for the owner only, also `name` (display
  name); null when the sender is unknown. Course Pass purchases are refused at checkout, so no request has courses.
  Co-authors and other readers get no `pendingPurchase` field.
- `POST /api/v1/admin/test-accounts` with `{"purchaseDecision": {"purchaseId": "<uuid>", "decision": "approve"|"decline"}}`
  (owner or admin; an admin only for accounts the hierarchy above lets them act on). Answer:
  `{data:{decision:"approve"|"decline", repeated:false|true, purchase:{...as above}}}`. Approve pays through the mock
  provider under the request's key and applies the membership exactly as a successful checkout (`succeeded`, or
  `scheduled` for a change or renewal; an upgrade supersedes the replaced purchase). Decline sets `status='failed'`,
  `provider_status='declined_by_owner'` and changes nothing else. The same decision again answers `repeated:true`
  and never pays twice.

Refusals: purchase and quote `403 PURCHASES_TEST_ACCOUNTS_ONLY` "In DEV, purchases are for test accounts; the owner
approves them." (real learners, testers, staff's own accounts, deleted test accounts); second request while one waits
`409 PURCHASE_REQUEST_PENDING` "A request is already waiting for the owner or an admin." (other pending purchases keep
`409 PURCHASE_PENDING`). Decisions: `401 AUTHENTICATION_REQUIRED`; `403 PURCHASE_APPROVAL_DISABLED` outside DEV;
`403 PURCHASE_APPROVAL_FORBIDDEN` "Only the owner or an admin can approve purchase requests." (co-author, reviewer,
tester, learner, or an admin for an account above an admin in the hierarchy); `422 PURCHASE_DECISION_INVALID`; `404 PURCHASE_NOT_FOUND`;
`409 PURCHASE_NOT_A_REQUEST` (not waiting for a decision); `409 PURCHASE_REQUEST_DECIDED` (the other decision was
already made); `409 PURCHASE_REQUEST_STALE` on approve after a Reset or once the replaced purchase is no longer
active (decline it and send a new request); `503 PAYMENT_UNAVAILABLE` when the mock provider did not settle (approve
again); `429 PAYMENT_BUSY` as for checkout. Staff plan upgrades (`/api/v1/admin/accounts/{id}/plan-upgrades`) are
unchanged.

Upgraded-by marker and reserved Reset (owner, 2026-11-04). Once a request is approved, the test account carries an
"upgraded by" marker until its next Reset (or Restore). Nothing new is stored and there is no migration: the marker is
the latest `purchase-approved` audit row (its `actor_id` is who decided, its `recorded_at` when) of a purchase of the
account that is `succeeded` or `scheduled` (an approval the mock provider declined does not count), recorded after the
account's latest `test_account_resets` row; the requester is the `requestedBy` of that purchase's `purchase-created`
row (`MembershipService.approvedRequest`, `UpgradeMarkers`). Because Reset writes a `test_account_resets` row, Reset
clears the marker; a declined request never makes one.

- `GET /api/v1/admin/test-accounts` rows in DEV, for every reader who can see the row (owner, co-author, admin), carry
  `upgradedBy`: `{self, label, name?, approvedBy:{self, label, name?}, plan:{code, name}, kind:"new"|"renewal"|"upgrade"|"change", approvedAt}`
  or null. `self` is true when the viewer is that person; `label` is the permanent `StaffAliases` label ("Admin 2",
  "Owner", "Unknown" when the sender was not recorded); `name` (display name) is present for the owner only.
- Every row also carries `canReset` (boolean) for the viewer: the existing Reset rule (DEV: an owner-created roster
  account, not a created learner; owner or admin; the one hierarchy) and the reservation below. The Web shows Reset only
  when it is true.
- The test-session picker (`DevTestSessions.usable` → `/internal/v1/test-sessions/accounts`, relayed unchanged by the
  Gateway as `/bff/api/v1/auth/test-accounts`) carries a compact `upgradedBy` on each account:
  `{self, label, name?, plan:"<plan name>", approvedAt}` or null, so people see the marker before switching. `self` is
  relative to the gate holder; `name` only when the gate holder is the owner.

While the marker is present, Reset (`POST /api/v1/admin/test-accounts/{id}/reset`) is allowed only to the owner and to
the requester (the person who sent the approved request through the test session). Anyone else, other admins
included, gets `403 RESET_RESERVED` "Only the owner or <label>, who upgraded this account, can reset it." (label only,
never a name); when the sender is unknown or is the owner, "Only the owner can reset this upgraded account." The
check runs under the account's row lock, after the existing checks and before the typed confirmation. An allowed Reset
puts the account back to its scenario as before and the marker disappears. Restore is unchanged. The refusal is the
usual `lookahead.event` WARN line `test-account.reset` with `reason=RESET_RESERVED`, `account.id` and `target.id`;
the success lines are unchanged.

Logs (`lookahead.payment`, ids, plan code and cents only): `purchase.approval-requested` when a request is created,
`purchase.approved` / `purchase.declined` with `actor.role` (owner or admin) and `repeated`, the usual
`membership.activated|upgraded|scheduled` on approval, `purchase.request` / `purchase.quote` refusals with reason
`PURCHASES_TEST_ACCOUNTS_ONLY`, and `purchase.decision` refusals. `CloudTestAccountsDatabaseTest` and
`MembershipDatabaseTest` cover the flow on the real database, including the marker for the owner (names), the requester
(self) and another admin (label only), the picker marker, the reserved Reset, `canReset` per viewer and a declined
request.

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
