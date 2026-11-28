package com.lookahead.domain.util;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.SignInModels.Session;
import java.time.Duration;
import java.time.Instant;

/**
 * The sign-in limits, the proof, family and freshness rules, and the sign-in refusals. DLV-1016:
 * these were static members of SignInService. SignInService applies them; the stores ask the rules
 * that decide which rows they return or delete ({@link #deadSession} and {@link #deadChallenge} for
 * retiring old records, {@link #waitingListed} and {@link #waitingExpires} for the waiting list).
 */
public final class SignInRules {
  private SignInRules() {}

  /** An account has at most this many active sign-ins; one more is a challenge to replace one. */
  public static final int SIGN_IN_LIMIT = 2;

  public static final int CHALLENGES_PER_WINDOW = 5;
  public static final Duration IDLE = Duration.ofMinutes(30),
      ABSOLUTE = Duration.ofDays(7),
      CHALLENGE = Duration.ofMinutes(5),
      WINDOW = Duration.ofMinutes(15);

  /**
   * A session's lastActiveAt is written at most this often: parallel calls of one page then write
   * nothing and cannot conflict.
   */
  public static final Duration TOUCH = Duration.ofMinutes(1);

  /**
   * DLV-969: a waiting account nobody admitted is removed after this long (the cleanup job's rule;
   * the waiting list shows the same time).
   */
  public static final Duration WAITING_EXPIRY = Duration.ofHours(48);

  /**
   * A sign-in record of a session not active for this long is dead and is removed by the {@code
   * sign-in-records} job. DLV-1016: this limit was in the sign-in record store.
   */
  public static final Duration RECORD_RETENTION = Duration.ofDays(7);

  /**
   * A session record is dead: its last activity is older than {@link #RECORD_RETENTION}. A record
   * without the time stays.
   */
  public static boolean deadSession(Instant lastActive, Instant now) {
    return lastActive != null && lastActive.isBefore(now.minus(RECORD_RETENTION));
  }

  /**
   * A challenge record is dead: it expired longer ago than the rate-limit window ({@link #WINDOW}),
   * so it no longer counts for anything. A record without the time stays.
   */
  public static boolean deadChallenge(Instant expires, Instant now) {
    return expires != null && expires.isBefore(now.minus(WINDOW));
  }

  /**
   * DLV-971: a waiting account is on the waiting list while it is not admitted and not disabled
   * (Reject takes it off until it is enabled again). DLV-1016: this rule was in the DEV access
   * store.
   */
  public static boolean waitingListed(boolean admitted, boolean active) {
    return !admitted && active;
  }

  /**
   * When a waiting account requested at {@code requested} is removed unless the owner admits it.
   */
  public static Instant waitingExpires(Instant requested) {
    return requested.plus(WAITING_EXPIRY);
  }

  /**
   * Not revoked, not idle for 30 minutes, not older than 7 days, issued under the account's current
   * sign-in generation.
   */
  public static boolean active(Session s, long generation, Instant now) {
    return s.revoked() == null
        && s.active().isAfter(now.minus(IDLE))
        && s.created().isAfter(now.minus(ABSOLUTE))
        && s.generation() == generation;
  }

  public static String email(SignInIdentity identity) {
    String email = identity.verifiedEmail();
    return email == null || email.length() > 254 ? null : email;
  }

  public static void requireFresh(Instant auth, Instant now) {
    if (auth.isAfter(now.plusSeconds(60)) || !auth.plus(Duration.ofMinutes(5)).isAfter(now))
      throw failure(403, "RECENT_AUTHENTICATION_REQUIRED");
  }

  public static String binding(String proof) {
    if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw denied();
    return Sha256.hex(proof);
  }

  public static String family(SignInIdentity identity) {
    return Sha256.hex(identity.issuer() + "\n" + identity.subject() + "\n" + identity.family());
  }

  public static AccountFailure failure(int status, String code) {
    return new AccountFailure(status, code, "Sign in again or contact the account administrator.");
  }

  public static AccountFailure denied() {
    return failure(401, "AUTHENTICATION_REQUIRED");
  }

  public static AccountFailure admissionRequired() {
    return failure(403, "ACCOUNT_ADMISSION_REQUIRED");
  }

  public static AccountFailure invalid() {
    return failure(400, "SIGN_IN_CHALLENGE_INVALID");
  }
}
