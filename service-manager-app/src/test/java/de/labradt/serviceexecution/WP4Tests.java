package de.labradt.serviceexecution;

import org.springframework.context.annotation.Import;
import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.ServiceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full End-to-End Integration Test: ServiceManager → ExternalApiRuntime → RestAdapter → target REST API.
 *
 * Requests are sent to https://httpbin.org, a public HTTP echo service,
 * tests GET/POST/PUT/DELETE calls.
 */
@Slf4j
@Import(StubScriptInterpreterConfig.class)
@SpringBootTest
class WP4Tests {

    @Autowired
    private ServiceManager serviceManager;

    private static final String HTTPBIN_BASE_URL = TestEnvironment.HTTPBIN_URL;
    private static final String GET_ENDPOINT = HTTPBIN_BASE_URL + "/get";
    private static final String POST_ENDPOINT = HTTPBIN_BASE_URL + "/post";
    private static final String PUT_ENDPOINT = HTTPBIN_BASE_URL + "/put";
    private static final String DELETE_ENDPOINT = HTTPBIN_BASE_URL + "/delete";
    // Deliberately returns HTTP 500, so we can exercise non-2xx error propagation.
    private static final String SERVER_ERROR_ENDPOINT = HTTPBIN_BASE_URL + "/status/500";
    // Nothing listens on this port, so the connection is refused immediately (fast, no DNS/timeout wait).
    private static final String UNREACHABLE_ENDPOINT = "http://localhost:1";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Prettify JSON response for better readability
     */
    private String prettifyJson(String response) {
        if (response == null || response.isBlank()) {
            return "(empty response)";
        }
        try {
            Object json = objectMapper.readValue(response, Object.class);
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            return response.substring(0, Math.min(200, response.length())) +
                    (response.length() > 200 ? "..." : "");
        }
    }

    /**
     * Execute a service through ServiceManager, poll for terminal status, and return the
     * cached ExecutionResponseDTO so callers can assert on success/output, not just status.
     */
    private ExecutionResponseDTO executeServiceAndWait(ExecutionRequestDTO request, long timeoutSeconds)
            throws InterruptedException {
        log.info("═════════════════════════════════════════════════════════");
        log.info("SERVICE MANAGER: Executing service request via orchestration layer");
        log.info("  - ServiceId: {}", request.getServiceId());
        log.info("  - ExecutionCategory: {}", request.getExecutionCategory());
        log.info("  - TechnologyAdapter: {}", request.getTechnologyAdapter());
        log.info("═════════════════════════════════════════════════════════\n");

        // Submit to ServiceManager (entry point of orchestration layer)
        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        assertEquals(ExecutionStatus.RUNNING, accepted.status(), "Service should be in RUNNING state after submission");
        String executionId = accepted.executionId();

        log.info("✓ Service submitted to ServiceManager: Status = {}", accepted.status());
        log.info("  [ORCHESTRATION] Request routed through ServiceManager");
        log.info("  [ROUTING] ExternalApiRuntime selected for ExecutionCategory.EXTERNAL");
        log.info("  [ADAPTER] RestAdapter selected for TechnologyAdapter.REST\n");

        // Poll for completion with timeout
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(executionId);

            if (currentStatus == ExecutionStatus.COMPLETED || currentStatus == ExecutionStatus.FAILED) {
                log.info("✓ Service execution completed with status: {}\n", currentStatus);
                ExecutionResponseDTO result = serviceManager.GetResult(executionId);
                assertNotNull(result, "Expected a cached result for " + request.getServiceId());
                return result;
            }

            Thread.sleep(100); // Poll every 100ms
        }

        fail("Service execution timed out after " + timeoutSeconds + " seconds");
        return null;
    }

    /**
     * Helper to create ExecutionRequestDTO for external API calls
     */
    private ExecutionRequestDTO createExternalRequest(String serviceId, String url, String httpMethod,
                                                     String payload, Map<String, String> queryParams) {
        Map<String, String> args = new HashMap<>();
        args.put("url", url);
        args.put("httpMethod", httpMethod);

        if (payload != null) {
            args.put("payload", payload);
        }

        if (queryParams != null && !queryParams.isEmpty()) {
            try {
                String queryParamsJson = objectMapper.writeValueAsString(queryParams);
                args.put("queryParams", queryParamsJson);
            } catch (Exception e) {
                log.error("Failed to serialize query params", e);
            }
        }

        return ExecutionRequestDTO.builder()
                .serviceId(serviceId)
                .executionCategory(ExecutionCategory.EXTERNAL)
                .technologyAdapter(TechnologyAdapter.REST)
                .args(args)
                .build();
    }

    // ==================== ORCHESTRATION LAYER TESTS ====================

    @Tag("integration")
    @Test
    @DisplayName("[ORCHESTRATION] ServiceManager routes EXTERNAL execution to correct runtime")
    @Tag("plugins")
    void testServiceManagerRoutesExternalExecution() {
        TestEnvironment.assumeHttpbin();
        log.info("Test: ServiceManager runtime routing for EXTERNAL type\n");

        ExecutionRequestDTO request = createExternalRequest(
                "routing-test-001",
                GET_ENDPOINT,
                "GET",
                null,
                null
        );

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);

        assertEquals(ExecutionStatus.RUNNING, accepted.status(), "Service should be routed to EXTERNAL runtime");
        log.info("✓ ServiceManager correctly routed request to EXTERNAL runtime");
        log.info("  Status: {}\n", accepted.status());
    }

    // ==================== FULL FLOW TESTS: GET ====================

    @Tag("integration")
    @Test
    @DisplayName("[FULL FLOW] GET: Simple request via ServiceManager orchestration")
    @Tag("plugins")
    void testFullFlowGet() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        ExecutionRequestDTO request = createExternalRequest(
                "full-flow-get",
                GET_ENDPOINT,
                "GET",
                null,
                null
        );

        ExecutionResponseDTO response = executeServiceAndWait(request, 10);

        assertTrue(response.isSuccess(), "Execution should succeed");
        assertNotNull(response.getOutputData(), "Response body should be present");
        assertTrue(response.getOutputData().contains("httpbin.org/get"),
                "httpbin echoes the request URL back in its JSON response");
        log.info("✓ [FULL FLOW] GET: request completed via ServiceManager");
        log.info("  Response:\n" + prettifyJson(response.getOutputData()).replaceAll("(?m)^", "    "));
        log.info("  Execution Time: {}ms\n", response.getExecutionDurationMs());
    }

    @Tag("integration")
    @Test
    @DisplayName("[FULL FLOW] GET: Request with query parameters is echoed back")
    @Tag("plugins")
    void testFullFlowGetWithQueryParams() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("machine", "cnc-42");
        queryParams.put("status", "running");

        ExecutionRequestDTO request = createExternalRequest(
                "full-flow-get-query-params",
                GET_ENDPOINT,
                "GET",
                null,
                queryParams
        );

        ExecutionResponseDTO response = executeServiceAndWait(request, 10);

        assertTrue(response.isSuccess(), "Execution should succeed");
        assertTrue(response.getOutputData().contains("cnc-42"), "Query param value should be echoed back");
        assertTrue(response.getOutputData().contains("running"), "Query param value should be echoed back");
        log.info("✓ [FULL FLOW] GET: query parameters round-tripped via ServiceManager");
        log.info("  Response:\n" + prettifyJson(response.getOutputData()).replaceAll("(?m)^", "    "));
    }

    // ==================== FULL FLOW TESTS: POST ====================

    @Tag("integration")
    @Test
    @DisplayName("[FULL FLOW] POST: JSON payload via ServiceManager orchestration")
    @Tag("plugins")
    void testFullFlowPost() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        String payload = """
                {
                  "serviceId": "full-flow-post",
                  "status": "initialized"
                }""";

        ExecutionRequestDTO request = createExternalRequest(
                "full-flow-post",
                POST_ENDPOINT,
                "POST",
                payload,
                null
        );

        ExecutionResponseDTO response = executeServiceAndWait(request, 10);

        assertTrue(response.isSuccess(), "Execution should succeed");
        assertTrue(response.getOutputData().contains("\"status\": \"initialized\""),
                "httpbin echoes the posted JSON body back under its 'json' field");
        log.info("✓ [FULL FLOW] POST: payload delivered via ServiceManager");
        log.info("  Response:\n" + prettifyJson(response.getOutputData()).replaceAll("(?m)^", "    "));
        log.info("  Execution Time: {}ms\n", response.getExecutionDurationMs());
    }

    // ==================== FULL FLOW TESTS: PUT ====================

    @Tag("integration")
    @Test
    @DisplayName("[FULL FLOW] PUT: Update with payload and query parameters via ServiceManager")
    @Tag("plugins")
    void testFullFlowPut() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("reason", "status-update");

        String payload = "{\"status\": \"running\"}";

        ExecutionRequestDTO request = createExternalRequest(
                "full-flow-put",
                PUT_ENDPOINT,
                "PUT",
                payload,
                queryParams
        );

        ExecutionResponseDTO response = executeServiceAndWait(request, 10);

        assertTrue(response.isSuccess(), "Execution should succeed");
        assertTrue(response.getOutputData().contains("status-update"), "Query param should be echoed back");
        assertTrue(response.getOutputData().contains("\"status\": \"running\""), "Payload should be echoed back");
        log.info("✓ [FULL FLOW] PUT: request completed via ServiceManager with query params");
        log.info("  Response:\n" + prettifyJson(response.getOutputData()).replaceAll("(?m)^", "    "));
        log.info("  Execution Time: {}ms\n", response.getExecutionDurationMs());
    }

    // ==================== FULL FLOW TESTS: DELETE ====================

    @Tag("integration")
    @Test
    @DisplayName("[FULL FLOW] DELETE: Request via ServiceManager")
    @Tag("plugins")
    void testFullFlowDelete() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        ExecutionRequestDTO request = createExternalRequest(
                "full-flow-delete",
                DELETE_ENDPOINT,
                "DELETE",
                null,
                null
        );

        ExecutionResponseDTO response = executeServiceAndWait(request, 10);

        assertTrue(response.isSuccess(), "Execution should succeed");
        assertTrue(response.getOutputData().contains("httpbin.org/delete"),
                "httpbin echoes the request URL back in its JSON response");
        log.info("✓ [FULL FLOW] DELETE: request completed via ServiceManager");
        log.info("  Response:\n" + prettifyJson(response.getOutputData()).replaceAll("(?m)^", "    "));
        log.info("  Execution Time: {}ms\n", response.getExecutionDurationMs());
    }

    // ==================== ERROR HANDLING ====================

    @Test
    @DisplayName("[ERROR HANDLING] ServiceManager handles missing URL error gracefully")
    @Tag("plugins")
    void testServiceManagerHandlesMissingUrl() throws InterruptedException {
        TestEnvironment.requirePlugins(TestEnvironment.REST_PLUGIN);
        Map<String, String> args = new HashMap<>();
        args.put("httpMethod", "GET");
        // Intentionally omit URL

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("error-missing-url-sm")
                .executionCategory(ExecutionCategory.EXTERNAL)
                .technologyAdapter(TechnologyAdapter.REST)
                .args(args)
                .build();

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        assertEquals(ExecutionStatus.RUNNING, accepted.status());

        // Wait for completion
        // Note: Timeout is 15 seconds because the callback executor may be delayed
        // while HTTP retries are occurring (typically 3 retry attempts with delays)
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(accepted.executionId());
            if (currentStatus == ExecutionStatus.FAILED) {
                log.info("✓ [ORCHESTRATION] ServiceManager correctly propagated error for missing URL");
                return;
            }
            Thread.sleep(100);
        }

        fail("Expected service to fail due to missing URL");
    }

    @Tag("integration")
    @Test
    @DisplayName("[ERROR HANDLING] ServiceManager propagates a non-2xx response as a failure")
    @Tag("plugins")
    void testServiceManagerHandlesServerErrorStatus() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        ExecutionRequestDTO request = createExternalRequest(
                "error-server-500",
                SERVER_ERROR_ENDPOINT,
                "GET",
                null,
                null
        );

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        assertEquals(ExecutionStatus.RUNNING, accepted.status());

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(accepted.executionId());
            if (currentStatus == ExecutionStatus.FAILED) {
                ExecutionResponseDTO result = serviceManager.GetResult(accepted.executionId());
                assertNotNull(result);
                assertNotNull(result.getErrorMessage());
                log.info("✓ [ERROR HANDLING] ServiceManager correctly propagated HTTP 500 as failure: {}",
                        result.getErrorMessage());
                return;
            }
            Thread.sleep(100);
        }

        fail("Expected service to fail due to upstream HTTP 500");
    }

    @Test
    @DisplayName("[ERROR HANDLING] ServiceManager handles a refused connection")
    @Tag("plugins")
    void testServiceManagerHandlesUnreachableHost() throws InterruptedException {
        TestEnvironment.requirePlugins(TestEnvironment.REST_PLUGIN);
        ExecutionRequestDTO request = createExternalRequest(
                "error-unreachable-host",
                UNREACHABLE_ENDPOINT,
                "GET",
                null,
                null
        );

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        assertEquals(ExecutionStatus.RUNNING, accepted.status());

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(accepted.executionId());
            if (currentStatus == ExecutionStatus.FAILED) {
                log.info("✓ [ERROR HANDLING] ServiceManager correctly propagated connection failure");
                return;
            }
            Thread.sleep(100);
        }

        fail("Expected service to fail due to refused connection");
    }

    @Test
    @DisplayName("[ERROR HANDLING] the Runtime continues to accept and correctly process new requests after a failed execution")
    void testServiceManagerRecoversAfterFailedExecution() throws InterruptedException {
        // Step 1: Submit a request that will deliberately fail (unreachable host). Without the Rest
        // plugin it fails earlier (no REST client registered); either way it must end as FAILED.
        ExecutionRequestDTO failingRequest = createExternalRequest(
                "error-recovery-failing-request",
                UNREACHABLE_ENDPOINT,
                "GET",
                null,
                null
        );

        ExecutionAcceptedDTO failingAccepted = serviceManager.ExecuteServiceAsync(failingRequest);
        assertEquals(ExecutionStatus.RUNNING, failingAccepted.status());
        log.info("Step 1: Submitted deliberately failing request, executionId={}", failingAccepted.executionId());

        long startTime = System.currentTimeMillis();
        boolean firstRequestFailed = false;
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(failingAccepted.executionId());
            if (currentStatus == ExecutionStatus.FAILED) {
                firstRequestFailed = true;
                log.info("✓ Step 1: First request failed as expected (status = FAILED)");
                break;
            }
            Thread.sleep(100);
        }

        if (!firstRequestFailed) {
            fail("Expected first request to fail (refused connection, or no REST client without the Rest plugin)");
        }

        // Step 2: Immediately submit a valid SCRIPT request
        ExecutionRequestDTO validRequest = ExecutionRequestDTO.builder()
                .serviceId("error-recovery-valid-request")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", "return 'recovery test successful'"))
                .build();

        ExecutionAcceptedDTO validAccepted = serviceManager.ExecuteServiceAsync(validRequest);
        assertEquals(ExecutionStatus.RUNNING, validAccepted.status(),
                "The Runtime should accept the new request with status RUNNING, unaffected by the prior failure");
        log.info("✓ Step 2: the Runtime accepted the new request immediately after the failure, executionId={}",
                validAccepted.executionId());

        // Step 3: Poll the new request until COMPLETED and assert it succeeded
        startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(validAccepted.executionId());
            if (currentStatus == ExecutionStatus.COMPLETED) {
                ExecutionResponseDTO result = serviceManager.GetResult(validAccepted.executionId());
                assertNotNull(result);
                assertTrue(result.isSuccess(), "New request should complete successfully");
                log.info("✓ Step 3: the Runtime correctly processed the new request to completion: {}",
                        result.getOutputData());
                log.info("✓ [ERROR HANDLING] the Runtime continues to accept and correctly process new requests after a failed execution\n");
                return;
            }
            if (currentStatus == ExecutionStatus.FAILED) {
                fail("New request unexpectedly failed after prior failure");
            }
            Thread.sleep(100);
        }

        fail("Expected new request to complete after prior failure");
    }

}
