-- DLV-969 (2026-11-02): DEV access by admission. A first sign-in creates a waiting row; the owner admits it from
-- People and access; a waiting row older than 48 hours is removed. Admitted rows, test accounts and roles are never
-- touched by the cleanup. V1-V16 retain their installed Flyway checksums.
ALTER TABLE cloud_accounts
    ADD COLUMN requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN admitted_at timestamptz,
    ADD COLUMN admitted_by uuid REFERENCES platform_subjects(id),
    ADD COLUMN verified_email varchar(254);
-- Rows admitted before this migration keep their admission; their request time is the migration time.
UPDATE cloud_accounts SET admitted_at = clock_timestamp() WHERE admitted AND admitted_at IS NULL;
CREATE INDEX cloud_accounts_waiting ON cloud_accounts(requested_at) WHERE NOT admitted;

-- Immutable admission history. No foreign key to cloud_accounts: an expired waiting row is deleted, its events stay.
CREATE TABLE cloud_admission_events (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    issuer varchar(256) NOT NULL,
    subject varchar(256) NOT NULL,
    actor_id uuid REFERENCES platform_subjects(id),
    action varchar(12) NOT NULL CHECK (action IN ('request','admit','expire')),
    roles jsonb NOT NULL DEFAULT '[]'::jsonb,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX cloud_admission_events_account ON cloud_admission_events(account_id, recorded_at);
CREATE TRIGGER cloud_admission_events_immutable BEFORE UPDATE OR DELETE ON cloud_admission_events
    FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();

-- Only a waiting row may ever be deleted; admitted accounts and their history stay.
CREATE FUNCTION reject_admitted_cloud_account_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.admitted THEN RAISE EXCEPTION 'Admitted cloud accounts are never deleted'; END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER cloud_accounts_waiting_only_delete BEFORE DELETE ON cloud_accounts
    FOR EACH ROW EXECUTE FUNCTION reject_admitted_cloud_account_delete();

-- The application may record a first contact, admit a waiting row and remove an expired one.
-- enabled and author_access stay operator-only (CloudAccountAdministration).
GRANT INSERT, DELETE ON cloud_accounts TO lookahead_platform_app;
GRANT UPDATE(admitted, admitted_at, admitted_by, requested_at, verified_email) ON cloud_accounts TO lookahead_platform_app;
GRANT SELECT, INSERT ON cloud_admission_events TO lookahead_platform_app;
REVOKE UPDATE, DELETE, TRUNCATE ON cloud_admission_events FROM lookahead_platform_app, PUBLIC;
