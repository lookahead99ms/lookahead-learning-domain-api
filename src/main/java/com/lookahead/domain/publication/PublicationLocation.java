package com.lookahead.domain.publication;

/** Verified immutable cloud files, or the existing explicitly configured Local files. */
public record PublicationLocation(String manifest, String contentRoot, String accountCatalog,
                                  boolean cloud, long cacheBytes, int cacheEntries) {}
