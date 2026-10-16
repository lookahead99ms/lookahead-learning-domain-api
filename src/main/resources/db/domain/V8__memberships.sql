-- Commercial access is separate from saved study plans and staff assignments.
CREATE TABLE membership_plans (
 id varchar(40) PRIMARY KEY, definition jsonb NOT NULL,
 price_cents integer NOT NULL CHECK(price_cents>=0), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE membership_sales (
 id uuid PRIMARY KEY, template varchar(40) NOT NULL, greeting varchar(500) NOT NULL,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 plan_ids jsonb NOT NULL, created_by uuid NOT NULL REFERENCES platform_subjects(id),
 stopped_at timestamptz, CHECK(ends_at>starts_at)
);
CREATE TABLE membership_purchases (
 id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES platform_subjects(id),
 plan_id varchar(40) NOT NULL REFERENCES membership_plans(id), plan_snapshot jsonb NOT NULL,
 request_fingerprint varchar(64) NOT NULL, idempotency_key varchar(128) NOT NULL,
 price_paid_cents integer NOT NULL CHECK(price_paid_cents>=0), discount jsonb,
 status varchar(20) NOT NULL CHECK(status IN ('pending','succeeded','failed','scheduled','expired')),
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 source varchar(20) NOT NULL CHECK(source IN ('checkout','staff-upgrade')),
 actor_id uuid NOT NULL REFERENCES platform_subjects(id), provider varchar(20) NOT NULL,
 provider_payment_id varchar(100) UNIQUE, provider_status varchar(40),
 replaces_id uuid REFERENCES membership_purchases(id), superseded_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(account_id,idempotency_key), CHECK(ends_at>starts_at)
);
CREATE INDEX membership_active_access ON membership_purchases(account_id,ends_at);
CREATE TABLE membership_payment_attempts (
 id uuid PRIMARY KEY, purchase_id uuid NOT NULL REFERENCES membership_purchases(id),
 result varchar(40) NOT NULL, error_class varchar(40), recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE membership_provider_events (
 provider varchar(20) NOT NULL, event_id varchar(160) NOT NULL,
 purchase_id uuid NOT NULL REFERENCES membership_purchases(id), recorded_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(provider,event_id)
);
CREATE TABLE membership_audit (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES platform_subjects(id),
 action varchar(40) NOT NULL, subject_id varchar(100) NOT NULL, detail jsonb NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now()
);
-- Durable local provider fixture: survives retries and application restarts.
CREATE TABLE membership_mock_payments (
 idempotency_key varchar(160) PRIMARY KEY, payment_id varchar(100) UNIQUE NOT NULL,
 amount_cents integer NOT NULL, currency varchar(3) NOT NULL, status varchar(40) NOT NULL
);
GRANT SELECT,INSERT,UPDATE ON membership_plans,membership_sales,membership_purchases,membership_mock_payments TO lookahead_platform_app;
GRANT SELECT,INSERT ON membership_payment_attempts,membership_provider_events,membership_audit TO lookahead_platform_app;
CREATE TRIGGER membership_audit_immutable BEFORE UPDATE OR DELETE ON membership_audit FOR EACH ROW EXECUTE FUNCTION reject_author_review_mutation();
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('free','{"id": "free", "name": "Free", "group": "regular", "currency": "USD", "priceBasis": "free", "scope": {"kind": "free"}, "duration": {"kind": "ongoing"}, "renewal": "not-applicable", "pending": []}'::jsonb,0);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('learn','{"id": "learn", "name": "Learn", "group": "regular", "currency": "USD", "priceBasis": "per-year", "scope": {"kind": "catalogs", "catalogs": ["learn"]}, "duration": {"kind": "years", "count": 1}, "renewal": "manual", "pending": []}'::jsonb,6999);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('grow','{"id": "grow", "name": "Grow", "group": "regular", "currency": "USD", "priceBasis": "per-year", "scope": {"kind": "catalogs", "catalogs": ["grow"]}, "duration": {"kind": "years", "count": 1}, "renewal": "manual", "pending": []}'::jsonb,9999);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('advance','{"id": "advance", "name": "Advance", "group": "regular", "currency": "USD", "priceBasis": "per-year", "scope": {"kind": "catalogs", "catalogs": ["learn", "grow"]}, "duration": {"kind": "years", "count": 1}, "renewal": "manual", "pending": []}'::jsonb,14499);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('complete','{"id": "complete", "name": "Complete", "group": "regular", "currency": "USD", "priceBasis": "per-year", "scope": {"kind": "catalogs", "catalogs": ["learn", "grow", "blaze"]}, "duration": {"kind": "years", "count": 1}, "renewal": "manual", "pending": []}'::jsonb,27999);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('course-pass','{"id": "course-pass", "name": "Course Pass", "group": "regular", "currency": "USD", "priceBasis": "per-course", "scope": {"kind": "courses", "min": 1, "max": 3}, "duration": {"kind": "pending"}, "renewal": "manual", "pending": ["Duration"]}'::jsonb,2799);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('interview-sprint','{"id": "interview-sprint", "name": "Interview Sprint", "group": "regular", "currency": "USD", "priceBasis": "one-time", "scope": {"kind": "catalogs", "catalogs": ["learn", "grow", "blaze"]}, "duration": {"kind": "days", "count": 100}, "renewal": "manual", "pending": []}'::jsonb,9999);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('monthly-promo','{"id": "monthly-promo", "name": "Monthly Promo", "group": "promo", "currency": "USD", "priceBasis": "per-month", "scope": {"kind": "catalogs", "catalogs": ["learn", "grow", "blaze"]}, "duration": {"kind": "months", "count": 1}, "renewal": "manual", "pending": []}'::jsonb,2799);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('annual-promo','{"id": "annual-promo", "name": "Annual Promo", "group": "promo", "currency": "USD", "priceBasis": "per-year", "scope": {"kind": "catalogs", "catalogs": ["learn", "grow", "blaze"]}, "duration": {"kind": "years", "count": 1}, "renewal": "manual", "pending": []}'::jsonb,10449);
INSERT INTO membership_plans(id,definition,price_cents) VALUES ('interview-pass-promo','{"id": "interview-pass-promo", "name": "Interview Pass Promo", "group": "promo", "currency": "USD", "priceBasis": "one-time", "scope": {"kind": "catalogs", "catalogs": ["learn", "grow", "blaze"]}, "duration": {"kind": "days", "count": 90}, "renewal": "manual", "pending": []}'::jsonb,5999);
