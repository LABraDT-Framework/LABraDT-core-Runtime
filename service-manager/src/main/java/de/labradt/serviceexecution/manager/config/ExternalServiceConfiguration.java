package de.labradt.serviceexecution.manager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.serviceexecution.manager.interceptor.HttpLoggingInterceptor;
import de.labradt.serviceexecution.manager.interceptor.RequestCorrelationInterceptor;
import de.labradt.serviceexecution.manager.interceptor.RetryInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuration for HTTP service clients.
 */
@Slf4j
@Configuration
public class ExternalServiceConfiguration {

    /**
     * Default ServiceCallConfig bean.
     */
    @Bean
    public ServiceCallConfig serviceCallConfig() {
        ServiceCallConfig config = ServiceCallConfig.builder()
                .timeout(Duration.ofSeconds(30))
                .maxRetries(3)
                .backoffMs(1000L)
                .backoffMultiplier(2.0)
                .logRequestBodies(false)  // Security: don't log request bodies in production
                .build();
        
        log.info("ServiceCallConfig initialized | timeout={}s, maxRetries={}, backoffMs={}ms",
                30, config.getMaxRetries(), config.getBackoffMs());
        
        return config;
    }


    /**
     * define the low-level mechanics of the HTTP connection.
     * This "Buffering" wrapper saves the response in memory, so it can be read multiple times
     * If logging interceptor reads the response to print it to the console, the stream is consumed, and actual application gets an empty response.
     */
    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) java.time.Duration.ofSeconds(30).toMillis());
        factory.setReadTimeout((int) java.time.Duration.ofSeconds(30).toMillis());
        factory.setBufferRequestBody(true);
        
        return new BufferingClientHttpRequestFactory(factory);
    }

    /**
     * Spring RestClient bean (Spring 6.1+)
     */
    @Bean
    public RestClient restClient(ServiceCallConfig config) {
        // Attach correlation (Trace-IDs), logging, and retry logic to every outgoing call.
        return RestClient.builder()
                .requestFactory(clientHttpRequestFactory())
                .requestInterceptor(new RequestCorrelationInterceptor(config))
                .requestInterceptor(new HttpLoggingInterceptor())
                .requestInterceptor(new RetryInterceptor(config))
                .build();
    }

    /**
     * for JSON serialization/deserialization.
     * convert Java objects into JSON strings and vice versa
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
