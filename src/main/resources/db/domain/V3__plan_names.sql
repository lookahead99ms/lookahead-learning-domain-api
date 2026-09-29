-- Preserve existing labels; allocate numbers once per account, including legacy plans.
ALTER TABLE plans ADD COLUMN name varchar(160), ADD COLUMN plan_number bigint;
WITH numbered AS (SELECT id, row_number() OVER (PARTITION BY account_id ORDER BY created_at,id) AS n FROM plans)
UPDATE plans SET name=goal, plan_number=numbered.n FROM numbered WHERE plans.id=numbered.id;
ALTER TABLE plans ALTER COLUMN name SET NOT NULL, ALTER COLUMN plan_number SET NOT NULL;
ALTER TABLE plans ADD CONSTRAINT plans_owner_number UNIQUE(account_id,plan_number);
ALTER TABLE platform_subjects ADD COLUMN last_plan_number bigint NOT NULL DEFAULT 0;
UPDATE platform_subjects s SET last_plan_number=(SELECT coalesce(max(plan_number),0) FROM plans WHERE account_id=s.id);
