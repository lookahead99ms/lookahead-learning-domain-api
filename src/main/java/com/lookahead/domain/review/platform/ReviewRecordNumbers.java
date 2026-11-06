package com.lookahead.domain.review.platform;

import org.springframework.jdbc.core.JdbcTemplate;

/** DLV-971: the next record number for a kind, from the Postgres sequences of V22 (review_record_seq, support_request_seq). */
@FunctionalInterface
public interface ReviewRecordNumbers {
    long next(String kind);

    /** The sequences live in the Domain database; the runtime role holds USAGE on both (V22). */
    final class Jdbc implements ReviewRecordNumbers {
        private final JdbcTemplate jdbc;
        public Jdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public long next(String kind) {
            String sequence = PlatformReviewModels.KIND_REQUEST.equals(kind) ? "support_request_seq" : "review_record_seq";
            Long value = jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, sequence);
            return value == null ? 0 : value;
        }
    }

    /** For tests and the Local fixture: counts in memory from 1. */
    final class InMemory implements ReviewRecordNumbers {
        private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public long next(String kind) { return counters.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet(); }
    }
}
