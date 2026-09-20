# Author Preview review records — DLV-921

This local candidate records a version-bound author decision in Domain PostgreSQL. A record means **pending Main reconciliation**; it does not approve a merge, change ticket status, update delivery JSON, edit a source file or mutate learner plans. No Git/GitHub credentials or runtime file-write endpoint exists.

## Trust and configuration

The endpoint is active only in `accounts,local-test,resource`, deployment environment `local`, author preview enabled, and no prod/production profile. All operations reuse `AuthorPreviewAccessService`: the current server-admitted author capability and full trusted catalog grants are required. The current local author capability uses the established seeded account contract; this does not introduce production roles or multi-author provisioning. History is scoped to the authenticated actor, even if more author accounts are supported later. A browser cannot supply another actor ID.

Set `LOOKAHEAD_AUTHOR_REVIEW_MANIFEST` to an absolute, read-only mounted JSON file containing at most 100 entries (64 KiB maximum):

```json
[
  {
    "artifactId": "sample-review",
    "artifactVersion": "sample/review-v1",
    "contentHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "ticketId": "DLV-999"
  }
]
```

This example hash is synthetic. The operator must hash the canonical review **source bytes** with SHA-256, review the ticket/version binding, and provide the real approved manifest privately. The generated HTML can have a different hash. The service does not read arbitrary browser-supplied paths or hash an arbitrary URL. Empty configuration exposes no artifacts; malformed configured manifests stop startup. Manifest changes require a controlled application restart. Coordinate the same manifest on every instance before admitting writes; rolling instances with differing manifests are not an approved publication procedure.

Run Domain migration V2 before candidate startup, using the existing one-shot `com.lookahead.domain.DomainApiMigration` entry point and migration-role configuration. Runtime receives only SELECT/INSERT on `author_review_events`; UPDATE/DELETE/TRUNCATE are denied, and a database trigger rejects row updates/deletes even by the table owner. Readiness verifies the new schema and runtime privileges. Events retain an actor subject reference without profile/email data. Account-deletion/retention policy must handle these audit references explicitly; no automatic deletion policy is introduced.

## HTTP contract

Browser routes start `/bff/api/v1/author/review-artifacts`; Gateway forwards only the matching `/api/v1/author/review-artifacts` routes to Domain. Domain accepts server-held bearer tokens, verifies them against Identity, and requires `account` scope plus author access. Domain is stateless and does not authenticate browser cookies; browser CSRF enforcement belongs to the BFF. Retrieve BFF CSRF from `/bff/api/v1/auth/csrf`, then send its `X-CSRF-TOKEN` on POST. Identity CSRF is not interchangeable.

| Method | Suffix | Request | Result |
| --- | --- | --- | --- |
| GET | empty | none | Current approved artifact array |
| GET | `/{artifactId}/events` | `limit` (1–100, default 50), optional `cursor` event UUID | `{entries, nextCursor}` newest first, own actor only |
| POST | `/{artifactId}/events` | Header `Idempotency-Key` UUID and submission JSON below; no query | 201 new receipt; 200 exact replay |

Every success is `{ "data": ..., "timestamp": "..." }` and all responses are `Cache-Control: no-store`. An artifact contains `artifactId`, `artifactVersion`, `contentHash`, `ticketId`. Artifact IDs match `[a-z0-9][a-z0-9-]{0,79}`. UUID strings must be canonical lowercase.

```json
{
  "artifactVersion": "sample/review-v1",
  "contentHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "ticketId": "DLV-999",
  "decision": "NEED_MORE",
  "comment": "Show recovery evidence before approval.",
  "supersedesEventId": null
}
```

Decisions are `APPROVE`, `DECLINE`, `NEED_MORE`. Comments are trimmed plain text up to 2,000 Unicode code points; DECLINE and NEED_MORE require an explanation. HTML/control characters are rejected (newline/tab allowed). POST bodies are bounded to 16 KiB. Extra request fields are rejected, including actor/time/event ID. `supersedesEventId` is omitted/null only for the first decision; later decisions must reference the newest own event for the artifact. Previous decisions remain readable.

A receipt contains `{event, replayed, reconciliationStatus: "PENDING_MAIN_RECONCILIATION"}`. Event fields are `eventId`, `artifactId`, `artifactVersion`, `contentHash`, `ticketId`, `decision`, `comment`, `actorId`, `recordedAt`, `idempotencyKey`, `supersedesEventId`. Actor derives from authenticated identity; timestamp derives from PostgreSQL. An internal monotonic sequence orders events, so wall-clock corrections cannot reverse supersession or pagination. Preserve the UUID key and exact submission through uncertain failures. Same key/payload returns the original event without a duplicate; changed payload conflicts. Account-row serialization and database uniqueness protect concurrent submissions across API instances. A manifest that has changed rejects stale version/hash/ticket, even on an old retry: reload and reconcile history instead of silently applying a decision to new content.

| HTTP | Code / condition |
| --- | --- |
| 400 | `MALFORMED_JSON`, invalid request parameter syntax |
| 401 | `AUTHENTICATION_REQUIRED` |
| 403 | `AUTHOR_PREVIEW_ACCESS_DENIED`; BFF `REQUEST_REJECTED` / insufficient scope |
| 404 | `REVIEW_ARTIFACT_NOT_FOUND`, `REVIEW_EVENT_NOT_FOUND` |
| 409 | `REVIEW_ARTIFACT_STALE`, `REVIEW_IDEMPOTENCY_CONFLICT`, `REVIEW_SUPERSESSION_CONFLICT` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `INVALID_REVIEW_REQUEST`, `REVIEW_COMMENT_REQUIRED` |
| 503 | `ACCOUNT_STORAGE_UNAVAILABLE`, `IDENTITY_UNAVAILABLE` |

Domain errors are flat `{status,code,message,path,timestamp}`. Gateway/security-layer errors can use their existing reduced envelope; clients should route by status/code. Never render comments as HTML.

## Verification and learning contribution

`./mvnw -o test` runs the source suite; database tests explicitly skip unless `DLV921_DATABASE_URL` and `DLV921_DATABASE_PASSWORD_FILE` identify the disposable PostgreSQL fixture. The fixture creates/drops a unique schema; never point it at production. `AuthorReviewDatabaseTest` covers the unmodified migration, runtime database permissions, append-only triggers, actor isolation, version binding, monotonic ordering, supersession, concurrent idempotency and rollback/retry. Its HTTP fixture uses the production Domain security/capability checks with synthetic Identity verification and proves readback after Spring application-context shutdown/restart. This is not a process/container restart, actual Identity/Gateway integration or backup/restore proof; Infra owns those cross-service gates. Gateway owns actual browser-session CSRF tests. These checks do not establish production authorization or audit retention readiness.

Case-study handoff for the private canonical platform lesson: teach the difference between a decision event and a workflow transition; canonical source hashing versus rendering hashes; transactional idempotency versus retries; append-only supersession versus destructive edits; and separate browser CSRF, token verification and resource authorization. Use synthetic IDs/comments and link to the existing API/security lessons rather than copying private review content. Publication and content gates remain Main-owned follow-up work.
