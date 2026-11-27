package com.lookahead.domain.config;

/**
 * The values of every runtime store: the directory (Local) or the bucket and key (cloud) shared by all, one prefix per
 * root. {@code validator.PlatformReviewStoreConfigValidator} checks them (DLV-1016).
 */
public record PlatformReviewStoreConfigValues(String directory, String bucket, String kmsKey, String reviews, String requests, String delivery) {
}
