package com.lookahead.domain.job;

import com.lookahead.domain.db.store.testaccounts.TestAccountFixtureSeed;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.validator.TestAccountFixtureValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Where the fixture check refuses a start (DLV-1016). With the seed job in the context the refusal comes when the job
 * is created, after the job's own dependencies and before any later bean; without the seed job (every environment
 * but a seeded Local one, PROD included) the validator bean alone refuses. Both were true before the job called the
 * check itself and are true after.
 */
class TestAccountFixtureSeedJobStartupTest {
    static final String OUTSIDE_LOCAL = "Domain fixtures require local-test and local deployment only";
    static final List<String> created = new ArrayList<>();

    /** The job's own dependencies, defined after the job so that they are created only because the job asks for them. */
    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean TestAccountFixtureSeed seedStore() { created.add("store"); return mock(TestAccountFixtureSeed.class, withSettings().mockMaker(MockMakers.PROXY)); }
        @Bean AccountGrantService subjects() { created.add("subjects"); return mock(AccountGrantService.class); }
        @Bean AccountCatalogService catalog() { created.add("catalog"); return mock(AccountCatalogService.class); }
    }

    /** A bean nobody asks for, defined after everything else: it exists only when the start got that far. */
    static class Later { Later() { created.add("later"); } }

    @BeforeEach void reset() { created.clear(); }

    /** The job first, as the scan registers it (package job before package validator), then the validator, then the rest. */
    private ApplicationContextRunner withTheSeedJob(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(TestAccountFixtureSeedJob.class, TestAccountFixtureValidator.class, Dependencies.class, Later.class)
                .withPropertyValues("spring.profiles.active=accounts,local-test", "app.local-test.seed-enabled=true").withPropertyValues(properties);
    }

    @Test void withTheSeedJobTheRefusalComesWhenTheJobIsCreatedAfterItsDependenciesAndBeforeAnyLaterBean() {
        withTheSeedJob("app.deployment-environment=dev").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage(OUTSIDE_LOCAL);
            assertThat(context.getStartupFailure()).hasMessageContaining("testAccountFixtureSeedJob");
            assertThat(created).containsExactly("store", "subjects", "catalog");
        });
    }

    @Test void aLocalSeededStartIsNotRefusedAndTheJobExists() {
        withTheSeedJob("app.deployment-environment=local").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TestAccountFixtureSeedJob.class).hasSingleBean(TestAccountFixtureValidator.class);
            assertThat(created).containsExactly("store", "subjects", "catalog", "later");
        });
    }

    @Test void withoutTheSeedJobTheValidatorBeanAloneRefusesInEveryEnvironment() {
        // PROD with the author switch on: no local-test profile and no seed, so the seed job does not exist.
        new ApplicationContextRunner().withUserConfiguration(TestAccountFixtureSeedJob.class, TestAccountFixtureValidator.class)
                .withPropertyValues("spring.profiles.active=accounts,prod", "app.deployment-environment=prod", "app.local-test.author-enabled=true").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage(OUTSIDE_LOCAL);
                    assertThat(context.getStartupFailure()).hasMessageContaining("testAccountFixtureValidator").hasMessageNotContaining("testAccountFixtureSeedJob");
                });
        // Local with the fixture profile and the author switch but no seed: again no seed job, and the second refusal of the check.
        new ApplicationContextRunner().withUserConfiguration(TestAccountFixtureSeedJob.class, TestAccountFixtureValidator.class)
                .withPropertyValues("spring.profiles.active=accounts,local-test", "app.deployment-environment=local", "app.local-test.author-enabled=true").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Local author access requires explicit local fixture seeding");
                    assertThat(context.getStartupFailure()).hasMessageContaining("testAccountFixtureValidator").hasMessageNotContaining("testAccountFixtureSeedJob");
                });
        // The same two environments without the switch start, and neither has a seed job.
        new ApplicationContextRunner().withUserConfiguration(TestAccountFixtureSeedJob.class, TestAccountFixtureValidator.class)
                .withPropertyValues("spring.profiles.active=accounts,prod", "app.deployment-environment=prod").run(context ->
                        assertThat(context).hasNotFailed().doesNotHaveBean(TestAccountFixtureSeedJob.class).hasSingleBean(TestAccountFixtureValidator.class));
    }
}
