package com.lookahead.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.service.access.AuthorPreviewLocalService;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.service.content.AccountCatalogService;
import com.lookahead.domain.service.content.AuthorPreviewAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class AuthorPreviewAccessControllerActivationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              AuthorPreviewAccessController.class, AuthorPreviewAccessService.class)
          .withBean(StaffAccessService.class, () -> mock(StaffAccessService.class))
          .withBean(
              AuthorPreviewLocalService.class,
              () -> new AuthorPreviewLocalService(new MockEnvironment()))
          .withBean(
              AccountGrantService.class,
              () -> mock(AccountGrantService.class, withSettings().mockMaker(MockMakers.SUBCLASS)))
          .withBean(
              AccountCatalogService.class, AuthorPreviewAccessControllerActivationTest::catalog)
          .withPropertyValues(
              "app.deployment-environment=local",
              "app.local-test.author-enabled=true",
              "spring.profiles.active=accounts,local-test,resource");

  @Test
  void endpointRegistersOnlyForExplicitLocalAuthorOAuthDeployment() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            AuthorPreviewAccessController.class, AuthorPreviewAccessService.class)
        .run(context ->
            assertThat(context)
                .doesNotHaveBean(AuthorPreviewAccessController.class)
                .doesNotHaveBean(AuthorPreviewAccessService.class));
    runner.run(context ->
        assertThat(context)
            .hasSingleBean(AuthorPreviewAccessController.class)
            .hasSingleBean(AuthorPreviewAccessService.class));
    for (String property :
        new String[] {
          "app.deployment-environment=prod",
          "app.deployment-environment=dev",
          "app.local-test.author-enabled=false",
          "spring.profiles.active=accounts,resource",
          "spring.profiles.active=local-test,resource",
          "spring.profiles.active=accounts,local-test",
          "spring.profiles.active=accounts,local-test,resource,prod",
          "spring.profiles.active=accounts,local-test,resource,production"
        }) {
      runner
          .withPropertyValues(property)
          .run(context ->
              assertThat(context)
                  .doesNotHaveBean(AuthorPreviewAccessController.class)
                  .doesNotHaveBean(AuthorPreviewAccessService.class));
    }
  }

  private static AccountCatalogService catalog() {
    var mapper = new ObjectMapper();
    var catalog = mapper.createObjectNode();
    catalog.put("schemaVersion", "account-catalog/v1");
    catalog.put("catalogVersion", "sha256:" + "a".repeat(64));
    catalog.putArray("algorithmVersions");
    catalog.putArray("rankingVersions");
    catalog.putArray("records");
    return new AccountCatalogService(mapper, catalog);
  }
}
