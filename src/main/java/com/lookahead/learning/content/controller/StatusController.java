package com.lookahead.learning.content.controller;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.dto.ApplicationStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
@Tag(name = "Application status")
public class StatusController {

    private final String applicationName;
    private final String applicationVersion;

    public StatusController(
            @Value("${spring.application.name}") String applicationName,
            @Value("${app.version}") String applicationVersion) {
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping
    @Operation(summary = "Return the current API status")
    public ApiResponse<ApplicationStatus> status() {
        return ApiResponse.success(
                new ApplicationStatus(applicationName, "UP", applicationVersion));
    }
}
