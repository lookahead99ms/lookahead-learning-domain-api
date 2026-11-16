package com.lookahead.domain.access;

import com.lookahead.domain.Events;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

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
    private final StaffAliasStore store;
    private final StaffAccessService access;
    private final StaffAccessRepository staff;

    public StaffAliases(JdbcTemplate jdbc, StaffAccessService access, StaffAccessRepository staff, PlatformTransactionManager manager) {
        this(new JdbcStaffAliasStore(jdbc, manager), access, staff);
    }
    /** DLV-970: the store (PostgreSQL or DynamoDB) is selected by {@code app.persistence.store}. */
    @org.springframework.beans.factory.annotation.Autowired
    public StaffAliases(StaffAliasStore store, StaffAccessService access, StaffAccessRepository staff) {
        this.store = store; this.access = access; this.staff = staff;
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

    private boolean owner(UUID accountId) { return LocalAuthorAccess.ACCOUNT_ID.equals(accountId) || store.owner(accountId); }

    /** The person's number for {@code role}, given now if they have none; two people racing for one number retry. */
    int number(UUID accountId, String role) { return store.number(accountId, role); }

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
