package com.lookahead.domain.publication;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** Only the two selected immutable release objects are fetched; listing serves live/ discovery alone. */
@FunctionalInterface
public interface PublicationObjectSource {
    InputStream open(String key) throws IOException;

    /** Every key under one prefix with its last-modified instant. A pinned release never lists. */
    default List<ListedObject> list(String prefix) throws IOException {
        throw new IOException("This publication source cannot list " + prefix);
    }

    record ListedObject(String key, Instant lastModified) {}
}
