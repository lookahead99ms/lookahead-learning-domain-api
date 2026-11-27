# Cloud authentication and durable sign-ins

DEV/PROD use Cognito managed login through Gateway. Local retains the Identity
application and its database. This is implemented application code and an
inactive deployment candidate; local controlled-provider tests do not establish
live Cognito, DynamoDB, ECS, IAM or network readiness.

Store note (DLV-970, 2026-11-17): the Domain's account, sign-in and admission data lives in
DynamoDB (`SignInStore`, called by `SignInService`, `AccountAggregateStore`; see [DynamoDB persistence](dynamodb-persistence.md)).
Where this page names PostgreSQL rows, migrations (`V10`, …), grants, triggers or
row locks, it records how the same rule was kept on the PostgreSQL store that served DEV until
2026-11-17; the rule is unchanged and its DynamoDB form is one conditional transaction on the
account's `CONTROL` revision with insert-only audit items.

## Ownership and verification

Gateway owns the browser's HTTP session, OAuth state/PKCE, access and refresh
tokens, CSRF protection, and an unguessable server-side sign-in proof. Tokens,
proofs, the internal Gateway secret, AWS credentials and private content are not
frontend configuration. Domain owns application account mapping, enabled/admitted
state, explicit author capability, course grants, and durable logical sign-ins in
its DynamoDB store (PostgreSQL until 2026-11-17). Cognito owns credentials and provider identity.

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
request. The owner alone admits, disables, enables and grants author capability, through
owner endpoints the Domain enforces.

## Durable sign-in policy

The account's `CONTROL` revision (a DynamoDB conditional transaction) serializes admission,
replacement, revocation and owner changes across Domain instances. At most two sign-ins are active.
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

## Account administration

There is no command-line account administration (the PostgreSQL-era command retired with that store on
2026-11-17). Admission is the owner's endpoint (People and access) or the protected owner identity's own first
contact; Disable, Enable, Delete and the author flag are owner endpoints too. Nothing reaches the
store outside the application.

## Admission (DLV-969)

Sign-in admission is a request-and-approve flow. The Gateway contract is unchanged: an
account that is not admitted still receives `403 ACCOUNT_ADMISSION_REQUIRED`.

First contact. When `POST /internal/v1/cloud-sign-ins/admit` finds no account for the verified
issuer and subject, `SignInService.admit` writes the account in one transaction: the `META`
and `CONTROL` items (not admitted, enabled, no author flag, `requestedAt` now, username, display
name and the provider-verified email when present), its `IDENTITY` binding, the `DIRECTORY` and
`WAITING` projections and a `request` admission event in Administrator Audit Events, and then
answers the 403. A later sign-in before admission refreshes username, display name and verified
email on the waiting account and does not change `requestedAt`; the waiting list shows the request
time and the expiry 48 hours later.

Owner endpoints, proxied by the Gateway with the other `/api/v1/admin/access` routes
and refused with `403 OWNER_REQUIRED` for anyone else, before any query:

- `GET /api/v1/admin/access/waiting` answers
  `{waiting:[{accountId, username, displayName, email, requestedAt, expiresAt}], expiryHours:48}`,
  oldest request first; `email` is the verified email or null; `expiresAt` is
  `requestedAt` plus 48 hours. `Cache-Control: no-store`.
- `POST /api/v1/admin/access/admit` with `{accountId, roles}` reads the account under its
  `CONTROL` revision, marks it admitted (`admittedAt`, `admittedBy`), saves the staff assignment through the
  existing owner `StaffAccessService.save` when `roles` is not empty (creating one, or
  updating the account's existing assignment with its id and revision), records an
  `admit` event with the roles, and answers `{accountId, username, displayName, admittedAt, roles}`
  in one transaction. Errors: `422 INVALID_ADMISSION` (not a canonical UUID, or a role
  outside co-author/admin/reviewer/tester), `404 ACCOUNT_NOT_FOUND`,
  `409 ACCOUNT_ALREADY_ADMITTED`. Owner access (`author_access`) is never granted here.

Cleanup. In cloud mode only, `DevAccessCleanupJob.expire` runs through `SweepRunner` at start and
every four hours (DLV-971). It walks the `WAITING` projection for accounts older than 48 hours and,
per account, writes an `expire` event and deletes the waiting account's items in one conditional
transaction (only while not admitted; a concurrent admission or another task's delete makes that
account's transaction lose and it is skipped), and returns the count.
Failures are logged as one WARN line and never stop the scheduler. The 48 hours are the
single constant `DevAccessCleanupJob.WAITING_EXPIRY`, also used for `expiresAt`. The
Cognito user is not touched; the person can sign in again and a new waiting row appears.

On DynamoDB the same rules hold through the item shapes: admission events are insert-only items in
Administrator Audit Events, a waiting account is deleted only by the 48-hour expiry or a merge (one
conditional delete per account), and an admitted account is never deleted by the cleanup.
`SignInStoreDatabaseTest` covers first contact, concurrent first contacts, owner
admission with roles, the lost-fence 409 and the cleanup against DynamoDB Local.

Test accounts in DEV (phase C, Domain side). `LOOKAHEAD_TEST_ACCOUNT_IDENTITY`
(`app.test-accounts.identity`, `local` by default) set to `dev` turns on created test learners in
DEV only: `TestAccountModeService.cloud()` is true only with that value in the `dev` environment; the
only other accepted value is `local`. Local behaviour is unchanged; the fixture roster (`seed`, `reset`,
the legacy preset, the Local real-account deletion) answers `403 TEST_ACCOUNTS_DISABLED` in
cloud mode and `GET /api/v1/admin/test-accounts` lists only learners with `initialization='created'`.
A DEV test account has no sign-in of its own since DLV-969 S35 (staff use it through a test
session): `TestAccountNoSignInClient` stands where the Local Identity operator stands and calls
nothing, so the Domain creates, disables and deletes no Cognito user for a test account. The completing transaction of `POST /api/v1/admin/test-accounts`
also writes the admitted account (`CONTROL`: admitted, enabled, no author flag, username =
display name = the generated username, `admittedBy` = the creator) and an `admit` event with roles `[]`, so the Waiting list and the audit stay
consistent; a retry with the same request key continues the reservation of the interrupted
attempt, and the lifetime allowance is unchanged. `DELETE` revokes the account's sign-ins,
erases its data and marks the registry
item deleted; the admitted account itself stays (an admitted account is never deleted by the
cleanup). `TestAccountServiceDatabaseTest` covers create, retry, delete and the roster refusal.

One access hierarchy for DEV test accounts (DLV-969, owner 2026-11-04). `StaffAccessRoleRules.allows` is the
single rule for the test-session picker and start (`TestSessionService`), the test-account list
(`GET /api/v1/admin/test-accounts`), Reset, creation scenarios and the People rows of `GET /api/v1/admin/access`.
The acting person's role is the lower of their roles (`StaffAccessRoleRules.role`):

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
`admin01`–`admin06`, in DEV with the `@lookahead.dev` suffix) are gone from the roster (the DynamoDB roster never held
them and the import of 2026-11-17 excluded test accounts). They are never created or restored again. A test account never carries a staff role; the hierarchy above still decides who
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
object. It is computed from the store on every read (`TestAccountDevRosterService`):

| Field | Meaning |
| --- | --- |
| `total` | roster entries (27) |
| `ready` | active owner-created roster accounts |
| `deleted` | roster accounts deleted before they became permanent; what Restore brings back |
| `withoutPlan` | ready learners whose scenario has a study plan they do not have (S37) |
| `changed` | ready accounts that differ from their scenario start; the card greys out "Reset all" at 0 (owner 2026-11-04) |

(PostgreSQL-era definition of `changed`, kept for the record; `TestAccountStore` answers the same fields from the account, plan and purchase items.) An account's scenario start is its latest `test_account_resets` row: Reset (`scenario`), Restore (`restore`) or, since
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
be read logs one `request.json` line naming the field (never its value). `TestAccountControllerTest` sends these
exact bodies through the controller with Spring Boot's JSON mapper.

### Owner-approved purchases for DEV test accounts (DLV-969, owner 2026-11-04)

In DEV (`app.deployment-environment=dev`) payments stay simulated, and only a test account staff created can
purchase: an active `test_account_registry` row with a `creator_id` and a `@lookahead.dev` username (the 27 roster
accounts and "Create test account" learners), used through a test session. `PaymentConfiguration` wraps the mock
provider in `PaymentOwnerApprovalClient`, so a checkout is a request: no payment, no access, no migration. Local
pays the mock at once as before; PROD has no payment provider.

A request is an ordinary `membership_purchases` row: `status='pending'`, `source='checkout'`,
`provider_status='awaiting_owner_approval'`, no `provider_payment_id`. The existing idempotency key, fingerprint,
plan rules (new, renewal, upgrade at the prorated difference, change in the final seven days, promo sale) and
overlap checks apply when it is created. Retrying with the same `Idempotency-Key` answers the same request and never
asks the provider; nothing processes pending purchases automatically. Its `purchase-created` audit row also records
`requestedBy`: the gate holder of the test session (`TestSessionFilter.REQUESTER_ATTRIBUTE`, set by the test-session
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
  `{self:false, label:"Admin 2"}` (the permanent `StaffAliasService` label) and, for the owner only, also `name` (display
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
row (`MembershipService.approvedRequest`, `TestAccountUpgradeMarkerService`). Because Reset writes a `test_account_resets` row, Reset
clears the marker; a declined request never makes one.

- `GET /api/v1/admin/test-accounts` rows in DEV, for every reader who can see the row (owner, co-author, admin), carry
  `upgradedBy`: `{self, label, name?, approvedBy:{self, label, name?}, plan:{code, name}, kind:"new"|"renewal"|"upgrade"|"change", approvedAt}`
  or null. `self` is true when the viewer is that person; `label` is the permanent `StaffAliasService` label ("Admin 2",
  "Owner", "Unknown" when the sender was not recorded); `name` (display name) is present for the owner only.
- Every row also carries `canReset` (boolean) for the viewer: the existing Reset rule (DEV: an owner-created roster
  account, not a created learner; owner or admin; the one hierarchy) and the reservation below. The Web shows Reset only
  when it is true.
- The test-session picker (`TestSessionService.usable` → `/internal/v1/test-sessions/accounts`, relayed unchanged by the
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
`PURCHASES_TEST_ACCOUNTS_ONLY`, and `purchase.decision` refusals. `TestAccountServiceDatabaseTest` and
`MembershipStoreDatabaseTest` cover the flow on the real database, including the marker for the owner (names), the requester
(self) and another admin (label only), the picker marker, the reserved Reset, `canReset` per viewer and a declined
request.

## Team Reviews and support requests (DLV-971, owner 2026-11-06)

A learner's support request (`POST /api/v1/support`: Report a problem, Share feedback, Ask a question) is
recorded as one record in the support-requests store of the assets bucket
(`support-requests/records/YYYY/MM/DD/SUP-000001.json`) before it is emailed, so the owner and admins see it
under Team Reviews; a staff review is one record under `reviews/` (`REV-000001`). Every item answers `kind`
(`"review"` or `"request"`), `requestType` (`"problem"` | `"feedback"` | `"question"`, `null` on a review),
`reference` (the support receipt reference, `null` on a review), `ticketId` (`null` until promoted) and
`ticketStatus` (the ticket's status on the delivery plan). The request keeps the learner's account as its
submitter with role `user`; the learner never lists Team Reviews, and a reviewer lists only the reviews they
wrote. `GET /api/v1/platform-reviews` lists both stores merged, newest first, paged by day.

| Acting role | Reads | Sets a status (`PUT .../triage`, incl. `closed` and reopen) | Promotes (`POST .../promote`) | Deletes |
| --- | --- | --- | --- | --- |
| owner | every item | every item | every item | own reviews while New; any request |
| admin | every item | requests only; a review answers `403 PLATFORM_REVIEW_ACCESS_DENIED` | requests only | own reviews while New |
| reviewer, co-author, tester | the reviews they wrote | nothing (`403`) | nothing (`403`) | own reviews while New |
| learner (`user`) | nothing | nothing | nothing | nothing |

Every status change is one insert-only item in Administrator Audit Events (`RECORD#<kind>#<recordId> / STATUS#…`,
`PlatformReviewStatusHistoryStore`; nothing updates or deletes it). Support requests with no update for seven days are closed by the sweep
(`closedBy` `system`, the note "No update in 7 days; closed automatically. Reply or reopen to continue.");
reviews are never swept. The owner can run one sweep, or all three, at once (`POST /api/v1/sweeps/run`
`{"job": ...}`, owner only; README, "Sweeps") instead of waiting for the 4-hour cadence. The receipt row in `support_receipts` is unchanged. Statuses answered to the learner:
`accepted` (email sent), `recorded` (recorded for Team Reviews, mail is not configured: `EMAIL_NOT_SENT` with
the missing setting, answered 202) and `unconfirmed` (email not confirmed, 503). A record store that is
unconfigured or fails logs `support.record` / `REQUEST_NOT_RECORDED` and the email still goes; with neither
the store nor mail the request key is released and the learner gets `503 SUPPORT_UNAVAILABLE` to try again.
Today's day files under the legacy prefix are imported once at start (README, "Platform reviews").
Tests: `PlatformReviewServiceTest`, `PlatformReviewControllerTest`, `SupportFeedbackServiceTest`,
`SupportControllerTest`, `SupportReceiptStoreDatabaseTest`, `PlatformReviewRecordNumberStoreDatabaseTest`.

## Delivery plan and promote (DLV-971, owner 2026-11-06)

`delivery/plan.json` in the assets bucket is the plan the Domain serves (`GET /api/v1/delivery/plan`, delivery
read) and edits: `POST .../work-items` (delivery create), `PUT .../work-items/{id}` (the status change follows
`StaffPermissions.permitsDeliveryWrite`: an admin moves work to review, a reviewer marks done from review, the
owner anything), `POST .../work-items/{id}/resolve-conflict` (owner or admin, `keep` `dev` or `local`). Every
body carries `expectedVersion`; a stale one answers `409 VERSION_CONFLICT` with `currentRevision`. Every write
raises the plan's `version`, archives the previous file as `delivery/archive/plan-v<N>.json` and uses a
conditional put. Tickets carry `createdIn` / `updatedIn` (`dev` on DEV, `local` locally; a missing stamp reads
as `local`), `updatedAt`, `origin {id, kind}` when promoted from Team Reviews, and `pendingLocal` when the
inbox sync found a conflict. The owner's local edits reach DEV through `delivery/inbox/plan.json`, merged
three-way against the archived base at start and every 4 hours (README, "Delivery plan"). The first
`plan.json` is uploaded by the owner by hand; until then the plan answers `404 DELIVERY_PLAN_MISSING`.
The Gateway forwards `/api/v1/delivery/plan/**` with the same session and bearer (`SCOPE_account`) as
platform reviews. Tests: `DeliveryPlanServiceTest`, `DeliveryPlanControllerTest`, `DeliveryPlanMergeTest`,
`DeliveryPlanStoreTest`, `ApiSecurityConfigurationIntegrationTest`.

## Configuration and validation

Cloud requires `LOOKAHEAD_COGNITO_ISSUER`, `AWS_REGION`,
`APP_OAUTH_CLIENT_ID`, `LOOKAHEAD_COGNITO_SCOPE_PREFIX`, and the separately
injected `LOOKAHEAD_DOMAIN_GATEWAY_SECRET` value (32–4096 characters; secret ARNs, unresolved placeholders and control characters are rejected). See `src/main/resources/application.yml`
and the application service manifest for authoritative bindings. The cloud store
configuration is `AWS_REGION` and the four `LOOKAHEAD_DYNAMODB_*_TABLE` names; the task role
reaches the tables, no secret is injected for them. Cloud readiness includes the `dynamodb`
check (the four tables answer and the data version meets the build's requirement) and the
publication gate.

Run Java21 `./mvnw test package`. Store suites opt in through
`DLV970_DYNAMODB_TEST_ENDPOINT` (a loopback DynamoDB Local; never the persistent laptop
store). They cover concurrent admission across registry instances, challenge binding/replay,
revocation/expiry, provider outage handling, and real signed JWTs through Spring
security/controllers into the store. Provider calls are controlled fixtures. Remaining
deployment gates include real client-secret transfer, the tables' IAM grants, private
Gateway-to-Domain TLS, live Cognito flows, and cross-service deployment evidence. No local
result authorizes cloud activation.
