package com.lookahead.domain.util;

import com.lookahead.domain.exception.AccountFailure;
import java.time.Duration;

/**
 * The rules of support feedback: how many messages one account may send in what window
 * (SupportFeedbackService passes the numbers to the receipt store, which counts inside its own
 * fenced unit), and the two refusals SupportFeedbackService makes when the store answers that
 * nothing was reserved. DLV-1016: the numbers and the refusals were written inside the store.
 */
public final class SupportFeedbackRules {
  private SupportFeedbackRules() {}

  /** Messages one account may send within {@link #WINDOW}. */
  public static final int PER_WINDOW = 5;

  public static final Duration WINDOW = Duration.ofHours(1);

  /**
   * The one test of an email address used for the sender, the inbox and a reply address (DLV-1016:
   * it was in SupportEmailClient).
   */
  public static boolean email(String value) {
    return value != null
        && value.length() <= 254
        && value.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+");
  }

  public static AccountFailure keyReused() {
    return new AccountFailure(
        409, "FEEDBACK_KEY_REUSED", "Use the same message when checking an existing submission");
  }

  public static AccountFailure rateLimited() {
    return new AccountFailure(
        429, "FEEDBACK_RATE_LIMIT", "Please wait before sending another message");
  }
}
