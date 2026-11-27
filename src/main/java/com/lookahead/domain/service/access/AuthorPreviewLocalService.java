package com.lookahead.domain.service.access;

import com.lookahead.domain.security.token.AccountPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Local author UI capability. Content still uses the ordinary catalog grants. */
@Component
public class AuthorPreviewLocalService {
    public static final String USERNAME = "author@lookahead.test";
    public static final UUID ACCOUNT_ID = UUID.nameUUIDFromBytes(
            ("lookahead-local-author:" + USERNAME).getBytes(StandardCharsets.UTF_8));
    private final Environment environment;

    public AuthorPreviewLocalService(Environment environment) { this.environment = environment; }

    public boolean allowed(AccountPrincipal principal) {
        if (Set.of("dev","prod").contains(environment.getProperty("app.deployment-environment","")))
            return principal != null && principal.isEnabled() && principal.cloudAuthor();
        return principal != null && principal.isEnabled()
                && ACCOUNT_ID.equals(principal.accountId()) && USERNAME.equals(principal.getUsername())
                && environment.getProperty("app.local-test.author-enabled", Boolean.class, false)
                && environment.acceptsProfiles(Profiles.of("accounts & local-test"))
                && "local".equals(environment.getProperty("app.deployment-environment"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }
}
