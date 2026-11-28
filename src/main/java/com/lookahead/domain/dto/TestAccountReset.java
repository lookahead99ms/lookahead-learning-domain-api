package com.lookahead.domain.dto;

import java.util.UUID;

/**
 * The body of a test account reset. DLV-1016: was nested in TestAccountService. {@code
 * expectedRevision} is a {@code Long}: a JSON reader refuses an absent value for a primitive, which
 * answered 400 MALFORMED_JSON; without it the reset now answers 428 REVISION_REQUIRED (DLV-969,
 * 2026-11-04).
 */
public record TestAccountReset(
    UUID key, Long expectedRevision, String confirmation, String preset) {
  public TestAccountReset(UUID key, long expectedRevision, String confirmation, String preset) {
    this(key, Long.valueOf(expectedRevision), confirmation, preset);
  }

  /** True only when the body sent this revision. */
  public boolean expects(long revision) {
    return expectedRevision != null && expectedRevision == revision;
  }
}
