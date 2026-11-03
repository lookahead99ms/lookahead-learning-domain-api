# DynamoDB persistence foundation

DLV-970, local candidate, 2026-11-02. This is a library foundation, not an activated
backend. No Spring configuration registers these classes, creates a client or
changes a controller/repository. All current JDBC services, Flyway history and
running Local/DEV data remain unchanged. Java SDK v2 uses the repository's existing
2.55.12 version; the new dependency is `dynamodb`.

## Current approved target: four DynamoDB tables

The latest user decision keeps full account-owned plan snapshots in DynamoDB.
Use `DynamoTableNames` (four explicit names), `StudyPlanDocumentStore` and
`StudyPlanDocumentMaintenance`. Metadata/progress remain in User Account Details;
full version snapshots/manifests/chunks live in User Study Plan Details. Reusable
published templates stay Content-owned, while adoption saves an independent full
snapshot. Author updates do not silently replace it. Current chunked storage,
verified publication and fenced abandonment cleanup remain the selected candidate.

The S3 prototype and shared-template-reference proposal are deferred. Their code,
tests and earlier implementation notes are retained below. No runtime configuration
or service selection changes; all aggregate and release/IAM gates remain open.

## Permanent staff labels (inactive V18 parity candidate)

`DynamoStaffAliasStore.assign(accountId, role)` owns a short transaction independent
of any directory caller. Supported roles are co-author, admin, reviewer, tester
and learner. Role priority, Owner labels, self-visibility, privacy projection and
role authorization remain service-adapter responsibilities; this library grants
no access and is not registered as a Spring bean.

User Account Details holds `STAFF_ROLE#role / COUNTER` with schemaVersion=1,
lastAssigned (0 through the positive SQL integer limit), revision and a canonical
UUID initializationId. Administrator Audit Events holds the matching immutable
`STAFF_ROLE#role / INITIALIZED` marker, `STAFF_ALIAS#accountId / ROLE#role`
assignment (number, assignedAt), and `STAFF_ROLE#role / NUMBER#0000000001` claim
(accountId, assignedAt). The ten-digit padded claim key is locale independent.

Allocation strongly reads the assignment first. If absent, it checks the required
counter and initialization marker, then atomically updates the observed counter
revision/value and INSERTs both permanent facts. A success or uncertain response
is reconciled by reading the assignment and checking its corresponding claim.
There is no reusable request token, max()/Scan, TTL, runtime seeding or reset.
Only conflict cancellations retry, at most five attempts with bounded jitter;
a combined counter-condition failure and immutable INSERT ValidationError is treated
as contention, while a lone validation error is not retried;
transport/permission errors retain the caller's retry boundary. Interruption stops
retries and preserves the thread interrupt flag.

An existing assignment remains readable if its mutable counter is unavailable,
but a missing/mismatched permanent claim fails closed. Exhausted, malformed,
missing or mismatched initialization/counter state blocks new allocation. A rolled
back counter cannot overwrite an already claimed number. A present assignment with a missing/mismatched claim fails closed. A missing
assignment before allocation is indistinguishable from a new account+role to this
key-based allocator: retained number claims alone cannot prove that no earlier
assignment was lost. Restore tooling must verify the complete immutable pair set
before allowing allocation; that readiness gate is not implemented here. Account deletion must retain
all alias partitions, counters and initialization markers.

Reviewed bootstrap must atomically initialize counters/markers and import any
retained assignments/high-water marks before activation; that tooling and its
permission proof are not delivered by this allocator. Real AWS insert-only tests,
full release coverage and the DLV-969 adapter/privacy gates remain required. The
Infra test command below includes `DynamoStaffAliasStoreTest` and
`DynamoStaffAliasDatabaseTest`; skipped database tests are not verification.

Earlier allocator batch (2026-11-03): 111 focused tests pass, including 52 DynamoDB Local
tests and all 18 allocator tests; zero skips/failures/errors. Persistence package
line coverage 98.95%, allocator 99.06%. This is the focused library gate, not the
full fixture-backed Domain release or effective AWS permission gate.

## Read-only alias integrity verifier (inactive operator candidate)

`DynamoStaffAliasVerifier.verify(Limits)` inspects a quiescent candidate through
strong GetItem reads of all five counters before/after a filtered strong Scan of
Administrator Audit Events. It verifies every assignment against its number claim
and every claim against its assignment, canonical account/role/time/number fields,
initialization IDs and counter high-water bounds. It returns aggregate counts only.
It accepts intact exhausted counters as structurally valid; that result does not
promise capacity for another allocation. Gaps in numbering are permitted.

Explicit limits bound each scan page (1–100 evaluated items), total pages (1–10,000)
and retained assignments/claims (1–100,000 each). Filtered empty pages still consume
the page budget and follow their continuation key. The first request omits the
continuation key. Malformed/duplicate records, missing pairs or markers, interrupted
reads, bad cursors, a changed counter or exhausted budgets throw instead of returning
partial success. This is maintenance-only Scan authority, not a permission needed
by the allocation request path; its operator identity/policy must be reviewed.

No data is changed, repaired, seeded or removed. There is no entry point that creates
an AWS client, Spring readiness bean, CLI, workflow or deployment change. Writers
must be held externally before inspection. Strong Scan does not provide a snapshot;
matching counters do not exclude a privileged concurrent restore/overwrite. A whole
pair lost from both sides is not detectable from the surviving records alone.
Reviewed backup/source inventory comparison and a maintained write fence remain
prerequisites for restored-target activation; this library is only one validation
component. F4 restore is not fully closed by the local tests.

Current local verification: 126 focused tests pass, including 60 database tests
and all 15 verifier cases, zero skips/failures/errors. Package lines 99.07%,
verifier 100%; full release and AWS/operator permission gates remain open.

## Atomic empty-alias bootstrap (inactive operator library)

`DynamoStaffAliasBootstrap.initialize(initializationId, initializedAt, maxScanPages)`
reads five counters/five initialization markers using one TransactGetItems snapshot.
A complete matching set returns a replay without changing counters; partial,
malformed or different initialization state fails. If absent, a bounded filtered
strong Scan must find no alias/role facts, then one ten-INSERT transaction creates
all five counters/markers. Success/lost replies reconcile durable state; no reusable
request token or counter reset. A late key collision rolls back all ten inserts.

The initial marker additionally stores canonical initializedAt. Required operator
privileges include transactional read, Scan and INSERT; no operator IAM is supplied
or verified by this library. External writer fencing must remain held throughout
inspection and creation. Concurrent identical writes converge; a caller that sees
newly populated scan data retries the same initialization after quiescence. Existing
labels require import, never an empty bootstrap. No workflow, endpoint, Spring bean,
AWS client creation, automatic repair or infrastructure provisioning is added.

Latest focused validation: 138 tests pass (68 database), no skips/failures/errors;
package lines 99.13%, bootstrap 100%. Full Domain release and effective AWS gates
remain separate. Selected PostgreSQL recording account data must be inventoried and
mapped for later import, including roles/plans/progress and issued labels. This
library does not export/import data or copy local authentication credentials.

## Deferred S3 prototype (historical notes; not the current target)

The full study-plan document target changed to private S3 on 2026-11-02. The
four-table/chunk sections below are retained as historical implementation notes.
Use `DynamoMetadataTables.from(Environment)` for the new path: it requires explicit
DEV/PROD names for User Account Details, Administrator Audit Events and Protected
Owner Identity. It does not require `user-study-plan-details-table-name`.
`AuditedAccountStore` and `ProtectedOwnerIdentityStore` accept this three-table
configuration; their earlier constructors remain for historical tests.

`S3StudyPlanDocumentObjects.Settings` requires environment, a globally unique
`lookahead-{dev|prod}-user-study-plan-details-<suffix>` bucket, expected 12-digit AWS
owner and a full customer-managed KMS key ARN. No client or resource is auto-created.
A version-enabled bucket, private access and correct IAM/KMS permissions must be
prepared and verified before runtime activation. Content Git holds design and
curriculum; private learner plans are not publication assets. Domain ownership is
the working assumption pending clarification of the user's Content-repo wording.

`S3StudyPlanDocumentStore` first creates a manifest and work job in User Account
Details, then uploads one bounded 1-byte–8-MiB immutable object with conditional
`If-None-Match: *`. It verifies size, SHA-256, metadata, encryption, version ID and
opaque ETag before marking the manifest UPLOADED. Lost replies reconcile through
verified stored objects/manifests. Publication atomically changes the account
pointer, audit INSERT, receipt and COMMITTED manifest, and deletes the work job.
A missing job is an idempotent delete, not an existence precondition. The manifest
and record revision are the transaction's publication fences. Caller deadline
checks occur before the request; they are not a server wall-clock commit deadline.

Object keys bind owner, plan and document UUIDs. Reads fetch the exact committed
S3 version and verify its bytes. Stable document/operation IDs, expected revision,
deadline, audit time and inputs are required for retries. The library does not
validate plan JSON or authorize users; service adapters must enforce those rules
and current plan/account lifecycle, including after a committed replay. Progress
updates remain small DynamoDB writes and do not rewrite full documents.

No S3 cleanup is implemented. Expired/interrupted uploads retain durable jobs;
a DynamoDB fence alone cannot stop a late S3 PUT. Safe orphan/history deletion,
retention, coordinated DynamoDB/S3 restore and full aggregate adapters are activation
gates. No browser credentials, public links or Content publication path are added.

Run the Infra `dynamodb_contract_tests.py` command below for all current and legacy
focused tests. The real S3 SDK uses a finite loopback HTTP fixture with synthetic
credentials; DynamoDB transactions use an owned, disposable DynamoDB Local
container. These tests prove library/protocol behavior, not managed S3 or AWS IAM.
The full fixture-backed Domain release coverage gate remains separate.

## Four-table configuration (current target)


| Label | DEV | PROD |
| --- | --- | --- |
| User Account Details | `lookahead-dev-user-account-details` | `lookahead-prod-user-account-details` |
| Administrator Audit Events | `lookahead-dev-administrator-audit-events` | `lookahead-prod-administrator-audit-events` |
| User Study Plan Details | `lookahead-dev-user-study-plan-details` | `lookahead-prod-user-study-plan-details` |
| Protected Owner Identity | `lookahead-dev-protected-owner-identity` | `lookahead-prod-protected-owner-identity` |

`DynamoTableNames.from(Environment)` requires `app.deployment-environment` to be
`dev` or `prod` and all four explicit properties:

```properties
app.dynamodb.user-account-details-table-name=lookahead-dev-user-account-details
app.dynamodb.administrator-audit-events-table-name=lookahead-dev-administrator-audit-events
app.dynamodb.user-study-plan-details-table-name=lookahead-dev-user-study-plan-details
app.dynamodb.protected-owner-identity-table-name=lookahead-dev-protected-owner-identity
```

Missing, duplicate, stale or cross-environment names fail validation. These
properties alone do not activate DynamoDB. A future user-reviewed restore to new
table names needs an explicit naming/configuration change; arbitrary names are
intentionally rejected by this first implementation. No account or Region is
selected and no table is created by the library.

## Implemented primitives

`AuditedAccountStore` commits exactly three distinct items with `ExecuteTransaction`:

1. Account record: `ACCOUNT#<uuid> / RECORD#v1#<UTF-8 byte length>:<recordId>`.
   Revision zero requests an insert; positive revisions require an exact existing
   revision and schema version. The new revision is the expected revision plus one.
2. Administrator event: account partition, `EVENT#<UTC timestamp with nine fractional
   digits>#<operation uuid>`. Includes actor, action, reason, record, revision and time.
   Automated membership events may identify a system actor. This is administrator
   oversight history, not a database diagnostic log or a payment ledger.
3. Durable mutation receipt: account partition, `MUTATION#<operation uuid>`, fingerprint
   and resulting revision. No TTL. The fingerprint covers all mutation/audit inputs.

All values are bound parameters. Only validated table identifiers enter SQL text.
Reads use base-table `GetItem` with `consistentRead=true`. An existing matching
receipt returns the original revision without another transaction; reused IDs with
different inputs fail. After an SDK exception the store checks the receipt once;
absence or another read failure preserves the original failure. The caller must
retain the same operation ID, payload, expected revision and audit timestamp for
retries. The SDK token is derived from account ID plus operation ID, preventing
cross-account token collisions when clients reuse an operation ID. Durable receipts,
not the SDK token's limited retention, establish long-lived replay behavior.

The primitive intentionally has no generic arbitrary-statement execution, delete,
owner-write or audit-update method. It cannot yet represent whole multi-record
aggregates. Service adapters must still enforce authorization, lifecycle/deletion
fences, projections, uniqueness, quotas and logical expiry in complete transactions.
Do not wire this primitive directly to plan/access endpoints or treat a replay
receipt as proof that a deleted plan is currently accessible.

`ProtectedOwnerIdentityStore` only strongly reads `OWNER / ROOT` from Protected Owner
Identity. Schema version 1 contains `issuer` and `subject`; both must match the
already verified token exactly. Missing binding denies owner authority. Malformed
schema/data or read failure cannot become an owner grant. Provisioning, bootstrap
receipts and rotation are not implemented; the runtime does not seed owner data.

`StudyPlanDocumentCodec` splits 1 byte through 8 MiB into 240-KiB binary chunks (at
most 35), preserving UTF-8 bytes even when a character crosses a chunk boundary.
It checks schema, total length, chunk count, every chunk length and whole-document
SHA-256 on decode. Missing, reordered, duplicate and damaged chunks fail validation.
It is not a JSON validator. `StudyPlanDocumentStore` now implements staging,
publication and abandonment cleanup, as detailed below. Full plan repository and
maintenance integration remain unfinished.

## Local validation

With Java 21:

```sh
./mvnw -Dtest='DynamoTableNamesTest,StudyPlanDocumentCodecTest,AuditedAccountStoreTest,ProtectedOwnerIdentityStoreTest,DynamoSdkBoundaryTest' test jacoco:report
```

The focused suite has 32 tests. `DynamoSdkBoundaryTest` uses the real SDK and an
ephemeral loopback HTTP fixture with fixed synthetic credentials; it closes the
fixture after each test. It exercises serialization and error/receipt recovery,
not DynamoDB transaction semantics or IAM. Other tests exercise validation,
conditional request construction, conflicting replays, uncertain outcomes, owner
matching and maximum-size/corrupted documents. Interface mocks use Mockito's proxy
maker so they also pass in the complete repository suite.

The existing full `-Pcoverage clean verify` gate remains unchanged and requires
both disposable PostgreSQL fixtures documented in the [README](../README.md#required-release-coverage-gate).
Focused-package coverage does not certify that full gate. Do not borrow, restart or
overwrite another agent's database fixtures to obtain a passing result.

## Remaining activation gates

Complete aggregate adapters, maintenance scheduling/backoff, service selection, bootstrap,
health/configuration changes, cross-service parity and concurrency/recovery tests.
Coordinate identity packages with DLV-969 and membership/gateway compatibility
with DLV-209. The revised scope stores memberships and minimal gateway references;
payment processing remains external and no payment-attempt ledger is added here.

AWS must still verify insert-only audit permissions using the actual runtime
principal, including denied native overwrite, update, delete and batch paths.
[PartiQL INSERT](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-reference.insert.html)
rejects duplicate keys; [conditional UPDATE](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-reference.update.html)
and [ExecuteTransaction](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_ExecuteTransaction.html)
provide the requested database primitives. DynamoDB Local now verifies the database
protocol; local tests do not establish effective AWS IAM. Runtime audit writes are INSERT
only in this code, but effective IAM remains a user-run pre-activation gate.

AWS deployment, permission changes, DEV reset and subsequent cleanup remain
user-run through reviewed workflows. No cloud action is performed by these tests.

## Staged document storage continuation

`StudyPlanDocumentStore` creates the manifest and abandonment job atomically. Each
chunk write checks the STAGING manifest in its transaction and permits only new
bytes or an identical retry. The caller supplies a stable document UUID and an
explicit UTC whole-second abandonment deadline; no retention default is selected.

Publication strongly reads and verifies all chunks, rechecks the deadline, then
atomically writes the account pointer, administrator event and receipt, changes
the manifest to COMMITTED, and removes its abandonment job. Ordinary-record and
document-publication receipts have distinct fingerprints. Missing or corrupt
chunks, changed stage/deadline or stale account revision prevent publication.
Uncertain commits reconcile through the durable receipt. Stable operation IDs,
expected revisions, audit timestamps and inputs remain required for retries.

An expired STAGING manifest can transition to DELETING. That transition races with
publication, so only one wins. Cleanup checks DELETING in every chunk-deletion
transaction, deleting at most 35 chunks per call. Interrupted cleanup restarts
safely from index zero. The job is removed only after chunks are gone; the small
DELETING manifest is retained to prevent ID reuse and late-upload resurrection.
COMMITTED documents are ineligible for abandonment cleanup. No TTL or scheduler
is enabled. Jobs use `WORK#DOCUMENT#00 / DUE#<12-digit epoch>#DOC#<owner>#<id>`.
Bounded global job discovery is implemented below. Scheduling/backoff and deletion
of published histories remain future aggregate/maintenance integration work.

`readPublished` returns only verified COMMITTED bytes. Its caller must authorize
the owning plan and enforce its current lifecycle/deletion fence. These primitives
are not a complete plan API and are not wired into Spring services.

`DynamoDocumentDatabaseTest` adds 14 real DynamoDB Local tests (46 focused tests
total), including 8-MiB storage, rollback, duplicate INSERT rejection with unchanged
audit data, interrupted calls, exact expiry, cleanup/publication races, competing
publications and receipt-kind separation. Run through Infra:

```sh
python3 local/database/dynamodb_contract_tests.py --domain /absolute/path/to/this/checkout
```

The helper uses the cached `amazon/dynamodb-local:2.6.1` image by immutable local ID,
a unique in-memory container and an ephemeral loopback port, then removes only that
container. The Java tests require `DLV970_DYNAMODB_TEST_ENDPOINT` and reject all
non-loopback endpoints. Without that explicit fixture the database tests skip.
Local duplicate INSERT produces a generic SDK exception, so the assertion checks
the duplicate-key rejection, HTTP 400 and unchanged audit data. Effective AWS
permission tests and the full fixture-backed release coverage gate remain separate.

## Bounded maintenance discovery

`StudyPlanDocumentMaintenance.cleanDue(limit, cursor)` processes one base-table,
strongly consistent Query page of 1–25 due jobs from `WORK#DOCUMENT#00`, oldest first.
It performs no Scan and registers no Spring bean or scheduler. Each cleanup retains
the existing per-document bound of at most 35 chunk deletions. This bounds work per
invocation, not wall-clock duration or AWS capacity; SDK timeout/retry settings belong
to later runtime configuration.

The returned internal cursor retains the first page's cutoff and last evaluated key.
Callers continue until `next` is null, then start a fresh sweep with no cursor. A
cursor may refer to a job just deleted by cleanup; DynamoDB Query still resumes after
its key. New/backdated jobs behind that cursor and failed jobs are revisited on the
next sweep. Queries are not snapshots and do not establish a global processing order.

Results identify each job as CLEANED, RETAINED or FAILED. Malformed metadata and
transient cleanup failures retain their job and do not stop later jobs in the page.
Missing, committed or not-yet-expired document manifests are not erased by discovery.
A query failure or invalid continuation fails the call before cleanup. Retained and
failed jobs require later operational monitoring/retry; this component does not
silently discard them or choose a retry schedule. The single existing job partition
has no production throughput proof or sharding claim.

Concurrent callers use the same fenced, idempotent deletion path. A losing worker
can report FAILED and retry in a later sweep; no lease or runtime activation is
introduced. A crash after deletion resumes through the retained DELETING manifest.

The focused fixture command above now runs 56 tests, including 17 DynamoDB Local
checks. Seven new unit tests cover query/cursor bounds, schema validation, failure
isolation and fixed cutoffs. Three new database tests cover exact deadlines,
pagination after deleted keys, backdated insertion, preservation of published/future
data, interrupted deletion, malformed jobs and competing workers. Full Domain
release coverage, service integration and effective AWS IAM remain separate gates.

## Published snapshot retirement (2026-11-03)

`StudyPlanDocumentRetirement.retire(stage, recordId, operationId, expectedRevision,
audit)` validates the exact current pointer and positive revision, then atomically
writes its tombstone, audit INSERT, durable receipt, RETIRED manifest and cleanup
job. The manifest is retained permanently in this candidate to prevent document
ID reuse. A competing replacement or retirement cancels conflicting writes.
Already-retired identical retries require the original durable receipt; absent or
incompatible receipts fail. Both chunk and deferred S3 publication retries now
apply the same rule once the manifest says COMMITTED, avoiding cached transaction
acknowledgments as substitutes for lost durable receipts.

`cleanRetired(id)` deletes at most 35 chunks under a per-transaction RETIRED/identity/
manifest fence, then removes its matching job. Retry starts at zero; missing chunks
are harmless. `cleanPage(limit, afterKey)` queries one strong page of 1–25 jobs,
returns CLEANED/RETAINED/FAILED outcomes and an internal continuation. Failed or
mismatched jobs stay for retry; new jobs behind the cursor need a fresh sweep.
Active documents cannot be cleaned through this path. Audit, receipt, manifest and
account tombstone remain. No scheduler, lease, Scan, TTL or runtime bean is added.

This is one current snapshot's storage retirement, not the endpoint's whole-plan
delete. Caller authorization and full lifecycle/history/progress/list/quota effects
must be integrated transactionally before activation. Existing deleted-response
semantics and restore integrity remain required. A read already in flight cannot
be revoked after bytes have been returned. PostgreSQL remains active; no HTTP
shape or runtime configuration changes.

The focused Infra fixture command now passes 93 tests (40 DynamoDB Local), with
98.93% package line coverage; the new retirement component has 100% line coverage.
This includes deferred-S3 regressions without selecting S3. The full fixture-backed
Domain release gate and effective AWS IAM gate remain separate and unresolved.
