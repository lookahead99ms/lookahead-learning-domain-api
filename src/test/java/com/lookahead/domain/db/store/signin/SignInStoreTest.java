package com.lookahead.domain.db.store.signin;

import com.lookahead.domain.service.signin.SignInService;
import com.lookahead.domain.model.SignInIdentity;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lookahead.domain.db.store.dynamodb.DynamoItems;
import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.exception.AccountFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** SignInService on the store on a mocked client: what is refused before any write, and how the store retries a lost revision race, then passes the conflict on. */
class SignInStoreTest {
    private final DynamoDbClient client = mock(DynamoDbClient.class, withSettings().mockMaker(MockMakers.PROXY));
    private final DynamoTableNames tables = DynamoTableNames.local();
    private final Instant now = Instant.parse("2026-11-10T10:00:00Z");
    private final SignInService signInService = new SignInService(new SignInStore(client, tables, Clock.fixed(now, ZoneOffset.UTC)), null);
    private final SignInIdentity identity = new SignInIdentity("https://issuer.test/pool", "subject", "family", "user", "User", now, "user@example.invalid");
    private final String proof = "p".repeat(43);

    private static GetItemResponse item(Map<String, AttributeValue> item) { return GetItemResponse.builder().item(item).build(); }
    private Map<String, AttributeValue> control(UUID id, boolean admitted, boolean enabled, String state, boolean pending) {
        return AccountItems.item(AccountItems.account(id), "CONTROL", AccountItems.attrs("issuer", DynamoItems.s(identity.issuer()), "subject", DynamoItems.s(identity.subject()),
                "username", DynamoItems.s("user"), "displayName", DynamoItems.s("User"), "verifiedEmail", DynamoItems.s("user@example.invalid"), "admitted", DynamoItems.bool(admitted),
                "enabled", DynamoItems.bool(enabled), "author", DynamoItems.bool(false), "managedTest", DynamoItems.bool(false), "credentialChangePending", DynamoItems.bool(pending),
                "accountState", DynamoItems.s(state), "revision", DynamoItems.n(3), "authGeneration", DynamoItems.n(0), "requestedAt", DynamoItems.s(now.toString())));
    }
    private void known(UUID id, Map<String, AttributeValue> control) {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            String sk = request.key().get("sk").s();
            if (sk.equals("BINDING") && request.key().get("pk").s().startsWith("IDENTITY#")) return item(AccountItems.item(request.key().get("pk").s(), "BINDING", AccountItems.attrs("accountId", DynamoItems.s(id.toString()),
                    "identityId", DynamoItems.s(UUID.randomUUID().toString()), "provider", DynamoItems.s("cognito"), "username", DynamoItems.s("user"), "verifiedEmail", DynamoItems.s("user@example.invalid"), "linkedAt", DynamoItems.s(now.toString()))));
            if (sk.equals("CONTROL")) return item(control);
            return item(Map.of());
        });
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
    }

    @Test void aMalformedProofAndAnUnknownIdentityOutsideFirstContactAreRefusedWithoutAnyWrite() {
        assertThatThrownBy(() -> signInService.admit(identity, null)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.status()).isEqualTo(401));
        verifyNoInteractions(client);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(item(Map.of()));
        assertThatThrownBy(() -> signInService.authenticate(identity, proof)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("ACCOUNT_ADMISSION_REQUIRED"));
        UUID id = UUID.randomUUID();
        known(id, control(id, true, true, "ACTIVE", false));
        assertThatThrownBy(() -> signInService.authenticate(identity, "short")).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.status()).isEqualTo(401));
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
        assertThatThrownBy(() -> signInService.rename(identity, proof, "x".repeat(81))).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("INVALID_SIGN_IN_LABEL"));
        assertThatThrownBy(() -> signInService.preferences(identity, proof, "sepia")).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("INVALID_PREFERENCES"));
        assertThatThrownBy(() -> signInService.links(identity, proof, Map.of())).isInstanceOf(AccountFailure.class);
    }

    @Test void disabledRemovedWaitingAndPendingCredentialAccountsAreRefusedBeforeAnySessionRead() {
        UUID id = UUID.randomUUID();
        known(id, control(id, true, false, "ACTIVE", false));
        assertThatThrownBy(() -> signInService.authenticate(identity, proof)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("AUTHENTICATION_REQUIRED"));
        known(id, control(id, true, true, "REMOVED", false));
        assertThatThrownBy(() -> signInService.inventory(identity, proof)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("AUTHENTICATION_REQUIRED"));
        known(id, control(id, false, true, "ACTIVE", false));
        assertThatThrownBy(() -> signInService.terminate(identity, proof)).isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.code()).isEqualTo("AUTHENTICATION_REQUIRED"));
        known(id, control(id, true, true, "ACTIVE", true));
        assertThatThrownBy(() -> signInService.authenticate(identity, proof)).isInstanceOfSatisfying(AccountFailure.class, f -> { assertThat(f.status()).isEqualTo(503); assertThat(f.code()).isEqualTo("CREDENTIAL_CHANGE_PENDING"); });
        verify(client, never()).query(any(QueryRequest.class));
        verify(client, never()).executeTransaction(any(ExecuteTransactionRequest.class));
    }

    @Test void aLostRevisionRaceIsRetriedThreeTimesThenSurfacedAsTheConflict() {
        UUID id = UUID.randomUUID();
        known(id, control(id, true, true, "ACTIVE", false));
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build());
        assertThatThrownBy(() -> signInService.admit(identity, proof)).isInstanceOf(DynamoWrites.Conflict.class);
        verify(client, times(SignInStore.ATTEMPTS)).executeTransaction(any(ExecuteTransactionRequest.class));
        // Anything else from the store is surfaced at once as unavailable.
        reset(client);
        known(id, control(id, true, true, "ACTIVE", false));
        when(client.executeTransaction(any(ExecuteTransactionRequest.class))).thenThrow(SdkClientException.builder().message("down").build());
        assertThatThrownBy(() -> signInService.admit(identity, proof)).isInstanceOf(DynamoWrites.Unavailable.class);
        verify(client, times(1)).executeTransaction(any(ExecuteTransactionRequest.class));
    }
}
