package com.lookahead.learning.content.dto;

import java.time.Instant;
import java.util.List;

public record ApiError(
        int status,
        String error,
        String message,
        String path,
        List<String> details,
        Instant timestamp) {
}
