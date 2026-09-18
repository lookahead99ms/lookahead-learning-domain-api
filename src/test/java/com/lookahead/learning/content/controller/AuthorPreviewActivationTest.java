package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import com.lookahead.learning.content.validator.SnapshotValidator;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class AuthorPreviewActivationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuthorPreviewAccessController.class, AuthorPreviewAccessService.class)
            .withBean(LocalAuthorAccess.class, () -> new LocalAuthorAccess(new MockEnvironment()))
            .withBean(AccountRepository.class, () -> mock(AccountRepository.class,
                    withSettings().mockMaker(MockMakers.SUBCLASS)))
            .withBean(SnapshotValidator.class, AuthorPreviewActivationTest::catalog)
            .withPropertyValues("app.deployment-environment=local", "app.local-test.author-enabled=true",
                    "spring.profiles.active=accounts,local-test,resource");

    @Test void endpointRegistersOnlyForExplicitLocalAuthorOAuthDeployment() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthorPreviewAccessController.class, AuthorPreviewAccessService.class)
                .run(context -> assertThat(context).doesNotHaveBean(AuthorPreviewAccessController.class)
                        .doesNotHaveBean(AuthorPreviewAccessService.class));
        runner.run(context -> assertThat(context).hasSingleBean(AuthorPreviewAccessController.class)
                .hasSingleBean(AuthorPreviewAccessService.class));
        for (String property : new String[]{"app.deployment-environment=prod",
                "app.deployment-environment=dev", "app.local-test.author-enabled=false",
                "spring.profiles.active=accounts,resource", "spring.profiles.active=local-test,resource",
                "spring.profiles.active=accounts,local-test", "spring.profiles.active=accounts,local-test,resource,prod",
                "spring.profiles.active=accounts,local-test,resource,production"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context)
                    .doesNotHaveBean(AuthorPreviewAccessController.class)
                    .doesNotHaveBean(AuthorPreviewAccessService.class));
        }
    }

    private static SnapshotValidator catalog() {
        var mapper = new ObjectMapper();
        var catalog = mapper.createObjectNode();
        catalog.put("schemaVersion", "account-catalog/v1");
        catalog.put("catalogVersion", "sha256:" + "a".repeat(64));
        catalog.putArray("algorithmVersions");
        catalog.putArray("rankingVersions");
        catalog.putArray("records");
        return new SnapshotValidator(mapper, catalog);
    }
}
