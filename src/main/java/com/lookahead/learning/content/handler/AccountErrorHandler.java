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
import java.time.Instant;
import java.util.LinkedHashMap;

@RestControllerAdvice
@Profile("accounts")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountErrorHandler {
    @ExceptionHandler(AccountFailure.class)
    ResponseEntity<?> failure(AccountFailure ex, HttpServletRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", ex.status()); body.put("code", ex.code()); body.put("message", ex.getMessage());
        body.put("path", request.getRequestURI()); body.put("timestamp", Instant.now());
        if (ex.currentRevision() != null) body.put("currentRevision", ex.currentRevision());
        return ResponseEntity.status(ex.status()).header("Cache-Control", "no-store").body(body);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException ex, HttpServletRequest request) {
        return failure(new AccountFailure(422, "INVALID_ACCOUNT_PAYLOAD", ex.getMessage()), request);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return failure(new AccountFailure(400, "MALFORMED_JSON", "Request body is not valid JSON"), request);
    }
    // Connection acquisition and failed commit/rollback can bypass JdbcTemplate's translation.
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class,
            TransactionSystemException.class})
    ResponseEntity<?> unavailable(RuntimeException ex, HttpServletRequest request) {
        return failure(new AccountFailure(503, "ACCOUNT_STORAGE_UNAVAILABLE", "Account storage is unavailable; retain your draft and retry the same request"), request);
    }
}
