package de.labradt.serviceexecution.manager;

import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.dtos.RunningExecutionDTO;
import de.labradt.serviceexecution.core.api.dtos.ServiceTriggerCommand;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.interfaces.IServiceOrchestrator;
import de.labradt.serviceexecution.core.api.interfaces.IRuntimeProvider;
import de.labradt.serviceexecution.manager.registry.ServiceDefinitionView;
import de.labradt.serviceexecution.manager.registry.ServiceRegistryResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ServiceManager implements IServiceOrchestrator {

    private final Map<ExecutionCategory, IRuntimeProvider> runtimeRegistry;

    private final ServiceRegistryResolver serviceRegistryResolver;

    // Pairs a tracked execution's status with the serviceId it belongs to, since serviceId
    // alone isn't unique per run and executionId is now the map key below.
    private record ExecutionInfo(String serviceId, ExecutionStatus status) {}

    // Tracks in-progress/terminal execution status, keyed by executionId 
    private final Map<String, ExecutionInfo> executionTracker = new ConcurrentHashMap<>();

    // Stores pending futures for cancellation, nested sync blocking, and cleanup, keyed by executionId
    private final Map<String, CompletableFuture<ExecutionResponseDTO>> resultMap = new ConcurrentHashMap<>();

    // Caches the final ExecutionResponseDTO after a service finishes (for polling clients), keyed by executionId
    private final Map<String, ExecutionResponseDTO> completedResults = new ConcurrentHashMap<>();

    private final RestClient restClient;

    @Value("${engine.result.endpoint:http://localhost:8080/engine/result}")
    private String engineResultEndpoint;

    // Thread pool for async completion handlers (callback POST requests, tracker updates)
    // Separated from runtime thread pools to prevent completion handlers from starving execution threads
    // 4 core threads, bounded queue of 100 tasks, CallerRunsPolicy for backpressure
    private final ThreadPoolExecutor callbackExecutor = new ThreadPoolExecutor(
        4,                              // core thread count
        4,                              // max thread count (fixed size)
        0, TimeUnit.SECONDS,            // no timeout (threads live forever)
        new LinkedBlockingQueue<>(100), // bounded queue prevents unbounded accumulation
        new ThreadFactory() {
            private final AtomicInteger threadCount = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("ServiceManager-CallbackPool-" + threadCount.incrementAndGet());
                t.setDaemon(false);
                log.debug("Created thread: {}", t.getName());
                return t;
            }
        },
        new ThreadPoolExecutor.CallerRunsPolicy() // rejected tasks run sync in caller thread
    );

    // Default timeout for async executions
    @Value("${service.manager.async-timeout-minutes:30}")
    private long asyncTimeoutMinutes;

    // Default timeout for nested synchronous executions
    @Value("${service.manager.sync-timeout-minutes:2}")
    private long syncTimeoutMinutes;

    /*
     * Constructor: Auto-discovers all runtime providers and builds the registry.
     * Spring automatically injects a List of ALL beans that implement IRuntimeProvider.
     */
    public ServiceManager(List<IRuntimeProvider> runtimes, ObjectProvider<ServiceRegistryResolver> serviceRegistryResolver, @Qualifier("restClient") RestClient restClient) {
        log.info("=== Auto-Discovering Runtime Providers ===");

        this.restClient = restClient;

        Map<ExecutionCategory, IRuntimeProvider> runtimeMap = new EnumMap<>(ExecutionCategory.class);

        for (IRuntimeProvider runtime : runtimes) {
            ExecutionCategory executionCategory = runtime.getExecutionCategory();
            log.info("✓ Auto-registered runtime: {} → {}", executionCategory, runtime.getExecutionCategory().name());
            runtimeMap.put(executionCategory, runtime);
        }

        // Make it immutable
        this.runtimeRegistry = Collections.unmodifiableMap(runtimeMap);
        this.serviceRegistryResolver = serviceRegistryResolver.getIfAvailable();

        log.info("=== Runtime Registry Complete ===");
        log.info("Total runtimes registered: {}", runtimeRegistry.size());
    }

    private IRuntimeProvider getRuntimeProvider(ExecutionCategory executionCategory) {
        IRuntimeProvider runtime = runtimeRegistry.get(executionCategory);
        if (runtime == null) {
            throw new IllegalArgumentException("No runtime provider found for ExecutionCategory: " + executionCategory);
        }
        return runtime;
    }

    // ==================== Core Execution Methods ====================

    /**
     * Resolves a single service ID from the registry and merges dynamic args on top of defaults.
     * Shared by both the async trigger path and the nested sync path.
     */
    private ExecutionRequestDTO resolveToRequest(String serviceId, Map<String, String> dynamicArgs) {
        ServiceDefinitionView def = serviceRegistryResolver.resolve(serviceId);
        Map<String, String> merged = new HashMap<>();
        if (def.getDefaultArgs() != null) merged.putAll(def.getDefaultArgs());
        if (dynamicArgs != null) merged.putAll(dynamicArgs);
        return ExecutionRequestDTO.builder()
                .serviceId(def.getServiceId())
                .executionCategory(def.getExecutionCategory())
                .technologyAdapter(def.getTechnologyAdapter())
                .fileReference(def.getFileReference())
                .args(merged)
                .build();
    }

    /**
     * Async trigger: resolves all service IDs from the registry and fires each with ExecuteServiceAsync
     * Called by POST /services/trigger
     */
    public List<ExecutionAcceptedDTO> resolveAndExecute(ServiceTriggerCommand command) {
        if (serviceRegistryResolver == null)
            throw new IllegalStateException("Service registry is not configured");
        if (command == null || command.getServiceIds() == null || command.getServiceIds().isEmpty())
            throw new IllegalArgumentException("serviceIds cannot be null or empty");

        List<ExecutionAcceptedDTO> triggered = new ArrayList<>();
        for (String serviceId : command.getServiceIds()) {
            triggered.add(ExecuteServiceAsync(resolveToRequest(serviceId, command.getDynamicArgs())));
        }
        return triggered;
    }

    /**
     * Nested-service sync trigger: resolves exactly one service ID and blocks until
     * ExecuteNestedServiceSync completes, returning the result directly to the caller
     */
    @Override
    public ExecutionResponseDTO resolveAndExecuteNested(ServiceTriggerCommand command) {
        if (serviceRegistryResolver == null)
            throw new IllegalStateException("Service registry is not configured");
        if (command == null || command.getServiceIds() == null || command.getServiceIds().size() != 1)
            throw new IllegalArgumentException("triggerNested requires exactly one serviceId");

        ExecutionRequestDTO request = resolveToRequest(
                command.getServiceIds().get(0), command.getDynamicArgs());
        try {
            return ExecuteNestedServiceSync(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResponseDTO.failure(request.getServiceId(), "Execution interrupted", 0L);
        }
    }

    public ExecutionAcceptedDTO ExecuteServiceAsync(ExecutionRequestDTO request) throws IllegalArgumentException {
        try {
            // Validate request integrity
            request.validate();

            log.info("[DISPATCH] ExecuteServiceAsync started on thread: {} | serviceId={}, type={}, runtime={}",
                    Thread.currentThread().getName(),
                    request.getServiceId(), request.getExecutionCategory(), request.getTechnologyAdapter());

            // Look up the runtime provider by type
            IRuntimeProvider runtime = getRuntimeProvider(request.getExecutionCategory());

            // A fresh identity for this specific dispatch
            // serviceId alone isn't unique per run, since the same service can be
            // triggered again while a prior run is still in progress
            String executionId = UUID.randomUUID().toString();
            String serviceId = request.getServiceId();
            ExecutionRequestDTO stamped = request.toBuilder().executionId(executionId).build();

            // Track execution status
            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.ACCEPTED));

            // First echo: Request accepted
            postResultToEngine(serviceId, executionId, ExecutionStatus.ACCEPTED, "Service queued for execution");

            // Dispatch to runtime - returns a CompletableFuture (non-blocking)
            CompletableFuture<ExecutionResponseDTO> future = runtime.ExecuteAsync(stamped);

            // Store future for tracking, cancellation, and nested sync support
            resultMap.put(executionId, future);

            // Update tracker to RUNNING
            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.RUNNING));
            postResultToEngine(serviceId, executionId, ExecutionStatus.RUNNING,
                    "Service dispatched to " + request.getExecutionCategory() + " runtime");

            // Wire up completion handlers (these run on callbackExecutor, not the runtime's thread)
            log.debug("[SUBMIT] Wiring completion handlers for serviceId={}, executionId={}", serviceId, executionId);
            future
                .orTimeout(asyncTimeoutMinutes, TimeUnit.MINUTES)
                .thenAcceptAsync(response -> {
                    try {
                        onJobComplete(executionId, response);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }, callbackExecutor)
                .exceptionally(throwable -> {
                    onJobFailed(executionId, serviceId, throwable);
                    return null;
                });

            return new ExecutionAcceptedDTO(executionId, serviceId, ExecutionStatus.RUNNING);

        } catch (Exception e) {
            String serviceId = (request != null && request.getServiceId() != null) ? request.getServiceId() : "unknown";
            log.error("Failed to execute service: serviceId={}, error={}", serviceId, e.getMessage(), e);
            // No executionId is minted on this path - dispatch never started, so no caller
            // ever learns an ID to poll a tracker entry by; there's nothing to record it under.
            postResultToEngine(serviceId, null, ExecutionStatus.FAILED, "Dispatch error: " + e.getMessage());
            throw new IllegalArgumentException("Execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Synchronous execution for nested service calls: Blocks until the result is available
     * Loopback path: Runtime → IServiceOrchestrator.ExecuteNestedServiceSync → ServiceManager → Runtime → result
     */
    public ExecutionResponseDTO ExecuteNestedServiceSync(ExecutionRequestDTO request) throws InterruptedException {
        request.validate();

        String executionId = UUID.randomUUID().toString();
        String serviceId = request.getServiceId();
        ExecutionRequestDTO stamped = request.toBuilder().executionId(executionId).build();
        CompletableFuture<ExecutionResponseDTO> future = null;

        try {
            log.info("ExecuteNestedServiceSync: serviceId={}, executionId={}, type={}, runtime={}",
                    serviceId, executionId, request.getExecutionCategory(), request.getTechnologyAdapter());

            IRuntimeProvider runtime = getRuntimeProvider(request.getExecutionCategory());

            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.ACCEPTED));

            // skip postResultToEngine because the parent top-level execution already reports its own outcome to the engine

            // Dispatch to runtime
            future = runtime.ExecuteAsync(stamped);
            resultMap.put(executionId, future);
            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.RUNNING));

            // Block and wait for the result (this is intentionally synchronous)
            ExecutionResponseDTO response = future.get(syncTimeoutMinutes, TimeUnit.MINUTES)
                    .toBuilder().executionId(executionId).build();

            ExecutionStatus finalStatus = response.isSuccess() ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
            executionTracker.put(executionId, new ExecutionInfo(serviceId, finalStatus));
            completedResults.put(executionId, response);
            resultMap.remove(executionId);

            log.info("Nested service {} completed: success={}", serviceId, response.isSuccess());
            return response;

        } catch (TimeoutException e) {
            log.error("Nested service {} timed out after {} minutes", serviceId, syncTimeoutMinutes);
            if (future != null) {
                future.cancel(true); 
            }
            ExecutionResponseDTO failed = ExecutionResponseDTO
                    .failure(serviceId, "Nested execution timed out after " + syncTimeoutMinutes + " minutes", 0L)
                    .toBuilder().executionId(executionId).build();
            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.FAILED));
            completedResults.put(executionId, failed);
            resultMap.remove(executionId);
            return failed;
        } catch (ExecutionException e) {
            log.error("Nested service {} failed: {}", serviceId, e.getCause().getMessage());
            ExecutionResponseDTO failed = ExecutionResponseDTO
                    .failure(serviceId, "Nested execution failed: " + e.getCause().getMessage(), 0L)
                    .toBuilder().executionId(executionId).build();
            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.FAILED));
            completedResults.put(executionId, failed);
            resultMap.remove(executionId);
            return failed;
        }
    }

    // ==================== Completion Handlers ====================

    /**
     * Called when a runtime's CompletableFuture completes normally.
     * Updates the tracker, cleans up the resultMap, and fires an echo to the Engine.
     */
    private void onJobComplete(String executionId, ExecutionResponseDTO response) throws InterruptedException {
        String serviceId = response.getServiceId();
        log.info("[COMPLETE] Job completed on thread: {} | serviceId={}, executionId={}, status={}, duration={}ms",
                Thread.currentThread().getName(), serviceId, executionId,
                response.isSuccess() ? "SUCCESS" : "FAILED",
                response.getExecutionDurationMs());

        ExecutionStatus finalStatus = response.isSuccess() ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
        ExecutionResponseDTO enriched = response.toBuilder().executionId(executionId).build();
        executionTracker.put(executionId, new ExecutionInfo(serviceId, finalStatus));
        completedResults.put(executionId, enriched);
        resultMap.remove(executionId);

        String info = response.isSuccess()
                ? (response.getOutputData() != null && !response.getOutputData().isBlank()
                    ? response.getOutputData().trim()
                    : "Completed in " + response.getExecutionDurationMs() + "ms")
                : "Failed: " + response.getErrorMessage();

        postResultToEngine(serviceId, executionId, finalStatus, info);
        log.debug("Service {} status updated: status={}", serviceId, finalStatus);
    }

    // Called when a runtime's CompletableFuture completes exceptionally (timeout, cancellation, unexpected error)
    private void onJobFailed(String executionId, String serviceId, Throwable throwable) {
        log.warn("[FAILED] Job failed on thread: {} | serviceId={}, executionId={}",
                Thread.currentThread().getName(), serviceId, executionId);

        executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.FAILED));
        resultMap.remove(executionId);

        // Unwrap CompletionException/ExecutionException to get the actual cause
        Throwable cause = throwable;
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            if (throwable.getCause() != null) {
                cause = throwable.getCause();
            }
        }

        String errorMessage;
        if (cause instanceof TimeoutException) {
            errorMessage = "Execution timed out after " + asyncTimeoutMinutes + " minutes";
        } else if (cause instanceof CancellationException) {
            errorMessage = "Execution was cancelled";
            executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.CANCELLED));
            postResultToEngine(serviceId, executionId, ExecutionStatus.CANCELLED, errorMessage);
            log.error("Service {} cancelled: {}", serviceId, errorMessage);
            return;
        } else {
            errorMessage = "Unexpected error: " + cause.getMessage();
        }

        ExecutionResponseDTO failedResult = ExecutionResponseDTO.failure(serviceId, errorMessage, 0L)
                .toBuilder().executionId(executionId).build();
        completedResults.put(executionId, failedResult);
        postResultToEngine(serviceId, executionId, ExecutionStatus.FAILED, errorMessage);
        log.error("Service {} failed exceptionally: {}", serviceId, errorMessage, throwable);
    }

    // ==================== Result Callbacks ====================

    private void postResultToEngine(String serviceId, String executionId, ExecutionStatus status, String info) {
        callbackExecutor.submit(() -> {
            log.debug("[NOTIFY] Posting result on thread: {} | serviceId={}, executionId={}, status={}",
                    Thread.currentThread().getName(), serviceId, executionId, status);
            try {
                // Build the response DTO using builder pattern
                ExecutionResponseDTO response = ExecutionResponseDTO.builder()
                        .serviceId(serviceId)
                        .executionId(executionId)
                        .status(status)
                        .success(status == ExecutionStatus.COMPLETED)
                        .outputData(info)
                        .completedAt(System.currentTimeMillis())
                        .build();

                // POST to the Engine's result endpoint using RestClient
                restClient.post()
                        .uri(engineResultEndpoint)
                        .body(response)
                        .retrieve()
                        .toBodilessEntity();

                log.trace("Result posted to engine: serviceId={}, executionId={}, status={}, endpoint={}",
                        serviceId, executionId, status, engineResultEndpoint);
            } catch (Exception e) {
                log.error("Error posting result to engine for serviceId={}: {}", serviceId, e.getMessage(), e);
            }
        });
    }

    // ==================== Service Lifecycle ====================

    public boolean StopService(String executionId) {
        log.info("Attempting to stop execution: {}", executionId);

        // Cancel the pending future (if it exists)
        CompletableFuture<ExecutionResponseDTO> future = resultMap.remove(executionId);
        if (future != null && !future.isDone()) {
            future.cancel(true); // may interrupt the running thread
            log.info("Cancelled pending future for execution: {}", executionId);
        }

        // Try to stop in each registered runtime
        for (IRuntimeProvider runtime : runtimeRegistry.values()) {
            if (runtime.Stop(executionId)) {
                // Record CANCELLED rather than removing the entry
                ExecutionInfo info = executionTracker.get(executionId);
                String serviceId = info != null ? info.serviceId() : "unknown";
                executionTracker.put(executionId, new ExecutionInfo(serviceId, ExecutionStatus.CANCELLED));
                postResultToEngine(serviceId, executionId, ExecutionStatus.CANCELLED, "Service stopped by request");
                return true;
            }
        }

        return false;
    }

    public ExecutionStatus CheckStatus(String executionId) {
        ExecutionInfo info = executionTracker.get(executionId);
        return info == null ? ExecutionStatus.NOT_FOUND : info.status();
    }

    public ExecutionResponseDTO GetResult(String executionId) {
        return completedResults.get(executionId);
    }

    // Returns every completed execution result cached since the service manager started.
    public List<ExecutionResponseDTO> GetHistory() {
        return new ArrayList<>(completedResults.values());
    }

    // Returns every execution currently tracked as ACCEPTED or RUNNING.
    public List<RunningExecutionDTO> GetRunningServices() {
        return executionTracker.entrySet().stream()
                .filter(e -> e.getValue().status() == ExecutionStatus.ACCEPTED || e.getValue().status() == ExecutionStatus.RUNNING)
                .map(e -> new RunningExecutionDTO(e.getKey(), e.getValue().serviceId(), e.getValue().status()))
                .collect(Collectors.toList());
    }

    // ==================== System Lifecycle ====================

    public Map<ExecutionCategory, IRuntimeProvider> getRuntimeRegistry() {
        return Collections.unmodifiableMap(runtimeRegistry);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ServiceManager");

        // Cancel all pending executions
        resultMap.forEach((executionId, future) -> {
            if (!future.isDone()) {
                future.cancel(true);
                log.info("Cancelled pending execution on shutdown: {}", executionId);
            }
        });
        resultMap.clear();

        callbackExecutor.shutdown();
        try {
            if (!callbackExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                callbackExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            callbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
