package com.lookahead.domain.publication;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** DLV-970 (2026-11-17): the cloud guard checks the task role, the endpoints and the binding; there is no database setting any more. */
class CloudRuntimeGuardTest {
    MockEnvironment valid() {
        return new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.publication.region","us-east-2")
                .withProperty("server.address","127.0.0.1");
    }
    @Test void aTaskWithoutStaticCredentialsOrDatabaseSettingsPassesWithoutCallingAws() { CloudRuntimeGuard.validate(valid()); }
    @Test void rejectsStaticCredentialsAndEndpointOverrides() {
        for (String name : new String[]{"AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_S3"}) {
            var credentials = valid().withProperty(name,"synthetic-invalid");
            assertThatThrownBy(()->CloudRuntimeGuard.validate(credentials)).as(name).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cloud application requires ECS task credentials and AWS service endpoints");
        }
    }
    @Test void aRetiredDatabaseSettingIsIgnored() {
        var leftover = valid().withProperty("spring.datasource.url","jdbc:postgresql://localhost/domain").withProperty("spring.datasource.password","short");
        assertThatCode(()->CloudRuntimeGuard.validate(leftover)).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void cloudRejectsPlaintextTaskInterfaceBinding(String mode) {
        var environment = valid().withProperty("app.deployment-environment",mode);
        assertThatCode(()->CloudRuntimeGuard.validate(environment)).doesNotThrowAnyException();
        for (String address : new String[]{"", "0.0.0.0", "::", "::1", "localhost", "10.0.1.12"}) {
            environment.withProperty("server.address",address);
            assertThatThrownBy(()->CloudRuntimeGuard.validate(environment))
                    .hasMessage("Cloud Domain HTTP must bind to 127.0.0.1 behind Service Connect TLS");
        }
    }
    @Test void theLowerCostProfileRequiresApplicationMtls() {
        var lowerCost = valid(); lowerCost.setActiveProfiles("lower-cost-dev");
        lowerCost.withProperty("server.ssl.enabled","true").withProperty("server.ssl.client-auth","need").withProperty("server.ssl.bundle","serving");
        assertThatCode(()->CloudRuntimeGuard.validate(lowerCost)).doesNotThrowAnyException();
        lowerCost.withProperty("server.ssl.client-auth","want");
        assertThatThrownBy(()->CloudRuntimeGuard.validate(lowerCost)).hasMessage("Lower-cost DEV requires application mTLS");
    }
}
