package de.labradt.serviceexecution.manager.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP Request/Response Logging Interceptor.
 * Logs requests and responses while redacting sensitive headers.
 * Useful for debugging and monitoring external service calls.
 *
 * Sensitive headers redacted: Authorization, X-API-Key, Cookie, etc.
 */
@Slf4j
public class HttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Set<String> SENSITIVE_HEADERS = new HashSet<>(Arrays.asList(
            "authorization",
            "x-api-key",
            "cookie",
            "x-auth-token",
            "x-access-token",
            "x-secret-key"
    ));

    private static final int MAX_RESPONSE_LOG_LENGTH = 500;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        logRequest(request, body);

        long startTime = System.currentTimeMillis();
        ClientHttpResponse response = execution.execute(request, body);
        long duration = System.currentTimeMillis() - startTime;

        logResponse(request, response, duration);

        return response;
    }

    /**
     * Log outgoing HTTP request with method, URL, and headers (redacted).
     */
    private void logRequest(HttpRequest request, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append("→ REQUEST: ").append(request.getMethod()).append(" ").append(request.getURI()).append("\n");
        
        request.getHeaders().forEach((name, values) -> {
            String value = isSensitiveHeader(name) ? "***REDACTED***" : String.join("; ", values);
            sb.append("  ").append(name).append(": ").append(value).append("\n");
        });

        if (body != null && body.length > 0) {
            sb.append("  Body: ").append(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }

        log.debug(sb.toString());
    }

    /**
     * Log incoming HTTP response with status, headers, timing, and truncated body.
     */
    private void logResponse(HttpRequest request, ClientHttpResponse response, long duration) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("← RESPONSE: ").append(response.getStatusCode().value())
                .append(" (").append(duration).append("ms)\n");

        response.getHeaders().forEach((name, values) -> {
            String value = isSensitiveHeader(name) ? "***REDACTED***" : String.join("; ", values);
            sb.append("  ").append(name).append(": ").append(value).append("\n");
        });

        try {
            String responseBody = readResponseBody(response);
            if (responseBody != null && !responseBody.isEmpty()) {
                sb.append("  Body: ").append(responseBody);
            }
        } catch (Exception e) {
            log.debug("Could not read response body for logging", e);
        }

        log.debug(sb.toString());
    }

    /**
     * Read response body without consuming the stream for further processing.
     */
    private String readResponseBody(ClientHttpResponse response) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Check if header name is sensitive and should be redacted.
     */
    private boolean isSensitiveHeader(String headerName) {
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase());
    }
}
