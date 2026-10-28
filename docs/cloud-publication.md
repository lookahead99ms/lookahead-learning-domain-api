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
| `LOOKAHEAD_PUBLICATION_RELEASE_ID` | Pinned mode: the reviewed Content calendar release tag `vYYYY.MM.DD.HH.NN` (regex `v\d{4}\.\d{2}\.\d{2}\.\d{2}\.(?:\d{2}\|[1-9]\d{2,})`; a `release-<hash>` value is rejected). Live mode: unset |
| `LOOKAHEAD_PUBLICATION_MANIFEST_SHA256` | Pinned mode: reviewed transport-manifest SHA256. Live mode: unset |
| `LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256` | Pinned mode: reviewed compressed-archive SHA256. Live mode: unset |
| `LOOKAHEAD_PUBLICATION_DIRECTORY` | Private temporary parent beneath `/tmp`; default `/tmp/lookahead-publications` |
| `LOOKAHEAD_CONTENT_CACHE_BYTES` | Default33554432; allowed0–67108864 |
| `LOOKAHEAD_CONTENT_CACHE_ENTRIES` | Default256; allowed1–1024 |
| `LOOKAHEAD_DOMAIN_DB_PASSWORD` | Injected secret value, never its ARN |
| `LOOKAHEAD_DOMAIN_JDBC_URL` | Regional RDS host, `sslmode=verify-full`, absolute `sslrootcert` referencing a valid CA bundle |

The three release variables are set together or left unset together; a partial pin
fails startup. A release is identified by its Content calendar release tag (user decision
2026-10-27); the earlier `release-` plus 24 hash characters identity is retired. S3 keys are
`live/<tag>/publication.tar.gz` and `live/<tag>/manifest.json` while a release serves, and
`archive/<tag>/…` once it is retired (user decision 2026-10-28: the serving release exists in
one place; only the folder carries the tag; object names are fixed). The manifest's `releaseId`
carries the same tag. **Pinned mode** reads `live/<tag>/` and checks both externally supplied
digests; it is the candidate-validation path and fails closed once that tag has been retired to
`archive/`. **Live mode** (all three unset, added 2026-10-26) lists the `live/` prefix and
serves the single complete release there; the Content publish run writes it and retires the
previous folder into `archive/` after readback.
Infra selects values in its environment YAML and binds the service manifest to
maintained CloudFormation parameters. Static AWS credentials and custom AWS endpoint
overrides are rejected. Runtime SQL roles and migrations remain separately owned;
injecting a password does not provision a database role.

## Startup and serving

Live mode first discovers the release: list `live/` (bounded to 64 objects, owner-checked
pagination), accept only `live/<tag>/manifest.json` and `live/<tag>/publication.tar.gz` keys,
take the complete folder with the highest tag (`manifest.json` is the completeness marker),
and require its archive to be present. Promotion
writes the archive before the manifest and removes the previous folder afterwards, so a
folder without a manifest is a copy in progress and is ignored, and a second complete folder
is a predecessor awaiting removal: the higher tag is served and a warning names the count and
the chosen tag. Tie rule: calendar tags are chronological, so the highest tag is the newest
release; the fixed-width `vYYYY.MM.DD.HH` part compares as text and the counter compares
numerically (`…01.100` follows `…01.99`). Object timestamps never decide the order; the
listing must still carry one per key. An empty prefix, a foreign key (including a retired
`release-<hash>` folder), a folder whose manifest names another tag, or a highest tag without
its archive fails startup; Domain never falls back to a lower tag. The manifest's own digest
is not pinned in live mode; the archive digest comes from that manifest.

1. Fetch only `<prefix>/manifest.json` (`live/<tag>/`, pinned or discovered); verify the
   externally pinned hash when pinned.
2. Validate its schema, that `releaseId` equals the selected tag, the archive hash and the
   bounded file inventory. Unknown manifest fields are ignored.
3. Stream `<prefix>/publication.tar.gz` to private temporary disk and verify the archive
   hash (externally pinned, or the manifest's `archiveSha256` in live mode) before extraction.
4. Extract regular files to a fresh directory. Reject traversal, links, sparse
   entries, duplicates, unlisted/missing files, size/digest mismatches and inventory
   disagreement. Limits:1GiB compressed and expanded payload,32MiB per file/manifest
   and50002 files, with a bounded decompression allowance for archive metadata.
5. Load the publication allowlist and server-only `account-catalog.json`. The catalog
   has no content route. Content identity is the full SHA-256 of publication bytes
   followed by catalog bytes; when the manifest records it as `contentSha256` it must
   match the extracted files, and a manifest without the field is accepted because every
   byte is already bound by the inventory digests. The tag is not derived from content:
   the same bytes may be released again under a later tag. Historical content-only
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
Pinned mode rolls out a new release by replacing the task with new pins. Live mode picks
up a newly promoted release when its tasks restart; a running task keeps the bytes it
verified at startup. Rollback in live mode is a separate approved restore of the
previous tag from `archive/<tag>/` into `live/<tag>/` (Infra `aws/s3/content_live.py promote`)
that archives and retires the current `live/` folder, followed by a task restart; because the
highest tag is served, the previous folder must be removed, not merely joined. In pinned mode rollback selects the previous
reviewed image/tag/digest combination.

## Validation and operational limits

Run `./mvnw test` and `./mvnw -DskipTests package`. The private Content utility
`tools/utilities/verify_aws_publication_contract.py` packages synthetic inputs with
Infra's Python tool and exercises the Java loader and policy/catalog consumers.
It makes no AWS calls and mutates no running service. The fixture tests in
`PublicationBootstrapTest` take the pin from the manifest's `releaseId`, so a tag-keyed
manifest (with or without `contentSha256`) passes; a pinned deployment additionally
validates the tag format through `PublicationSettings`.

Real ECS injection, S3/IAM/endpoint denial, Linux image startup, RDS CA provenance,
database-role provisioning, rollout rollback and measured heap/disk use require
deployment verification. The Cognito provider and Domain account/session path is
implemented and locally verified; the inactive cloud candidate now removes custom
Identity while preserving Local. See [cloud authentication](cloud-authentication.md)
for operator admission, test boundaries and remaining live-provider gates.
