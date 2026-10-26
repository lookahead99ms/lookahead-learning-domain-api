package com.lookahead.domain.testaccounts;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Transaction-owned data erasure. Identity authorization and tombstones belong to the caller. */
public final class AccountDataErasure {
    private final JdbcTemplate jdbc;
    public AccountDataErasure(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Cloud callers always pass false; a purge context alone never grants audit authority. */
    public void erase(UUID id, boolean localFixturePurge) {
        jdbc.query("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE", rs -> {}, id);
        jdbc.update("DELETE FROM free_plan_creations WHERE account_id=?", id);
        jdbc.update("DELETE FROM free_plan_deletions WHERE account_id=?", id);
        jdbc.update("DELETE FROM mutation_receipts WHERE account_id=?", id);
        jdbc.update("DELETE FROM plans WHERE account_id=?", id);
        jdbc.update("DELETE FROM account_grants WHERE account_id=?", id);
        jdbc.update("DELETE FROM support_receipts WHERE account_id=?", id);
        jdbc.update("UPDATE staff_access SET roles='[]',topic_grants='[]',full_curriculum=false,revision=revision+1,updated_at=clock_timestamp() WHERE account_id=?", id);
        if (localFixturePurge && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'membership_audit','DELETE')", Boolean.class))) {
            jdbc.query("SELECT set_config('lookahead.test_account_purge',?,true)", rs -> {}, id.toString());
            jdbc.update("DELETE FROM membership_audit WHERE subject_id IN (SELECT id::text FROM membership_purchases WHERE account_id=?)", id);
        }
        jdbc.update("DELETE FROM membership_mock_payments WHERE idempotency_key IN (SELECT idempotency_key FROM membership_purchases WHERE account_id=?)", id);
        jdbc.update("DELETE FROM membership_payment_attempts WHERE purchase_id IN (SELECT id FROM membership_purchases WHERE account_id=?)", id);
        jdbc.update("DELETE FROM membership_provider_events WHERE purchase_id IN (SELECT id FROM membership_purchases WHERE account_id=?)", id);
        // Delete the complete account set in one statement; intra-account replacement chains
        // disappear together. A cross-account reference fails and rolls back, never cascades.
        jdbc.update("DELETE FROM membership_purchases WHERE account_id=?", id);
    }
}
