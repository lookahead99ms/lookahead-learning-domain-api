package com.lookahead.domain.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * DLV-1016: the one place that turns bytes or text into a lower-case SHA-256 hex digest (it was
 * written out in eleven places).
 */
public final class Sha256 {
  private Sha256() {}

  public static String hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** The digest of the text's UTF-8 bytes. */
  public static String hex(String text) {
    return hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
