package com.lookahead.domain.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-1016: the rules the stores ask instead of deciding themselves (what a paid or granting
 * purchase is, which sign-in records are dead, who is on the waiting list, what a roster row is).
 */
class StoreRulesTest {
  private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
  private final JsonMapper json = JsonMapper.builder().build();

  private ObjectNode purchase(String status, Instant starts, Instant ends, int paid) {
    var p = json.createObjectNode();
    p.put("status", status)
        .put("startsAt", starts.toString())
        .put("endsAt", ends.toString())
        .put("pricePaidCents", paid)
        .put("planId", "learn");
    return p;
  }

  @Test
  void aPurchaseGrantsOnlyWhileSucceededOrScheduledNotSupersededStartedAndNotEnded() {
    assertThat(MembershipRules.grantsAt(purchase("succeeded", NOW, NOW.plusSeconds(1), 0), NOW))
        .as("starts now")
        .isTrue();
    assertThat(
            MembershipRules.grantsAt(
                purchase("scheduled", NOW.minusSeconds(5), NOW.plusSeconds(1), 0), NOW))
        .isTrue();
    assertThat(
            MembershipRules.grantsAt(
                purchase("pending", NOW.minusSeconds(5), NOW.plusSeconds(1), 0), NOW))
        .isFalse();
    assertThat(
            MembershipRules.grantsAt(
                purchase("succeeded", NOW.plusSeconds(1), NOW.plusSeconds(9), 0), NOW))
        .as("not started")
        .isFalse();
    assertThat(MembershipRules.grantsAt(purchase("succeeded", NOW.minusSeconds(9), NOW, 0), NOW))
        .as("ends now")
        .isFalse();
    var superseded = purchase("succeeded", NOW.minusSeconds(5), NOW.plusSeconds(1), 0);
    superseded.put("supersededAt", NOW.toString());
    assertThat(MembershipRules.grantsAt(superseded, NOW)).isFalse();
    var cleared = purchase("succeeded", NOW.minusSeconds(5), NOW.plusSeconds(1), 0);
    cleared.putNull("supersededAt");
    assertThat(MembershipRules.grantsAt(cleared, NOW))
        .as("a NULL supersededAt is as unset as a missing one")
        .isTrue();
  }

  @Test
  void aPaidMembershipWasPaidForOrHasAPricedPlanAndThePlanIsReadOnlyWhenNothingWasPaid() {
    var asked = new ArrayList<String>();
    var priced = json.createObjectNode().put("priceCents", 4900);
    var free = json.createObjectNode().put("priceCents", 0);
    assertThat(
            MembershipRules.paid(
                purchase("succeeded", NOW, NOW.plusSeconds(60), 100),
                NOW,
                id -> {
                  asked.add(id);
                  return free;
                }))
        .as("something was paid")
        .isTrue();
    assertThat(asked).as("the plan is not read when the purchase was paid for").isEmpty();
    assertThat(
            MembershipRules.paid(
                purchase("scheduled", NOW.plusSeconds(30), NOW.plusSeconds(60), 0),
                NOW,
                id -> {
                  asked.add(id);
                  return priced;
                }))
        .as("the plan is priced")
        .isTrue();
    assertThat(asked).containsExactly("learn");
    assertThat(
            MembershipRules.paid(
                purchase("succeeded", NOW, NOW.plusSeconds(60), 0), NOW, id -> free))
        .as("free and free today")
        .isFalse();
    assertThat(
            MembershipRules.paid(
                purchase("succeeded", NOW, NOW.plusSeconds(60), 0), NOW, id -> null))
        .as("the plan is gone")
        .isFalse();
    asked.clear();
    assertThat(
            MembershipRules.paid(
                purchase("succeeded", NOW.minusSeconds(60), NOW, 100),
                NOW,
                id -> {
                  asked.add(id);
                  return priced;
                }))
        .as("ended")
        .isFalse();
    assertThat(
            MembershipRules.paid(
                purchase("refunded", NOW, NOW.plusSeconds(60), 100),
                NOW,
                id -> {
                  asked.add(id);
                  return priced;
                }))
        .as("not succeeded or scheduled")
        .isFalse();
    var superseded = purchase("succeeded", NOW, NOW.plusSeconds(60), 100);
    superseded.put("supersededAt", NOW.toString());
    assertThat(
            MembershipRules.paid(
                superseded,
                NOW,
                id -> {
                  asked.add(id);
                  return priced;
                }))
        .as("superseded")
        .isFalse();
    assertThat(asked).as("no plan is read for a purchase that cannot count").isEmpty();
  }

  @Test
  void signInRecordsAreDeadPastTheirLimitsAndARecordWithoutATimeStays() {
    assertThat(SignInRules.RECORD_RETENTION).isEqualTo(Duration.ofDays(7));
    assertThat(SignInRules.deadSession(NOW.minus(SignInRules.RECORD_RETENTION).minusMillis(1), NOW))
        .isTrue();
    assertThat(SignInRules.deadSession(NOW.minus(SignInRules.RECORD_RETENTION), NOW))
        .as("exactly at the limit stays")
        .isFalse();
    assertThat(SignInRules.deadSession(null, NOW)).isFalse();
    assertThat(SignInRules.deadChallenge(NOW.minus(SignInRules.WINDOW).minusMillis(1), NOW))
        .isTrue();
    assertThat(SignInRules.deadChallenge(NOW.minus(SignInRules.WINDOW), NOW)).isFalse();
    assertThat(SignInRules.deadChallenge(null, NOW)).isFalse();
  }

  @Test
  void theWaitingListShowsAccountsNotAdmittedAndNotDisabledWithTheirExpiry() {
    assertThat(SignInRules.waitingListed(false, true)).isTrue();
    assertThat(SignInRules.waitingListed(true, true)).as("admitted").isFalse();
    assertThat(SignInRules.waitingListed(false, false))
        .as("rejected: disabled until enabled again")
        .isFalse();
    assertThat(SignInRules.waitingExpires(NOW)).isEqualTo(NOW.plus(Duration.ofHours(48)));
  }

  @Test
  void aRosterRowHasACreatorAndWasNotMadeByCreateAccount() {
    assertThat(TestAccountRules.rosterRow(UUID.randomUUID(), "seeded")).isTrue();
    assertThat(TestAccountRules.rosterRow(UUID.randomUUID(), null)).isTrue();
    assertThat(TestAccountRules.rosterRow(UUID.randomUUID(), "created")).isFalse();
    assertThat(TestAccountRules.rosterRow(null, "seeded")).isFalse();
  }
}
