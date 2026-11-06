-- DLV-971 (owner, 2026-11-06): the managed test roster has learner test accounts only. The co-author and admin test
-- accounts the roster used to carry (Local: coauthor01, coauthor02, admin01-admin06; DEV: the same names with the
-- @lookahead.dev suffix) are no longer in the application's roster, so a database that already holds them retires
-- them here: their staff role is cleared, their registry row is marked deleted (last changed by this migration), and
-- a creation-ledger row, where one exists, is marked deleted too. Their platform_subjects and cloud_accounts rows stay:
-- a deleted roster account keeps its admitted row (V17), purchase history stays superseded and audited, and nothing
-- here is ever created again (TestAccountRoster lists learner01-learner27 only). Runs as the migration user; the
-- runtime role's audit revocation (V15) is untouched. Idempotent: a second run finds nothing to change.
-- V1-V20 retain their installed Flyway checksums.
WITH retired AS (
    SELECT account_id FROM test_account_registry
    WHERE username ~ '^(coauthor0[12]|admin0[1-6])(@lookahead\.dev)?$'
)
UPDATE staff_access s
SET roles = '[]'::jsonb, topic_grants = '[]'::jsonb, full_curriculum = false,
    revision = revision + 1, updated_at = clock_timestamp()
FROM retired r
WHERE s.account_id = r.account_id
  AND (s.roles <> '[]'::jsonb OR s.topic_grants <> '[]'::jsonb OR s.full_curriculum);

UPDATE test_account_creations c
SET state = 'deleted'
FROM test_account_registry r
WHERE c.account_id = r.account_id
  AND r.username ~ '^(coauthor0[12]|admin0[1-6])(@lookahead\.dev)?$'
  AND c.state <> 'deleted';

UPDATE test_account_registry
SET creation_state = 'deleted', revision = revision + 1, last_changed_by = 'V21', last_changed_at = clock_timestamp()
WHERE username ~ '^(coauthor0[12]|admin0[1-6])(@lookahead\.dev)?$'
  AND creation_state <> 'deleted';
