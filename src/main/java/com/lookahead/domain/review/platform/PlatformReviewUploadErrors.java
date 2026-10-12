package com.lookahead.domain.review.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
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
public class PlatformReviewUploadErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return failure(413, "PLATFORM_REVIEW_TOO_LARGE", "Images must be 5 MB or smaller each and 20 MB or less in total.", request);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<?> malformed(MultipartException ex, HttpServletRequest request) {
        return failure(400, "MALFORMED_MULTIPART", "The upload could not be read; try again.", request);
    }

    private static ResponseEntity<?> failure(int status, String code, String message, HttpServletRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", status); body.put("code", code); body.put("message", message);
        body.put("path", request.getRequestURI()); body.put("timestamp", Instant.now());
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
