package de.labradt.serviceexecution.manager.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * Configuration DTO for external service calls.
 * Controls timeout, retry behavior, and endpoint configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCallConfig {

    // Endpoint URL of the external service.
    private String endpoint;

    // HTTP request timeout duration
    @Builder.Default
    private Duration timeout = Duration.ofSeconds(30);

    // Maximum number of retry attempts for errors (5xx)
    @Builder.Default
    private int maxRetries = 3;

    // Initial backoff duration in milliseconds for exponential retry
    @Builder.Default
    private long backoffMs = 1000L;

    // Multiplier for exponential backoff
    @Builder.Default
    private double backoffMultiplier = 2.0;

    // Whether to include request/response bodies in debug logs(security)
    // Don't log massive payloads or sensitive user data into application logs
    @Builder.Default
    private boolean logRequestBodies = false;

    // Sensitive header names to redact from logs
    @Builder.Default
    private String redactHeaders = "Authorization,X-API-Key,Cookie";

    // Custom HTTP headers to include in every request.
    // Format: "Header-Name:Value,Another-Header:AnotherValue"
    private String customHeaders;

    // Correlation ID for tracing
    // A unique ID attached to the request allowing to trace exactly how a specific request traveled through entire infrastructure.
    private String correlationId;

    // Calculate next retry delay with exponential backoff
    public long calculateBackoffDelay(int retryAttempt) {
        return (long) (backoffMs * Math.pow(backoffMultiplier, retryAttempt));
    }
}