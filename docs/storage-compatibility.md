# Domain API storage compatibility (history)

The service is named **Domain API**. Its Java package is `com.lookahead.domain`,
its executable is `lookahead-domain-api-<version>.jar`, and its configuration uses
`LOOKAHEAD_DOMAIN_*` environment variables.

Until 2026-11-17 the Domain ran on a PostgreSQL schema that predated the service rename and kept
its installed identifiers: the runtime role `lookahead_platform_app`, the migration role
`lookahead_platform_migrator` and the subject table `platform_subjects`.

PostgreSQL was retired under DLV-970 on 2026-11-17 (owner: "delete all Postgres things") after
the real accounts were imported into DynamoDB (data step 5, run 44162520740) and a final manual
snapshot of `lookahead-dev-db` was taken. Nothing of that store is left in this repository; the
snapshot is the only remaining copy of the old schema and its names. Account
UUIDs were preserved by the import, so nothing that referenced an account changed identity.

DynamoDB items carry no legacy names: keys are `ACCOUNT#<uuid>`, `STAFFACCESS`, `CATALOG`,
`SEQUENCE#…` and the other families listed in [DynamoDB persistence](dynamodb-persistence.md);
the four table names are the approved `lookahead-<env>-*` names pinned in the deployment
templates. Spring's `PlatformTransactionManager` is an external framework type and is
unrelated to this service's old name.
