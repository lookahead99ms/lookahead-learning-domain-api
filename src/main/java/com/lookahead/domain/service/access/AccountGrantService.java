package com.lookahead.domain.service.access;

import com.lookahead.domain.db.store.access.AccountSubject;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.membership.MembershipGrants;
import com.lookahead.domain.model.StaffPermissions;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.util.CatalogIds;
import com.lookahead.domain.util.MembershipRules;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Domain-owned subject references and current grants; never reads Identity storage. */
@Service
public class AccountGrantService {
    private final AccountSubject store;
    private final StaffAccess staff;
    private final AccountCatalogService catalog;
    private final MembershipGrants memberships;
    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @Autowired
    public AccountGrantService(AccountSubject store, StaffAccess staff,
            AccountCatalogService catalog, MembershipGrants memberships) {
        this.store=store;this.staff=staff;this.catalog=catalog;this.memberships=memberships;
    }

    public void ensureSubject(UUID subject) { store.ensureSubject(subject); }

    public Set<String> findTopicGrants(UUID subject) {
        // DLV-931: V5 migrates stored look-ahead grants; this also covers a grant written afterwards by an old tool.
        var result = new HashSet<>(CatalogIds.normalize(store.grantTopics(subject)));
        var assignment=staff.forAccount(subject);
        if(assignment!=null) {
            result.addAll(assignment.topicGrants());
            var permissions=StaffPermissions.forRoles(false, assignment.roles());
            if(assignment.fullCurriculum()||permissions.fullCurriculum())result.addAll(catalog.allTopicIds());
            // DLV-968: admin, co-author and reviewer read the staff documents (/content/staff/...); a tester or plain user does not.
            if(permissions.previewDocuments())result.add(CatalogIds.STAFF_DOCUMENTS_SCOPE);
        }
        // DLV-968 (2026-11-02): the owner (cloud author capability) holds the whole curriculum, as People and access already states;
        // without this the owner saw "Full curriculum" there and "Unlock this premium content" on every paid lesson.
        if(store.authorEnabled(subject)) {
            result.addAll(catalog.allTopicIds());
            result.add(CatalogIds.STAFF_DOCUMENTS_SCOPE);
        }
        result.addAll(MembershipRules.topics(memberships.catalogScopes(subject),catalog.allTopicIds()));
        return Set.copyOf(result);
    }

    /** Compatibility method: enabled state comes only from this freshly verified request. */
    public boolean isEnabled(UUID subject) { return currentIdentityEnabled(subject); }

    public static boolean currentIdentityEnabled(UUID subject) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && principal.isEnabled() && principal.accountId().equals(subject);
    }
}
