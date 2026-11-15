package com.lookahead.domain.access;

import com.lookahead.domain.persistence.PersistenceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** DLV-970: the PostgreSQL stores of the access package that need the transaction manager, registered in postgresql mode only. */
@Configuration
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
class JdbcStaffAliasStoreBean {
    @Bean StaffAliasStore jdbcStaffAliasStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new JdbcStaffAliasStore(jdbc, manager); }
}
