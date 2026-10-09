package com.lookahead.domain.cloud;

import java.time.Instant;

/** Validated provider claims, never an application authorization decision. */
public record CognitoIdentity(String issuer,String subject,String family,String username,String displayName,Instant authenticatedAt) {
    @Override public String toString(){return "CognitoIdentity[redacted]";}
}
