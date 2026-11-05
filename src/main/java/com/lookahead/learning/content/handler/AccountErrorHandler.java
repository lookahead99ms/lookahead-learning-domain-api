package com.lookahead.learning.content.handler;

import com.lookahead.learning.content.exception.AccountFailure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.LinkedHashMap;

@RestControllerAdvice
@Profile("accounts")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(AccountErrorHandler.class);

    @ExceptionHandler(AccountFailure.class)
    ResponseEntity<?> failure(AccountFailure ex, HttpServletRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", ex.status()); body.put("code", ex.code()); body.put("message", ex.getMessage());
        body.put("path", GlobalExceptionHandler.redact(request.getRequestURI())); body.put("timestamp", Instant.now());
        if (ex.currentRevision() != null) body.put("currentRevision", ex.currentRevision());
        return ResponseEntity.status(ex.status()).header("Cache-Control", "no-store").body(body);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException ex, HttpServletRequest request) {
        return failure(new AccountFailure(422, "INVALID_ACCOUNT_PAYLOAD", ex.getMessage()), request);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // DLV-969 (2026-11-04): say which field could not be read, by name only (never a value), so a body that does not fit
        // its record is found from one log line instead of guessed at.
        String field = fieldOf(ex);
        com.lookahead.domain.Events.refused(com.lookahead.domain.Events.EVENT, "request.json", "MALFORMED_JSON")
                .addKeyValue("url.path", com.lookahead.domain.Events.path(request)).addKeyValue("json.field", field)
                .addKeyValue("error.type", com.lookahead.domain.Events.types(ex))
                .log("Request body on {} {} refused with 400: {} could not be read ({})", GlobalExceptionHandler.forLog(request.getMethod()),
                        com.lookahead.domain.Events.path(request), field.isEmpty() ? "the body" : "field " + field, com.lookahead.domain.Events.types(ex));
        return failure(new AccountFailure(400, "MALFORMED_JSON", "Request body is not valid JSON"), request);
    }

    /** The JSON path of the field that failed, e.g. "roster" or "purchaseDecision.purchaseId"; names only, never values. */
    static String fieldOf(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause())
            if (at instanceof tools.jackson.databind.DatabindException databind && !databind.getPath().isEmpty())
                return databind.getPath().stream()
                        .map(reference -> reference.getPropertyName() != null ? reference.getPropertyName() : "[" + reference.getIndex() + "]")
                        .collect(java.util.stream.Collectors.joining(".")).replace(".[", "[");
        return "";
    }
    // Connection acquisition and failed commit/rollback can bypass JdbcTemplate's translation.
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class,
            TransactionSystemException.class})
    ResponseEntity<?> unavailable(RuntimeException ex, HttpServletRequest request) {
        // The caller never sees the driver's detail; the log keeps it so an outage can be diagnosed.
        log.warn("Account storage unavailable: {} {}", GlobalExceptionHandler.forLog(request.getMethod()),
                GlobalExceptionHandler.forLog(request.getRequestURI()), ex);
        return failure(new AccountFailure(503, "ACCOUNT_STORAGE_UNAVAILABLE", "Account storage is unavailable; retain your draft and retry the same request"), request);
    }
}
