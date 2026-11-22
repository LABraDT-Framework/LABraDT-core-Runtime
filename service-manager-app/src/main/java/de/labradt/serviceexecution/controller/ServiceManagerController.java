package de.labradt.serviceexecution.controller;

import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.dtos.RunningExecutionDTO;
import de.labradt.serviceexecution.core.api.dtos.ServiceTriggerCommand;
import java.util.List;
import de.labradt.serviceexecution.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for the Service Manager.
 *
 * POST /services/trigger        - async, called by the engine when a rule fires.
 *                                 Resolves all serviceIds from the registry, fires
 *                                 each concurrently, returns HTTP 202 immediately with
 *                                 an executionId per triggered serviceId. Final results
 *                                 arrive via POST /engine/result.
 *
 * POST /services/triggerNested  - sync, called by a running container.
 *                                 Accepts exactly one serviceId, blocks until
 *                                 complete, returns ExecutionResponseDTO inline.
 *
 * GET  /services/status?executionId=    - poll current status of one execution
 *                                 (ACCEPTED/RUNNING/COMPLETED/FAILED/CANCELLED/NOT_FOUND).
 *                                 Keyed by executionId, not serviceId - the same serviceId
 *                                 can have multiple concurrent/repeated runs.
 * GET  /services/result?executionId=    - full ExecutionResponseDTO (including outputData)
 *                                 for one completed execution; 404 if none cached.
 * GET  /services/history        - every completed ExecutionResponseDTO cached since the
 *                                 service manager started, across all services.
 * GET  /services/running        - every execution currently ACCEPTED or RUNNING.
 * POST /services/stop?executionId=      - stop a running (or pending) execution.
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping({"/service-manager", "/services"})
public class ServiceManagerController {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String SERVICE_ERROR   = "SERVICE_ERROR";

    private final ServiceManager serviceManager;

    public ServiceManagerController(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
    }

    /**
     * Async trigger - called by the engine when a rule fires.
     * Resolves all service IDs from the registry, fires each via ExecuteServiceAsync
     * (fire-and-forget), and returns HTTP 202 immediately with the list of triggered IDs.
     * Final results arrive later via the engine's callback (POST /engine/result).
     */
    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(@RequestBody ServiceTriggerCommand command) {
        log.info("[ServiceManagerController] Async trigger: serviceIds={}", command.getServiceIds());
        try {
            List<ExecutionAcceptedDTO> triggered = serviceManager.resolveAndExecute(command);
            return ResponseEntity.accepted().body(new TriggerAcknowledgmentDTO(triggered, "Queued for execution"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(INVALID_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[ServiceManagerController] Unexpected error during async trigger", e);
            return ResponseEntity.status(500).body(new ErrorResponse(
                    SERVICE_ERROR, "Failed to trigger service(s): " + e.getMessage()));
        }
    }

    /**
     * Nested-service sync trigger - called by a running container via SERVICE_TRIGGER_NESTED_URL.
     * Receives exactly one serviceId, blocks until the nested service completes, and returns
     * the ExecutionResponseDTO directly so the calling container gets the result inline.
     */
    @PostMapping("/triggerNested")
    public ResponseEntity<ExecutionResponseDTO> triggerNested(@RequestBody ServiceTriggerCommand command) {
        log.info("[ServiceManagerController] Nested sync trigger: serviceId={}",
                command.getServiceIds() != null ? command.getServiceIds() : "null");
        String serviceId = (command.getServiceIds() != null && !command.getServiceIds().isEmpty())
                ? command.getServiceIds().get(0) : "unknown";
        try {
            ExecutionResponseDTO response = serviceManager.resolveAndExecuteNested(command);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ServiceManagerController] Nested trigger failed for {}: {}", serviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ExecutionResponseDTO.failure(serviceId, SERVICE_ERROR + ": " + e.getMessage(), 0L));
        }
    }

    /**
     * Check the status of a running execution
     *
     * GET http://localhost:PORT/service-manager/status?executionId=...
     */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> checkStatus(@RequestParam String executionId) {
        log.debug("[ServiceManagerController] Status check: executionId={}", executionId);

        ExecutionStatus status = serviceManager.CheckStatus(executionId);

        return ResponseEntity.ok(new StatusResponse(
                executionId,
                status,
                "Current status for execution: " + status
        ));
    }

    /**
     * Return the full result (including outputData) for a completed execution.
     *
     * GET http://localhost:PORT/services/result?executionId=...
     */
    @GetMapping("/result")
    public ResponseEntity<Object> getResult(@RequestParam String executionId) {
        ExecutionResponseDTO result = serviceManager.GetResult(executionId);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("NOT_FOUND", "No completed result for: " + executionId));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Return every completed execution result cached since the service manager started
     * (regardless of which client triggered it or which CLI session is asking).
     *
     * GET http://localhost:PORT/services/history
     */
    @GetMapping("/history")
    public ResponseEntity<List<ExecutionResponseDTO>> getHistory() {
        return ResponseEntity.ok(serviceManager.GetHistory());
    }

    /**
     * List every execution currently ACCEPTED or RUNNING.
     *
     * GET http://localhost:PORT/services/running
     */
    @GetMapping("/running")
    public ResponseEntity<List<RunningExecutionDTO>> getRunning() {
        return ResponseEntity.ok(serviceManager.GetRunningServices());
    }

    /**
     * Stop a running execution
     *
     * POST http://localhost:PORT/service-manager/stop?executionId=...
     */
    @PostMapping("/stop")
    public ResponseEntity<StopResponse> stopService(@RequestParam String executionId) {
        log.info("[ServiceManagerController] Stop request: executionId={}", executionId);

        boolean stopped = serviceManager.StopService(executionId);

        StopResponse response = new StopResponse(
                executionId,
                stopped,
                stopped ? "Service stopped successfully" : "Service not found or already completed"
        );

        return stopped
            ? ResponseEntity.ok(response)
            : ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    // ==================== Response DTOs ====================

    public record TriggerAcknowledgmentDTO(
        List<ExecutionAcceptedDTO> triggered,
        String message
    ) {}

    public record ExecutionStartedDTO(
            String serviceId,
            ExecutionStatus status,
            Long startedAt,
            String message
    ) {
        /**
         * Create accepted response with current timestamp
         */
        public static ExecutionStartedDTO accepted(String serviceId) {
            return accepted(serviceId, System.currentTimeMillis());
        }

        /**
         * Create accepted response with specified timestamp (for testing)
         */
        public static ExecutionStartedDTO accepted(String serviceId, Long timestamp) {
            return new ExecutionStartedDTO(
                    serviceId,
                    ExecutionStatus.ACCEPTED,
                    timestamp,
                    "Service execution request accepted and queued"
            );
        }

        /**
         * Create running response with current timestamp
         */
        public static ExecutionStartedDTO running(String serviceId) {
            return running(serviceId, System.currentTimeMillis());
        }

        /**
         * Create running response with specified timestamp (for testing)
         */
        public static ExecutionStartedDTO running(String serviceId, Long timestamp) {
            return new ExecutionStartedDTO(
                    serviceId,
                    ExecutionStatus.RUNNING,
                    timestamp,
                    "Service is already running"
            );
        }
    }

    public record StatusResponse(
            String executionId,
            ExecutionStatus status,
            String message
    ) {}

    public record StopResponse(
            String executionId,
            boolean success,
            String message
    ) {}

    public record ErrorResponse(
            String error,
            String message
    ) {}
}
