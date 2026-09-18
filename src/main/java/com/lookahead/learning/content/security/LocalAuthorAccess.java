package com.lookahead.learning.content.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Local author UI capability. Content still uses the ordinary catalog grants. */
@Component
public class LocalAuthorAccess {
    public static final String USERNAME = "author@lookahead.test";
    public static final UUID ACCOUNT_ID = UUID.nameUUIDFromBytes(
            ("lookahead-local-author:" + USERNAME).getBytes(StandardCharsets.UTF_8));
    private final Environment environment;

    public LocalAuthorAccess(Environment environment) { this.environment = environment; }

    public boolean allowed(AccountPrincipal principal) {
        return principal != null && principal.isEnabled()
                && ACCOUNT_ID.equals(principal.accountId()) && USERNAME.equals(principal.getUsername())
                && environment.getProperty("app.local-test.author-enabled", Boolean.class, false)
                && environment.acceptsProfiles(Profiles.of("accounts & local-test"))
                && "local".equals(environment.getProperty("app.deployment-environment"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }
}
