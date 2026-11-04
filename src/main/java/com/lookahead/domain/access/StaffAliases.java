package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DLV-969 (owner decision 2026-11-03): only the owner sees who people are. Everyone else sees another person as a
 * permanent role label, "Owner", "Co-author 2", "Admin 1", "Learner 4". A number is given the first time a label is
 * needed and never changes or returns (V18 staff_aliases), so old feedback keeps its author after a deletion.
 */
@Service
public class StaffAliases {
    /** A person with several roles is labelled by the first of these; with none they are a Learner. */
    static final List<String> ORDER = List.of("co-author", "admin", "reviewer", "tester");
    static final int ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final StaffAccessService access;
    private final StaffAccessRepository staff;
    /** Numbers are given in their own short transaction: callers may be read-only, and a clash must not abort them. */
    private final TransactionTemplate assign;

    public StaffAliases(JdbcTemplate jdbc, StaffAccessService access, StaffAccessRepository staff, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.access = access; this.staff = staff;
        this.assign = new TransactionTemplate(manager);
        assign.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** True when {@code viewer} may see real identities: the owner only. */
    public boolean seesIdentities(AccountPrincipal viewer) {
        try { return access.permissions(viewer).manageAccess(); }
        catch (com.lookahead.learning.content.exception.AccountFailure refused) { return false; }
        catch (RuntimeException unknown) {
            // DLV-969 (owner, 2026-11-04): fail closed, but say so: the owner would otherwise see role labels with no reason.
            String types = Events.types(unknown);
            if (PERMISSIONS.due(types)) Events.failed(Events.EVENT, "alias.identities", "PERMISSIONS_UNAVAILABLE")
                    .addKeyValue("account.id", viewer == null ? null : viewer.accountId()).addKeyValue("error.type", types)
                    .log("Could not check whether account {} may see identities ({}); showing role labels instead",
                            viewer == null ? null : viewer.accountId(), types);
            return false;
        }
    }
    private static final com.lookahead.domain.Throttle PERMISSIONS = new com.lookahead.domain.Throttle();

    /** The label other people see for {@code accountId}. */
    public String label(UUID accountId) {
        if (accountId == null) return "Unknown";
        if (owner(accountId)) return "Owner";
        var assignment = staff.forAccount(accountId);
        Set<String> roles = assignment == null ? Set.of() : assignment.roles();
        String role = ORDER.stream().filter(roles::contains).findFirst().orElse("learner");
        return title(role) + " " + number(accountId, role);
    }

    private boolean owner(UUID accountId) {
        return LocalAuthorAccess.ACCOUNT_ID.equals(accountId) || Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM cloud_accounts WHERE account_id=? AND author_access)", Boolean.class, accountId));
    }

    /** The person's number for {@code role}, given now if they have none; two people racing for one number retry. */
    int number(UUID accountId, String role) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var known = jdbc.queryForList("SELECT number FROM staff_aliases WHERE account_id=? AND role=?", Integer.class, accountId, role);
            if (!known.isEmpty()) {
                // Found after this call's insert: the number was given on try number `attempt`.
                if (attempt > 0) Events.success(Events.EVENT, "alias.assigned").addKeyValue("target.id", accountId)
                        .addKeyValue("retry.attempt", attempt).addKeyValue("retry.max", ATTEMPTS)
                        .log("Role label {} {} given to account {} on attempt {} of {}", title(role), known.getFirst(), accountId, attempt, ATTEMPTS);
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
                            accountId, title(role), attempt + 1, ATTEMPTS);
            }
        }
        Events.failed(Events.EVENT, "alias.assign", "RETRIES_EXHAUSTED").addKeyValue("target.id", accountId).addKeyValue("retry.max", ATTEMPTS)
                .log("Could not give a role label to account {} ({}) after {} attempts", accountId, title(role), ATTEMPTS);
        throw new IllegalStateException("Could not assign a role label");
    }

    static String title(String role) {
        return switch (role) {
            case "co-author" -> "Co-author";
            case "admin" -> "Admin";
            case "reviewer" -> "Reviewer";
            case "tester" -> "Tester";
            default -> "Learner";
        };
    }
}
