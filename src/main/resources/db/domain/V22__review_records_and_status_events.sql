-- DLV-971 (owner, 2026-11-06): reviews and learner support requests are one JSON record each in the assets bucket
-- (reviews/records/..., support-requests/records/...), numbered REV-000001 / SUP-000001 from two sequences here.
-- Every status change on a record or on a delivery ticket is appended to review_status_events; S3 holds current
-- state only. The membership-audit pattern applies: the runtime role may append and read, never change or remove a
-- row (the trigger refuses it for every role, the REVOKE and the check keep the grant honest). No foreign key on
-- actor_id or record_id: a removed account or a deleted record keeps its history; actor_id is NULL for the
-- automatic close (closedBy "system"). V1-V21 retain their installed Flyway checksums.
CREATE SEQUENCE review_record_seq AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;
CREATE SEQUENCE support_request_seq AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;
GRANT USAGE ON SEQUENCE review_record_seq, support_request_seq TO lookahead_platform_app;

CREATE TABLE review_status_events (
    id uuid PRIMARY KEY,
    record_id varchar(40) NOT NULL,
    kind varchar(12) NOT NULL CHECK (kind IN ('review', 'request', 'ticket')),
    actor_id uuid,
    from_status varchar(40),
    to_status varchar(40) NOT NULL,
    note_set boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX review_status_events_record ON review_status_events(record_id, recorded_at);
CREATE TRIGGER review_status_events_immutable BEFORE UPDATE OR DELETE ON review_status_events
    FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();

GRANT SELECT, INSERT ON review_status_events TO lookahead_platform_app;
REVOKE UPDATE, DELETE, TRUNCATE ON review_status_events FROM lookahead_platform_app, PUBLIC;

DO $$
BEGIN
    IF has_table_privilege('lookahead_platform_app', 'review_status_events', 'UPDATE,DELETE,TRUNCATE')
        OR has_any_column_privilege('lookahead_platform_app', 'review_status_events', 'UPDATE') THEN
        RAISE EXCEPTION 'review_status_events mutation remains effective; inspect inherited grants and ownership';
    END IF;
END;
$$;
