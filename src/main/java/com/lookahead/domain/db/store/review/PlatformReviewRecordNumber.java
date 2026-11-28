package com.lookahead.domain.db.store.review;

/**
 * DLV-971: the next record number for a kind (review records and support requests), unique and
 * increasing.
 */
@FunctionalInterface
public interface PlatformReviewRecordNumber {
  long next(String kind);
}
