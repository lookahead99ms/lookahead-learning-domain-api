package com.lookahead.domain.db.store.review;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/** The object store used when neither a directory nor a bucket is configured: every operation fails, so the routes answer 503 instead of saving data that would be lost. */
public final class PlatformReviewMissingObjectStore implements PlatformReviewObjects {
    private UncheckedIOException missing() { return new UncheckedIOException(new IOException("Platform review storage is not configured")); }
    public Optional<Stored> read(String key) { throw missing(); }
    public boolean write(String key, byte[] bytes, String expectedVersion) { throw missing(); }
    public void put(String key, byte[] bytes) { throw missing(); }
    public List<String> listAll(String prefix) { throw missing(); }
    public void delete(String key) { throw missing(); }
}
