package com.lookahead.domain.cloud;

/** DLV-970: the 48-hour expiry of waiting accounts on one store; {@link CloudAdmissionCleanup} wraps it with the run log. */
public interface AdmissionCleanupStore {
    /** Removes every waiting account older than {@link CloudAdmissionCleanup#WAITING_EXPIRY}; the number removed. */
    int expire();
}
