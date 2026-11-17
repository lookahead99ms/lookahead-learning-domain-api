# Domain API storage compatibility (history)

The service is named **Domain API**. Its Java package is `com.lookahead.domain`,
its executable is `lookahead-domain-api-<version>.jar`, and its configuration uses
`LOOKAHEAD_DOMAIN_*` environment variables.

Until 2026-11-17 the Domain's PostgreSQL schema predated the service rename, so the
installed identifiers were kept behind one adapter, `LegacyStorageNames`: the runtime role
`lookahead_platform_app`, the migration role `lookahead_platform_migrator`, the subject table
`platform_subjects` and the original migration `db/domain/V1__platform.sql` (SHA-256
`0ee1758c0c67c224c59408ed7cf5433dcd5becb9ef181f64ac37e6d3ed33597f`, validated by Flyway
on every start of the migration tooling).

PostgreSQL was retired under DLV-970 on 2026-11-17 (owner: "delete all Postgres things") after
the real accounts were imported into DynamoDB (data step 5, run 44162520740) and a final manual
snapshot of `lookahead-dev-db` was taken. The adapter, the migrations, the roles and the tables
are gone from this repository; the snapshot is the only remaining copy of the old schema. Account
UUIDs were preserved by the import, so nothing that referenced an account changed identity.

DynamoDB items carry no legacy names: keys are `ACCOUNT#<uuid>`, `STAFFACCESS`, `CATALOG`,
`SEQUENCE#…` and the other families listed in [DynamoDB persistence](dynamodb-persistence.md);
the four table names are the approved `lookahead-<env>-*` names pinned in the deployment
templates. Spring's `PlatformTransactionManager` is an external framework type and is
unrelated to this service's old name.
