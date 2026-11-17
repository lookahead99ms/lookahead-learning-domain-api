# DynamoDB persistence

DLV-970. DynamoDB is the Domain's one store. From 2026-11-10 to 2026-11-17 the Domain ran on either store,
chosen at startup by `app.persistence.store`; DEV was cut over on 2026-11-17 (`07 · Migrate DEV data` to data
version 5, then a deploy on `dynamodb`) and PostgreSQL was retired the same day with the switch, the `Jdbc*`
stores, Flyway and the SQL migrations (owner: "delete all Postgres things"; a final RDS snapshot is kept). Every
aggregate has one `Dynamo*` store behind its interface, the application context has no data source or JDBC
bean, and the data is prepared by the numbered migration steps below. The sections that follow this summary
describe the library layer (2026-11-02 to 2026-11-04) and remain accurate for the primitives.

## Store adapters (2026-11-10; the PostgreSQL column of this table retired 2026-11-17)

| Area | Interface(s) | DynamoDB | Item families |
| --- | --- | --- | --- |
| Accounts, identities, sessions, admission, merge | `CloudSignInRegistry` (abstract), `CloudMergeService` (abstract), `AdmissionCleanupStore`, `AdmissionStore`, `AccountRemovalStore`, `AccountStore` | `DynamoCloudSignInRegistry`, `DynamoCloudMergeService`, `DynamoCloudAccounts`, `Dynamo*Store`, `DynamoSignInRecordRetirement` | `ACCOUNT#id / META·CONTROL·IDENTITY#…·SESSION#…·CHALLENGE#…·PREFERENCES·LINKS·GRANT#topic`, `IDENTITY#issuer#subject / BINDING`, `EMAIL#… / META·ACCOUNT#id·MERGE#…`, `MERGE#digest / TOKEN`, `FAMILY#digest / BINDING`, `DIRECTORY#00 / PERSON#…`, admission, merge and removal events in Administrator Audit Events |
| Staff access, labels, directory, maintenance | `StaffAccessRepository` (abstract), `StaffAliasStore`, `AccountDirectoryStore`, `AdminDirectoryStore`, `AccountMaintenanceStore` | `Dynamo*` + `DynamoStaffAliasStore` library | `STAFFACCESS / ASSIGNMENT#id`, `ACCOUNT#id / STAFF`, `INVITE#email / ASSIGNMENT`, `STAFF# / EVENT#…` (insert-only), labels `STAFF_ALIAS#…` |
| Plans, versions, progress, receipts, lifecycle, free ledgers, support receipts | `PlanRepository`, `PlanLifecycleStore`, `FreePlanLedgerStore`, `SupportReceiptRepository`, `PlanErasure` | `DynamoPlanRepository` with `DynamoOwnerTransactionManager` (one PartiQL transaction per `@Transactional` unit, `ACCOUNT#id / META` revision as the owner lock), documents ≤200 KiB inline else `StudyPlanDocumentStore` | `ACCOUNT#id / PLAN#id·PLANLIST#…·MUTATION#key·SUPPORT#key·LIFECYCLE#…·FREECREATE#…·FREEDELETE#…`, `PLAN#owner#id / VERSION#…·ACTIVITY#…`, `WORK#PLAN-PURGE#00 / …` |
| Memberships, catalog, sales, mock gateway, access summary | `MembershipStore`, `AccessSummaryStore`, `MembershipGrants`, `MockPaymentStore` | `Dynamo*` | `CATALOG / PLAN#id·META`, `SALES / SALE#…·META`, `ACCOUNT#id / MEMBERSHIP#id`, `PURCHASE#id / ACCOUNT`, `PROVIDEREVENT#ns#id / RECEIPT`, `MOCKPAYMENT#…`, audit `MEMBERSHIP#subject / EVENT#…` |
| Test accounts, scenarios, erasure | `TestAccountStore`, `ScenarioPurchaseStore`, `AccountDataErasure` | `Dynamo*` | `ACCOUNT#id / TEST·CREATION`, `TESTACCOUNTS#00 / …`, `CREATOR#id / CREATE#key`, reset receipts `TEST#id / RESET#key` (audit) |
| Author reviews, record numbers, status history | `AuthorReviewStore`, `ReviewRecordNumbers`, `ReviewStatusHistory` | `Dynamo*` | `ACCOUNT#actor / REVIEW#artifact·REVIEWKEY#key`, audit `REVIEW#actor#artifact / EVENT#seq#id`, `SEQUENCE#kind / COUNTER`, `RECORD#kind#id / STATUS#…` |
| Sweeps | `MaintenanceJobs` | `RegisteredMaintenanceJobs`: `study-plan-documents`, `retired-study-plan-documents`, `plan-purge`, `account-erasure` (queue `WORK#ACCOUNT-ERASURE#00`) | — |
| HTTP mapping | `AccountErrorHandler`, `DomainStorageFailureFilter` | `DynamoWrites.Unavailable` and SDK failures → 503 `ACCOUNT_STORAGE_UNAVAILABLE`; `DynamoWrites.Conflict` → 409 `REVISION_CONFLICT` | — |

Author review submission reads the idempotency receipt before the artifact head.
Because two strongly consistent reads are not one snapshot, a conflicting head
triggers a fresh receipt read before rejection. A duplicate committed between reads
replays the original event; a changed payload is an idempotency conflict; a stale
decision without a matching receipt remains a supersession conflict. The atomic
head/receipt/event write and existing revision guards are unchanged.

Cross-area reads and writes go through small interfaces (`AccountGrantsLookup`, `AccountIdentityLookup`,
`AccountRecordsMaintenance`, `MembershipTopicSource`, `MembershipFacts`, `MembershipErasure`,
`TestAccountRegistryLookup`, `LearningDataErasure`, `PlanErasure`, `PlanActivityLookup`, `PlanMaintenanceJobs`),
each with one dynamodb-mode adapter (`DynamoAccountRecords`, `DynamoPlanFacts`, `DynamoMembershipFacts`,
`DynamoTestAccountRegistry`, `DynamoPlanMaintenanceJobs`, `DynamoAccountErasureJob`). `ACCOUNT#id / META`
(schemaVersion 1, revision, lastPlanNumber, createdAt) is the one item every owner-scoped transaction fences on;
the account aggregate creates it at first contact and the test-account store on creation.

Retired with PostgreSQL (2026-11-17), without a DynamoDB counterpart: the `CloudAccountAdministration` command-line
tool (the owner identity admits itself at first contact, or at its next sign-in when `OWNER / ROOT` was written after
that contact; every other account action is an owner endpoint; `resolve-password-change` has no caller) and the Local
audit purge (an insert-only audit has nothing to purge).

Data steps shipped by this build (`DomainDynamoCatalog.REQUIRED = 5`): 1 `base-tables`, 2 `staff-alias-counters`,
3 `membership-catalog` (the ten catalog plans and `CATALOG / META`; a replay accepts the seeded ids), 4
`review-record-counters` (`SEQUENCE#review-record` and `SEQUENCE#support-request` at 0), 5 `account-access-import`
(on DEV, run 44162520740 of 2026-11-17: the real PostgreSQL accounts with their identities, admissions, roles and
labels, written in the application's own item shapes with their PostgreSQL ids, the record counters continued from
the sequence values; since the retirement of the source the step only writes its empty result marker on a store
without one and verifies the accounts an existing marker names; see [docs/dynamodb-runtime.md](dynamodb-runtime.md)).

Validation (2026-11-10, integrated tree): `./mvnw -o -q test-compile` clean; the Infra DynamoDB Local wrapper with
every `*Dynamo*Test` plus the foundation tests: 340 tests, 0 failures, 1 environment-gated skip; line coverage
persistence 100%, persistence/dynamodb 99.4%, persistence/dynamodb/migration 96.0%, new Dynamo classes per area
97–100%; `./mvnw -o -q test -Dtest='!*DatabaseTest'`: 870 tests, 0 failures (two contract tests need the
fetched Content contracts and pass with `-Dcontracts.dir`); `DynamoModeApplicationContextTest` against the
persistent local store: 446 bean definitions, refresh succeeded, no DataSource/JdbcTemplate/Flyway bean, exactly one
`PlatformTransactionManager` (`dynamoOwnerTransactionManager`). The full fixture-backed release gate and the local
end-to-end run are recorded in the Content evidence folder for this batch.

## Library foundation (2026-11-02)

The persistence library (`persistence/dynamodb`) was delivered first and the store adapters on top of it
on 2026-11-10; everything below is wired by `DynamoPersistenceConfiguration` and the `Dynamo*` beans,
unconditional since the PostgreSQL retirement of 2026-11-17. Java SDK v2 at the repository's 2.55.12; the dependency is `dynamodb` only.

## Runtime settings, migration tooling and the local store (2026-11-10, switch retired 2026-11-17)

`DynamoPersistenceConfiguration` supplies the `DynamoDbClient`, `DynamoTableNames`, `DynamoSettings`, the
release `DynamoDataRequirement` and the `dynamodb` readiness indicator, unconditionally; the readiness group
names `readinessState`, `dynamodb` and `contentPublication`. The store switch (`app.persistence.store`,
`PersistenceStore.Check`, the `domainDatabase` indicator) went with PostgreSQL; a DynamoDB failure has nothing
to fall back to and answers 503 until the store recovers.

`DynamoSettings`: Local needs `app.dynamodb.endpoint` (`LOOKAHEAD_DYNAMODB_ENDPOINT`) pointing at a
loopback DynamoDB Local (`http://127.0.0.1:<port>`) and uses synthetic credentials and the four DEV
literal table names (`DynamoTableNames.local()`); DEV and PROD must not set an endpoint, take
`AWS_REGION` and the task role's credentials, and name the four tables explicitly
(`LOOKAHEAD_DYNAMODB_USER_ACCOUNT_DETAILS_TABLE`, `…ADMINISTRATOR_AUDIT_EVENTS_TABLE`,
`…USER_STUDY_PLAN_DETAILS_TABLE`, `…PROTECTED_OWNER_IDENTITY_TABLE`). Refusals name the setting, never its value.

Migration tooling (`persistence.dynamodb.migration`): `DomainDynamoCatalog` is the immutable ordered list
of data steps this build ships (the first two: 1 `base-tables`: the four tables exist, are ACTIVE and key on
string pk/sk; 2 `staff-alias-counters`: the five permanent-label counters and markers through the reviewed
bootstrap with a fixed initialization identity; steps 3 and 4 are listed in the summary above), each hashed over
version, name and definition; `REQUIRED` is the release
minimum bound to the catalog in tests. `DynamoMigrationLedger` keeps `DATA_VERSION#DOMAIN / STEP#<ten digits>`
and `CURRENT` in Protected Owner Identity and records a step in one transaction that inserts the step,
advances CURRENT from the exact previous version and checks the runner still holds the fenced
`DATA_VERSION#DOMAIN / LOCK` (`DynamoMigrationLock`: monotonic fence, 15-minute lease, takeover only after
expiry, release keeps the fence). `DynamoMigrationRunner.migrate` applies each missing step apply → verify →
record under the lock and stops at the first failure with nothing recorded for it; `verify` is read-only
(contiguous history, catalog checksums, every known step's postconditions, CURRENT agrees; data ahead of the
build is accepted and only known steps are checked); damaged or edited history fails closed without quoting
records. `DomainDynamoMigration` is the entrypoint the DEV migration task and the laptop run from the
application jar:

```sh
LOOKAHEAD_ENVIRONMENT=local LOOKAHEAD_DYNAMODB_ENDPOINT=http://127.0.0.1:4400 \
java -cp target/lookahead-domain-api-0.0.1-SNAPSHOT.jar \
  -Dloader.main=com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoMigration \
  org.springframework.boot.loader.launch.PropertiesLauncher migrate   # or verify | status
```

Exit 0 succeeded, 1 failed (the ledger is unchanged past the last recorded step), 2 usage; one JSON line
reports before/after/required/applied/verified. Logger `lookahead.migration`: a held lock is one WARN
line (`Migration migrate failed: Another migration run holds the lock`); a failed step is one ERROR line
`Data step N <name> failed before it was recorded; CURRENT stays at N-1: <exception type: its message>;
<hint>; the task exits 1 and a rerun resumes from step N` (a DynamoDB permission refusal points at the IAM
policy, a missing table at the Infra stack, a connection failure at the VPC endpoint);
item contents never appear. The persistent laptop store is operated with the
Infra script `local/database/dynamodb_local.py` (container `lookahead-local-dynamodb` on 127.0.0.1:4400,
volume `lookahead-local-dynamodb-data`); the disposable test fixture in `dynamodb_contract_tests.py` is separate.

Shared adapter helpers: `DynamoItems` (typed key prefixes, length-delimited `v1#<len>:<text>` for free text
in keys, fixed-width times, ten-digit padded numbers, fail-closed attribute readers, JSON ↔ map/list values,
`schemaVersion` on every item) and `DynamoWrites` (one PartiQL `ExecuteTransaction`: INSERT for every
administrator audit event and every uniqueness sentinel, UPDATE with double-quoted attribute names and a
WHERE fence, DELETE that is a silent no-op on a missing item, a condition-only check; a failed condition or
duplicate key is `Conflict` with one reason per statement, anything else `Unavailable`). PartiQL rather than
`TransactWriteItems` because the runtime role's only write permission on the audit table is
`dynamodb:PartiQLInsert`, and one transaction cannot mix the two APIs. `DynamoWritesDatabaseTest` pins these
semantics on DynamoDB Local.

Validation (2026-11-10, Infra wrapper on DynamoDB Local): 219 focused tests pass after the foundation
(192 library tests on current main plus the migration, settings, configuration and store-selection tests),
then the PartiQL helper tests (3 unit, 3 database, 4 items); persistence package line coverage 99.32% before
the helpers. The built jar ran `status` (0) → `migrate` (applied both steps) → `verify` → `status` (2) against
the persistent local store.

## The four tables and their names

| Label | DEV | PROD |
| --- | --- | --- |
| User Account Details | `lookahead-dev-user-account-details` | `lookahead-prod-user-account-details` |
| Administrator Audit Events | `lookahead-dev-administrator-audit-events` | `lookahead-prod-administrator-audit-events` |
| User Study Plan Details | `lookahead-dev-user-study-plan-details` | `lookahead-prod-user-study-plan-details` |
| Protected Owner Identity | `lookahead-dev-protected-owner-identity` | `lookahead-prod-protected-owner-identity` |

Every table keys on string `pk` / `sk`; no index. `DynamoTableNames.from(Environment)` requires
`app.deployment-environment` `dev` or `prod` and the four explicit `app.dynamodb.*-table-name`
properties (`LOOKAHEAD_DYNAMODB_*_TABLE`); missing, duplicate, stale or cross-environment names refuse
startup; Local reuses the DEV literals on DynamoDB Local (`DynamoTableNames.local()`). The runtime role
writes the first three through PartiQL only (`PartiQLInsert` alone on the audit table, so every audit
event is insert-only by permission, not by convention) and only reads Protected Owner Identity; the
migration-task role alone writes the ledger there. A test (`DynamoRuntimeOperationsTest`) fails the build
if any main source calls a native write (`putItem`, `updateItem`, `deleteItem`, `transactWriteItems`,
`batchWriteItem`), so the policy and the code cannot drift apart silently.

## Libraries under the adapters

**`DynamoWrites` and `DynamoItems`** are described above. `DynamoWrites.classify(SdkException)` is the one
mapping every library uses: a cancelled condition, duplicate key or transaction conflict is `Conflict`
(409 `REVISION_CONFLICT` at the web layer), anything else `Unavailable` (503 `ACCOUNT_STORAGE_UNAVAILABLE`);
`DynamoOwnerUnit.DynamoOwnerUnavailable` (an owner fence that could not be read) is an `Unavailable`.

**`AuditedAccountStore`** commits one account record (`ACCOUNT#<uuid> / RECORD#v1#<len>:<id>`, revision
fenced), one administrator event (`EVENT#<time>#<operation>`) and one durable mutation receipt
(`MUTATION#<operation>`, fingerprint over every input) in one transaction; a replay with the same inputs
answers the recorded revision without a write, different inputs fail, an uncertain commit is reconciled
through the receipt. **`ProtectedOwnerIdentityStore`** strongly reads `OWNER / ROOT` (issuer, subject) and
denies owner authority on anything missing or malformed; the runtime never writes it.

**Account control** (`DynamoAccountControlStore.transition`): the owner's Disable / Enable / Delete of an
admitted account as one transaction on `ACCOUNT#id / CONTROL` (state, revision, `authGeneration`), a
fingerprinted command receipt `ACCOUNT_COMMAND#id / OP#operation`, the admission event, and for Delete the
terminal `ACCOUNT#id / REMOVED` marker in the audit table plus the erasure job `ACCOUNT_REMOVAL#id / JOB`
(phase ERASE → DONE by `DynamoAccountErasure`, resumable through the `account-erasure` sweep). Owner and
author accounts, managed test accounts and waiting accounts are protected; a merge deletes an admitted
account in the same shape from its own transaction (`DynamoCloudAccounts.remove`).

**Permanent staff labels**: `DynamoStaffAliasStore.assign(account, role)` allocates the next number of a
role through a conditional counter update and two immutable INSERT claims; `DynamoStaffAliasBootstrap`
initialises the five counters and markers once (data step 2); `DynamoStaffAliasVerifier.verify()` checks,
per role and by key, the counter, its marker, the claim at the high-water mark, that claim's alias and
the absence of the next number: at most five `GetItem`s per role, never a Scan.

**Data version and readiness**: `DynamoDataVersionReader` reads `DATA_VERSION#DOMAIN / CURRENT` in Protected
Owner Identity (absent = 0; format and namespace checked); `DynamoReadiness` probes the other three tables
and compares that watermark with `DynamoDataRequirement` (`DomainDynamoCatalog.REQUIRED`), at most four
`GetItem`s per check, two-second budget, UP/DOWN only with `DATA_VERSION_BEHIND`, `TABLE_MISSING`,
`ACCESS_DENIED` or `STORE_UNAVAILABLE` through the `HealthReport` logger. A matching watermark alone cannot
prove a backfill survived a partial restore; the migration's `verify` does that.

**Study-plan documents** (snapshots and mutation responses over 200 KiB): `StudyPlanDocumentCodec` splits 1
byte to 8 MiB into 240-KiB chunks (at most 35) and verifies length, count and SHA-256 on decode.
`StudyPlanDocumentStore` stages a manifest with an abandonment job, writes chunks under the STAGING fence
(identical retries allowed), and publishes by verifying every chunk, then writing the account pointer, the
audit event, the receipt, the COMMITTED manifest (with `publishedAt`) and removing the job in one
transaction. A COMMITTED manifest with the same content is reusable whatever its deadline, so a retried
mutation whose response was published before a failed commit succeeds. Abandoned STAGING manifests move to
DELETING and their chunks are deleted in fenced batches (`StudyPlanDocumentMaintenance.cleanDue`, the
`study-plan-documents` sweep, one strongly consistent page of due jobs from `WORK#DOCUMENT#00`, no Scan).
`StudyPlanDocumentRetirement.retire` tombstones a published snapshot (RETIRED manifest with `retiredAt`,
audit, receipt, cleanup job) and `cleanPage` removes its chunks (the `retired-study-plan-documents` sweep);
manifests are retained so a document id is never reused. Every write is PartiQL through `DynamoWrites`.

**Migration tooling** (`persistence/dynamodb/migration`): `DynamoMigrationCatalog` (consecutive steps,
SHA-256 over version, name and definition), `DynamoMigrationLedger` (`DATA_VERSION#DOMAIN / STEP#…` and
`CURRENT`, one fenced transaction per step), `DynamoMigrationLock` (15-minute fenced lease with a monotonic
fence, takeover after expiry), `DynamoMigrationRunner` (migrate = apply, verify, record; verify is read-only;
data ahead of the build is accepted; a failed release is attached to the step failure, never masks it) and
the `DomainDynamoMigration` entrypoint. The ledger and lock are the only items the migration task writes
natively-shaped through PartiQL; the running application's role only reads them.

## Validation

Focused suites run against a disposable DynamoDB Local through the Infra wrapper
(`python3 local/database/dynamodb_contract_tests.py --domain <checkout>`), or directly with
`DLV970_DYNAMODB_TEST_ENDPOINT=http://127.0.0.1:<port>` (loopback only; the database tests skip without it).
Every `*DatabaseTest` creates the four tables when absent and purges the key prefixes it writes; never point
the variable at the persistent laptop store. The full fixture-backed release gate (`-Pcoverage clean verify`,
85% line coverage) needs this one fixture (`DLV970_DYNAMODB_TEST_ENDPOINT` and `DLV970_LOCAL_DYNAMODB_ENDPOINT`,
`CoverageFixtureAvailabilityTest`); the PostgreSQL fixtures retired with the store on 2026-11-17. Local tests
exercise the database protocol, not IAM: effective AWS permissions are proven by the Infra probes A01–A16
after the six-role transition, user-run.

## Open items

- `CloudAccountAdministration` retired with PostgreSQL (2026-11-17): `admit`, `disable`, `enable` and the author
  flags are owner endpoints or the owner's self-admission; `resolve-password-change` has no counterpart and no
  caller.
- The Local audit purge retired with PostgreSQL (the audit table is insert-only; nothing needs purging).
- A plan creation refused at commit (lost owner fence) after a > 200 KiB version document was published
  leaves a COMMITTED, unreferenced document; the purge sweep does not retire it yet.
- Where the retired PostgreSQL store waited on a row lock, DynamoDB tells the loser to reload (409): plans of one owner, a
  creator's simultaneous test-account creates, concurrent checkouts; `DynamoTestAccountStore.transact`
  retries re-runnable bodies three times.
- Study plans, purchases, receipts, reviews and audit history were not imported from PostgreSQL (owner decision
  2026-11-17: accounts and access only, by `DomainDynamoCatalog` step 5; none of the real accounts had plans). They
  remain only in the final RDS snapshot.
