-- Existing undated plans remain unactivated. Never infer study day one from created_at.
CREATE TABLE plan_lifecycle (
 account_id uuid NOT NULL,
 plan_id uuid PRIMARY KEY,
 state varchar(20) NOT NULL CHECK(state IN ('active','archived','completed')),
 start_date date NOT NULL,
 day_count integer NOT NULL CHECK(day_count BETWEEN 1 AND 180),
 daily_minutes integer NOT NULL CHECK(daily_minutes BETWEEN 1 AND 900),
 subject_ids jsonb NOT NULL CHECK(jsonb_typeof(subject_ids)='array'),
 FOREIGN KEY(account_id,plan_id) REFERENCES plans(account_id,id) ON DELETE CASCADE
);
CREATE INDEX plan_lifecycle_owner ON plan_lifecycle(account_id);
GRANT SELECT,INSERT,UPDATE,DELETE ON plan_lifecycle TO lookahead_platform_app;
