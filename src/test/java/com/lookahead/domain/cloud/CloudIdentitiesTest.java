package com.lookahead.domain.cloud;

import com.lookahead.domain.testaccounts.TestAccountRoster;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** DLV-969 S36 identity rules shared by the registry and the import; the store itself is proven by DynamoCloudSignInRegistryDatabaseTest. */
class CloudIdentitiesTest {
    @Test void theProviderComesFromTheIssuerAndTheCognitoUsername(){
        assertThat(CloudIdentities.provider(TestAccountRoster.TEST_ISSUER,"Google_1")).isEqualTo("test");
        assertThat(CloudIdentities.provider("https://issuer.invalid","Google_1")).isEqualTo("google");
        assertThat(CloudIdentities.provider("https://issuer.invalid","google_1")).isEqualTo("google");
        assertThat(CloudIdentities.provider("https://issuer.invalid","googler@example.invalid")).isEqualTo("cognito");
        assertThat(CloudIdentities.provider("https://issuer.invalid",null)).isEqualTo("cognito");
    }
    @Test void emailsAreComparedInLowerCase(){
        assertThat(CloudIdentities.normalizeEmail("Paula.X@Gmail.COM")).isEqualTo("paula.x@gmail.com");
        assertThat(CloudIdentities.normalizeEmail("plain@example.invalid")).isEqualTo("plain@example.invalid");
    }
}
