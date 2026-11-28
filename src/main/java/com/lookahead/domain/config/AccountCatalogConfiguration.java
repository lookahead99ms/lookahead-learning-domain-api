package com.lookahead.domain.config;

import com.lookahead.domain.model.AccountCatalog;
import com.lookahead.domain.service.content.AccountCatalogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The checked account catalog as a value, for the classes that only read it: the study plan
 * validators. DLV-1016: they took {@link AccountCatalogService} itself. The service still loads and
 * checks the catalog when it is created, so the start-up refusal of a catalog that cannot be loaded
 * comes from the same bean as before.
 */
@Configuration
@Profile("accounts")
public class AccountCatalogConfiguration {
  @Bean
  AccountCatalog accountCatalog(AccountCatalogService service) {
    return service.catalog();
  }
}
