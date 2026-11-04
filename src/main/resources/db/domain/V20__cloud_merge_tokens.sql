-- DLV-969 S36 (owner decision 2026-11-04): merge of accounts that already share a verified email. The person asks
-- from a signed-in session, gets one single-use link at that email, chooses the account to keep and the others are
-- deleted. The token is stored as a digest only (SHA-256, hex); the link in the email is the only copy of the token.
-- Additive only: no existing grant or column changes, so the Domain release running before this migration stays
-- ready on a database at 20.
CREATE TABLE cloud_merge_tokens (
    token_digest char(64) PRIMARY KEY,
    email varchar(254) NOT NULL,
    -- Only an admitted account can ask (it needs a sign-in), and admitted accounts are never deleted (V17 trigger).
    requested_by uuid NOT NULL REFERENCES cloud_accounts(account_id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    -- Set when the merge commits, or when a new request for the same email replaces this link.
    used_at timestamptz
);
-- Rate limit and the one outstanding link are both per address, case-insensitive.
CREATE INDEX cloud_merge_tokens_email ON cloud_merge_tokens(lower(email), created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON cloud_merge_tokens TO lookahead_platform_app;
