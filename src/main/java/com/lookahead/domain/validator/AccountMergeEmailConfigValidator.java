package com.lookahead.domain.validator;

import com.lookahead.domain.config.AccountMergeEmailConfigValues;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Startup check of the merge email settings: the sender address and the SES region while the email is enabled
 * ({@code app.email.enabled}), and the origin of the link in it ({@code app.public-url}). A value that is missing or
 * malformed refuses startup, naming the setting. DLV-1016: these checks were {@code AccountMergeEmailConfigValues.from}
 * and {@code publicUrl} and moved here word for word.
 */
public final class AccountMergeEmailConfigValidator {
    private AccountMergeEmailConfigValidator() {}

    public static final String DEV_PUBLIC_URL="https://dev.devlookahead.com";
    /** A bare address, or an ASCII display name and the address in angle brackets ("Look Ahead <no-reply@…>"). */
    public static final String FROM="([A-Za-z0-9][A-Za-z0-9 .'-]{0,62} <[^<>@\\s]+@[^<>@\\s]+\\.[^<>@\\s]+>)|[^<>@\\s]+@[^<>@\\s]+\\.[^<>@\\s]+";
    /** Off (the default): nothing else is read. On: the sender address and the region are required and checked. */
    public static AccountMergeEmailConfigValues validated(Environment env){
        if(!Boolean.parseBoolean(env.getProperty("app.email.enabled","false").strip()))return new AccountMergeEmailConfigValues(false,"","");
        String from=env.getProperty("app.email.from","").strip(),region=env.getProperty("app.email.region","").strip();
        if(!from.matches(FROM))
            throw new IllegalStateException("LOOKAHEAD_EMAIL_ENABLED=true needs LOOKAHEAD_EMAIL_FROM (app.email.from): an address, or a name and <address>");
        if(!region.matches(CloudRuntimeValidator.AWS_REGION))
            throw new IllegalStateException("LOOKAHEAD_EMAIL_ENABLED=true needs AWS_REGION (app.email.region) for SES");
        return new AccountMergeEmailConfigValues(true,from,region);
    }
    /**
     * The link origin: {@code LOOKAHEAD_PUBLIC_URL}; unset, {@link #DEV_PUBLIC_URL} in dev (and local), and in any other
     * environment a startup failure, but only when the email is actually sent. A value that is set must be an https origin.
     */
    public static String publicUrl(Environment env){
        String url=env.getProperty("app.public-url","").strip();
        if(url.isEmpty()){
            if(Set.of("dev","local").contains(env.getProperty("app.deployment-environment","")))return DEV_PUBLIC_URL;
            if(Boolean.parseBoolean(env.getProperty("app.email.enabled","false").strip()))
                throw new IllegalStateException("LOOKAHEAD_EMAIL_ENABLED=true needs LOOKAHEAD_PUBLIC_URL (app.public-url) outside dev");
            return "";
        }
        if(!url.matches("https://[A-Za-z0-9.-]+(:[0-9]+)?/?"))throw new IllegalStateException("LOOKAHEAD_PUBLIC_URL (app.public-url) must be an https origin");
        return url;
    }
}
