package com.lookahead.domain.publication;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class CloudRuntimeGuardTest {
    @TempDir Path directory;
    MockEnvironment valid() throws Exception {
        var store = KeyStore.getInstance(Path.of(System.getProperty("java.home"),"lib","security","cacerts").toFile(), "changeit".toCharArray());
        var certificate = store.getCertificate(store.aliases().nextElement());
        Path pem = directory.resolve("ca.pem");
        Files.writeString(pem,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(certificate.getEncoded())+"\n-----END CERTIFICATE-----\n");
        return new MockEnvironment().withProperty("app.deployment-environment","dev").withProperty("app.publication.region","us-east-2")
                .withProperty("server.address","127.0.0.1")
                .withProperty("spring.datasource.password","synthetic-test-password")
                .withProperty("spring.datasource.url","jdbc:postgresql://synthetic.abc.us-east-2.rds.amazonaws.com/lookahead_platform?sslmode=verify-full&sslrootcert="+pem);
    }
    @Test void injectedValueAndExplicitRdsTlsPassWithoutCallingAws() throws Exception { CloudRuntimeGuard.validate(valid()); }
    @Test void rejectsMissingSecretArnInsteadOfValueStaticCredentialsAndLocalDatabase() throws Exception {
        for (String value : new String[]{"", "arn:aws:secretsmanager:us-east-2:123456789012:secret:example", "${MISSING}"}) {
            var env = valid().withProperty("spring.datasource.password",value);
            assertThatThrownBy(()->CloudRuntimeGuard.validate(env)).hasMessage("Cloud database requires an injected secret value");
        }
        var credentials = valid().withProperty("AWS_ACCESS_KEY_ID","synthetic-invalid");
        assertThatThrownBy(()->CloudRuntimeGuard.validate(credentials)).isInstanceOf(IllegalStateException.class);
        var local = valid().withProperty("spring.datasource.url","jdbc:postgresql://localhost/domain");
        assertThatThrownBy(()->CloudRuntimeGuard.validate(local)).isInstanceOf(IllegalStateException.class);
        var insecure = valid(); insecure.withProperty("spring.datasource.url",insecure.getProperty("spring.datasource.url").replace("verify-full","require"));
        assertThatThrownBy(()->CloudRuntimeGuard.validate(insecure)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void cloudRejectsPlaintextTaskInterfaceBinding(String mode) throws Exception {
        var environment = valid().withProperty("app.deployment-environment",mode);
        assertThatCode(()->CloudRuntimeGuard.validate(environment)).doesNotThrowAnyException();
        for (String address : new String[]{"", "0.0.0.0", "::", "::1", "localhost", "10.0.1.12"}) {
            environment.withProperty("server.address",address);
            assertThatThrownBy(()->CloudRuntimeGuard.validate(environment))
                    .hasMessage("Cloud Domain HTTP must bind to 127.0.0.1 behind Service Connect TLS");
        }
    }
}
