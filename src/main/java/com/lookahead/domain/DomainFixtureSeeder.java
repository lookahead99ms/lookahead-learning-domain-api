package com.lookahead.domain;

import com.lookahead.domain.compatibility.LegacyStorageNames;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name="app.local-test.seed-enabled", havingValue="true")
public class DomainFixtureSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AccountRepository subjects;
    private final SnapshotValidator catalog;
    private final Environment environment;
    public DomainFixtureSeeder(JdbcTemplate jdbc, AccountRepository subjects, SnapshotValidator catalog,
                                 Environment environment, DomainFixtureGuard guard) {
        this.jdbc = jdbc; this.subjects = subjects; this.catalog = catalog; this.environment = environment;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (catalog.allTopicIds().isEmpty()) throw new IllegalStateException("Synthetic Domain grants require a nonempty trusted catalog");
        for (int number=1; number<=10; number++) {
            UUID id = UUID.nameUUIDFromBytes(("lookahead-local-test:learner%02d".formatted(number)).getBytes(StandardCharsets.UTF_8));
            // A restart must not restore revoked learner grants or overwrite their expiry.
            int inserted = jdbc.update("INSERT INTO " + LegacyStorageNames.SUBJECTS_TABLE + "(id) VALUES (?) ON CONFLICT (id) DO NOTHING", id);
            if (inserted == 0) continue;
            for (String topic : catalog.allTopicIds()) {
                if (number == 9 && !Set.of("learn:hands-on-dsa", "learn:algorithmic-patterns").contains(topic)) continue;
                jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?) ON CONFLICT DO NOTHING", id, topic);
            }
        }
        if (environment.getProperty("app.local-test.author-enabled", Boolean.class, false)) {
            subjects.ensureSubject(LocalAuthorAccess.ACCOUNT_ID);
            for (String topic : catalog.allTopicIds()) jdbc.update("INSERT INTO account_grants(account_id,topic_id) VALUES (?,?) ON CONFLICT DO NOTHING", LocalAuthorAccess.ACCOUNT_ID, topic);
        }
    }
}
