package com.lookahead.learning.content.repository;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

/** Domain-owned subject references and current grants; never reads Identity storage. */
@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    private final com.lookahead.domain.access.StaffAccessRepository staff;
    private final com.lookahead.learning.content.validator.SnapshotValidator catalog;
    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; this.staff=null; this.catalog=null; }
    @org.springframework.beans.factory.annotation.Autowired
    public AccountRepository(JdbcTemplate jdbc, com.lookahead.domain.access.StaffAccessRepository staff,
            com.lookahead.learning.content.validator.SnapshotValidator catalog) {
        this.jdbc=jdbc;this.staff=staff;this.catalog=catalog;
    }

    private com.lookahead.domain.membership.MembershipGrants memberships;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void memberships(com.lookahead.domain.membership.MembershipGrants value){memberships=value;}

    public void ensureSubject(UUID subject) {
        jdbc.update("INSERT INTO " + LegacyStorageNames.SUBJECTS_TABLE + "(id) VALUES (?) ON CONFLICT (id) DO NOTHING", subject);
    }

    public Set<String> findTopicGrants(UUID subject) {
        // DLV-931: V5 migrates stored look-ahead grants; this also covers a grant written afterwards by an old tool.
        var result = new java.util.HashSet<>(com.lookahead.learning.content.util.CatalogIds.normalize(
                jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id = ? AND (valid_until IS NULL OR valid_until > now())", String.class, subject)));
        if(staff!=null) {
            var assignment=staff.forAccount(subject);
            if(assignment!=null) {
                result.addAll(assignment.topicGrants());
                if(assignment.fullCurriculum()||assignment.roles().contains("tester"))result.addAll(catalog.allTopicIds());
            }
        }
        if(memberships!=null)result.addAll(memberships.topics(subject));
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
