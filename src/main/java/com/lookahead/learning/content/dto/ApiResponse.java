package com.lookahead.learning.content.dto;

import java.time.Instant;

public record ApiResponse<T>(T data, Instant timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, Instant.now());
    }
}
