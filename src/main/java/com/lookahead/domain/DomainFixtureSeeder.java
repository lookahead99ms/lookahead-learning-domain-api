package com.lookahead.domain;

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

@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name="app.local-test.seed-enabled", havingValue="true")
public class DomainFixtureSeeder implements ApplicationRunner {
    private final FixtureSeedStore store;
    private final AccountRepository subjects;
    private final SnapshotValidator catalog;
    private final Environment environment;
    public DomainFixtureSeeder(JdbcTemplate jdbc, AccountRepository subjects, SnapshotValidator catalog,
                                 Environment environment, DomainFixtureGuard guard) {
        this(new JdbcFixtureSeedStore(jdbc, null), subjects, catalog, environment, guard);
    }
    /** DLV-970: the store (PostgreSQL or DynamoDB) is selected by {@code app.persistence.store}. */
    @org.springframework.beans.factory.annotation.Autowired
    public DomainFixtureSeeder(FixtureSeedStore store, AccountRepository subjects, SnapshotValidator catalog,
                                 Environment environment, DomainFixtureGuard guard) {
        this.store = store; this.subjects = subjects; this.catalog = catalog; this.environment = environment;
    }
    @Override public void run(ApplicationArguments args) {
        if (catalog.allTopicIds().isEmpty()) throw new IllegalStateException("Synthetic Domain grants require a nonempty trusted catalog");
        store.transaction(this::seed);
    }
    private void seed() {
        for (int number=1; number<=10; number++) {
            UUID id = UUID.nameUUIDFromBytes(("lookahead-local-test:learner%02d".formatted(number)).getBytes(StandardCharsets.UTF_8));
            // A restart must not restore revoked learner grants or overwrite their expiry.
            if (!store.insertSubject(id)) continue;
            for (String topic : catalog.allTopicIds()) {
                if (number == 9 && !Set.of("learn:hands-on-dsa", "learn:algorithmic-patterns").contains(topic)) continue;
                store.grant(id, topic);
            }
        }
        if (environment.getProperty("app.local-test.author-enabled", Boolean.class, false)) {
            subjects.ensureSubject(LocalAuthorAccess.ACCOUNT_ID);
            for (String topic : catalog.allTopicIds()) store.grant(LocalAuthorAccess.ACCOUNT_ID, topic);
        }
    }
}
