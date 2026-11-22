package de.labradt.serviceexecution.runtime.container;

import de.labradt.serviceexecution.runtime.container.orchestrator.ContainerConfig;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.runtime.container.orchestrator.ContainerStatus;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.core.api.interfaces.IRuntimeProvider;
import de.labradt.serviceexecution.runtime.container.orchestrator.IContainerOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class ContainerRuntime implements IRuntimeProvider {

    @Value("${server.port:8080}")
    private String port;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${engine.storage.base-url:http://localhost:8080}")
    private String engineStorageBaseUrl;

    private final RestClient restClient;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Level 2 routing
    private final Map<TechnologyAdapter, IContainerOrchestrator> orchestratorRegistry;

    // Each task gets its own lightweight virtual thread
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    // Limits concurrent container operations to prevent overwhelming the container backend
    private final Semaphore concurrencyLimit;

    // Track running container metadata: executionId -> {containerId, TechnologyAdapter}
    private static class ContainerMetadata {
        final String containerId;
        final TechnologyAdapter environment;
        final ContainerConfig config;

        ContainerMetadata(String containerId, TechnologyAdapter environment, ContainerConfig config) {
            this.containerId = containerId;
            this.environment = environment;
            this.config = config;
        }
    }

    private final ConcurrentHashMap<String, ContainerMetadata> runningServices = new ConcurrentHashMap<>();

    public ContainerRuntime(List<IContainerOrchestrator> orchestrators,
                             @Qualifier("storageRestClient") RestClient restClient,
                             @Value("${container.runtime.max-concurrent-containers:10}") int maxConcurrentContainers) {

        this.restClient = restClient;
        this.concurrencyLimit = new Semaphore(maxConcurrentContainers);
        log.info("Container concurrency limit set to {}", maxConcurrentContainers);

        log.info("=== Auto-Discovering Container Orchestrators ===");

        Map<TechnologyAdapter, IContainerOrchestrator> tempRegistry = new HashMap<>();

        if (orchestrators != null && !orchestrators.isEmpty()) {
            for (IContainerOrchestrator orchestrator : orchestrators) {
                TechnologyAdapter env = orchestrator.getOrchestratorType();
                log.info("✓ Auto-registered orchestrator: {} → {}", env, orchestrator.getClass().getSimpleName());
                tempRegistry.put(env, orchestrator);
            }
        } else {
            log.warn("No container orchestrators configured. ContainerRuntime will not be able to execute containers.");
        }

        this.orchestratorRegistry = Collections.unmodifiableMap(tempRegistry);

        log.info("=== Container Orchestrator Registry Complete ===");
        log.info("Total orchestrators registered: {}", orchestratorRegistry.size());
    }

    private IContainerOrchestrator getOrchestrator(TechnologyAdapter environment) {
        IContainerOrchestrator orchestrator = orchestratorRegistry.get(environment);
        if (orchestrator == null) {
            throw new IllegalArgumentException("No container orchestrator found for type: " + environment);
        }
        return orchestrator;
    }

    // For testing
    public Map<TechnologyAdapter, IContainerOrchestrator> getOrchestratorRegistry() {
        return orchestratorRegistry;
    }

    @Override
    public ExecutionCategory getExecutionCategory() {
        return ExecutionCategory.CONTAINER;
    }

    @Override
    public CompletableFuture<ExecutionResponseDTO> ExecuteAsync(ExecutionRequestDTO request) {
        log.info("ContainerRuntime: ExecuteAsync - serviceId={}, executionId={}, technologyAdapter={}",
                request.getServiceId(), request.getExecutionId(), request.getTechnologyAdapter());

        CompletableFuture<ExecutionResponseDTO> future = new CompletableFuture<>();
        String executionId = request.getExecutionId();

        executorService.submit(() -> {
            long startTime = System.currentTimeMillis();
            String containerId = null;
            
            try {
                // Acquire semaphore permit
                concurrencyLimit.acquire();
                log.debug("[SEMAPHORE] Permit acquired for serviceId={} | available={}",
                        request.getServiceId(), concurrencyLimit.availablePermits());

                // Validate request
                request.validate();

                // Get the appropriate orchestrator from registry
                IContainerOrchestrator orchestrator = getOrchestrator(request.getTechnologyAdapter());
                log.debug("[ROUTE] Routing to {} orchestrator", request.getTechnologyAdapter());

                // build ContainerConfig from request
                ContainerConfig config = buildContainerConfig(request, orchestrator);

                // Container lifecycle: pull → create → start → getLogs → stop
                // Prepare image (handles both Scenario A: pull and Scenario B: build)
                String imageRef = orchestrator.prepareImage(config, request.getServiceId());
                config.setImageRef(imageRef);
                log.info("[PREPARE] ✓ Image prepared: {}", imageRef);

                containerId = orchestrator.createContainer(config);
                // Update running services with actual containerId
                runningServices.put(executionId, new ContainerMetadata(containerId, request.getTechnologyAdapter(), config));
                log.info("[LIFECYCLE] ✓ Container created for serviceId={}, containerId={}", request.getServiceId(), containerId);

                orchestrator.startContainer(containerId, config);
                log.info("[LIFECYCLE] ✓ Container started for serviceId={}", request.getServiceId());

                String outputData = orchestrator.getLogs(containerId);
                log.info("[LIFECYCLE] ✓ Logs captured for serviceId={}", request.getServiceId());

                ContainerStatus exitStatus = orchestrator.getStatus(containerId);
                long duration = System.currentTimeMillis() - startTime;

                if (exitStatus == ContainerStatus.FAILED) {
                    log.warn("[FAILED] Container exited non-zero for serviceId={}, duration={}ms", request.getServiceId(), duration);
                    future.complete(ExecutionResponseDTO.failure(
                            request.getServiceId(),
                            outputData.isBlank() ? "Container exited with non-zero exit code" : outputData.trim(),
                            duration
                    ));
                } else {
                    log.info("[SUCCESS] Container execution completed on thread: {} | serviceId={}, duration={}ms", Thread.currentThread().getName(), request.getServiceId(), duration);
                    future.complete(ExecutionResponseDTO.success(
                            request.getServiceId(),
                            outputData,
                            duration
                    ));
                }

            } catch (IllegalArgumentException e) {
                log.error("[ERROR] Invalid request for serviceId={}: {}", request.getServiceId(), e.getMessage());
                future.complete(ExecutionResponseDTO.failure(
                        request.getServiceId(),
                        "Invalid container config: " + e.getMessage(),
                        System.currentTimeMillis() - startTime
                ));
            } catch (Exception e) {
                log.error("[ERROR] Container execution failed on thread: {} | serviceId={}, duration={}ms, error={}",
                        Thread.currentThread().getName(), request.getServiceId(), 
                        System.currentTimeMillis() - startTime, e.getMessage(), e);
                future.complete(ExecutionResponseDTO.failure(
                        request.getServiceId(),
                        "Container execution error: " + e.getMessage(),
                        System.currentTimeMillis() - startTime
                ));

            } finally {
                // Clean up: stop and remove container
                if (containerId != null) {
                    try {
                        ContainerMetadata meta = runningServices.get(executionId);
                        if (meta != null) {
                            IContainerOrchestrator orchestrator = getOrchestrator(request.getTechnologyAdapter());
                            orchestrator.stopContainer(containerId, meta.config);
                            log.info("[CLEANUP] Container stopped and removed for executionId={}", executionId);
                        } else {
                            log.debug("[CLEANUP] No metadata left for executionId={} - already stopped elsewhere", executionId);
                        }
                    } catch (Exception e) {
                        log.error("[CLEANUP] Failed to stop container {}: {}", containerId, e.getMessage());
                    }
                }

                concurrencyLimit.release();
                log.debug("[SEMAPHORE] Permit released for serviceId={} | available={}", request.getServiceId(), concurrencyLimit.availablePermits());
                runningServices.remove(executionId);
                log.debug("[CLEANUP] Execution removed from running list: {}", executionId);
            }
        });

        return future;
    }

    // Builds ContainerConfig from ExecutionRequestDTO
    private ContainerConfig buildContainerConfig(ExecutionRequestDTO request, IContainerOrchestrator orchestrator) throws IllegalArgumentException {
        
        String imageRef = null;
        File buildFile = null;

        if(request.getArgs().get("image") != null && !request.getArgs().get("image").isBlank()){
            imageRef = request.getArgs().get("image");
        }
        else if(request.getFileReference() != null && !request.getFileReference().isBlank()) {
            // Fetch the build file (Dockerfile, Containerfile, ...) from engine storage and write to temp file
            buildFile = fetchBuildFileFromStorage(request.getFileReference());
        }
        else{
            throw new IllegalArgumentException("Container execution requires either args['image'] or fileReference to specify the container image");
        }

        long startupTimeoutSeconds = Long.parseLong(
            request.getArgs().getOrDefault("startupTimeout", "10")
        );

        long stopTimeoutSeconds = Long.parseLong(
            request.getArgs().getOrDefault("stopTimeout", "30")
        );

        // Extract the container command, if present. Two forms are supported:
        //   CMD_ARGS - a JSON array, e.g. ["cmd", "/c", "..."] or ["/mytool"] - passed to the
        //              container exactly as given. Use this when the target image has no POSIX
        //              shell (Windows containers, distroless images) or needs argv-form control.
        //   CMD      - a plain shell string, e.g. "echo hello && curl ...". Auto-wrapped in
        //              ["sh", "-c", ...] as a convenience default for ordinary Linux images.
        // If both are supplied, CMD_ARGS wins and CMD is ignored (warning is logged)
        String[] command = null;
        String cmdArgsArg = request.getArgs().get("CMD_ARGS");
        String cmdArg = request.getArgs().get("CMD");
        if (cmdArgsArg != null && !cmdArgsArg.isBlank()) {
            command = parseCommandArgs(cmdArgsArg);
            log.info("[CONFIG] ✓ Container command taken from CMD_ARGS, passed through unwrapped");
            log.debug("[CONFIG] Command array: {}", java.util.Arrays.toString(command));
            if (cmdArg != null && !cmdArg.isBlank()) {
                log.warn("[CONFIG] Both CMD and CMD_ARGS were supplied - CMD_ARGS takes precedence, CMD is ignored");
            }
        } else if (cmdArg != null && !cmdArg.isBlank()) {
            // For shell commands, wrap in sh -c to ensure proper interpretation
            command = new String[]{"sh", "-c", cmdArg};
            log.info("[CONFIG] ✓ Container command extracted and wrapped with sh -c");
            log.debug("[CONFIG] Command array: {}", java.util.Arrays.toString(command));
            log.debug("[CONFIG] Raw CMD arg: {}", cmdArg);
        }

        // All args become environment variables in the container
        var envVars = new HashMap<>(request.getArgs());
        envVars.remove("image"); // Remove non-env args
        envVars.remove("startupTimeout");
        envVars.remove("stopTimeout");
        envVars.remove("CMD");      // CMD is handled separately as container command
        envVars.remove("CMD_ARGS"); // CMD_ARGS is handled separately as container command
        envVars.remove("ports");  // Ports handled separately
        envVars.remove("volumes"); // Volumes handled separately

        // Inject service manager URLs so the container can trigger other services.
        // SERVICE_TRIGGER_URL        → POST /services/trigger        (async, fire-and-forget, no result)
        // SERVICE_TRIGGER_NESTED_URL → POST /services/triggerNested  (sync, blocks until nested service done, returns result)
        String serviceManagerBaseUrl = orchestrator.getServiceManagerBaseUrl(port, contextPath);
        if (serviceManagerBaseUrl != null && !serviceManagerBaseUrl.isBlank()) {
            envVars.put("SERVICE_TRIGGER_URL",        serviceManagerBaseUrl + "/services/trigger");
            envVars.put("SERVICE_TRIGGER_NESTED_URL", serviceManagerBaseUrl + "/services/triggerNested");
        } else {
            log.warn("[CONFIG] Orchestrator {} cannot make the service manager reachable from a container; "
                    + "SERVICE_TRIGGER_URL/SERVICE_TRIGGER_NESTED_URL not injected, nested service calls will fail",
                    orchestrator.getOrchestratorType());
        }

        // Parse port mappings if provided (format: "8080:8080,3000:3000")
        Map<Integer, Integer> ports = new HashMap<>();
        String portsArg = request.getArgs().get("ports");
        if (portsArg != null && !portsArg.isBlank()) {
            try {
                String[] portMappings = portsArg.split(",");
                for (String mapping : portMappings) {
                    String[] parts = mapping.trim().split(":");
                    if (parts.length == 2) {
                        int internalPort = Integer.parseInt(parts[0]);
                        int externalPort = Integer.parseInt(parts[1]);
                        ports.put(internalPort, externalPort);
                    }
                }
                log.debug("[CONFIG] ✓ Port mappings configured: {}", ports);
            } catch (NumberFormatException e) {
                log.warn("[CONFIG] Invalid port mapping format: {}", portsArg);
            }
        }

        // Parse volumes if provided (format: "/data:/data,/config:/etc/config")
        List<String> volumes = null;
        String volumesArg = request.getArgs().get("volumes");
        if (volumesArg != null && !volumesArg.isBlank()) {
            volumes = java.util.Arrays.asList(volumesArg.split(","));
            for (int i = 0; i < volumes.size(); i++) {
                volumes.set(i, volumes.get(i).trim());
            }
            log.debug("[CONFIG] ✓ Volumes configured: {}", volumes);
        }

        return ContainerConfig.builder()
                .imageRef(imageRef)
                .envVars(envVars)
                .command(command)
                .startupTimeoutSeconds(startupTimeoutSeconds)
                .stopTimeoutSeconds(stopTimeoutSeconds)
                .buildFile(buildFile)
                .ports(ports)
                .volumes(volumes)
                .build();
    }

    @Override
    public boolean Stop(String executionId) {
        log.info("Stopping container execution: {}", executionId);

        ContainerMetadata metadata = runningServices.get(executionId);
        if (metadata == null) {
            log.warn("No running execution found for executionId: {}", executionId);
            return false;
        }

        if (metadata.containerId == null || metadata.containerId.isEmpty()) {
            log.warn("No container ID found for executionId: {}, removing from running services", executionId);
            runningServices.remove(executionId);
            return true;
        }

        try {
            IContainerOrchestrator orchestrator = getOrchestrator(metadata.environment);
            orchestrator.stopContainer(metadata.containerId, metadata.config);
            log.info("✓ Container stopped via {} orchestrator for executionId={}, containerId={}",
                    orchestrator.getOrchestratorType(), executionId, metadata.containerId);
        } catch (Exception e) {
            log.error("Failed to stop container {} for executionId={}: {}", metadata.containerId, executionId, e.getMessage(), e);
            return false;
        }

        runningServices.remove(executionId);
        log.debug("[CLEANUP] Execution removed from running list: {}", executionId);
        return true;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ContainerRuntime");
        executorService.shutdown();
    }

    /**
     * Fetches the build file content from the engine storage via HTTP and writes it 
     * to a temp file. The fileReference is the storage key returned by
     * POST /engine/storage/upload at registration time
     *
     * GET {engineStorageBaseUrl}/engine/storage/{fileReference}  ->  build file text
     */
    private File fetchBuildFileFromStorage(String fileReference) throws IllegalArgumentException {
        log.info("[STORAGE] Fetching build file from engine storage: key={}", fileReference);

        String storageUrl = engineStorageBaseUrl + "/engine/storage/" + fileReference;
        log.debug("[STORAGE] Storage endpoint: {}", storageUrl);

        try {
            String buildFileContent = restClient.get().uri(storageUrl).retrieve().body(String.class);

            if (buildFileContent == null || buildFileContent.isBlank()) {
                throw new IllegalArgumentException("Engine storage returned empty content for key: " + fileReference);
            }

            log.info("[STORAGE] ✓ Build file fetched ({} bytes)", buildFileContent.length());

            // Preserve the original filename
            String buildFileName = deriveBuildFileName(fileReference);
            // Write the build file content to a temp file
            Path tempDir = Files.createTempDirectory("container-build-" + System.currentTimeMillis());
            Path buildFilePath = tempDir.resolve(buildFileName);
            Files.writeString(buildFilePath, buildFileContent);

            log.info("[STORAGE] ✓ Build file written to temp: {}", buildFilePath);
            return buildFilePath.toFile();

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[STORAGE] Failed to fetch build file: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to fetch build file from engine storage: " + e.getMessage(), e);
        }
    }

    /*
     * Storage keys are of the form "services/<uuid>-<originalFileName>" (see
     * AASController#uploadToStorage), so the segment after the last '-' is the
     * name the file was actually uploaded under (Dockerfile, Containerfile, ...).
     * Falls back to "Dockerfile" if that can't be determined.
     */
    private String deriveBuildFileName(String fileReference) {
        if (fileReference == null || fileReference.isBlank()) {
            return "Dockerfile";
        }
        String basename = fileReference.substring(fileReference.lastIndexOf('/') + 1);
        int lastDash = basename.lastIndexOf('-');
        String candidate = (lastDash >= 0 && lastDash < basename.length() - 1)
                ? basename.substring(lastDash + 1)
                : basename;
        return candidate.isBlank() ? "Dockerfile" : candidate;
    }

    /*
     * Parses CMD_ARGS as a JSON array of strings, e.g. ["cmd", "/c", "echo hi"]
     */
    private String[] parseCommandArgs(String cmdArgsJson) {
        try {
            String[] parsed = objectMapper.readValue(cmdArgsJson, String[].class);
            if (parsed.length == 0) {
                throw new IllegalArgumentException("CMD_ARGS must not be an empty array");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "CMD_ARGS must be a JSON array of strings, e.g. [\"cmd\", \"/c\", \"echo hi\"]: " + e.getMessage(), e);
        }
    }
}
