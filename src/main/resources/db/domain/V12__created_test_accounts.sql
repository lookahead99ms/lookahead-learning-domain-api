-- A creator's lifetime allowance survives deletion of the learner fixture.
ALTER TABLE test_account_registry ADD COLUMN creator_id uuid REFERENCES platform_subjects(id);
ALTER TABLE test_account_registry ADD COLUMN creation_state varchar(12) NOT NULL DEFAULT 'active'
    CHECK (creation_state IN ('active','deleted'));
ALTER TABLE test_account_registry DROP CONSTRAINT test_account_registry_initialization_check;
ALTER TABLE test_account_registry ADD CONSTRAINT test_account_registry_initialization_check
    CHECK (initialization IN ('preserved','scenario','legacy','created'));
CREATE TABLE test_account_creations (
    creator_id uuid NOT NULL REFERENCES platform_subjects(id),
    mutation_key uuid NOT NULL,
    account_id uuid NOT NULL UNIQUE,
    username varchar(32) NOT NULL UNIQUE,
    state varchar(12) NOT NULL CHECK (state IN ('pending','active','deleted')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (creator_id, mutation_key)
);
CREATE INDEX test_account_creations_creator ON test_account_creations(creator_id);
GRANT SELECT,INSERT,UPDATE ON test_account_creations TO lookahead_platform_app;
-- Ordinary membership audit stays immutable. A deleted synthetic learner may
-- purge only audit rows for purchases owned by that learner, before purchases
-- are removed. The retained creation ledger is the lifetime quota audit.
CREATE FUNCTION guard_managed_test_audit_purge() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('lookahead.test_account_purge', true) IS NOT NULL
       AND EXISTS (
           SELECT 1 FROM membership_purchases p
           JOIN test_account_registry r ON r.account_id = p.account_id
           WHERE p.id::text = OLD.subject_id
             AND p.account_id::text = current_setting('lookahead.test_account_purge', true)
             AND (r.initialization = 'created' OR r.username ~ '^learner(0[1-9]|1[0-9]|2[0-7])$')
             AND r.creation_state = 'active'
       ) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Membership audit is append-only';
END;
$$;
DROP TRIGGER membership_audit_immutable ON membership_audit;
CREATE TRIGGER membership_audit_immutable BEFORE UPDATE OR DELETE ON membership_audit
    FOR EACH ROW EXECUTE FUNCTION guard_managed_test_audit_purge();
GRANT DELETE ON membership_audit,membership_mock_payments TO lookahead_platform_app;
-- V11 owns free-plan allowance history; this grant permits erasure of a deleted
-- synthetic learner's history once that migration is installed.
DO $$ BEGIN
    IF to_regclass('free_plan_creations') IS NOT NULL THEN
        GRANT DELETE ON free_plan_creations,free_plan_deletions TO lookahead_platform_app;
    END IF;
END $$;
