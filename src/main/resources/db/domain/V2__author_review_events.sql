-- Append-only review receipts. No ticket status or learner-plan mutation is permitted.
CREATE TABLE author_review_events (
    event_id uuid PRIMARY KEY,
    event_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    artifact_id varchar(80) NOT NULL,
    artifact_version varchar(120) NOT NULL,
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    ticket_id varchar(12) NOT NULL CHECK (ticket_id ~ '^DLV-[1-9][0-9]{0,5}$'),
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVE','DECLINE','NEED_MORE')),
    comment varchar(2000) NOT NULL,
    actor_id uuid NOT NULL REFERENCES platform_subjects(id),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    supersedes_event_id uuid,
    UNIQUE (actor_id,idempotency_key),
    UNIQUE (actor_id,artifact_id,event_id),
    FOREIGN KEY (actor_id,artifact_id,supersedes_event_id)
        REFERENCES author_review_events(actor_id,artifact_id,event_id),
    CHECK (decision='APPROVE' OR length(trim(comment))>0)
);
CREATE INDEX author_review_events_history ON author_review_events(actor_id,artifact_id,event_sequence DESC);
CREATE UNIQUE INDEX author_review_events_one_successor ON author_review_events(supersedes_event_id) WHERE supersedes_event_id IS NOT NULL;
GRANT SELECT,INSERT ON author_review_events TO lookahead_platform_app;
REVOKE UPDATE,DELETE,TRUNCATE ON author_review_events FROM lookahead_platform_app;
CREATE FUNCTION reject_author_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Author review events are append-only';
END;
$$;
CREATE TRIGGER author_review_events_immutable BEFORE UPDATE OR DELETE ON author_review_events
    FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();
