# DynamoDB runtime: the store, local run, migration entrypoint

DLV-970 (lane D, 2026-11-10). This page is the short operator view; the design and the
library classes are in [DynamoDB persistence](dynamodb-persistence.md).

## The one store

DynamoDB is the one store (DLV-970; PostgreSQL served DEV until 2026-11-17 and was retired with its
store switch, Flyway and the database settings).
What is wired: `DynamoDbClient`, `DynamoTableNames`, `DynamoConnection`, the `dynamodb` readiness
check, every store class under `db/store` and the maintenance jobs under `job`. What never exists: any
`DataSource`, JDBC template, JDBC transaction manager or Flyway bean, and the classes themselves
are off the class path (`DomainApiApplicationContextTest` asserts both). The readiness group
names `readinessState`, `dynamodb` and `contentPublication`.

The store's failures: a transaction whose outcome is unknown (`DynamoWrites.Unavailable`) or an
SDK failure reaching the web layer answers `503 ACCOUNT_STORAGE_UNAVAILABLE`; a condition that
did not hold after the store's own retries (`DynamoWrites.Conflict`) answers `409 REVISION_CONFLICT`.
The 503 is one ERROR line that names the operation and table(s) (`DynamoWrites.Unavailable.call()`),
quotes the SDK's own message (an IAM denial names the role and the action it lacks; the answer body
never carries it), the status and code the caller got and the reference of the stack trace that
follows; the 409 is one WARN line with the failure's type (see README → Reading the log). There is
no other store to fall back to.

## Settings

| Setting (variable) | Local | DEV / PROD |
| --- | --- | --- |
| `app.deployment-environment` (`LOOKAHEAD_ENVIRONMENT`) | `local` | `dev` / `prod` |
| `app.dynamodb.endpoint` (`LOOKAHEAD_DYNAMODB_ENDPOINT`) | required, `http://127.0.0.1:<port>` and nothing else | must be empty (the Region's service) |
| `app.dynamodb.region` (`AWS_REGION`) | ignored (`us-east-2`) | required |
| the four `LOOKAHEAD_DYNAMODB_*_TABLE` names | ignored: Local reuses the four `lookahead-dev-*` literal names on its own container | required, all four from one environment |

Credentials: Local uses synthetic static credentials; DEV and PROD use the task role's
default credential chain. Building the client opens no connection.

## Local run

1. Start DynamoDB Local on loopback (the persistent laptop store is `127.0.0.1:4400`):
   `docker run --rm -p 127.0.0.1:4400:8000 amazon/dynamodb-local:2.6.1 -jar DynamoDBLocal.jar -sharedDb -dbPath /home/dynamodblocal`
   (add a volume on `/home/dynamodblocal` to keep the data).
2. Create the four tables once; Infra creates them in AWS, migration step 1 only verifies
   them. With `AWS_ACCESS_KEY_ID=synthetic AWS_SECRET_ACCESS_KEY=synthetic AWS_REGION=us-east-2`,
   for each of `lookahead-dev-user-account-details`, `lookahead-dev-administrator-audit-events`,
   `lookahead-dev-user-study-plan-details`, `lookahead-dev-protected-owner-identity`:
   `aws dynamodb create-table --endpoint-url http://127.0.0.1:4400 --table-name <name> --billing-mode PAY_PER_REQUEST --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE`
3. Run the migration (below) with `LOOKAHEAD_ENVIRONMENT=local LOOKAHEAD_DYNAMODB_ENDPOINT=http://127.0.0.1:4400`.
4. Start Domain with `LOOKAHEAD_ENVIRONMENT=local` and
   `LOOKAHEAD_DYNAMODB_ENDPOINT=http://127.0.0.1:4400`. Readiness answers UP once the data version watermark
   reaches the build's requirement (`DomainDynamoCatalog.REQUIRED`).

Tests never use the 4400 store: `*DatabaseTest` classes run on a disposable in-memory
container through the Infra wrapper (`DLV970_DYNAMODB_TEST_ENDPOINT`), and the whole-context
test instantiates beans only, with no call to the store, when `DLV970_LOCAL_DYNAMODB_ENDPOINT`
is set.

## Migration entrypoint

The Infra migration task runs it from the application image before the app of the same
build is deployed (data first, then app; no pipeline gate):

```
java -cp /opt/lookahead/app.jar -Dloader.main=com.lookahead.domain.persistence.dynamodb.migration.DomainDynamoMigration \
     org.springframework.boot.loader.launch.PropertiesLauncher migrate|verify|status
```

Inputs are process variables: `LOOKAHEAD_ENVIRONMENT`, `AWS_REGION`, the four
`LOOKAHEAD_DYNAMODB_*_TABLE` names (dev/prod) or `LOOKAHEAD_DYNAMODB_ENDPOINT` (local),
`GITHUB_RUN_ID` for the run identity (`github-<id>`, else `local-<uuid>`). No command reads
anything but the four tables (the PostgreSQL import source of step 5, `LOOKAHEAD_IMPORT_*`, was
retired with PostgreSQL on 2026-11-17). It prints one JSON
line (`command`, `environment`, `runId`, `before`, `after`, `required`, `applied`, `verified`,
`ahead`, or `failed` with the failure's type). Exit 0: the command succeeded; 1: it failed and
the ledger is unchanged past the last recorded step (a held lock is `LOCKED`, a changed
applied step `LEDGER_CONFLICT`); 2: usage error.

`migrate` takes the fenced lock, applies and verifies each catalog step the store is behind
on, and records it in the ledger (`DATA_VERSION#DOMAIN` in Protected Owner Identity);
`verify` re-verifies every applied step this build knows; `status` reads the watermark. The
catalog (`DomainDynamoCatalog`) is append-only: step 1 `base-tables` verifies the four
tables, step 2 `staff-alias-counters` seeds the permanent label counters, step 3
`membership-catalog` seeds the ten membership plans and the catalog revision, step 4
`review-record-counters` initialises the two record-number counters at 0, step 5
`account-access-import` (owner decision 2026-11-17). On DEV, run 44162520740 of 2026-11-17 read the
PostgreSQL platform database once and wrote every real account with its PostgreSQL id (control and
subject items, identities, email carriers, directory and waiting projections, the staff assignment
and its events, admission events at their recorded times, permanent label claims), the live pending
staff invitations, raised the label counters to the highest imported number and the two record
counters to the sequence values, and recorded the outcome in `IMPORT#account-access / RESULT`.
The source was retired the same day: the step keeps its number, name and definition text (the DEV
ledger holds that checksum), leaves a store that carries the marker untouched, writes the empty
marker on a store without one (a fresh local store, PROD), and `verify` still checks that every
account the marker names is there as imported. Managed test accounts were not imported: the roster
is created again from the Test accounts page. The migration-task role alone may write the ledger;
the running app's role only reads it.

## Sweeps

`SweepRunner` has a fourth job, `maintenance` (`POST /api/v1/sweeps/run {"job":
"maintenance"}` or part of `all`, and the 4-hour cadence). It runs every job registered with
`MaintenanceJobRegistry`, each in bounded pages (at most 40 per sweep). The
registered jobs are `study-plan-documents` (`StudyPlanDocumentCleanupStore.cleanDue`: abandoned
uploads), `retired-study-plan-documents` (`StudyPlanDocumentRetirementStore.cleanPage`: chunks of
retired snapshots), `plan-purge` (`StudyPlanPurgeJob` over `StudyPlanPurgeStore`: deleted plans' versions, activity and
receipts), `account-erasure` (`AccountErasureJob` over `AccountErasureStore`: deletions interrupted after
their state transition) and `sign-in-records` (`SignInRecordRetirementJob`: sessions not
active for 7 days and challenges past the 15-minute window, per account through the directory;
`FAMILY#` bindings stay so a revoked token family is never admitted again). The 48-hour
admission cleanup runs as before through its own store.

## Accounts: what differs from the retired PostgreSQL store (history)

- The protected owner identity is admitted at its own first contact (`admission.admitted-owner`);
  no operator command does this. If `OWNER / ROOT` is
  written only after that first contact (the DEV cutover order of 2026-11-17), the owner's waiting
  account is admitted at its next sign-in instead, by the same writes as the owner endpoint, with
  the author flag set. Everyone else waits for the owner as before.
- A session's `lastActiveAt` is written at most once a minute and never fences the control item,
  so a page's parallel calls do not contend; idle expiry counts from the last write.
- Sign-in challenges carry the generation they were issued under and die with it (Disable,
  Delete, a merge, "end other sign-ins"), as the retired SQL cancelled them.
- The e-mailed account merge (`AccountMergeStore`, called by `AccountMergeService`) is one fenced transaction on the
  email's sentinel and every account's control revision; the deleted accounts' erasure runs
  after it, resumable by `account-erasure`. A race answers `409 MERGE_CONFLICT`, never a half
  merge. Links live as `MERGE#digest / TOKEN` with an `EMAIL#email / MERGE#…` index.
- Lost conditions answer `409`; a transaction whose outcome is unknown answers `503`. Where
  PostgreSQL waited on a row lock, DynamoDB tells the loser to reload and retry.
