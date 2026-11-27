package com.lookahead.domain.security.handler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DLV-1016: the error bodies that were written out in more than one place: the JSON object the two MVC error handlers
 * answer with, and the fixed bodies the security filters and the entry point write before MVC is reached.
 */
public final class ErrorBodies {
    private ErrorBodies() {}

    /** 503 while the sign-in check cannot be made. */
    public static final String IDENTITY_UNAVAILABLE = "{\"status\":503,\"code\":\"IDENTITY_UNAVAILABLE\",\"message\":\"Identity verification is unavailable; retain your work and retry\"}";
    /** 401 without a valid sign-in. */
    public static final String AUTHENTICATION_REQUIRED = "{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to continue\"}";
    /** 503 while the account store does not answer, before MVC is reached (the MVC handler has its own sentence). */
    public static final String ACCOUNT_STORAGE_UNAVAILABLE = "{\"status\":503,\"code\":\"ACCOUNT_STORAGE_UNAVAILABLE\",\"message\":\"Account storage is unavailable; retain your work and retry\"}";
    /** The words of the 409 for a write whose condition did not hold; the same before MVC and in it. */
    public static final String REVISION_CONFLICT_MESSAGE = "The record changed while you were saving; reload before retrying";
    /** 409 before MVC is reached. */
    public static final String REVISION_CONFLICT = "{\"status\":409,\"code\":\"REVISION_CONFLICT\",\"message\":\"" + REVISION_CONFLICT_MESSAGE + "\"}";

    /** status, code, message, path, timestamp, in this order; the caller decides how the path is shown. */
    public static Map<String, Object> body(int status, String code, String message, String path) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", status); body.put("code", code); body.put("message", message);
        body.put("path", path); body.put("timestamp", Instant.now());
        return body;
    }
}
