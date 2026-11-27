package com.lookahead.domain.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Multipart failures happen before a controller is chosen, so they need a global handler in the account envelope. */
@RestControllerAdvice
@Profile("accounts")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformReviewUploadErrorHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return failure(413, "PLATFORM_REVIEW_TOO_LARGE", "Images must be 5 MB or smaller each and 20 MB or less in total.", request);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<?> malformed(MultipartException ex, HttpServletRequest request) {
        return failure(400, "MALFORMED_MULTIPART", "The upload could not be read; try again.", request);
    }

    private static ResponseEntity<?> failure(int status, String code, String message, HttpServletRequest request) {
        var body = ErrorBodies.body(status, code, message, request.getRequestURI());
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
