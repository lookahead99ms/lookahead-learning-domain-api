-- Owner-scoped allowance history survives plan deletion and is never inferred from plan numbering.
CREATE TABLE free_plan_creations (
 account_id uuid NOT NULL REFERENCES platform_subjects(id),
 plan_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(account_id,plan_id)
);
CREATE INDEX free_plan_creations_owner_time ON free_plan_creations(account_id,created_at);

CREATE TABLE free_plan_deletions (
 account_id uuid NOT NULL REFERENCES platform_subjects(id),
 plan_id uuid NOT NULL,
 deleted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(account_id,plan_id)
);
CREATE INDEX free_plan_deletions_owner_time ON free_plan_deletions(account_id,deleted_at);

GRANT SELECT,INSERT ON free_plan_creations,free_plan_deletions TO lookahead_platform_app;
