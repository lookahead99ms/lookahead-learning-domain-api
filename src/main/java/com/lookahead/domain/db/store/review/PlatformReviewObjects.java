package com.lookahead.domain.db.store.review;

import java.util.List;
import java.util.Optional;

/**
 * DLV-928: byte storage for platform review records and images; DLV-971 (owner, 2026-11-06): one instance per
 * runtime store root in the assets bucket (reviews/, support-requests/, delivery/).
 * The operations map one-to-one to S3 (GetObject, conditional PutObject with If-Match / If-None-Match,
 * ListObjectsV2, DeleteObject), so the Local disk store can be replaced by an S3 store without changing the service.
 * Keys are relative to the store's root: path segments of letters, digits, dot, dash and underscore, never a
 * {@code ..} segment ({@link #KEY}). Failures surface as {@link java.io.UncheckedIOException}.
 */
// Exception, decided 2026-11-25 (DLV-1016): db.store is not DynamoDB only. It also holds the object-backed stores: this
// interface, its implementations PlatformReviewFileSystemObjectStore (a folder on disk), PlatformReviewS3ObjectStore (a
// prefix of the assets bucket) and PlatformReviewMissingObjectStore, and the two stores that keep their data through it,
// PlatformReviewRecordStore and delivery.DeliveryPlanStore. They are the persistence layer of the reviews, the support
// requests and the delivery plan, so they stay with the other stores. No class under db.store calls S3 itself: every S3
// call of these stores is made by client.PlatformReviewS3Client, which PlatformReviewS3ObjectStore is given.
public interface PlatformReviewObjects {
    /** A relative key: segments of [A-Za-z0-9._-], no empty or {@code ..} segment, no leading slash. */
    String KEY = "(?!.*(?:^|/)\\.\\.(?:/|$))[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*";

    /** Object bytes and an opaque version used for conditional writes. */
    record Stored(byte[] bytes, String version) {}

    Optional<Stored> read(String key);

    /**
     * Writes only when the object's current version equals {@code expectedVersion}, or when the
     * object does not exist and {@code expectedVersion} is null. Returns false when another writer won.
     */
    boolean write(String key, byte[] bytes, String expectedVersion);

    /** Unconditional write for objects with a fresh, unique key (images). */
    void put(String key, byte[] bytes);


    /** Every key under {@code prefix} at any depth, in no particular order (records/YYYY/MM/DD/ID.json). */
    List<String> listAll(String prefix);

    void delete(String key);
}
