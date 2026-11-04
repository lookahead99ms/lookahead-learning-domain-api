-- DLV-969 S36 (owner decision 2026-11-04): one Gmail, one account. V4 said "cloud identity is never inferred from an
-- email address". This change infers it ONLY from a provider-verified email: Google's email_verified, or a password
-- user's inbox code. A new sign-in is linked when that email matches exactly one active, non-test account; otherwise it
-- is a new waiting account, as before. One account may now hold several provider identities (Cognito users).
-- Additive only: cloud_accounts.issuer/subject stay as each account's first identity and no existing grant or column
-- changes, so the Domain release running before this migration stays ready on a database at 19.
CREATE TABLE cloud_identities (
    id uuid PRIMARY KEY,
    -- ON DELETE CASCADE: the 48-hour cleanup of a waiting account (also in a release before this one) removes its
    -- identities with it. Admitted accounts are never deleted (V17 trigger), so their identities stay.
    account_id uuid NOT NULL REFERENCES cloud_accounts(account_id) ON DELETE CASCADE,
    issuer varchar(256) NOT NULL,
    subject varchar(256) NOT NULL,
    -- The Cognito username: Disable, Enable and Delete act on every username of the account.
    username varchar(320) NOT NULL,
    provider varchar(16) NOT NULL CHECK (provider IN ('google','cognito','test')),
    verified_email varchar(254),
    linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    link_source varchar(16) NOT NULL CHECK (link_source IN ('first_contact','linked','backfill','merge')),
    UNIQUE (issuer, subject)
);
CREATE INDEX cloud_identities_account ON cloud_identities(account_id);
CREATE INDEX cloud_identities_verified_email ON cloud_identities(lower(verified_email)) WHERE verified_email IS NOT NULL;

-- Every existing account keeps its one identity.
INSERT INTO cloud_identities(id, account_id, issuer, subject, username, provider, verified_email, linked_at, link_source)
SELECT gen_random_uuid(), account_id, issuer, subject, username,
       CASE WHEN issuer = 'urn:lookahead:dev-test-account' THEN 'test'
            WHEN username ILIKE 'google\_%' THEN 'google' ELSE 'cognito' END,
       verified_email, requested_at, 'backfill'
FROM cloud_accounts;

-- The admission history also records a link and a future owner-confirmed merge.
ALTER TABLE cloud_admission_events DROP CONSTRAINT cloud_admission_events_action_check;
ALTER TABLE cloud_admission_events ADD CONSTRAINT cloud_admission_events_action_check
    CHECK (action IN ('request','admit','expire','disable','enable','remove','link','merge'));

GRANT SELECT, INSERT, UPDATE, DELETE ON cloud_identities TO lookahead_platform_app;
