package com.lookahead.domain.security.handler;

import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.dynamodb.OwnerTransactionUnit;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.RequestLog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import tools.jackson.databind.DatabindException;

@RestControllerAdvice
@Profile("accounts")
// One step after PlatformReviewUploadErrorHandler, which is also first in line: the two were
// ordered by package name
// before the classes shared this package, and that order (upload errors first) is kept explicitly.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccountErrorHandler {

  @ExceptionHandler(AccountFailure.class)
  ResponseEntity<?> failure(AccountFailure ex, HttpServletRequest request) {
    var body =
        ErrorBodies.body(
            ex.status(), ex.code(), ex.getMessage(), EventLog.redact(request.getRequestURI()));
    if (ex.currentRevision() != null) body.put("currentRevision", ex.currentRevision());
    return ResponseEntity.status(ex.status()).header("Cache-Control", "no-store").body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid(IllegalArgumentException ex, HttpServletRequest request) {
    return failure(new AccountFailure(422, "INVALID_ACCOUNT_PAYLOAD", ex.getMessage()), request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<?> malformed(HttpMessageNotReadableException ex, HttpServletRequest request) {
    // DLV-969 (2026-11-04): say which field could not be read, by name only (never a value), so a
    // body that does not fit
    // its record is found from one log line instead of guessed at.
    String field = fieldOf(ex);
    EventLog.refused(EventLog.EVENT, "request.json", "MALFORMED_JSON")
        .addKeyValue("url.path", EventLog.path(request))
        .addKeyValue("json.field", field)
        .addKeyValue("error.type", EventLog.types(ex))
        .log(
            "Request body on {} {} refused with 400: {} could not be read ({})",
            EventLog.forLog(request.getMethod()),
            EventLog.path(request),
            field.isEmpty() ? "the body" : "field " + field,
            EventLog.types(ex));
    return failure(
        new AccountFailure(400, "MALFORMED_JSON", "Request body is not valid JSON"), request);
  }

  /**
   * The JSON path of the field that failed, e.g. "roster" or "purchaseDecision.purchaseId"; names
   * only, never values.
   */
  static String fieldOf(Throwable failure) {
    for (Throwable at = failure; at != null; at = at.getCause())
      if (at instanceof DatabindException databind && !databind.getPath().isEmpty())
        return databind.getPath().stream()
            .map(reference ->
                reference.getPropertyName() != null
                    ? reference.getPropertyName()
                    : "[" + reference.getIndex() + "]")
            .collect(Collectors.joining("."))
            .replace(".[", "[");
    return "";
  }

  /**
   * DLV-970: the store's outage answers 503. {@link DynamoWrites.Unavailable} (a transaction whose
   * outcome is unknown), its read-side twin {@link OwnerTransactionUnit.OwnerReadUnavailable} and
   * the SDK's own failures (no connection, throttling, a service error) reaching the web layer
   * answer 503 {@code ACCOUNT_STORAGE_UNAVAILABLE}. Plain logs (owner, 2026-11-17): a dependency
   * down is an ERROR an operator acts on; the caller sees only the safe message, the ERROR line
   * names the operation and the table(s) when the store knows them, quotes the SDK's own message
   * (IAM denials, request IDs and endpoints help the one person reading these private logs) and
   * ends with the reference of the stack trace that follows.
   */
  // Exception, decided 2026-11-25 (DLV-1016): this handler names two AWS SDK exception types
  // although it is no client or store.
  // A store call that is not wrapped in DynamoWrites reaches here as the SDK's own exception, and
  // Spring chooses an
  // @ExceptionHandler by exception class only (for the failure and for its causes), so the store's
  // own test
  // (DynamoFailures.unavailable, which StorageFailureFilter uses) cannot replace the two class
  // names here. Wrapping the
  // exception in the stores instead would change this ERROR line: "DynamoDB call" would become the
  // operation and its
  // table, and the stack trace under the line would start at the wrapper. The quoted message would
  // stay.
  @ExceptionHandler({
    DynamoWrites.Unavailable.class,
    OwnerTransactionUnit.OwnerReadUnavailable.class,
    SdkClientException.class,
    DynamoDbException.class
  })
  ResponseEntity<?> storeUnavailable(RuntimeException ex, HttpServletRequest request) {
    String reference = RequestLog.reference();
    String call = ex instanceof DynamoWrites.Unavailable store ? store.call() : "DynamoDB call";
    String code = EventLog.code(ex);
    boolean refused = EventLog.permissionRefused(EventLog.status(ex), code);
    EventLog.errorWithStack(
        "{} {} {}: {} {} ({}); answered 503 ACCOUNT_STORAGE_UNAVAILABLE; reference {}{}",
        EventLog.forLog(request.getMethod()),
        EventLog.forLog(request.getRequestURI()),
        RequestLog.failedAfter(request),
        call,
        refused ? "was refused" : "failed",
        EventLog.quoted(ex),
        reference,
        hint(ex),
        ex);
    return failure(
        new AccountFailure(
            503,
            "ACCOUNT_STORAGE_UNAVAILABLE",
            "Account storage is unavailable; retain your draft and retry the same request"),
        request);
  }

  private static String hint(Throwable failure) {
    String hint = EventLog.hint(failure);
    return hint == null ? "" : "; " + hint;
  }

  /**
   * DLV-970: a condition that did not hold after the store's own retries is the existing revision
   * conflict answer.
   */
  @ExceptionHandler(DynamoWrites.Conflict.class)
  ResponseEntity<?> storeConflict(DynamoWrites.Conflict ex, HttpServletRequest request) {
    EventLog.warnWithoutStack(
        "Account storage conflict: {} {} ({})",
        EventLog.forLog(request.getMethod()),
        EventLog.forLog(request.getRequestURI()),
        EventLog.types(ex));
    return failure(
        new AccountFailure(409, "REVISION_CONFLICT", ErrorBodies.REVISION_CONFLICT_MESSAGE),
        request);
  }
}
