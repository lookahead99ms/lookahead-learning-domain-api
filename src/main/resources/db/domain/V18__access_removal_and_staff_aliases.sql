-- DLV-969 (2026-11-03): the owner disables, enables and deletes DEV sign-in accounts, and everyone except the owner
-- sees other people as permanent role labels ("Co-author 2"). Additive only: no existing grant or column changes,
-- so the Domain release running before this migration stays ready on a database at 18.

-- One row per account that is disabled or deleted; no row = active. A deleted account stays deleted: its provider
-- identity is gone, and the same person returns as a new waiting account.
CREATE TABLE cloud_account_states (
    account_id uuid PRIMARY KEY REFERENCES cloud_accounts(account_id),
    state varchar(8) NOT NULL CHECK (state IN ('disabled','removed')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    changed_by uuid NOT NULL REFERENCES platform_subjects(id)
);
CREATE FUNCTION reject_removed_cloud_account_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'removed' THEN RAISE EXCEPTION 'A removed cloud account stays removed'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cloud_account_states_removed_final BEFORE UPDATE OR DELETE ON cloud_account_states
    FOR EACH ROW EXECUTE FUNCTION reject_removed_cloud_account_change();
GRANT SELECT, INSERT, UPDATE, DELETE ON cloud_account_states TO lookahead_platform_app;

-- The admission history also records the owner's Disable, Enable and Delete.
ALTER TABLE cloud_admission_events DROP CONSTRAINT cloud_admission_events_action_check;
ALTER TABLE cloud_admission_events ADD CONSTRAINT cloud_admission_events_action_check
    CHECK (action IN ('request','admit','expire','disable','enable','remove'));

-- Permanent role labels: a number is given the first time a label is needed and never changes or returns, so
-- "Co-author 2" stays Co-author 2 after Co-author 1 is deleted.
CREATE TABLE staff_aliases (
    account_id uuid NOT NULL REFERENCES platform_subjects(id),
    role varchar(16) NOT NULL CHECK (role IN ('co-author','admin','reviewer','tester','learner')),
    number integer NOT NULL CHECK (number > 0),
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (account_id, role),
    UNIQUE (role, number)
);
CREATE TRIGGER staff_aliases_immutable BEFORE UPDATE OR DELETE ON staff_aliases
    FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();
GRANT SELECT, INSERT ON staff_aliases TO lookahead_platform_app;
REVOKE UPDATE, DELETE, TRUNCATE ON staff_aliases FROM lookahead_platform_app, PUBLIC;

-- Today's staff get their numbers in the order their access last changed.
INSERT INTO staff_aliases(account_id, role, number)
SELECT account_id, role, row_number() OVER (PARTITION BY role ORDER BY updated_at, account_id)
FROM (
    SELECT s.account_id, r.role, s.updated_at
    FROM staff_access s CROSS JOIN LATERAL jsonb_array_elements_text(s.roles) AS r(role)
    WHERE s.account_id IS NOT NULL AND r.role IN ('co-author','admin','reviewer','tester')
) AS staff;
