package com.lookahead.domain.persistence;

import com.lookahead.domain.StartupChecks;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * DLV-970: the one store this Domain process reads and writes. PostgreSQL is the default and today's running
 * backend; DynamoDB is selected explicitly with {@code LOOKAHEAD_PERSISTENCE_STORE=dynamodb}. The value is checked
 * once at startup and any other value stops the app before it takes traffic: a typo must never start a Domain
 * with no store at all (both configurations are conditional on this property), and a DynamoDB failure never
 * falls back to PostgreSQL.
 */
public enum PersistenceStore {
    POSTGRESQL("postgresql"), DYNAMODB("dynamodb");

    public static final String PROPERTY = "app.persistence.store";
    public static final String SETTING = "app.persistence.store (LOOKAHEAD_PERSISTENCE_STORE)";

    private final String value;

    PersistenceStore(String value) { this.value = value; }

    public String value() { return value; }

    /**
     * The configured store; a missing value means PostgreSQL, anything unknown refuses startup. The value is matched
     * exactly, without trimming: {@code @ConditionalOnProperty} does not trim either, so {@code " dynamodb "} would pass
     * a trimmed check here and then start a Domain with neither configuration.
     */
    public static PersistenceStore select(Environment environment) {
        String configured = environment.getProperty(PROPERTY, POSTGRESQL.value);
        for (PersistenceStore store : values()) if (store.value.equals(configured)) return store;
        throw StartupChecks.refuse(SETTING, "PERSISTENCE_STORE_INVALID", "must be postgresql or dynamodb",
                "Domain requires an explicit persistence store");
    }

    /** Fails the context on an unknown store, whichever configurations happened to match. */
    @Component
    public static class Check {
        private final PersistenceStore store;

        public Check(Environment environment) { this.store = select(environment); }

        public PersistenceStore store() { return store; }
    }
}
