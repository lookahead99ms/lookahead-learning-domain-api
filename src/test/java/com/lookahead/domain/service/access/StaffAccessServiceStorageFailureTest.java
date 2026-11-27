package com.lookahead.domain.service.access;

import com.lookahead.domain.dto.StaffAccessChange;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.exception.StorageFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.AccountCatalogService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DLV-1016: the service tells the store's two outcomes apart by the one store-level type, not by the store's technology types. */
class StaffAccessServiceStorageFailureTest {
    private final AccountPrincipal owner = new AccountPrincipal(UUID.randomUUID(), "owner@lookahead.test", "Owner", true);
    private final StaffAccess assignments = mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final StaffAccessService access;

    StaffAccessServiceStorageFailureTest() {
        var policy = mock(AuthorPreviewLocalService.class); when(policy.allowed(owner)).thenReturn(true);
        var catalog = mock(AccountCatalogService.class); when(catalog.allTopicIds()).thenReturn(Set.of());
        access = new StaffAccessService(assignments, policy, catalog);
    }
    private static StaffAccessChange change() { return new StaffAccessChange(UUID.randomUUID(), UUID.randomUUID(), null, Set.of("tester"), Set.of(), false, 0); }

    @Test void aLostRevisionConditionAtTheSaveIsTheSame409AsAStaleRevision() {
        var lost = new DynamoWrites.Conflict(List.of("ConditionalCheckFailed"));
        assertThat(lost).isInstanceOf(StorageFailure.class); assertThat(lost.conflict()).isTrue();
        doThrow(lost).when(assignments).save(any(), any(), any(), any());
        assertThatThrownBy(() -> access.save(owner, change())).isInstanceOfSatisfying(AccountFailure.class, refused -> {
            assertThat(refused.status()).isEqualTo(409); assertThat(refused.code()).isEqualTo("ACCESS_ASSIGNMENT_CONFLICT");
            assertThat(refused.getMessage()).isEqualTo("Reload the current access assignment before saving.");
        });
    }

    @Test void anUnknownOutcomeAtTheSaveGoesOnAsItIsForTheHandlers503() {
        var unknown = new StorageFailure("the store did not answer") { @Override public boolean conflict() { return false; } };
        doThrow(unknown).when(assignments).save(any(), any(), any(), any());
        assertThatThrownBy(() -> access.save(owner, change())).isSameAs(unknown);
    }
}
