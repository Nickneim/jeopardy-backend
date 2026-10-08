package com.nickneim.jeopardy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser origins (scheme + host + port) allowed to call the REST API and
 * open the /ws WebSocket. Set with {@code app.cors.allowed-origins}, or the
 * {@code ALLOWED_ORIGINS} environment variable (comma-separated).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    public String[] allowedOriginsArray() {
        return allowedOrigins.toArray(String[]::new);
    }
}
