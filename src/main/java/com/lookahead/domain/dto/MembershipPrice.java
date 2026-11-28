package com.lookahead.domain.dto;

/**
 * The body of a plan price change. {@code priceCents} is an {@code Integer}: a JSON reader refuses
 * an absent value for a primitive (400 MALFORMED_JSON); without it the price is now refused as
 * invalid, 422 (DLV-969, 2026-11-04).
 */
public record MembershipPrice(Integer priceCents) {
  /** The price sent, or 0 (an invalid price) when the body has none. */
  public int cents() {
    return priceCents == null ? 0 : priceCents;
  }
}
