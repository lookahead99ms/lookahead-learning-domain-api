package com.lookahead.domain.service.access;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.lookahead.domain.db.store.access.StaffAccess;
import com.lookahead.domain.db.store.access.StaffAlias;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.LogCapture;
import com.lookahead.domain.security.token.AccountPrincipal;
import java.net.ConnectException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * DLV-969 (owner, 2026-11-04): when the owner check fails, role labels are shown (fail closed) and
 * the log says why.
 */
class StaffAliasServiceIdentityCheckTest {
  final StaffAccessService access = mock(StaffAccessService.class);
  final StaffAliasService aliases =
      new StaffAliasService(
          mock(StaffAlias.class, withSettings().mockMaker(MockMakers.SUBCLASS)),
          access,
          mock(StaffAccess.class, withSettings().mockMaker(MockMakers.SUBCLASS)));
  final AccountPrincipal viewer =
      new AccountPrincipal(UUID.randomUUID(), "owner@example.invalid", "Owner Person", true);

  @Test
  void aFailedOwnerCheckShowsLabelsAndIsLoggedOnceWhileARefusalStaysQuiet() {
    try (var log = LogCapture.start()) {
      when(access.permissions(viewer))
          .thenThrow(new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in"));
      assertThat(aliases.seesIdentities(viewer)).isFalse();
      assertThat(log.lines()).isEmpty();
      reset(access);
      when(access.permissions(viewer))
          .thenThrow(SdkClientException.create("db.internal", new ConnectException("down")));
      assertThat(aliases.seesIdentities(viewer)).isFalse();
      assertThat(aliases.seesIdentities(viewer)).isFalse();
      var line = log.only("alias.identities");
      assertThat(line.getLevel()).isEqualTo(Level.WARN);
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Could not check whether account "
                  + viewer.accountId()
                  + " may see identities (SdkClientException < ConnectException); showing role"
                  + " labels instead");
      assertThat(LogCapture.keys(line)).containsEntry("reason", "PERMISSIONS_UNAVAILABLE");
      log.containsNone("owner@example.invalid", "Owner Person", "db.internal");
    }
  }
}
