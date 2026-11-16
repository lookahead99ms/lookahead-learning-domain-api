package com.lookahead.domain.persistence;

import com.lookahead.domain.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class PersistenceStoreTest {
    @Test void postgresqlIsTheDefaultAndDynamodbMustBeNamed() {
        assertThat(PersistenceStore.select(new MockEnvironment())).isEqualTo(PersistenceStore.POSTGRESQL);
        assertThat(PersistenceStore.select(new MockEnvironment().withProperty(PersistenceStore.PROPERTY, "postgresql"))).isEqualTo(PersistenceStore.POSTGRESQL);
        assertThat(PersistenceStore.select(new MockEnvironment().withProperty(PersistenceStore.PROPERTY, "dynamodb"))).isEqualTo(PersistenceStore.DYNAMODB);
        assertThat(new PersistenceStore.Check(new MockEnvironment().withProperty(PersistenceStore.PROPERTY, "dynamodb")).store()).isEqualTo(PersistenceStore.DYNAMODB);
        assertThat(PersistenceStore.DYNAMODB.value()).isEqualTo("dynamodb");
    }

    /** P6: no trimming either, because {@code @ConditionalOnProperty} does not trim: " dynamodb " would pass here and match neither configuration. */
    @Test void anyOtherValueRefusesStartupNamingTheSettingNotTheValue() {
        for (String value : new String[]{"postgres", "DynamoDB", "", "both", " dynamodb ", "postgresql "}) {
            try (var logs = LogCapture.start("lookahead.startup")) {
                var environment = new MockEnvironment().withProperty(PersistenceStore.PROPERTY, value);
                assertThatThrownBy(() -> new PersistenceStore.Check(environment)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Domain requires an explicit persistence store");
                assertThat(logs.messages()).singleElement().asString().contains("Startup refused: " + PersistenceStore.SETTING)
                        .contains("must be postgresql or dynamodb").doesNotContain("both");
            }
        }
    }
}
