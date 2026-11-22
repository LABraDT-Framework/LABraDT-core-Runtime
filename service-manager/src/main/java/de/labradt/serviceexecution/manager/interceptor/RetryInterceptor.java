package de.labradt.serviceexecution.manager.interceptor;

import lombok.extern.slf4j.Slf4j;
import de.labradt.serviceexecution.manager.config.ServiceCallConfig;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * HTTP Retry Interceptor.
 * Implements exponential backoff retry logic for transient failures (5xx errors).
 * Provides resilience to temporary service outages.
 *
 * Retry Strategy:
 * - Only retries on 5xx server errors (NOT 4xx client errors)
 * - Respects maximum retry attempts from ServiceCallConfig
 * - Uses exponential backoff with configurable multiplier
 * - Logs each retry attempt for observability
 */
@Slf4j
public class RetryInterceptor implements ClientHttpRequestInterceptor {

    private final ServiceCallConfig config;

    public RetryInterceptor(ServiceCallConfig config) {
        this.config = config;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        int attemptCount = 0;
        IOException lastException = null;

        while (attemptCount <= config.getMaxRetries()) {
            try {
                ClientHttpResponse response = execution.execute(request, body);
                
                // If successful or client error (4xx), don't retry
                if (response.getStatusCode().value() < 500) {
                    return response;
                }
                
                // Server error (5xx) - may retry
                if (attemptCount < config.getMaxRetries()) {
                    log.warn("Received 5xx status ({}), will retry | attempt={}/{}", 
                            response.getStatusCode().value(), attemptCount + 1, config.getMaxRetries());
                    
                    long backoffDuration = config.calculateBackoffDelay(attemptCount);
                    try {
                        Thread.sleep(backoffDuration);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Retry sleep interrupted");
                        return response;
                    }
                    attemptCount++;
                } else {
                    log.error("Max retries exhausted for {} {}", request.getMethod(), request.getURI());
                    return response;
                }
                
            } catch (IOException e) {
                lastException = e;
                
                if (attemptCount < config.getMaxRetries()) {
                    log.warn("Request failed with IOException, will retry | attempt={}/{} | error: {}",
                            attemptCount + 1, config.getMaxRetries(), e.getMessage());
                    
                    long backoffDuration = config.calculateBackoffDelay(attemptCount);
                    try {
                        Thread.sleep(backoffDuration);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Retry sleep interrupted");
                        throw e;
                    }
                    attemptCount++;
                } else {
                    log.error("Max retries exhausted for {} {} | error: {}", 
                            request.getMethod(), request.getURI(), e.getMessage());
                    throw e;
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        throw new IOException("Request failed after " + config.getMaxRetries() + " retries");
    }
}
