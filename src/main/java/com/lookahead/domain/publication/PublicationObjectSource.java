package com.lookahead.domain.publication;

import java.io.IOException;
import java.io.InputStream;

/** Only the two selected immutable release objects are fetched. */
@FunctionalInterface
public interface PublicationObjectSource {
    InputStream open(String key) throws IOException;
}
