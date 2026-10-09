# Cloud publication startup

Local keeps `LOOKAHEAD_CONTENT_PUBLICATION`, `LOOKAHEAD_CONTENT_ROOT` and
`LOOKAHEAD_ACCOUNT_CATALOG`. It creates no S3 client or container credential
provider and keeps uncached file reads. Existing Local data/services are unaffected.

DEV/PROD select cloud startup from `LOOKAHEAD_ENVIRONMENT`. Missing or invalid
settings fail startup; mounted Local files are never fallback data. Domain alone
calls S3. AWS SDK v2 uses explicit region and ECS container task credentials, a
bounded HTTP client and `expectedBucketOwner`; it does not select workstation
profiles or static access keys. Fargate injects approved secrets through its separate
execution role, so the application needs no Secrets Manager client.

| Variable | Required cloud value |
| --- | --- |
| `AWS_REGION` | Owning environment region |
| `LOOKAHEAD_AWS_ACCOUNT_ID` | Owning twelve-digit account |
| `LOOKAHEAD_PUBLICATION_BUCKET` | Owning private publication bucket |
| `LOOKAHEAD_PUBLICATION_RELEASE_ID` | Reviewed `release-` plus24 lowercase hash characters |
| `LOOKAHEAD_PUBLICATION_MANIFEST_SHA256` | Reviewed transport-manifest SHA256 |
| `LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256` | Reviewed compressed-archive SHA256 |
| `LOOKAHEAD_PUBLICATION_DIRECTORY` | Private temporary parent beneath `/tmp`; default `/tmp/lookahead-publications` |
| `LOOKAHEAD_CONTENT_CACHE_BYTES` | Default33554432; allowed0–67108864 |
| `LOOKAHEAD_CONTENT_CACHE_ENTRIES` | Default256; allowed1–1024 |
| `LOOKAHEAD_DOMAIN_DB_PASSWORD` | Injected secret value, never its ARN |
| `LOOKAHEAD_DOMAIN_JDBC_URL` | Regional RDS host, `sslmode=verify-full`, absolute `sslrootcert` referencing a valid CA bundle |

Infra selects values in its environment YAML and binds the service manifest to
maintained CloudFormation parameters. Static AWS credentials and custom AWS endpoint
overrides are rejected. Runtime SQL roles and migrations remain separately owned;
injecting a password does not provision a database role.

## Startup and serving

1. Fetch only `releases/<releaseId>/manifest.json`, verify its externally pinned hash.
2. Validate its schema, selected release/archive hashes and bounded file inventory.
3. Stream `releases/<releaseId>/publication.tar.gz` to private temporary disk and
   verify the externally pinned archive hash before extraction.
4. Extract regular files to a fresh directory. Reject traversal, links, sparse
   entries, duplicates, unlisted/missing files, size/digest mismatches and inventory
   disagreement. Limits:1GiB compressed and expanded payload,32MiB per file/manifest
   and50002 files, with a bounded decompression allowance for archive metadata.
5. Load the publication allowlist and server-only `account-catalog.json`. The catalog
   has no content route. Cloud release identity hashes publication bytes followed by
   catalog bytes; changing either changes the release ID. Historical content-only
   archives remain an offline format but cannot start the cloud application.
6. Mark verified files owner-readable and directories owner-readable/searchable.
   Publish the location bean only after verification. Policy/catalog initialization
   must succeed before readiness, which includes `contentPublication` and database
   checks. Partial downloads never become serving locations; startup fails closed.
7. Serve verified task-local files. LRU eviction bounds retained body bytes and entry
   count; oversized assets bypass the cache. Returned arrays cannot poison cached
   data. Account status and grants are checked before every protected response,
   including cache hits. Metadata, request copies and parsing require additional heap.

No automatic refresh, Redis, extra EBS, public S3 URLs or private content in images.
Roll out a new pinned release to replace a task. Rollback selects the previous
reviewed image/release/hash combination; healthy tasks keep their selected bytes.

## Validation and operational limits

Run `./mvnw test` and `./mvnw -DskipTests package`. The private Content utility
`tools/utilities/verify_aws_publication_contract.py` packages synthetic inputs with
Infra's Python tool and exercises the Java loader and policy/catalog consumers.
It makes no AWS calls and mutates no running service.

Real ECS injection, S3/IAM/endpoint denial, Linux image startup, RDS CA provenance,
database-role provisioning, rollout rollback and measured heap/disk use require
deployment verification. The Cognito provider and Domain account/session path is
implemented and locally verified; the inactive cloud candidate now removes custom
Identity while preserving Local. See [cloud authentication](cloud-authentication.md)
for operator admission, test boundaries and remaining live-provider gates.
