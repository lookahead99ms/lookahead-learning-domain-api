# Domain API storage compatibility

The service is named **Domain API**. Its Java package is `com.lookahead.domain`,
its executable is `lookahead-domain-api.jar`, and its configuration uses
`LOOKAHEAD_DOMAIN_*` environment variables.

The first installed database schema predates this service rename. Existing
database objects are persisted data contracts, so a source rename must not
replace them, provision an empty database, or change an applied migration.

`com.lookahead.domain.compatibility.LegacyStorageNames` is the single Java adapter
for these historical identifiers:

| Adapter constant | Installed identifier | Purpose |
| --- | --- | --- |
| `RUNTIME_ROLE` | `lookahead_platform_app` | Exact restricted application role |
| `MIGRATOR_ROLE` | `lookahead_platform_migrator` | Exact separate schema migration role |
| `SUBJECTS_TABLE` | `platform_subjects` | Credential-free Identity subject references |
| `INITIAL_MIGRATION_RESOURCE` | `db/domain/V1__platform.sql` | Original migration with unchanged filename and bytes |

The original migration's SHA-256 is
`0ee1758c0c67c224c59408ed7cf5433dcd5becb9ef181f64ac37e6d3ed33597f`.
Its resource directory changed to `db/domain`; its SQL, version, description and
script filename did not change. Flyway still validates the installed history.

The runtime username defaults to `LegacyStorageNames.RUNTIME_ROLE` only when no
username is configured. An explicitly supplied username must still match that
exact role. Neither runtime nor migration startup accepts a new role merely
because its name contains `domain`. Existing privilege checks remain in force.

Infra retains the installed database, owner role, volume and secret identities.
Those identifiers describe existing storage, not another running application.
Any future database-object rename needs an additive migration and a coordinated
infrastructure change with data-preservation and permission checks. Do not edit
the historical migration or run Flyway repair to conceal a checksum change.

Spring's `PlatformTransactionManager` is an external framework type and is
unrelated to this service's old name.
