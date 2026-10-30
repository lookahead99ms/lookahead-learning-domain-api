# Domain DEV activation contract

This is the application-side contract for preparing a dormant DEV service. It does
not install IAM, populate secrets, publish images, create services or authorize a
deployment. Infra owns those operations and their separate reviewed plans.
Local verification does not establish effective AWS permissions or cloud readiness.

## Runtime and transport

Use the Java21 application image, UID/GID 10001, a read-only root filesystem and a
writable task-local `/tmp`. The image carries the checksum-verified RDS CA bundle
at `/opt/lookahead/trust/rds-global-bundle.pem` and the container health probe.
The default local artifact is `target/lookahead-domain-api-0.0.1-SNAPSHOT.jar`;
release builds use `lookahead-domain-api-<version>.jar` and copy it to
`/opt/lookahead/app.jar` in the image. A locally built JAR is not an image receipt.

| Input | Required DEV behavior |
| --- | --- |
| `LOOKAHEAD_ENVIRONMENT` | `dev`; Local/PROD cannot use the DEV transport profile |
| `SPRING_PROFILES_ACTIVE` | `lower-cost-dev`; application also includes `accounts,resource` |
| `LOOKAHEAD_RUNTIME_MODE` | `lower-cost-dev`; selects the loopback management health port in the image probe |
| `JAVA_TOOL_OPTIONS` | The reviewed Infra candidate supplies the image's DNS security file and bounded HTTP connection settings; keep that source binding |
| `LOOKAHEAD_TLS_SERVER_NAME` | Exact slot-specific Domain DNS SAN, such as `domain-green.lookahead-dev.internal`; resolve the real name through reviewed discovery |
| `LOOKAHEAD_DOMAIN_SEED_ENABLED` | `false` |
| `LOOKAHEAD_DOMAIN_AUTHOR_ENABLED` | `false` |

The DEV profile serves application mTLS on `0.0.0.0:8443`, TLS1.2/1.3, with client
authentication required. The certificate filter authorizes the exact Gateway
client DNS SAN `gateway.lookahead-dev.internal` and client-auth EKU. Gateway must
also supply the existing JWT/sign-in proof/shared-secret contracts; TLS does not
grant an account or curriculum entitlement.

Management HTTP binds only to `127.0.0.1:8081`. The task-local probe requests
`/actuator/health/readiness`; it requires HTTP 200 with only `{"status":"UP"}`.
Readiness includes `readinessState`, `domainDatabase` and `contentPublication`.
It does not prove a real browser login, public ingress or all optional features.
The ordinary Local listener remains on its configured address/port, defaulting to
`127.0.0.1:8080`; the image's Local default bind address is supplied separately.

## Injected credentials

The ECS execution role retrieves the three selected secrets below. Domain consumes
their injected values before context refresh; it does not fetch Secrets Manager
values at runtime. Bind each selector to an explicitly reviewed immutable version.
Never put values in configuration examples, workflow output, logs or evidence.

| Infra parameter prefix | Secret JSON field | Injected environment name | Application requirement |
| --- | --- | --- | --- |
| `Tls` | `schema` | `LOOKAHEAD_TLS_SCHEMA` | `lookahead-tls/v1` |
| `Tls` | `serverCertificate` | `LOOKAHEAD_TLS_SERVER_CERTIFICATE` | Valid server-auth leaf/chain with exact Domain slot SAN and at least one day remaining |
| `Tls` | `serverKey` | `LOOKAHEAD_TLS_SERVER_KEY` | Matching PKCS8 private-key PEM, with no extra blocks/material |
| `Tls` | `trust` | `LOOKAHEAD_TLS_TRUST` | Valid self-signed CA anchors for the reviewed private transport trust |
| `DomainApp` | `password` | `LOOKAHEAD_DOMAIN_DB_PASSWORD` | Exactly 48 alphanumeric characters in this DEV injected-credential contract |
| `DomainGateway` | Whole scalar secret | `LOOKAHEAD_DOMAIN_GATEWAY_SECRET` | Same secret used by Gateway; resolved, no control characters, 32–4096 characters |

The existing Infra selector shape is
`<secret-arn>:<json-key>::<version-id>`; a whole scalar uses an empty JSON key.
The `Tls`, `DomainApp` and `DomainGateway` parameters each have an ARN and version
ID. These names describe bindings; they are not authorization to read/populate
secrets. Domain receives no confidential application OAuth client secret,
admission client secret, admission pool credentials, master DB password or signing
private key. Its TLS private key is an explicitly injected transport credential.

## Identity and application state

| Input | Required binding |
| --- | --- |
| `AWS_REGION` | Selected region shared by publication/Cognito/RDS validation |
| `LOOKAHEAD_COGNITO_ISSUER` | Exact regional **application** pool issuer, distinct from Gateway's DEV admission pool |
| `APP_OAUTH_CLIENT_ID` | Application client ID whose access tokens Gateway forwards; alphanumeric, 10–128 characters |
| `LOOKAHEAD_COGNITO_SCOPE_PREFIX` | Reviewed resource-server prefix, without trailing slash |

Domain validates issuer/signature, access-token use, client, times, token family,
authentication time and required scopes, then checks provider revocation using
the user's token. `GetUser`, `ChangePassword` and `GlobalSignOut` use token
authorization through an anonymous-credentials Cognito client; they do not require
adding Cognito administrative IAM actions to Domain's task role. Gateway's separate
admission `AdminGetUser` and operations-table reads are not Domain requirements.

PostgreSQL owns Domain account mapping, grants, sign-ins, plans and progress.
Runtime cannot create/admit accounts or grant author access automatically. See
[cloud authentication](cloud-authentication.md) for sign-in and recovery contracts.

## Database and publication

| Input | Required binding |
| --- | --- |
| `LOOKAHEAD_DOMAIN_JDBC_URL` | Regional RDS endpoint and `lookahead_platform`, `sslmode=verify-full`, absolute readable RDS CA bundle path |
| `SPRING_DATASOURCE_USERNAME` | Exact existing runtime role `lookahead_platform_app` |
| `LOOKAHEAD_AWS_ACCOUNT_ID` | Exact twelve-digit publication owner account |
| `LOOKAHEAD_PUBLICATION_BUCKET` | Existing reviewed private publication bucket |
| `LOOKAHEAD_PUBLICATION_RELEASE_ID` | Candidate's explicit Content calendar tag `vYYYY.MM.DD.HH.NN` |
| `LOOKAHEAD_PUBLICATION_MANIFEST_SHA256` | Candidate manifest digest, supplied with the tag |
| `LOOKAHEAD_PUBLICATION_ARCHIVE_SHA256` | Candidate archive digest, supplied with the tag |
| `LOOKAHEAD_PUBLICATION_DIRECTORY` | Private normalized absolute directory under `/tmp`; default `/tmp/lookahead-publications` |
| `LOOKAHEAD_CONTENT_CACHE_BYTES` | Default 33554432; allowed 0–67108864 |
| `LOOKAHEAD_CONTENT_CACHE_ENTRIES` | Default 256; allowed 1–1024 |

The candidate path pins the tag and both digests together. It reads the two objects
under `live/<tag>/`, validates hashes/allowlists/catalog, then exposes verified
task-local content. A retired tag under `archive/` fails this pinned startup;
there is no local-mount or older-release fallback.

Domain's application task role needs `s3:GetObject` on those publication objects
and the corresponding S3-mediated KMS decryption when required by their encryption.
The current application sends no S3 VersionId; do not substitute
`s3:GetObjectVersion`-only authority and claim it matches the request. Pinned mode
does not list the bucket. Unpinned live discovery additionally needs a prefix-scoped
`s3:ListBucket` grant and is a distinct operating choice, not required merely to
start this candidate. The publication pipeline's receipt records object versions;
the application's existing pin is the calendar tag plus both content digests.

Runtime Flyway stays disabled. Existing schema/history and restricted privileges
must be verified through Infra's approved Flyway Utils flow before service start.
The retired Domain migration/bootstrap launchers remain disabled. Account
administration is a separate explicitly invoked operator capability, never an
application startup side effect.

Optional cloud platform-review image storage requires its separate S3/KMS inputs
and grants; missing storage returns 503 for that capability. Core readiness is not
proof that those optional submission paths have been activated.

## Verification and unresolved deployment gates

Run the database-backed [85% coverage gate](../README.md#required-release-coverage-gate).
`DomainActivationConfigurationTest` loads the shipped YAML with synthetic inputs
and checks DEV transport/readiness, pinned publication, identity binding, disabled
runtime migrations/seeding and Local/PROD preservation. Publication tests reject
malformed/non-progressing pagination while retaining valid empty-page progress.

The private Content command
`python3 tools/utilities/verify_aws_publication_contract.py` checks Infra's synthetic
archive through Domain extraction/hash/allowlist/catalog consumers without AWS.
See [cloud publication](cloud-publication.md) for the format and startup limits.

Actual secret versions/CA provenance, DNS/discovery, compatible immutable image,
ECS injection/task credentials, S3/KMS denials, RDS TLS/grants, Cognito sign-in,
cross-service saved progress and failure recovery still need deployment evidence.
Shared-role authority, lifecycle/shutdown ownership and the finite verifier are
central activation decisions. This document does not enable deployment or approve
an upload, role change, schedule, service start or public cutover.
