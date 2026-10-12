-- DLV-933: application profile data for Cognito identities; provider credentials stay in Cognito.
CREATE TABLE cloud_account_preferences (
    account_id uuid PRIMARY KEY REFERENCES cloud_accounts(account_id),
    theme varchar(6) NOT NULL DEFAULT 'system' CHECK (theme IN ('light','dark','system')),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE cloud_account_profile_links (
    account_id uuid NOT NULL REFERENCES cloud_accounts(account_id),
    kind varchar(16) NOT NULL CHECK (kind IN ('github','linkedin','x','website')),
    url varchar(300) NOT NULL,
    PRIMARY KEY(account_id,kind)
);
GRANT SELECT,INSERT,UPDATE,DELETE ON cloud_account_preferences,cloud_account_profile_links TO lookahead_platform_app;
