package com.lookahead.domain.util;

/**
 * The limits of the delivery plan file. DLV-1016: the size limit was a constant of the delivery
 * plan store, which the file validator read.
 */
public final class DeliveryPlanRules {
  private DeliveryPlanRules() {}

  /** The largest delivery plan file, in bytes (16 MB). */
  public static final int MAX_BYTES = 16 * 1024 * 1024;
}
