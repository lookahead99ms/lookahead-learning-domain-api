-- Forward-only correction. V1-V14 retain their installed Flyway checksums.
-- Local fixture purge is separately provisioned by the Local migration job.
REVOKE UPDATE, DELETE, TRUNCATE ON membership_audit, author_review_events,
    staff_access_events, real_account_maintenance FROM lookahead_platform_app, PUBLIC;

DO $$
DECLARE auditTable regclass;
BEGIN
    FOREACH auditTable IN ARRAY ARRAY['membership_audit'::regclass,
        'author_review_events'::regclass, 'staff_access_events'::regclass,
        'real_account_maintenance'::regclass]
    LOOP
        IF has_table_privilege('lookahead_platform_app', auditTable, 'UPDATE,DELETE,TRUNCATE')
            OR has_any_column_privilege('lookahead_platform_app', auditTable, 'UPDATE') THEN
            RAISE EXCEPTION 'Runtime audit mutation remains effective; inspect inherited grants and ownership';
        END IF;
    END LOOP;
END;
$$;
