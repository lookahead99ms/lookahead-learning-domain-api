package com.lookahead.domain.cloud;

import com.lookahead.domain.testaccounts.TestAccountRoster;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DLV-969 S36 identity helpers on a mocked JdbcTemplate; the SQL itself is proven by CloudIdentityLinkingDatabaseTest. */
class CloudIdentitiesTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final UUID account=UUID.randomUUID();

    @Test void theProviderComesFromTheIssuerAndTheCognitoUsername(){
        assertThat(CloudIdentities.provider(TestAccountRoster.TEST_ISSUER,"Google_1")).isEqualTo("test");
        assertThat(CloudIdentities.provider("https://issuer.invalid","Google_1")).isEqualTo("google");
        assertThat(CloudIdentities.provider("https://issuer.invalid","google_1")).isEqualTo("google");
        assertThat(CloudIdentities.provider("https://issuer.invalid","googler@example.invalid")).isEqualTo("cognito");
        assertThat(CloudIdentities.provider("https://issuer.invalid",null)).isEqualTo("cognito");
    }
    @Test void everyUsernameOfTheAccountAndItsOwnOnlyWhileItsOwnIdentityHasNoRow(){
        // Its own identity has a row (here or, after a merge, under the kept account): only the rows count.
        assertThat(CloudIdentities.usernames(jdbc,account,"own@example.invalid")).isEmpty();
        when(jdbc.queryForObject(contains("NOT EXISTS"),eq(Boolean.class),eq(account))).thenReturn(true);
        assertThat(CloudIdentities.usernames(jdbc,account,"own@example.invalid")).containsExactly("own@example.invalid");
        when(jdbc.queryForList(contains("FROM cloud_identities"),eq(String.class),eq(account))).thenReturn(List.of("Google_1"));
        assertThat(CloudIdentities.usernames(jdbc,account,"own@example.invalid")).containsExactly("own@example.invalid","Google_1");
        when(jdbc.queryForList(contains("FROM cloud_identities"),eq(String.class),eq(account))).thenReturn(List.of("Google_1","own@example.invalid"));
        assertThat(CloudIdentities.usernames(jdbc,account,"own@example.invalid")).containsExactly("Google_1","own@example.invalid");
        assertThat(CloudIdentities.usernames(jdbc,account,null)).containsExactly("Google_1","own@example.invalid");
    }
    @Test void emailsAreComparedAndLockedInLowerCaseAndTheOfferNeedsAnEmailAndAnAnswer(){
        assertThat(CloudIdentities.normalizeEmail("Paula.X@Gmail.COM")).isEqualTo("paula.x@gmail.com");
        assertThat(CloudIdentities.mergeable(jdbc,account,null)).isFalse();assertThat(CloudIdentities.mergeable(jdbc,account," ")).isFalse();
        verifyNoInteractions(jdbc);
        assertThat(CloudIdentities.mergeable(jdbc,account,"a@gmail.invalid")).isFalse();
        assertThat(CloudIdentities.signInMethods(jdbc,account)).isEmpty();
    }
    @Test void theDuplicateCountNeedsAnEmailAndTreatsNoAnswerAsZero(){
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,account,null)).isZero();
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,account," ")).isZero();
        verifyNoInteractions(jdbc);
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,account,"a@gmail.invalid")).isZero();
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("a@gmail.invalid"),eq("a@gmail.invalid"),eq(account))).thenReturn(3);
        assertThat(CloudIdentities.sameEmailAccounts(jdbc,account,"a@gmail.invalid")).isEqualTo(3);
        // Test accounts and deleted accounts are never counted; disabled ones are.
        assertThat(CloudIdentities.sameEmailAccounts("?","?")).contains(TestAccountRoster.TEST_ISSUER).contains("s.state='removed'");
        assertThat(CloudIdentities.candidates()).contains("m.enabled").contains("cloud_account_states");
    }
}
