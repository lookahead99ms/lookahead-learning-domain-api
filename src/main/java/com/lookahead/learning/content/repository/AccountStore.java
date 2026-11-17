package com.lookahead.learning.content.repository;

import java.util.List;
import java.util.UUID;

/**
 * DLV-970: the subject item and curriculum grants behind {@link AccountRepository}. {@link DynamoAccountStore} reads
 * {@code ACCOUNT#id / META} and {@code GRANT#topic} items (the PostgreSQL store retired 2026-11-17).
 */
public interface AccountStore {
    void ensureSubject(UUID subject);
    /** The account's unexpired topic grants, as stored. */
    List<String> grantTopics(UUID subject);
    /** True for an enabled account with cloud author access (the owner). */
    boolean authorEnabled(UUID subject);
}
