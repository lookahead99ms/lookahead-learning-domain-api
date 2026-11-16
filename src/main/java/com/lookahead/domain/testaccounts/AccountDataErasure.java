package com.lookahead.domain.testaccounts;

import java.util.UUID;

/** Transaction-owned data erasure. Identity authorization and tombstones belong to the caller. */
public interface AccountDataErasure {
    /** Cloud callers always pass false; a purge context alone never grants audit authority. */
    void erase(UUID id, boolean localFixturePurge);
}
