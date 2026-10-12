-- DLV-930: platform authorization, separate from identity and commercial grants.
CREATE TABLE staff_access (
    id uuid PRIMARY KEY,
    account_id uuid UNIQUE REFERENCES platform_subjects(id),
    pending_email varchar(254),
    roles jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(roles)='array'),
    topic_grants jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(topic_grants)='array'),
    full_curriculum boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision>0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((account_id IS NULL) <> (pending_email IS NULL)),
    CHECK (pending_email IS NULL OR pending_email=lower(pending_email))
);
CREATE UNIQUE INDEX staff_access_pending_email ON staff_access(pending_email) WHERE account_id IS NULL;
CREATE TABLE staff_access_events (
    event_id uuid PRIMARY KEY,
    assignment_id uuid NOT NULL REFERENCES staff_access(id),
    actor_id uuid NOT NULL REFERENCES platform_subjects(id),
    action varchar(16) NOT NULL CHECK (action IN ('assign','claim')),
    previous_state jsonb,
    next_state jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
GRANT SELECT,INSERT,UPDATE ON staff_access TO lookahead_platform_app;
GRANT SELECT,INSERT ON staff_access_events TO lookahead_platform_app;
REVOKE UPDATE,DELETE,TRUNCATE ON staff_access_events FROM lookahead_platform_app;
CREATE TRIGGER staff_access_events_immutable BEFORE UPDATE OR DELETE ON staff_access_events
    FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();
