package com.lookahead.domain.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cors")
public record CorsConfigValues(List<String> allowedOrigins) {}
