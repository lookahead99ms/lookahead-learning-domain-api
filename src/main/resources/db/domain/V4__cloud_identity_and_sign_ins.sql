-- Cloud identity is never inferred from an email address or a Local account UUID.
CREATE TABLE cloud_accounts (
    account_id uuid PRIMARY KEY REFERENCES platform_subjects(id),
    issuer varchar(256) NOT NULL,
    subject varchar(256) NOT NULL,
    admitted boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    author_access boolean NOT NULL DEFAULT false,
    credential_change_pending boolean NOT NULL DEFAULT false,
    username varchar(320) NOT NULL,
    display_name varchar(160) NOT NULL,
    UNIQUE (issuer, subject)
);
CREATE TABLE cloud_sign_ins (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES cloud_accounts(account_id),
    family_digest char(64) NOT NULL UNIQUE,
    binding_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    last_active_at timestamptz NOT NULL,
    recent_auth_at timestamptz NOT NULL,
    revoked_at timestamptz,
    label varchar(80) NOT NULL DEFAULT '',
    client_description varchar(80) NOT NULL DEFAULT 'Browser'
);
CREATE INDEX cloud_sign_ins_owner ON cloud_sign_ins(account_id);
CREATE UNIQUE INDEX cloud_sign_ins_active_binding ON cloud_sign_ins(account_id,binding_digest) WHERE revoked_at IS NULL;
CREATE TABLE cloud_sign_in_challenges (
    token_digest char(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES cloud_accounts(account_id),
    family_digest char(64) NOT NULL,
    binding_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    auth_at timestamptz NOT NULL,
    cancelled boolean NOT NULL DEFAULT false,
    selected_id uuid,
    admitted_id uuid REFERENCES cloud_sign_ins(id)
);
CREATE INDEX cloud_sign_in_challenges_owner ON cloud_sign_in_challenges(account_id);
-- Runtime may read identities and edit profile text, but cannot grant admission,
-- enabled state or author capability. Those are explicit operator transactions.
GRANT SELECT ON cloud_accounts TO lookahead_platform_app;
GRANT UPDATE(username,display_name,credential_change_pending) ON cloud_accounts TO lookahead_platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON cloud_sign_ins,cloud_sign_in_challenges TO lookahead_platform_app;
