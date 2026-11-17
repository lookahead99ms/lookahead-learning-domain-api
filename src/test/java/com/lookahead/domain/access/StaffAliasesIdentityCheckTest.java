package com.lookahead.domain.access;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.LogCapture;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DLV-969 (owner, 2026-11-04): when the owner check fails, role labels are shown (fail closed) and the log says why. */
class StaffAliasesIdentityCheckTest {
    final StaffAccessService access = mock(StaffAccessService.class);
    final StaffAliases aliases = new StaffAliases(mock(StaffAliasStore.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)), access, mock(StaffAccessRepository.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)));
    final AccountPrincipal viewer = new AccountPrincipal(UUID.randomUUID(), "owner@example.invalid", "Owner Person", true);

    @Test void aFailedOwnerCheckShowsLabelsAndIsLoggedOnceWhileARefusalStaysQuiet() {
        try (var log = LogCapture.start()) {
            when(access.permissions(viewer)).thenThrow(new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in"));
            assertThat(aliases.seesIdentities(viewer)).isFalse();
            assertThat(log.lines()).isEmpty();
            reset(access);
            when(access.permissions(viewer)).thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("db.internal", new java.net.ConnectException("down")));
            assertThat(aliases.seesIdentities(viewer)).isFalse();
            assertThat(aliases.seesIdentities(viewer)).isFalse();
            var line = log.only("alias.identities");
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).isEqualTo("Could not check whether account " + viewer.accountId()
                    + " may see identities (SdkClientException < ConnectException); showing role labels instead");
            assertThat(LogCapture.keys(line)).containsEntry("reason", "PERMISSIONS_UNAVAILABLE");
            log.containsNone("owner@example.invalid", "Owner Person", "db.internal");
        }
    }
}
