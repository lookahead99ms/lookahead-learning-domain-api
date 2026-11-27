package com.lookahead.domain.service.access;

import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.access.StaffAlias;
import com.lookahead.domain.dto.StaffAccessSharedAssignment;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.LogThrottle;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DLV-969 (owner decision 2026-11-03): only the owner sees who people are. Everyone else sees another person as a
 * permanent role label, "Owner", "Co-author 2", "Admin 1", "Learner 4". A number is given the first time a label is
 * needed and never changes or returns (V18 staff_aliases), so old feedback keeps its author after a deletion.
 */
@Service
public class StaffAliasService {
    /** A person with several roles is labelled by the first of these; with none they are a Learner. */
    static final List<String> ORDER = List.of("co-author", "admin", "reviewer", "tester");
    private final StaffAlias store;
    private final StaffAccessService access;
    private final StaffAccess staff;

    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @Autowired
    public StaffAliasService(StaffAlias store, StaffAccessService access, StaffAccess staff) {
        this.store = store; this.access = access; this.staff = staff;
    }

    /** True when {@code viewer} may see real identities: the owner only. */
    public boolean seesIdentities(AccountPrincipal viewer) {
        try { return access.permissions(viewer).manageAccess(); }
        catch (AccountFailure refused) { return false; }
        catch (RuntimeException unknown) {
            // DLV-969 (owner, 2026-11-04): fail closed, but say so: the owner would otherwise see role labels with no reason.
            String types = EventLog.types(unknown);
            if (PERMISSIONS.due(types)) EventLog.failed(EventLog.EVENT, "alias.identities", "PERMISSIONS_UNAVAILABLE")
                    .addKeyValue("account.id", viewer == null ? null : viewer.accountId()).addKeyValue("error.type", types)
                    .log("Could not check whether account {} may see identities ({}); showing role labels instead",
                            viewer == null ? null : viewer.accountId(), types);
            return false;
        }
    }
    private static final LogThrottle PERMISSIONS = new LogThrottle();

    /**
     * The People rows as {@code viewer} may see them (DLV-969). The owner gets the saved assignments as they are.
     * Anyone else gets a {@link StaffAccessSharedAssignment} for each row: another person's role label, never their
     * account id or invitation email; their own row keeps their account id and carries their own name. Who may open
     * the list, and which rows it holds, is {@link StaffAccessService#list}. DLV-1016: moved here from the controller.
     */
    public List<?> assignments(AccountPrincipal viewer) {
        var rows = access.list(viewer);
        if (seesIdentities(viewer)) return rows;
        return rows.stream().map(row -> {
            boolean own = viewer.accountId().equals(row.accountId());
            String label = row.accountId() == null ? "Pending invitation" : own ? ownName(viewer) : label(row.accountId());
            return new StaffAccessSharedAssignment(row.id(), own ? row.accountId() : null, null, label, row.accountId() == null,
                    row.roles(), row.topicGrants(), row.fullCurriculum(), row.revision());
        }).toList();
    }

    private static String ownName(AccountPrincipal viewer) {
        String name = viewer.displayName();
        return name == null || name.isBlank() ? viewer.getUsername() : name.strip();
    }

    /** The label other people see for {@code accountId}. */
    public String label(UUID accountId) {
        if (accountId == null) return "Unknown";
        if (owner(accountId)) return "Owner";
        var assignment = staff.forAccount(accountId);
        Set<String> roles = assignment == null ? Set.of() : assignment.roles();
        String role = ORDER.stream().filter(roles::contains).findFirst().orElse("learner");
        return title(role) + " " + number(accountId, role);
    }

    private boolean owner(UUID accountId) { return AuthorPreviewLocalService.ACCOUNT_ID.equals(accountId) || store.owner(accountId); }

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
