# DynamoDB runtime: store selection, local run, migration entrypoint

DLV-970 (lane D, 2026-11-10). This page is the short operator view; the design and the
library classes are in [DynamoDB persistence](dynamodb-persistence.md).

## Store selection

One process reads and writes exactly one store, chosen by `LOOKAHEAD_PERSISTENCE_STORE`
(`app.persistence.store`):

| Value | What is wired | What never exists |
| --- | --- | --- |
| `postgresql` (default, unset) | Hikari `DataSource`, `JdbcTemplate`, `JdbcTransactionManager`, the `domainDatabase` readiness check, every `Jdbc…` store | the DynamoDB client |
| `dynamodb` | `DynamoDbClient`, `DynamoTableNames`, `DynamoSettings`, the `dynamodb` readiness check, every `Dynamo…` store, the DynamoDB maintenance jobs | any `DataSource`, JDBC template, transaction manager or Flyway bean (`DynamoModeApplicationContextTest` asserts it) |

Any other value stops the process before it takes traffic (`PERSISTENCE_STORE_INVALID`).
A DynamoDB failure never falls back to PostgreSQL. The readiness group lists both
indicator names; the selected store contributes one.

In `dynamodb` mode the store's failures answer what the PostgreSQL ones do: a transaction
whose outcome is unknown (`DynamoWrites.Unavailable`) or an SDK failure reaching the web
layer answers `503 ACCOUNT_STORAGE_UNAVAILABLE`; a condition that did not hold after the
store's own retries (`DynamoWrites.Conflict`) answers `409 REVISION_CONFLICT`. The log line
carries the failure's types only, never the SDK message.

The PostgreSQL-only tools stay PostgreSQL-only and are not Spring beans: `DomainApiBootstrap`
(retired entrypoint, Local role provisioning helpers), `DomainApiMigration` (Flyway
configuration and migration-role checks), `domain.database.DomainDatabaseConnections`,
`InitialMigrationGuard` and `LocalAuditProvisioning`. Nothing in `dynamodb` mode calls them.

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
4. Start Domain with `LOOKAHEAD_PERSISTENCE_STORE=dynamodb`, `LOOKAHEAD_ENVIRONMENT=local`,
   `LOOKAHEAD_DYNAMODB_ENDPOINT=http://127.0.0.1:4400` and no `LOOKAHEAD_DOMAIN_JDBC_URL` /
   `LOOKAHEAD_DOMAIN_DB_PASSWORD`. Readiness answers UP once the data version watermark
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
`LOOKAHEAD_DYNAMODB_*_TABLE` names (dev/prod) or `LOOKAHEAD_DYNAMODB_ENDPOINT` (local), and
`GITHUB_RUN_ID` for the run identity (`github-<id>`, else `local-<uuid>`). It prints one JSON
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
`review-record-counters` initialises the two record-number counters at 0. The migration-task
role alone may write the ledger; the running app's role only reads it.

## Sweeps in dynamodb mode

`RuntimeSweeps` gains a fourth job, `maintenance` (`POST /api/v1/sweeps/run {"job":
"maintenance"}` or part of `all`, and the 4-hour cadence). It runs every job registered with
`MaintenanceJobs`, each in bounded pages (at most 40 per sweep). In `dynamodb` mode the
registered jobs are `study-plan-documents` (`StudyPlanDocumentMaintenance.cleanDue`: abandoned
uploads), `retired-study-plan-documents` (`StudyPlanDocumentRetirement.cleanPage`: chunks of
retired snapshots), `plan-purge` (`DynamoPlanPurge`: deleted plans' versions, activity and
receipts), `account-erasure` (`DynamoAccountErasure.resumePage`: deletions interrupted after
their state transition) and `sign-in-records` (`DynamoSignInRecordRetirement`: sessions not
active for 7 days and challenges past the 15-minute window, per account through the directory;
`FAMILY#` bindings stay so a revoked token family is never admitted again). The 48-hour
admission cleanup runs as before through its own store. In `postgresql` mode nothing is
registered and the job answers an empty list.

## Accounts in dynamodb mode: what differs from PostgreSQL

- The protected owner identity is admitted at its own first contact (`admission.admitted-owner`);
  on PostgreSQL the operator's `CloudAccountAdministration admit` did this. If `OWNER / ROOT` is
  written only after that first contact (the DEV cutover order of 2026-11-17), the owner's waiting
  account is admitted at its next sign-in instead, by the same writes as the owner endpoint, with
  the author flag set. Everyone else waits for the owner as before.
- A session's `lastActiveAt` is written at most once a minute and never fences the control item,
  so a page's parallel calls do not contend; idle expiry counts from the last write.
- Sign-in challenges carry the generation they were issued under and die with it (Disable,
  Delete, a merge, "end other sign-ins"), as the SQL cancels them.
- The e-mailed account merge (`DynamoCloudMergeService`) is one fenced transaction on the
  email's sentinel and every account's control revision; the deleted accounts' erasure runs
  after it, resumable by `account-erasure`. A race answers `409 MERGE_CONFLICT`, never a half
  merge. Links live as `MERGE#digest / TOKEN` with an `EMAIL#email / MERGE#…` index.
- Lost conditions answer `409`; a transaction whose outcome is unknown answers `503`. Where
  PostgreSQL waited on a row lock, DynamoDB tells the loser to reload and retry.
