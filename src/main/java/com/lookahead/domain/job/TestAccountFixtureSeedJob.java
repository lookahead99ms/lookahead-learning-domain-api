package com.lookahead.domain.job;

import com.lookahead.domain.db.store.testaccounts.TestAccountFixtureSeed;
import com.lookahead.domain.validator.TestAccountFixtureValidator;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.content.AccountCatalogService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name="app.local-test.seed-enabled", havingValue="true")
public class TestAccountFixtureSeedJob implements ApplicationRunner {
    private final TestAccountFixtureSeed store;
    private final AccountGrantService subjects;
    private final AccountCatalogService catalog;
    private final Environment environment;
    /**
     * DLV-970: the seed writes go to the DynamoDB store ({@link com.lookahead.domain.db.store.testaccounts.TestAccountFixtureSeedStore}).
     * DLV-1016: the fixture check is the first statement, so a start with fixtures outside local-test is refused when
     * this job is created, once its own dependencies exist, which is where the refusal always came (the job took the
     * validator bean as a last parameter it never read).
     */
    @Autowired
    public TestAccountFixtureSeedJob(TestAccountFixtureSeed store, AccountGrantService subjects, AccountCatalogService catalog,
                                 Environment environment) {
        TestAccountFixtureValidator.validate(environment);
        this.store = store; this.subjects = subjects; this.catalog = catalog; this.environment = environment;
    }
    @Override public void run(ApplicationArguments args) {
        if (catalog.allTopicIds().isEmpty()) throw new IllegalStateException("Synthetic Domain grants require a nonempty trusted catalog");
        seed();
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
            subjects.ensureSubject(AuthorPreviewLocalService.ACCOUNT_ID);
            for (String topic : catalog.allTopicIds()) store.grant(AuthorPreviewLocalService.ACCOUNT_ID, topic);
        }
    }
}
