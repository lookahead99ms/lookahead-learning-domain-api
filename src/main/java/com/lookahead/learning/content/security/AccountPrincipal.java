package com.lookahead.learning.content.security;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Password-free identity admitted by this request's authoritative token validation. */
public record AccountPrincipal(UUID accountId, String username, String displayName, boolean enabled)
        implements Principal {
    @Override public String getName() { return accountId.toString(); }
    public String getUsername() { return username; }
    public boolean isEnabled() { return enabled; }
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_LEARNER"));
    }
    @Override public String toString() { return "AccountPrincipal[redacted]"; }
}
