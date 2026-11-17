package com.lookahead.domain;

import com.lookahead.domain.persistence.dynamodb.DynamoReadiness;
import com.lookahead.domain.review.DynamoAuthorReviewStore;
import com.lookahead.domain.review.platform.ReviewRecordNumbers;
import com.lookahead.domain.review.platform.ReviewStatusHistory;
import com.lookahead.domain.sweeps.MaintenanceJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.context.servlet.AnnotationConfigServletWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import static org.assertj.core.api.Assertions.*;

/**
 * DLV-970 (lane D): the whole application context in dynamodb mode. The shipped scan (the same packages and
 * auto-configuration as {@link DomainApiApplication}, servlet web conditions on a mock servlet context) is refreshed
 * with every bean definition lazy. The first test asserts that the refresh succeeds (lane G: every lane integrated) and
 * on definitions: no data source, JDBC or Flyway bean (PostgreSQL retired 2026-11-17), exactly one {@code PlatformTransactionManager}
 * (lane B's {@code DynamoOwnerTransactionManager}, which every {@code @Transactional} service proxies through), and the
 * store's own beans, which must also build. The second test, enabled only with {@code DLV970_LOCAL_DYNAMODB_ENDPOINT}
 * (for example {@code http://127.0.0.1:4400}), builds every bean one by one from the bean factory, requires that none
 * fails and writes the report to {@code target/dynamodb-context-report.txt}. Building beans opens no connection: the
 * endpoint is never touched and the readiness probe is never called.
 */
class DynamoModeApplicationContextTest {
    static final String ENDPOINT = "DLV970_LOCAL_DYNAMODB_ENDPOINT";
    static final List<String> LANE_D = List.of("accountErrorHandler", "domainStorageFailureFilter", "dynamoDbClient", "dynamodb", "registeredMaintenanceJobs",
            "studyPlanDocumentMaintenanceJob", "retiredStudyPlanDocumentJob", "reviewRecordNumbers", "reviewStatusHistory", "dynamoAuthorReviewStore");
    @TempDir Path directory;

    /**
     * The shipped application's scan ({@link DomainApiApplication}: the same packages, the same auto-configuration
     * exclusion) minus the {@code @Configuration} classes nested in test classes, which share the scanned packages.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.ComponentScan(basePackages = {"com.lookahead.domain", "com.lookahead.learning.content"}, excludeFilters = {
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.CUSTOM, classes = org.springframework.boot.context.TypeExcludeFilter.class),
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.CUSTOM, classes = org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter.class),
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = DomainApiApplication.class),
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX, pattern = ".*Test(\\$.*)?$")})
    static class ShippedApplication { }

    /**
     * A refresh that fails tears the bean factory down (singletons, the environment beans, the active flag). To build
     * the remaining beans one by one for the report, the factory is prepared again the way a refresh prepares it.
     */
    static final class RecoverableContext extends AnnotationConfigServletWebApplicationContext {
        void recover() throws Exception {
            prepareBeanFactory(getBeanFactory());
            postProcessBeanFactory(getBeanFactory());
            var active = org.springframework.context.support.AbstractApplicationContext.class.getDeclaredField("active");
            active.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) active.get(this)).set(true);
            // The placeholder configurer that ran during the refresh was destroyed with the singletons; @ConfigurationProperties beans need an applied one.
            for (var configurer : getBeanFactory().getBeansOfType(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class).values())
                configurer.postProcessBeanFactory(getBeanFactory());
        }
    }

    /** The refreshed (or refused and recovered) context and its bean factory, which answers definitions and builds beans either way. */
    static final class Shipped implements AutoCloseable {
        final RecoverableContext context = new RecoverableContext();
        Throwable startupFailure;
        Shipped(String endpoint, Path directory) throws Exception {
            Path catalog = Path.of("src/test/resources/accounts/catalog.json").toAbsolutePath();
            Path reviews = Files.createDirectories(directory.resolve("reviews"));
            context.setServletContext(new MockServletContext());
            TestPropertyValues.of("spring.profiles.active=accounts,resource,local,local-test",
                    "spring.config.import=optional:configtree:/nonexistent-domain-dynamodb-context/",
                    "LOOKAHEAD_ENVIRONMENT=local", "LOOKAHEAD_DYNAMODB_ENDPOINT=" + endpoint,
                    "LOOKAHEAD_OAUTH_ISSUER=http://127.0.0.1:4380", "LOOKAHEAD_IDENTITY_UPSTREAM=http://127.0.0.1:4380",
                    "APP_OAUTH_CLIENT_ID=lookahead-web-gateway", "app.identity.verifier-secret=synthetic-verifier-secret-for-context-test",
                    "LOOKAHEAD_DOMAIN_GATEWAY_SECRET=synthetic-domain-gateway-secret-at-least-32",
                    "LOOKAHEAD_DOMAIN_AUTHOR_ENABLED=true", "LOOKAHEAD_DOMAIN_SEED_ENABLED=true",
                    "LOOKAHEAD_ACCOUNT_CATALOG=" + catalog, "LOOKAHEAD_PLATFORM_REVIEW_DIRECTORY=" + reviews,
                    // springdoc's @Lazy(false) beans would pull MVC's conversion service, and with it every Converter bean, into the refresh.
                    "springdoc.api-docs.enabled=false").applyTo(context);
            new ConfigDataApplicationContextInitializer().initialize(context);
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
            context.register(ShippedApplication.class);
            try { context.refresh(); } catch (RuntimeException failure) { startupFailure = failure; context.recover(); }
        }
        DefaultListableBeanFactory factory() { return context.getDefaultListableBeanFactory(); }
        String[] names(Class<?> type) { return factory().getBeanNamesForType(type, true, false); }
        /** Bean names of a class that may not be on the class path (then there are none). */
        static boolean shippedClass(String className) { try { Class.forName(className); return true; } catch (ClassNotFoundException absent) { return false; } }
        String[] names(String className) {
            try { return names(Class.forName(className)); } catch (ClassNotFoundException absent) { return new String[0]; }
        }
        @Override public void close() { if (context.isActive()) context.close(); }
    }

    @Test void theContextDefinesNoDataSourceJdbcTransactionManagerOrFlywayBeanAndBuildsTheStoresOwn() throws Exception {
        for (String retired : List.of("org.flywaydb.core.Flyway", "org.postgresql.Driver", "org.springframework.jdbc.core.JdbcOperations", "com.zaxxer.hikari.HikariDataSource"))
            assertThat(Shipped.shippedClass(retired)).as(retired + " is off the class path with PostgreSQL").isFalse();
        try (var shipped = new Shipped("http://127.0.0.1:4400", directory)) {
            assertThat(shipped.factory().getBeanDefinitionCount()).as("the shipped scan was registered").isGreaterThan(100);
            assertThat(shipped.startupFailure).as("the context refreshes").isNull();
            for (String absent : List.of("com.lookahead.learning.content.config.AccountDatabaseConfiguration", "com.lookahead.domain.DomainDatabaseHealthIndicator",
                    "com.lookahead.domain.review.JdbcAuthorReviewStore", "com.lookahead.domain.persistence.PersistenceStore$Check"))
                assertThat(shipped.names(absent)).as(absent).isEmpty();
            // DLV-970 lane G: the one transaction manager is lane B's owner-unit manager; no JDBC manager exists.
            assertThat(shipped.names("org.springframework.jdbc.support.JdbcTransactionManager")).isEmpty();
            assertThat(shipped.names("org.springframework.jdbc.datasource.DataSourceTransactionManager")).isEmpty();
            assertThat(shipped.names(PlatformTransactionManager.class)).containsExactly("dynamoOwnerTransactionManager");
            assertThat(shipped.factory().getBean(PlatformTransactionManager.class)).isInstanceOf(com.lookahead.learning.content.repository.DynamoOwnerTransactionManager.class);
            assertThat(shipped.names("org.springframework.transaction.annotation.AnnotationTransactionAttributeSource")).as("@Transactional is proxied").isNotEmpty();
            assertThat(shipped.names("org.flywaydb.core.Flyway")).isEmpty();
            for (String name : shipped.factory().getBeanDefinitionNames())
                assertThat(name).as("no Flyway or data source auto-configuration").doesNotContainIgnoringCase("flyway").doesNotContain("DataSourceAutoConfiguration");
            assertThat(shipped.names(DynamoDbClient.class)).hasSize(1);
            assertThat(shipped.names(DynamoReadiness.class)).containsExactly("dynamodb");
            assertThat(shipped.names(DynamoAuthorReviewStore.class)).hasSize(1);
            assertThat(shipped.names(ReviewRecordNumbers.class)).containsExactly("reviewRecordNumbers");
            assertThat(shipped.names(ReviewStatusHistory.class)).containsExactly("reviewStatusHistory");
            assertThat(shipped.factory().getBean(ReviewRecordNumbers.class)).isInstanceOf(ReviewRecordNumbers.Dynamo.class);
            assertThat(shipped.factory().getBean(ReviewStatusHistory.class)).isInstanceOf(ReviewStatusHistory.Dynamo.class);
            assertThat(shipped.names(MaintenanceJobs.Job.class)).containsExactlyInAnyOrder("studyPlanDocumentMaintenanceJob", "retiredStudyPlanDocumentJob", "dynamoAccountErasureJob", "dynamoSignInRecordRetirement");
            // Lane G's cross-lane adapters are the one implementation of every declared interface in dynamodb mode.
            for (Class<?> declared : List.of(com.lookahead.domain.membership.AccountGrantsLookup.class, com.lookahead.domain.testaccounts.AccountIdentityLookup.class,
                    com.lookahead.domain.testaccounts.AccountRecordsMaintenance.class, com.lookahead.learning.content.repository.MembershipTopicSource.class,
                    com.lookahead.domain.access.MembershipErasure.class, com.lookahead.domain.access.MembershipFacts.class, com.lookahead.domain.access.TestAccountRegistryLookup.class,
                    com.lookahead.domain.access.LearningDataErasure.class, com.lookahead.domain.testaccounts.PlanErasure.class, com.lookahead.domain.testaccounts.PlanActivityLookup.class,
                    com.lookahead.learning.content.repository.PlanMaintenanceJobs.class))
                assertThat(shipped.names(declared)).as(declared.getSimpleName()).hasSize(1);
            assertThat(shipped.names(MaintenanceJobs.class)).containsExactly("registeredMaintenanceJobs");
            // Lane D's own beans build today, whatever the other lanes still need for the refresh.
            for (String name : LANE_D) assertThat(shipped.factory().getBean(name)).as(name).isNotNull();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = ENDPOINT, matches = ".+")
    void everyBeanIsBuiltAndTheOnesThatStillFailToWireAreReported() throws Exception {
        try (var shipped = new Shipped(System.getenv(ENDPOINT), directory)) {
            var failures = new TreeMap<String, String>();
            var causes = new TreeMap<String, List<String>>();
            for (String name : shipped.factory().getBeanDefinitionNames()) {
                try { shipped.factory().getBean(name); }
                catch (BeansException failure) {
                    String bean = name; Throwable at = failure;
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof BeanCreationException creation && creation.getBeanName() != null) bean = creation.getBeanName();
                        at = cause;
                    }
                    String root = at.getClass().getSimpleName() + ": " + firstLine(at.getMessage());
                    failures.put(name, bean.equals(name) ? root : "needs " + bean + " (" + root + ")");
                    causes.computeIfAbsent(bean + " -> " + root, key -> new ArrayList<>()).add(name);
                }
            }
            var report = new StringBuilder("DLV-970 dynamodb-mode context: " + shipped.factory().getBeanDefinitionCount() + " bean definitions, " + failures.size() + " fail to build\n");
            if (shipped.startupFailure != null) {
                Throwable root = shipped.startupFailure;
                while (root.getCause() != null) root = root.getCause();
                report.append("Refresh failed: ").append(firstLine(shipped.startupFailure.getMessage())).append("\n  root: ").append(root.getClass().getSimpleName()).append(": ").append(firstLine(root.getMessage())).append('\n');
            } else report.append("Refresh succeeded\n");
            report.append("\nRoot causes (bean -> failure: beans that need it)\n");
            causes.forEach((cause, dependents) -> report.append(cause).append(": ").append(String.join(", ", dependents)).append('\n'));
            report.append("\nEvery failing bean\n");
            failures.forEach((name, why) -> report.append(name).append(": ").append(why).append('\n'));
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/dynamodb-context-report.txt"), report);
            System.out.println(report);
            assertThat(failures.keySet()).doesNotContainAnyElementsOf(LANE_D);
            assertThat(shipped.startupFailure).as("the refresh succeeded").isNull();
            assertThat(failures).as("every bean of the shipped scan builds in dynamodb mode").isEmpty();
            // The sweep runner holds the registered maintenance jobs: lane D's two, lane B's purge and lane A's erasure.
            assertThat(shipped.factory().getBean(MaintenanceJobs.class).jobs()).extracting(MaintenanceJobs.Job::name)
                    .containsExactlyInAnyOrder("study-plan-documents", "retired-study-plan-documents", "plan-purge", "account-erasure", "sign-in-records");
        }
    }

    static String firstLine(String message) {
        if (message == null) return "";
        int end = message.indexOf('\n');
        return (end < 0 ? message : message.substring(0, end)).replaceAll("(?i)password=\\S+", "password=[redacted]");
    }
}
