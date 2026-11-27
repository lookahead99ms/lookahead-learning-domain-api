package com.lookahead.domain.db.store.review;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


/** For tests and the Local fixture: counts in memory from 1. */
public final class PlatformReviewRecordNumberMemoryStore implements PlatformReviewRecordNumber {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    @Override public long next(String kind) { return counters.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet(); }
}
