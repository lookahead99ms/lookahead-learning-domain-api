package com.lookahead.domain.db.store.review;

import com.lookahead.domain.model.SupportFeedbackModels;
import java.time.Duration;
import java.util.Objects;

/** A reservation is committed before contacting SMTP. An uncertain dispatch is never auto-repeated. */
public interface SupportReceipt {
    /** Why no receipt was reserved or answered: the key already carries a different request, or the account has used its quota in the window. */
    enum Refused { KEY_REUSED, QUOTA_REACHED }
    /** A new receipt to dispatch ({@code dispatch} true), the existing receipt of the same request ({@code dispatch} false), or nothing reserved ({@code refused} set, no receipt). */
    record Reservation(SupportFeedbackModels.Receipt receipt, boolean dispatch, Refused refused) {
        public Reservation(SupportFeedbackModels.Receipt receipt, boolean dispatch) { this(receipt, dispatch, null); }
        public static Reservation refused(Refused why) { return new Reservation(null, false, Objects.requireNonNull(why)); }
    }
    /**
     * Reserves a receipt for the key, or answers the existing one. At most {@code perWindow} new reservations per account within
     * {@code window}; the caller names the quota. DLV-1016: a key that carries another request and a used-up quota are answered
     * as {@link Refused}, written nowhere; the service refuses.
     */
    Reservation reserve(String owner, String key, String digest, int perWindow, Duration window);
    void accepted(String owner, String key);
    /** DLV-971: frees an unconfirmed reservation whose request was neither recorded nor sent, so a retry starts over. */
    void release(String owner, String key);
}
