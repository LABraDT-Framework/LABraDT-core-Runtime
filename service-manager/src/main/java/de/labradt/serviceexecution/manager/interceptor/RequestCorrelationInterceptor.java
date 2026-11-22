package de.labradt.serviceexecution.manager.interceptor;

import lombok.extern.slf4j.Slf4j;
import de.labradt.serviceexecution.manager.config.ServiceCallConfig;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.UUID;

/**
 * Request Correlation Interceptor.
 * Adds correlation IDs and tracing headers to outgoing requests.
 * Enables distributed tracing and request correlation across microservices.
 *
 * Headers added:
 * - X-Correlation-ID: Unique identifier to trace requests across services
 * - X-Request-ID: Individual request identifier
 * - X-Request-Start-Time: Timestamp when request originated
 *
 * Use case: Correlate logs across multiple services in microservices architecture
 */
@Slf4j
public class RequestCorrelationInterceptor implements ClientHttpRequestInterceptor {

    private final ServiceCallConfig config;

    public RequestCorrelationInterceptor(ServiceCallConfig config) {
        this.config = config;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        // Add or propagate correlation ID
        String correlationId = config.getCorrelationId() != null ? 
                config.getCorrelationId() : 
                UUID.randomUUID().toString();
        
        request.getHeaders().set("X-Correlation-ID", correlationId);
        
        // Add unique request ID
        String requestId = UUID.randomUUID().toString();
        request.getHeaders().set("X-Request-ID", requestId);
        
        // Add request start time
        request.getHeaders().set("X-Request-Start-Time", String.valueOf(System.currentTimeMillis()));
        
        log.trace("Added correlation headers | correlationId={}, requestId={}, url={}", 
                correlationId, requestId, request.getURI());
        
        return execution.execute(request, body);
    }
}
