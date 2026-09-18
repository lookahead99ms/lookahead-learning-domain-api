package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

/** Platform-owned subject references and current grants; never reads Identity storage. */
@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureSubject(UUID subject) {
        jdbc.update("INSERT INTO platform_subjects(id) VALUES (?) ON CONFLICT (id) DO NOTHING", subject);
    }

    public Set<String> findTopicGrants(UUID subject) {
        return Set.copyOf(jdbc.queryForList("SELECT topic_id FROM account_grants WHERE account_id = ? AND (valid_until IS NULL OR valid_until > now())", String.class, subject));
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
