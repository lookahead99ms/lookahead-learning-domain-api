package com.lookahead.domain.review.platform;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * DLV-928: byte storage for platform review day files and images. The operations map one-to-one
 * to S3 (GetObject, conditional PutObject with If-Match / If-None-Match, ListObjectsV2,
 * DeleteObject), so the Local disk store can be replaced by an S3 store without changing the service.
 * Failures surface as {@link UncheckedIOException}.
 */
public interface PlatformReviewObjects {
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

    /** Keys directly under {@code prefix}, in no particular order. */
    List<String> list(String prefix);

    void delete(String key);
}
