package com.lookahead.domain.cloud;

import com.lookahead.domain.access.AccountRemovalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Set;

/**
 * DLV-969 S36: the merge of accounts that share a verified email, cloud mode only. SES sends the link only with
 * {@code LOOKAHEAD_EMAIL_ENABLED=true}, and then {@code LOOKAHEAD_EMAIL_FROM} and {@code AWS_REGION} are required (startup
 * fails naming the missing one); otherwise the email is only logged as skipped. {@code LOOKAHEAD_PUBLIC_URL} is the
 * origin of the link.
 */
@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev' || '${app.deployment-environment:}' == 'prod'")
public class CloudMergeConfiguration {
    static final String DEV_PUBLIC_URL="https://dev.devlookahead.com";
    /** A bare address, or an ASCII display name and the address in angle brackets ("Look Ahead <no-reply@…>"). */
    static final String FROM="([A-Za-z0-9][A-Za-z0-9 .'-]{0,62} <[^<>@\\s]+@[^<>@\\s]+\\.[^<>@\\s]+>)|[^<>@\\s]+@[^<>@\\s]+\\.[^<>@\\s]+";
    @Bean EmailSender emailSender(Environment env){
        if(!Boolean.parseBoolean(env.getProperty("app.email.enabled","false").strip()))return new LoggingEmailSender();
        String from=env.getProperty("app.email.from","").strip(),region=env.getProperty("app.email.region","").strip();
        if(!from.matches(FROM))
            throw new IllegalStateException("LOOKAHEAD_EMAIL_ENABLED=true needs LOOKAHEAD_EMAIL_FROM (app.email.from): an address, or a name and <address>");
        if(!region.matches("[a-z]{2}-[a-z]+-\\d"))
            throw new IllegalStateException("LOOKAHEAD_EMAIL_ENABLED=true needs AWS_REGION (app.email.region) for SES");
        return new SesEmailSender(SesEmailSender.taskRoleClient(region),from);
    }
    @Bean CloudMergeService cloudMerges(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountRemovalService removal,EmailSender email,Environment env){
        return new CloudMergeService(jdbc,manager,removal,email,publicUrl(env));
    }
    /**
     * The link origin: {@code LOOKAHEAD_PUBLIC_URL}; unset, {@link #DEV_PUBLIC_URL} in dev (and local), and in any other
     * environment a startup failure, but only when the email is actually sent. A value that is set must be an https origin.
     */
    static String publicUrl(Environment env){
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
