package com.lookahead.learning.content.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Runs the real JDBC transaction interceptor and HTTP advice without a database or server. */
@SpringJUnitConfig(AccountStorageOutageIntegrationTest.TestConfiguration.class)
@ActiveProfiles("accounts")
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class AccountStorageOutageIntegrationTest {
    private static final String DRIVER_DETAIL = "synthetic driver: SELECT private_notes; password=synthetic-do-not-expose";

    @Autowired private OutageController controller;
    @Autowired private TransactionalAccountService service;
    @Autowired private UnavailableDataSource dataSource;
    @Autowired private AccountErrorHandler accountErrors;
    @Autowired private GlobalExceptionHandler globalErrors;
    private MockMvc http;

    @BeforeEach
    void prepareHttpBoundary() {
        dataSource.connectionAttempts.set(0);
        dataSource.rollbackAttempts.set(0);
        dataSource.failDuringRollback = false;
        dataSource.failCommit = false;
        dataSource.rollbackSqlState = null;
        service.bodyExecutions().set(0);
        http = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(accountErrors, globalErrors).build();
    }

    @Test
    void transactionBeginOutageReturnsSafe503BeforeExecutingMutation() throws Exception {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        var result = http.perform(post("/api/v1/plans/test-transaction-outage")).andReturn();

        assertThat(dataSource.connectionAttempts).hasValue(1);
        assertThat(service.bodyExecutions()).hasValue(0);
        assertThat(result.getResolvedException()).isInstanceOf(CannotCreateTransactionException.class)
                .hasCauseInstanceOf(SQLTransientConnectionException.class);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getContentAsString())
                .contains("\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\"", "retain your draft", "retry the same request")
                .doesNotContain("SELECT", "private_notes", "password", "synthetic-do-not-expose",
                        "SQLTransientConnectionException", "CannotCreateTransactionException", "stackTrace");
    }

    @Test
    void unrelatedProgrammingFailureRemains500() throws Exception {
        var result = http.perform(post("/api/v1/plans/test-programming-failure")).andReturn();

        assertThat(dataSource.connectionAttempts).hasValue(0);
        assertThat(service.bodyExecutions()).hasValue(0);
        assertThat(result.getResolvedException()).isInstanceOf(IllegalStateException.class);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString())
                .contains("An unexpected error occurred")
                .doesNotContain("ACCOUNT_STORAGE_UNAVAILABLE", "synthetic programming detail");
    }

    @Test
    void queryFailureFollowedByRollbackFailureRemainsStorageUnavailable() throws Exception {
        dataSource.failDuringRollback = true;
        var result = http.perform(post("/api/v1/plans/test-rollback-outage")).andReturn();

        assertThat(dataSource.connectionAttempts).hasValue(1);
        assertThat(dataSource.rollbackAttempts).hasValue(1);
        assertThat(service.bodyExecutions()).hasValue(1);
        assertThat(result.getResolvedException()).isInstanceOf(TransactionSystemException.class);
        var failure = (TransactionSystemException) result.getResolvedException();
        assertThat(failure.getApplicationException()).isInstanceOf(DataAccessException.class);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString())
                .contains("ACCOUNT_STORAGE_UNAVAILABLE")
                .doesNotContain("SELECT", "private_notes", "password", "synthetic-do-not-expose", "rollback detail");
    }

    @Test
    void sqlState08RollbackFailureIsTranslatedAndReturns503() throws Exception {
        dataSource.failDuringRollback = true;
        dataSource.rollbackSqlState = "08003";
        var result = http.perform(post("/api/v1/plans/test-rollback-outage")).andReturn();
        assertThat(dataSource.rollbackAttempts).hasValue(1);
        assertThat(result.getResolvedException()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(((SQLException) result.getResolvedException().getCause()).getSQLState()).isEqualTo("08003");
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString()).contains("ACCOUNT_STORAGE_UNAVAILABLE")
                .doesNotContain("synthetic", "SQLException", "password", "private_notes");
    }

    @Test
    void commitFailureReturns503WithoutClaimingDurableMutation() throws Exception {
        dataSource.failDuringRollback = true;
        dataSource.failCommit = true;
        var result = http.perform(post("/api/v1/plans/test-transaction-outage")).andReturn();
        assertThat(dataSource.connectionAttempts).hasValue(1);
        assertThat(service.bodyExecutions()).hasValue(1);
        assertThat(result.getResolvedException()).isInstanceOf(TransactionSystemException.class);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString()).contains("ACCOUNT_STORAGE_UNAVAILABLE")
                .doesNotContain("mutation-executed", "synthetic", "SQLException", "password");
    }

    @Test
    void isolatedPreFixAdviceDemonstratesRollback500Regression() throws Exception {
        dataSource.failDuringRollback = true;
        var preFixHttp = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PreFixAccountErrorHandler(), globalErrors).build();
        var result = preFixHttp.perform(post("/api/v1/plans/test-rollback-outage")).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(TransactionSystemException.class);
        assertThat(((TransactionSystemException) result.getResolvedException()).getApplicationException())
                .isInstanceOf(DataAccessException.class);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
    }

    /** Reproduces the exact pre-fix mapping without modifying production advice. */
    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class PreFixAccountErrorHandler extends AccountErrorHandler {
        @Override
        @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
        ResponseEntity<?> unavailable(RuntimeException ex, HttpServletRequest request) {
            return super.unavailable(ex, request);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({AccountErrorHandler.class, GlobalExceptionHandler.class})
    static class TestConfiguration {
        @Bean UnavailableDataSource unavailableDataSource() { return new UnavailableDataSource(); }
        @Bean JdbcTransactionManager transactionManager(UnavailableDataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }
        @Bean TransactionalAccountService transactionalAccountService(UnavailableDataSource dataSource) {
            return new TransactionalAccountService(dataSource);
        }
        @Bean OutageController outageController(TransactionalAccountService service) { return new OutageController(service); }
    }

    static class UnavailableDataSource extends AbstractDataSource {
        final AtomicInteger connectionAttempts = new AtomicInteger();
        final AtomicInteger rollbackAttempts = new AtomicInteger();
        boolean failDuringRollback;
        boolean failCommit;
        String rollbackSqlState;
        @Override public Connection getConnection() throws SQLException {
            connectionAttempts.incrementAndGet();
            if (!failDuringRollback) throw new SQLTransientConnectionException(DRIVER_DETAIL);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getAutoCommit" -> true;
                        case "isReadOnly", "isClosed" -> false;
                        case "prepareStatement", "createStatement" -> throw new SQLTransientConnectionException(DRIVER_DETAIL, "08006");
                        case "rollback" -> {
                            rollbackAttempts.incrementAndGet();
                            // A driver without a translatable SQL state exercises Spring's
                            // TransactionSystemException fallback, preserving the application exception.
                            throw new SQLException("synthetic rollback detail", rollbackSqlState);
                        }
                        case "commit" -> {
                            if (failCommit) throw new SQLException("synthetic commit detail");
                            yield null;
                        }
                        case "setAutoCommit", "setReadOnly", "close", "clearWarnings" -> null;
                        case "toString" -> "Synthetic unavailable connection";
                        default -> throw new UnsupportedOperationException("Unexpected connection method: " + method.getName());
                    });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }

    static class TransactionalAccountService {
        private final JdbcTemplate jdbc;
        TransactionalAccountService(UnavailableDataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }
        final AtomicInteger bodyExecutions = new AtomicInteger();
        public AtomicInteger bodyExecutions() { return bodyExecutions; }
        @Transactional public String save() {
            bodyExecutions.incrementAndGet();
            return "mutation-executed";
        }
        @Transactional public String queryThenRollbackFailure() {
            bodyExecutions.incrementAndGet();
            return jdbc.queryForObject("SELECT private_notes", String.class);
        }
        public String programmingFailure() { throw new IllegalStateException("synthetic programming detail"); }
    }

    @RestController
    static class OutageController {
        private final TransactionalAccountService service;
        OutageController(TransactionalAccountService service) { this.service = service; }
        @PostMapping("/api/v1/plans/test-transaction-outage") public String save() { return service.save(); }
        @PostMapping("/api/v1/plans/test-rollback-outage") public String rollbackOutage() {
            return service.queryThenRollbackFailure();
        }
        @PostMapping("/api/v1/plans/test-programming-failure") public String programmingFailure() {
            return service.programmingFailure();
        }
    }
}
