package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

/** Domain-owned subject references and current grants; never reads Identity storage. */
@Repository
public class AccountRepository {
    private final AccountStore store;
    private final com.lookahead.domain.access.StaffAccessRepository staff;
    private final com.lookahead.learning.content.validator.SnapshotValidator catalog;
    public AccountRepository(AccountStore store) { this.store = store; this.staff=null; this.catalog=null; }
    /** DLV-970: the DynamoDB store (PostgreSQL retired 2026-11-17). */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountRepository(AccountStore store, com.lookahead.domain.access.StaffAccessRepository staff,
            com.lookahead.learning.content.validator.SnapshotValidator catalog) {
        this.store=store;this.staff=staff;this.catalog=catalog;
    }

    private com.lookahead.domain.membership.MembershipGrants memberships;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void memberships(com.lookahead.domain.membership.MembershipGrants value){memberships=value;}
    // DLV-970 cross-lane: provided by lane C (membership topics).
    private MembershipTopicSource membershipTopics;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void membershipTopics(MembershipTopicSource value){membershipTopics=value;}

    public void ensureSubject(UUID subject) { store.ensureSubject(subject); }

    public Set<String> findTopicGrants(UUID subject) {
        // DLV-931: V5 migrates stored look-ahead grants; this also covers a grant written afterwards by an old tool.
        var result = new java.util.HashSet<>(com.lookahead.learning.content.util.CatalogIds.normalize(store.grantTopics(subject)));
        if(staff!=null) {
            var assignment=staff.forAccount(subject);
            if(assignment!=null) {
                result.addAll(assignment.topicGrants());
                var permissions=com.lookahead.domain.access.StaffPermissions.forRoles(false, assignment.roles());
                if(assignment.fullCurriculum()||permissions.fullCurriculum())result.addAll(catalog.allTopicIds());
                // DLV-968: admin, co-author and reviewer read the staff documents (/content/staff/...); a tester or plain user does not.
                if(permissions.previewDocuments())result.add(com.lookahead.learning.content.util.CatalogIds.STAFF_DOCUMENTS_SCOPE);
            }
            // DLV-968 (2026-11-02): the owner (cloud author capability) holds the whole curriculum, as People and access already states;
            // without this the owner saw "Full curriculum" there and "Unlock this premium content" on every paid lesson.
            if(store.authorEnabled(subject)) {
                result.addAll(catalog.allTopicIds());
                result.add(com.lookahead.learning.content.util.CatalogIds.STAFF_DOCUMENTS_SCOPE);
            }
        }
        if(membershipTopics!=null)result.addAll(membershipTopics.topics(subject));
        else if(memberships!=null)result.addAll(memberships.topics(subject));
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
