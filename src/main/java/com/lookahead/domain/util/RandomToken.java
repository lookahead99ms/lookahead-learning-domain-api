package com.lookahead.domain.util;

import java.security.SecureRandom;
import java.util.Base64;

/** DLV-1016: the one maker of single-use tokens: 32 random bytes as 43 URL-safe characters (it was written out twice). */
public final class RandomToken {
    private RandomToken() {}

    public static String next() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
