package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** DLV-970: the PostgreSQL role-label numbers of {@link StaffAliases}, verbatim (V18 staff_aliases). */
public final class JdbcStaffAliasStore implements StaffAliasStore {
    static final int ATTEMPTS = StaffAliases.ATTEMPTS;
    private final JdbcTemplate jdbc;
    /** Numbers are given in their own short transaction: callers may be read-only, and a clash must not abort them. */
    private final TransactionTemplate assign;

    public JdbcStaffAliasStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.assign = new TransactionTemplate(manager);
        assign.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public boolean owner(UUID accountId) {
        return LocalAuthorAccess.ACCOUNT_ID.equals(accountId) || Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access)", Boolean.class, accountId));
    }

    /** The person's number for {@code role}, given now if they have none; two people racing for one number retry. */
    @Override public int number(UUID accountId, String role) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var known = jdbc.queryForList("SELECT number FROM staff_aliases WHERE account_id=? AND role=?", Integer.class, accountId, role);
            if (!known.isEmpty()) {
                // Found after this call's insert: the number was given on try number `attempt`.
                if (attempt > 0) Events.success(Events.EVENT, "alias.assigned").addKeyValue("target.id", accountId)
                        .addKeyValue("retry.attempt", attempt).addKeyValue("retry.max", ATTEMPTS)
                        .log("Role label {} {} given to account {} on attempt {} of {}", StaffAliases.title(role), known.getFirst(), accountId, attempt, ATTEMPTS);
                return known.getFirst();
            }
            try {
                assign.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO staff_aliases(account_id,role,number)
                    SELECT ?,?,COALESCE(max(number),0)+1 FROM staff_aliases WHERE role=?
                    ON CONFLICT (account_id,role) DO NOTHING
                    """, accountId, role, role));
            } catch (DuplicateKeyException taken) {
                // Another person took that number in the same moment; read again and take the next one.
                Events.retry(Events.EVENT, "alias.assign", "NUMBER_TAKEN", attempt + 1, ATTEMPTS).addKeyValue("target.id", accountId)
                        .log("Role label number for account {} ({}) was taken by someone else at the same moment: attempt {} of {}; trying the next number",
                            accountId, StaffAliases.title(role), attempt + 1, ATTEMPTS);
            }
        }
        Events.failed(Events.EVENT, "alias.assign", "RETRIES_EXHAUSTED").addKeyValue("target.id", accountId).addKeyValue("retry.max", ATTEMPTS)
                .log("Could not give a role label to account {} ({}) after {} attempts", accountId, StaffAliases.title(role), ATTEMPTS);
        throw new IllegalStateException("Could not assign a role label");
    }

}
