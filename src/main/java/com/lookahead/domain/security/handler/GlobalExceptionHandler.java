package com.lookahead.domain.security.handler;

import com.lookahead.domain.dto.ApiError;
import com.lookahead.domain.log.EventLog;
import com.lookahead.domain.log.RequestLog;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<String> details =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList();
    return error(HttpStatus.BAD_REQUEST, "Request validation failed", request, details);
  }

  /**
   * DLV-969 (owner, 2026-11-03): a failure nobody expected is written to the log with its stack
   * trace, so CloudWatch shows what went wrong. The caller gets only a reference, which is also in
   * the log line, to find it there. Spring's own request failures (no such path, wrong method,
   * missing parameter) are the caller's mistake: they keep their 4xx status and log one WARN line,
   * so they never look like a crash or trip the deploy's ERROR scan.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
    HttpStatus refused =
        exception instanceof ErrorResponse response
            ? HttpStatus.resolve(response.getStatusCode().value())
            : null;
    if (refused != null && refused.is4xxClientError()) {
      EventLog.warnWithoutStack(
          "Request refused: {} {} -> {} {}",
          EventLog.forLog(request.getMethod()),
          EventLog.forLog(request.getRequestURI()),
          refused.value(),
          exception.getClass().getSimpleName());
      return error(refused, refused.getReasonPhrase(), request, List.of());
    }
    String reference = RequestLog.reference();
    // Plain logs (owner, 2026-11-17): what was being done, why and where in our code, what the
    // caller got, the reference.
    EventLog.errorWithStack(
        "{} {} {}: {}; answered 500 INTERNAL_SERVER_ERROR; reference {}",
        EventLog.forLog(request.getMethod()),
        EventLog.forLog(request.getRequestURI()),
        RequestLog.failedAfter(request),
        EventLog.unexpected(exception),
        reference,
        exception);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred",
        request,
        List.of("Reference: " + reference));
  }

  private ResponseEntity<ApiError> error(
      HttpStatus status, String message, HttpServletRequest request, List<String> details) {
    ApiError body =
        new ApiError(
            status.value(),
            status.getReasonPhrase(),
            message,
            EventLog.redact(request.getRequestURI()),
            details,
            Instant.now());
    return ResponseEntity.status(status).body(body);
  }
}
