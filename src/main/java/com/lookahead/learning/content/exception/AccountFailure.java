package com.lookahead.learning.content.exception;

public final class AccountFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentRevision;
    public AccountFailure(int status, String code, String message) { this(status, code, message, null); }
    public AccountFailure(int status, String code, String message, Long revision) {
        super(message); this.status = status; this.code = code; this.currentRevision = revision;
    }
    public int status() { return status; }
    public String code() { return code; }
    public Long currentRevision() { return currentRevision; }
}
