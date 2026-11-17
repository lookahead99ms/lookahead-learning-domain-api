package com.lookahead.learning.content.handler;

import com.lookahead.domain.Events;
import com.lookahead.domain.RequestLog;
import com.lookahead.domain.persistence.dynamodb.DynamoWrites;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.DynamoOwnerUnit;

import jakarta.servlet.http.HttpServletRequest;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
    /**
     * DLV-970: the store's outage answers 503. {@link DynamoWrites.Unavailable} (a transaction whose outcome is unknown),
     * its read-side twin {@link DynamoOwnerUnit.DynamoOwnerUnavailable} and the SDK's own failures (no connection,
     * throttling, a service error) reaching the web layer answer 503 {@code ACCOUNT_STORAGE_UNAVAILABLE}. Plain logs
     * (owner, 2026-11-17): a dependency down is an ERROR an operator acts on; the caller sees only the safe message, the
     * ERROR line names the operation and the
     * table(s) when the store knows them, quotes the SDK's own message (IAM denials, request IDs and endpoints help the
     * one person reading these private logs) and ends with the reference of the stack trace that follows.
     */
    @ExceptionHandler({DynamoWrites.Unavailable.class, DynamoOwnerUnit.DynamoOwnerUnavailable.class, SdkClientException.class, DynamoDbException.class})
    ResponseEntity<?> storeUnavailable(RuntimeException ex, HttpServletRequest request) {
        String reference = RequestLog.reference();
        String call = ex instanceof DynamoWrites.Unavailable store ? store.call() : "DynamoDB call";
        String code = Events.code(ex);
        boolean refused = Events.permissionRefused(Events.status(ex), code);
        log.error("{} {} {}: {} {} ({}); answered 503 ACCOUNT_STORAGE_UNAVAILABLE; reference {}{}",
                GlobalExceptionHandler.forLog(request.getMethod()), GlobalExceptionHandler.forLog(request.getRequestURI()),
                RequestLog.failedAfter(request), call, refused ? "was refused" : "failed", Events.quoted(ex), reference, hint(ex), ex);
        return failure(new AccountFailure(503, "ACCOUNT_STORAGE_UNAVAILABLE", "Account storage is unavailable; retain your draft and retry the same request"), request);
    }

    private static String hint(Throwable failure) {
        String hint = Events.hint(failure);
        return hint == null ? "" : "; " + hint;
    }

    /** DLV-970: a condition that did not hold after the store's own retries is the existing revision conflict answer. */
    @ExceptionHandler(DynamoWrites.Conflict.class)
    ResponseEntity<?> storeConflict(DynamoWrites.Conflict ex, HttpServletRequest request) {
        log.warn("Account storage conflict: {} {} ({})", GlobalExceptionHandler.forLog(request.getMethod()),
                GlobalExceptionHandler.forLog(request.getRequestURI()), com.lookahead.domain.Events.types(ex));
        return failure(new AccountFailure(409, "REVISION_CONFLICT", "The record changed while you were saving; reload before retrying"), request);
    }
}
