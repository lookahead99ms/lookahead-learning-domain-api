package com.lookahead.domain.util;

import java.util.UUID;

/**
 * DLV-1016: the one reading of a UUID in its canonical form (36 characters, lower case); four
 * request validators, the DEV admission service, the Local token converter and {@link
 * PayloadReaders#uuid} each had their own copy. The two callers that also accept upper case letters
 * ({@code PayloadReaders.uuid}, {@code AccountMergeRequestValidator}) lower-case the text before
 * they ask.
 */
public final class CanonicalUuid {
  private CanonicalUuid() {}

  /**
   * The UUID, or null when the text is absent, is no UUID or is not written in the canonical form.
   * Each caller answers with its own refusal.
   */
  public static UUID parse(String text) {
    if (text == null) return null;
    try {
      UUID id = UUID.fromString(text);
      return id.toString().equals(text) ? id : null;
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }
}
