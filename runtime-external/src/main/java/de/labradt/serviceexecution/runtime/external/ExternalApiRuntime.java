package de.labradt.serviceexecution.runtime.external;

import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.core.api.interfaces.IRuntimeProvider;
import de.labradt.serviceexecution.runtime.external.client.IExternalServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Semaphore;

/**
 * ExternalApiRuntime: Executes external service calls (REST, MQTT, gRPC).
 *
 * Uses virtual threads (Project Loom) because the workload is I/O-bound:
 * threads spend most of their time blocked on network responses from external APIs.
 * Virtual threads unmount from carrier threads during I/O waits, allowing
 * thousands of concurrent external API calls without platform thread starvation.
 */
@Slf4j
@Component
public class ExternalApiRuntime implements IRuntimeProvider {


    private final Map<TechnologyAdapter, IExternalServiceClient> clientRegistry;

    // Each task gets its own lightweight virtual thread
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    // Limits concurrent HTTP requests to prevent overwhelming external target services
    private final Semaphore concurrencyLimit;

    // Tracks the executor Future for each in-flight call so Stop() can actually interrupt it,
    // instead of just clearing a bookkeeping flag while the request keeps running underneath.
    private final ConcurrentHashMap<String, Future<?>> runningServices = new ConcurrentHashMap<>();

    public ExternalApiRuntime(List<IExternalServiceClient> clients,
                               @Value("${external.runtime.max-concurrent-requests:10}") int maxConcurrentRequests) {

        this.concurrencyLimit = new Semaphore(maxConcurrentRequests);
        log.info("External API concurrency limit set to {}", maxConcurrentRequests);

        log.info("=== Auto-Discovering External Service Clients ===");

        Map<TechnologyAdapter, IExternalServiceClient> tempRegistry = new HashMap<>();
        
        if (clients != null && !clients.isEmpty()) {
            for (IExternalServiceClient client : clients) {
                TechnologyAdapter clientType = client.getClientType();
                log.info("✓ Auto-registered client: {} → {}", clientType, client.getClass().getSimpleName());
                tempRegistry.put(clientType, client);
            }
        } else {
            log.warn("No external service clients configured. ExternalApiRuntime will not be able to route requests.");
        }

        this.clientRegistry = Collections.unmodifiableMap(tempRegistry);
        
        log.info("=== External Client Registry Complete ===");
        log.info("Total clients registered: {}", clientRegistry.size());
    }

    private IExternalServiceClient getClient(TechnologyAdapter environment) {
        IExternalServiceClient client = clientRegistry.get(environment);
        if (client == null) {
            throw new IllegalArgumentException("No external service client found for type: " + environment);
        }
        return client;
    }

    // For testing
    public Map<TechnologyAdapter, IExternalServiceClient> getClientRegistry() {
        return clientRegistry;
    }

    @Override
    public ExecutionCategory getExecutionCategory() {
        return ExecutionCategory.EXTERNAL;
    }

    @Override
    public CompletableFuture<ExecutionResponseDTO> ExecuteAsync(ExecutionRequestDTO request) {
        log.info("ExternalApiRuntime: ExecuteAsync - serviceId={}, executionId={}, clientType={}",
                request.getServiceId(), request.getExecutionId(), request.getTechnologyAdapter());

        CompletableFuture<ExecutionResponseDTO> future = new CompletableFuture<>();
        String executionId = request.getExecutionId();

        // Submit to executor for async execution
        Future<?> handle = executorService.submit(() -> {
            long startTime = System.currentTimeMillis();
            log.info("[EXEC] External API execution started | serviceId={}, runtime={}",
                    request.getServiceId(), request.getTechnologyAdapter());
            try {
                // Acquire semaphore permit
                concurrencyLimit.acquire();
                log.debug("[SEMAPHORE] Permit acquired for serviceId={} | available={}",
                        request.getServiceId(), concurrencyLimit.availablePermits());

                // Optional artificial delay (arg "delayMs") 
                // to test Stop() because otherwise service finishes too fast
                // Thread.sleep is interruptible, so Stop()'s handle.cancel(true) actually aborts the wait immediately
                String delayArg = request.getArgsCopy().get("delayMs");
                if (delayArg != null && !delayArg.isBlank()) {
                    long delayMs = Long.parseLong(delayArg);
                    log.info("[DELAY] Sleeping {}ms before external call for serviceId={}", delayMs, request.getServiceId());
                    Thread.sleep(delayMs);
                }

                // Route to the appropriate external service client based on TechnologyAdapter    
                TechnologyAdapter clientType = request.getTechnologyAdapter();
                IExternalServiceClient client = getClient(clientType);

                log.debug("[ROUTE] Routing serviceId={} to {} external service client", request.getServiceId(), clientType);
                log.info("[DISPATCH] Sending request via {} client for serviceId={}", clientType, request.getServiceId());

                // Execute the external call
                String response = client.SendRequest(request.getArgsCopy());

                long duration = System.currentTimeMillis() - startTime;

                log.info("[RESPONSE_BODY] {}", response);
                log.info("[SUCCESS] External API call completed | serviceId={}", request.getServiceId());

                future.complete(ExecutionResponseDTO.success(
                        request.getServiceId(),
                        response,
                        duration
                ));

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("[ERROR] External API call failed on thread: {} | serviceId={}, duration={}ms, error={}",
                        Thread.currentThread().getName(), request.getServiceId(), duration, e.getMessage(), e);
                Thread.currentThread().interrupt();
                future.complete(ExecutionResponseDTO.failure(
                        request.getServiceId(),
                        "External API error: " + e.getMessage(),
                        duration
                ));
            } finally {
                concurrencyLimit.release();
                log.debug("[SEMAPHORE] Permit released for serviceId={} | available={}",
                        request.getServiceId(), concurrencyLimit.availablePermits());
                runningServices.remove(executionId);
            }
        });

        runningServices.put(executionId, handle);
        // Guard against the task finishing (and removing itself) before the line above ran.
        if (handle.isDone()) {
            runningServices.remove(executionId);
        }

        return future;
    }

    @Override
    public boolean Stop(String executionId) {
        log.info("Stopping external API execution: {}", executionId);
        Future<?> handle = runningServices.remove(executionId);
        if (handle == null) {
            return false;
        }
        // Best-effort interrupt; the underlying HTTP call may keep running regardless.
        // Either way, we stop tracking and waiting for this service and its eventual
        // real outcome (if the request keeps running) is discarded.
        handle.cancel(true);
        return true;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ExternalApiRuntime");
        executorService.shutdown();
    }
}
