-- Keep a minimal audit of owner initiated Local/DEV downgrades and Local deletions.
CREATE TABLE real_account_maintenance (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES platform_subjects(id),
    actor_id uuid NOT NULL REFERENCES platform_subjects(id),
    action varchar(12) NOT NULL CHECK (action IN ('downgrade','delete')),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX real_account_one_deletion ON real_account_maintenance(account_id) WHERE action='delete';
CREATE TRIGGER real_account_maintenance_immutable BEFORE UPDATE OR DELETE ON real_account_maintenance
    FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();
GRANT SELECT,INSERT ON real_account_maintenance TO lookahead_platform_app;
