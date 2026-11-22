package de.labradt.serviceexecution.runtime.script;

import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.core.api.interfaces.IRuntimeProvider;
import de.labradt.serviceexecution.runtime.script.interpreter.IScriptInterpreter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class ScriptRuntime implements IRuntimeProvider {

    @Value("${engine.storage.base-url:http://localhost:8080}")
    private String engineStorageBaseUrl;

    private static final HttpClient ENGINE_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Level 2 routing: Script type interpreter registry
    private final Map<TechnologyAdapter, IScriptInterpreter> interpreterRegistry;

    // Thread pool for script executions
    // 4 core threads, bounded queue of 100 tasks, CallerRunsPolicy for backpressure
    private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(
            4, // core thread count
            4, // max thread count (fixed size)
            0, TimeUnit.SECONDS, // no timeout (threads live forever)
            new LinkedBlockingQueue<>(100), // bounded queue prevents unbounded accumulation
            new ThreadFactory() {
                private final AtomicInteger threadCount = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("ScriptRuntime-ExecutorPool-" + threadCount.incrementAndGet());
                    t.setDaemon(false);
                    log.debug("Created thread: {}", t.getName());
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // rejected tasks run sync in caller thread
    );

    // Tracks the executor Future for each in-progress script execution, keyed by
    // executionId. Used for cancellation and status tracking.
    private final ConcurrentHashMap<String, Future<?>> runningServices = new ConcurrentHashMap<>();

    // Constructor: Auto-discovers all script interpreters and builds the registry.
    public ScriptRuntime(List<IScriptInterpreter> interpreters) {
        log.info("=== Auto-Discovering Script Interpreters ===");

        // Build the interpreter registry
        Map<TechnologyAdapter, IScriptInterpreter> tempRegistry = new HashMap<>();

        for (IScriptInterpreter interpreter : interpreters) {
            TechnologyAdapter env = interpreter.getScriptType();
            log.info("✓ Auto-registered interpreter: {} → {}", env, interpreter.getClass().getSimpleName());
            tempRegistry.put(env, interpreter);
        }

        // Make immutable
        this.interpreterRegistry = Collections.unmodifiableMap(tempRegistry);

        log.info("=== Script Interpreter Registry Complete ===");
        log.info("Total interpreters registered: {}", interpreterRegistry.size());
    }

    private IScriptInterpreter getInterpreter(TechnologyAdapter environment) {
        IScriptInterpreter interpreter = interpreterRegistry.get(environment);
        if (interpreter == null) {
            throw new IllegalArgumentException("No script interpreter found for type: " + environment);
        }
        return interpreter;
    }

    // for testing purposes
    public Map<TechnologyAdapter, IScriptInterpreter> getInterpreterRegistry() {
        return interpreterRegistry;
    }

    @Override
    public ExecutionCategory getExecutionCategory() {
        return ExecutionCategory.SCRIPT;
    }

    @Override
    public CompletableFuture<ExecutionResponseDTO> ExecuteAsync(ExecutionRequestDTO request) {
        log.info("ScriptRuntime: ExecuteAsync - serviceId={}, executionId={}, scriptType={}",
                request.getServiceId(), request.getExecutionId(), request.getTechnologyAdapter());

        CompletableFuture<ExecutionResponseDTO> future = new CompletableFuture<>();
        String executionId = request.getExecutionId();

        // Submit to executor for async execution
        Future<?> handle = executorService.submit(() -> {
            long startTime = System.currentTimeMillis();
            log.info("[EXEC] Script execution started on thread: {} | serviceId={}, runtime={}",
                    Thread.currentThread().getName(), request.getServiceId(), request.getTechnologyAdapter());
            try {
                // Get the appropriate interpreter for this script type
                TechnologyAdapter environment = request.getTechnologyAdapter();
                IScriptInterpreter interpreter = getInterpreter(environment);

                log.debug("[ROUTE] Routing serviceId={} to {} interpreter", request.getServiceId(), environment);

                // Determine script content: either from testContent arg or from fileReference
                // Priority: fileReference > testContent arg
                String testContent = request.getArgsCopy().get("testContent");
                if (request.getFileReference() != null && !request.getFileReference().isBlank()) {
                    testContent = resolveScriptContent(request.getFileReference());
                }
                if (testContent == null || testContent.isBlank()) {
                    throw new IllegalArgumentException(
                            "No script content: set testContent (directly or via a 'testContent' arg) or provide a readable fileReference");
                }

                // Execute the script with the interpreter
                log.trace("[SCRIPT] Executing script content ({} chars) for serviceId={}",
                        testContent.length(), request.getServiceId());
                String output = interpreter.execute(testContent, request.getArgsCopy());

                long duration = System.currentTimeMillis() - startTime;

                log.info(
                        "[SUCCESS] Script execution completed on thread: {} | serviceId={}, duration={}ms, \nOutput\n{}",
                        Thread.currentThread().getName(), request.getServiceId(), duration, output);

                future.complete(ExecutionResponseDTO.success(
                        request.getServiceId(),
                        output,
                        duration));

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("[ERROR] Script execution failed on thread: {} | serviceId={}, duration={}ms, error={}",
                        Thread.currentThread().getName(), request.getServiceId(), duration, e.getMessage(), e);

                future.complete(ExecutionResponseDTO.failure(
                        request.getServiceId(),
                        "Script execution error: " + e.getMessage(),
                        duration));
            } finally {
                runningServices.remove(executionId);
                log.debug("[CLEANUP] Execution removed from running list: {}", executionId);
            }
        });

        runningServices.put(executionId, handle);
        // Guard against the task finishing (and removing itself) before the line above
        // ran.
        if (handle.isDone()) {
            runningServices.remove(executionId);
        }

        return future;
    }

    @Override
    public boolean Stop(String executionId) {
        log.info("Stopping script execution: {}", executionId);
        Future<?> handle = runningServices.remove(executionId);
        if (handle == null) {
            return false;
        }
        // Best-effort interrupt; not all interpreters honor it. Either way, we stop
        // tracking and waiting for this service here - its eventual outcome (if
        // the underlying script keeps running) is discarded rather than reported.
        handle.cancel(true);
        return true;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ScriptRuntime");
        executorService.shutdown();
    }

    /**
     * Fetches script content using fileReference from the engine storage via HTTP.
     */
    private String resolveScriptContent(String fileReference) throws Exception {
        String url = engineStorageBaseUrl + "/engine/storage/" + fileReference;
        log.info("[SCRIPT] Fetching script from engine storage: key={}", fileReference);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> resp = ENGINE_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalArgumentException(
                    "Engine storage returned HTTP " + resp.statusCode() + " for key: " + fileReference);
        }
        String content = resp.body();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Engine storage returned empty content for key: " + fileReference);
        }
        log.info("[SCRIPT] ✓ Script fetched from engine storage ({} bytes)", content.length());
        return content;
    }

}