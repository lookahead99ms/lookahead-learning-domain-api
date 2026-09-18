package com.lookahead.learning.content.service;

/** A reservation is committed before contacting SMTP. An uncertain dispatch is never auto-repeated. */
public interface SupportReceiptStore {
    record Reservation(SupportFeedbackService.Receipt receipt, boolean dispatch) {}
    Reservation reserve(String owner, String key, String digest);
    void accepted(String owner, String key);
}
