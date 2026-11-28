package com.lookahead.domain.service.testaccounts;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.Mockito.*;

import com.lookahead.domain.controller.TestAccountController;
import com.lookahead.domain.controller.TestSessionController;
import com.lookahead.domain.db.store.testaccounts.TestAccountIdentities;
import com.lookahead.domain.dto.MembershipPurchaseDecision;
import com.lookahead.domain.dto.TestAccountCreate;
import com.lookahead.domain.dto.TestSessionStart;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.validator.TestAccountCreateRequestValidator;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * DLV-969 S35: the test-session service, the Gateway's internal routes and the roster route,
 * without a database (the filter on /api/**: TestSessionFilterTest).
 */
class TestSessionServiceTest {
  static final UUID ACCOUNT = UUID.fromString("2c7e9a4d-1b3f-4c5e-8d9a-0b1c2d3e4f5a");
  static final AccountPrincipal LEARNER =
      new AccountPrincipal(ACCOUNT, "learner07@lookahead.dev", "Learner07", true, false, null);

  /**
   * Answers like the real service: the Gateway secret is "secret", holder "sub-admin" may use
   * ACCOUNT only.
   */
  static final class Stub extends TestSessionService {
    RuntimeException failure;

    Stub() {
      super((TestAccountIdentities) null, null, "issuer", "secret");
    }

    @Override
    public AccountPrincipal actAs(String holder, UUID account) {
      if (failure != null) throw failure;
      if (account == null)
        throw new AccountFailure(422, "TEST_ACCOUNT_REQUIRED", "Choose a test account.");
      if (!"sub-admin".equals(holder))
        throw new AccountFailure(403, "TEST_SESSION_FORBIDDEN", "No");
      if (!ACCOUNT.equals(account)) throw new AccountFailure(404, "TEST_ACCOUNT_NOT_FOUND", "No");
      return LEARNER;
    }

    @Override
    public List<Usable> usable(String holder) {
      return "sub-admin".equals(holder)
          ? List.of(
              new Usable(
                  ACCOUNT,
                  LEARNER.username(),
                  LEARNER.displayName(),
                  List.of(),
                  "Free · no study plan"))
          : List.of();
    }
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  /**
   * DLV-1016: the service compares plain text; an absent secret is compared like any other and
   * never matches.
   */
  @Test
  void theSecretIsComparedAsPlainTextAndAnAbsentOneNeverMatches() {
    var sessions = new TestSessionService((TestAccountIdentities) null, null, "issuer", "secret");
    assertThat(sessions.secretMatches("secret")).isTrue();
    for (String wrong : new String[] {null, "", "secre", "secretx", "Secret", " secret", "sécret"})
      assertThat(sessions.secretMatches(wrong)).as("supplied " + wrong).isFalse();
    // An empty configured secret cannot be met by a call without the header only because start-up
    // refuses secrets under 32 characters;
    // the comparison itself treats "absent" as empty text.
    assertThat(
            new TestSessionService((TestAccountIdentities) null, null, "issuer", "")
                .secretMatches(null))
        .isTrue();
    assertThatThrownBy(
            () -> new TestSessionService((TestAccountIdentities) null, null, "issuer", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theGateHoldersAccountIsLookedUpForTheRecordAndNeverFailsTheSession() {
    var holder = UUID.fromString("7d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b");
    var asked = new ArrayList<String>();
    // A hand-written store, not a Mockito mock: other test classes switch Mockito's mock maker
    // inside the same JVM.
    var store =
        (TestAccountIdentities)
            Proxy.newProxyInstance(
                TestAccountIdentities.class.getClassLoader(),
                new Class<?>[] {TestAccountIdentities.class},
                (proxy, method, arguments) -> {
                  if (!method.getName().equals("accountOf"))
                    throw new UnsupportedOperationException(method.getName());
                  asked.add(arguments[0] + " " + arguments[1]);
                  if ("sub-broken".equals(arguments[1]))
                    throw new IllegalStateException("store down");
                  return "sub-admin".equals(arguments[1]) ? holder : null;
                });
    var sessions = new TestSessionService(store, null, "issuer", "secret");
    assertThat(sessions.holderAccountId("sub-admin")).isEqualTo(holder);
    assertThat(sessions.holderAccountId("sub-unknown")).isNull();
    assertThat(sessions.holderAccountId("sub-broken")).isNull();
    // No subject, a blank one or one longer than 256 characters is not asked of the store at all.
    for (String none : new String[] {null, "", "   ", "x".repeat(257)})
      assertThat(sessions.holderAccountId(none)).isNull();
    assertThat(asked)
        .containsExactly("issuer sub-admin", "issuer sub-unknown", "issuer sub-broken");
  }

  @Test
  void theInternalRoutesAnswerForTheGateHolder() {
    var routes = new TestSessionController(new Stub());
    var response = new MockHttpServletResponse();
    assertThat(routes.accounts("sub-admin", response).data()).asInstanceOf(LIST).hasSize(1);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(routes.accounts("sub-learner", new MockHttpServletResponse()).data())
        .asInstanceOf(LIST)
        .isEmpty();
    assertThat(
            routes
                .start("sub-admin", new TestSessionStart(ACCOUNT), new MockHttpServletResponse())
                .data())
        .isEqualTo(
            Map.of(
                "accountId",
                ACCOUNT,
                "username",
                LEARNER.username(),
                "displayName",
                LEARNER.displayName()));
    assertThatThrownBy(() -> routes.start("sub-admin", null, new MockHttpServletResponse()))
        .isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.status()).isEqualTo(422));
    assertThatThrownBy(() ->
            routes.start("sub-admin", new TestSessionStart(null), new MockHttpServletResponse()))
        .isInstanceOf(AccountFailure.class);
    assertThatThrownBy(() ->
            routes.start(
                "sub-learner", new TestSessionStart(ACCOUNT), new MockHttpServletResponse()))
        .isInstanceOfSatisfying(AccountFailure.class, f -> assertThat(f.status()).isEqualTo(403));
  }

  @Test
  void theCreateRouteSendsARosterRequestToTheRosterAndAddsItsProgressToTheAllowance() {
    // Subclass mock maker: the image build runs without the inline agent (as in
    // AuthorPreviewAccessControllerActivationTest).
    var settings = withSettings().mockMaker(MockMakers.SUBCLASS).strictness(Strictness.LENIENT);
    var creation = mock(TestAccountCreationService.class, settings);
    var roster = mock(TestAccountDevRosterService.class, settings);
    var accounts = mock(TestAccountService.class, settings);
    var controller =
        new TestAccountController(
            accounts,
            creation,
            new TestAccountAdminService(accounts, creation, roster),
            new TestAccountCreateRequestValidator());
    var owner = new AccountPrincipal(UUID.randomUUID(), "owner", "Owner", true, true);
    var status = new TestAccountDevRosterService.Status(27, 5, 0);
    when(roster.next(owner)).thenReturn(status);
    assertThat(
            controller
                .create(owner, new TestAccountCreate(null, null, null, true, false, null, null))
                .data())
        .isEqualTo(status);
    verify(creation, never()).create(any(), any());
    var single =
        new TestAccountCreate(UUID.randomUUID(), null, "learner02", false, false, null, null);
    when(creation.create(owner, single))
        .thenReturn(new TestAccountCreationService.Created(ACCOUNT, "test-1@lookahead.dev", true));
    assertThat(controller.create(owner, single).data())
        .isEqualTo(new TestAccountCreationService.Created(ACCOUNT, "test-1@lookahead.dev", true));
    verify(roster, times(1)).next(owner);
    when(creation.allowance(owner)).thenReturn(new TestAccountCreationService.Allowance(1, 0, 1));
    when(roster.statusFor(owner)).thenReturn(status);
    assertThat(controller.allowance(owner).data())
        .isEqualTo(new TestAccountCreationService.Allowance(1, 0, 1, status, null));
    assertThat(status.remaining()).isEqualTo(22);
    assertThat(new TestAccountCreate(ACCOUNT, "x", null, false, false, null, null).toString())
        .isEqualTo("TestAccountCreate[redacted]");
    // DLV-969 (owner, 2026-11-04): {"restoreRoster": true, "confirm": "RESTORE"} on the same route
    // goes to the roster's Restore.
    when(roster.restore(owner, new TestAccountDevRosterService.Restore("RESTORE")))
        .thenReturn(new TestAccountDevRosterService.Restored(2));
    assertThat(
            controller
                .create(
                    owner, new TestAccountCreate(null, null, null, false, true, "RESTORE", null))
                .data())
        .isEqualTo(new TestAccountDevRosterService.Restored(2));
    assertThat(
            controller
                .create(owner, new TestAccountCreate(null, null, null, true, true, "RESTORE", null))
                .data())
        .isEqualTo(new TestAccountDevRosterService.Restored(2));
    verify(roster, times(1)).next(owner);
    verify(creation, times(1)).create(any(), any());
    // DLV-969 (owner, 2026-11-04): {"purchaseDecision": {...}} on the same route is a decision on a
    // DEV purchase request.
    var decision = new MembershipPurchaseDecision(UUID.randomUUID(), "approve");
    var decided = new ObjectMapper().createObjectNode().put("decision", "approve");
    when(accounts.decidePurchase(owner, decision)).thenReturn(decided);
    assertThat(
            controller
                .create(
                    owner, new TestAccountCreate(null, null, null, true, true, "RESTORE", decision))
                .data())
        .isEqualTo(decided);
    verify(roster, times(1)).next(owner);
    verify(roster, times(2)).restore(any(), any());
    verify(creation, times(1)).create(any(), any());
  }
}
