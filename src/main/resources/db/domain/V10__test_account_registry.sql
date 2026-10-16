CREATE TABLE test_account_registry (
 account_id uuid PRIMARY KEY REFERENCES platform_subjects(id),
 username varchar(32) NOT NULL UNIQUE,
 is_test_account boolean NOT NULL DEFAULT true CHECK(is_test_account),
 initialization varchar(20) NOT NULL CHECK(initialization IN ('preserved','scenario','legacy')),
 revision bigint NOT NULL DEFAULT 1,
 last_changed_by varchar(100) NOT NULL,
 last_changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE test_account_resets (
 account_id uuid NOT NULL REFERENCES test_account_registry(account_id),
 mutation_key uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES platform_subjects(id),
 preset varchar(20) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(account_id,mutation_key)
);
CREATE TRIGGER test_account_resets_immutable BEFORE UPDATE OR DELETE ON test_account_resets
 FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();
CREATE FUNCTION touch_test_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
 target := COALESCE(NEW.account_id,OLD.account_id);
 UPDATE test_account_registry SET revision=revision+1,last_changed_at=clock_timestamp(),
   last_changed_by=COALESCE(NULLIF(current_setting('lookahead.actor_id',true),''),target::text)
 WHERE account_id=target;
 RETURN NULL;
END; $$;
CREATE TRIGGER test_account_plan_changes AFTER INSERT OR UPDATE OR DELETE ON plans FOR EACH ROW EXECUTE FUNCTION touch_test_account();
CREATE TRIGGER test_account_grant_changes AFTER INSERT OR UPDATE OR DELETE ON account_grants FOR EACH ROW EXECUTE FUNCTION touch_test_account();
CREATE TRIGGER test_account_purchase_changes AFTER INSERT OR UPDATE ON membership_purchases FOR EACH ROW EXECUTE FUNCTION touch_test_account();
CREATE TRIGGER test_account_role_changes AFTER INSERT OR UPDATE OR DELETE ON staff_access FOR EACH ROW EXECUTE FUNCTION touch_test_account();
CREATE VIEW reportable_platform_subjects AS SELECT s.* FROM platform_subjects s
 WHERE NOT EXISTS(SELECT 1 FROM test_account_registry t WHERE t.account_id=s.id);
GRANT SELECT,INSERT,UPDATE ON test_account_registry TO lookahead_platform_app;
GRANT SELECT,INSERT ON test_account_resets TO lookahead_platform_app;
GRANT SELECT ON reportable_platform_subjects TO lookahead_platform_app;
