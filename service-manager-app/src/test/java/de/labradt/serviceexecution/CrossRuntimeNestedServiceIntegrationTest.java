package de.labradt.serviceexecution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.ServiceManager;
import de.labradt.serviceexecution.registry.ServiceRegistry;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hub for cross-runtime nested service execution integration tests. 
 * 
 * the only two runtimes that can actually trigger a nested call,
 * script via {@code triggerNestedService} bridge or a container curling
 * {@code $SERVICE_TRIGGER_NESTED_URL}) triggering a nested service of any runtime
 * (CONTAINER, SCRIPT, or EXTERNAL).
 */
@Tag("plugins")
@Slf4j
@Tag("integration")
@EnabledIf(value = "de.labradt.serviceexecution.TestEnvironment#port8082Free",
        disabledReason = "port 8082 is in use (stop the running service manager); this test binds the fixed port 8082")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class CrossRuntimeNestedServiceIntegrationTest {

    @BeforeEach
    void requiresRuntimePlugins() {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
    }

    @Autowired
    private ServiceManager serviceManager;

    @Autowired
    private ServiceRegistry serviceRegistry;

    @BeforeEach
    void registerNestedServices() {
        registerIfAbsent(ServiceDefinition.builder()
                .serviceId("nested-container")
                .displayName("Nested Container")
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .defaultArgs(Map.of("image", "hello-world:latest"))
                .build());
        registerIfAbsent(ServiceDefinition.builder()
                .serviceId("nested-lua")
                .displayName("Nested Lua")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .defaultArgs(Map.of("testContent", "return 'Hello from nested Lua'"))
                .build());
        registerIfAbsent(ServiceDefinition.builder()
                .serviceId("nested-lua-broken")
                .displayName("Nested Lua (always fails)")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .defaultArgs(Map.of("testContent", "error('Simulated nested failure')"))
                .build());
        registerIfAbsent(ServiceDefinition.builder()
                .serviceId("nested-external")
                .displayName("Nested External")
                .executionCategory(ExecutionCategory.EXTERNAL)
                .technologyAdapter(TechnologyAdapter.REST)
                .defaultArgs(Map.of("url", TestEnvironment.HTTPBIN_URL + "/post", "httpMethod", "POST"))
                .build());
    }

    private void registerIfAbsent(ServiceDefinition def) {
        try { serviceRegistry.register(def); } catch (RuntimeException ignored) {}
    }

    /**
     * Executes a service and polls until it reaches a terminal status, then returns the
     * cached result. Polls by the executionId returned from ExecuteServiceAsync
     */
    private ExecutionResponseDTO executeAndAwaitCompletion(ExecutionRequestDTO request) throws InterruptedException {
        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        String executionId = accepted.executionId();

        int retries = 0;
        ExecutionStatus status = serviceManager.CheckStatus(executionId);
        while (status != ExecutionStatus.COMPLETED && status != ExecutionStatus.FAILED && retries < 400) {
            Thread.sleep(100);
            retries++;
            status = serviceManager.CheckStatus(executionId);
        }

        assertEquals(ExecutionStatus.COMPLETED, status,
                "Expected " + request.getServiceId() + " to complete, but status was " + status);

        ExecutionResponseDTO result = serviceManager.GetResult(executionId);
        assertNotNull(result, "Expected a cached result for " + request.getServiceId());
        return result;
    }

    @Test
    @DisplayName("Nested execution gets its own executionId, individually pollable via CheckStatus/GetResult")
    public void nestedExecutionIsIndividuallyPollable() throws Exception {
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("nested-lua")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", "return 'Hello from nested Lua'"))
                .build();

        ExecutionResponseDTO response = serviceManager.ExecuteNestedServiceSync(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getExecutionId(), "Nested call should be stamped with its own executionId");

        assertEquals(ExecutionStatus.COMPLETED, serviceManager.CheckStatus(response.getExecutionId()));
        ExecutionResponseDTO cached = serviceManager.GetResult(response.getExecutionId());
        assertNotNull(cached, "Nested execution's own executionId should be individually pollable");
        assertEquals(response.getOutputData(), cached.getOutputData());
    }

    // ==================== LUA (parent) -> * (nested) ====================

    @Tag("docker")
    @Test
    @DisplayName("Test 1: Lua service script calls a nested container service")
    public void luaCallsNestedContainer() throws Exception {
        TestEnvironment.assumeDocker();
        log.info("=== Test 1: Lua script -> Nested Container Service ===");

        String luaScript = """
                print("Lua script starting...")
                local result = triggerNestedService("nested-container", {})
                return "Container returned: " .. result
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("lua-parent-service")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", luaScript))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutputData().contains("Container returned:"));
        log.info("Parent execution finished with output: {}", result.getOutputData());
    }

    @Test
    @DisplayName("Test 6: Lua service script calls a nested Lua script service")
    public void luaCallsNestedLua() throws Exception {
        log.info("=== Test 6: Lua script -> Nested Lua Script Service ===");

        String luaScript = """
                local result = triggerNestedService("nested-lua", {})
                return "Nested lua said: " .. result
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("lua-parent-to-lua")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", luaScript))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);

        assertTrue(result.isSuccess());
        assertEquals("Nested lua said: Hello from nested Lua", result.getOutputData());
    }

    @Test
    @DisplayName("Test 7: Lua service script calls a nested external REST service")
    public void luaCallsNestedExternal() throws Exception {
        TestEnvironment.assumeHttpbin();
        log.info("=== Test 7: Lua script -> Nested External API Service ===");

        String luaScript = """
                local result = triggerNestedService("nested-external", {payload = '{"source":"lua-parent"}'})
                return "External said: " .. result
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("lua-parent-to-external")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", luaScript))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutputData().contains("External said:"));
        assertTrue(result.getOutputData().contains("httpbin.org/post"),
                "httpbin echoes the request URL back in its JSON response");
    }

    @Test
    @DisplayName("Test 8: Lua script does NOT trigger a nested service when its condition is false")
    public void luaDoesNotTriggerNestedServiceWhenConditionFalse() throws Exception {
        log.info("=== Test 8: Lua script -> no nested call taken (false branch) ===");

        String luaScript = """
                local temp = tonumber(temperature)
                if temp > 80 then
                    return "Escalated: " .. triggerNestedService("nested-container", {})
                else
                    return "Temperature OK: " .. temperature
                end
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("lua-parent-conditional")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("temperature", "50", "testContent", luaScript))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);

        assertTrue(result.isSuccess());
        assertEquals("Temperature OK: 50", result.getOutputData(),
                "Output must be exactly the untouched branch - no nested container round-trip took place");
    }

    @Test
    @DisplayName("Test 9: Lua script gracefully handles a failing nested service")
    public void luaHandlesNestedServiceFailure() throws Exception {
        log.info("=== Test 9: Lua script -> nested service fails, parent recovers ===");

        String luaScript = """
                local result = triggerNestedService("nested-lua-broken")
                if result == "" then
                    return "FALLBACK: nested service failed, using default"
                else
                    return result
                end
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("lua-parent-resilient")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", luaScript))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);

        // The parent script itself completes successfully even though the nested call failed,
        // because triggerNestedService() only ever returns a string (never throws on failure).
        assertTrue(result.isSuccess());
        assertEquals("FALLBACK: nested service failed, using default", result.getOutputData());
    }

    @Tag("docker")
    @Test
    @DisplayName("Test 10: Lua script triggers multiple nested services, across runtimes, in one workflow")
    public void luaTriggersMultipleNestedServicesAcrossRuntimes() throws Exception {
        TestEnvironment.assumeDocker();
        log.info("=== Test 10: Lua script -> nested Lua then nested Container (chained) ===");

        String luaScript = """
                local step1 = triggerNestedService("nested-lua", {})
                local step2 = triggerNestedService("nested-container", {})
                return "STEP1[" .. step1 .. "] STEP2[" .. step2 .. "]"
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("lua-parent-multi-step")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", luaScript))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutputData().contains("STEP1[Hello from nested Lua]"),
                "First nested call (Lua target) should have completed before the second started");
        assertTrue(result.getOutputData().contains("STEP2["), "Second nested call (Container target) should also have run");
    }

    // ==================== CONTAINER (parent) -> * (nested) ====================

    @Tag("docker")
    @Test
    @DisplayName("Test 2: Container service calls a nested container service")
    public void containerCallsNestedContainer() throws Exception {
        TestEnvironment.assumeDocker();
        log.info("=== Test 2: Container -> Nested Container Service ===");

        // The parent container uses curl to hit the host's REST endpoint, invoking
        // another container via the nested-sync endpoint.
        String curlCommand = "curl -X POST -H 'Content-Type: application/json' " +
                "-d '{\"serviceIds\": [\"nested-container\"], \"dynamicArgs\": {}}' " +
                "$SERVICE_TRIGGER_NESTED_URL";

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("curl-parent-container")
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .args(Map.of(
                        "image", "alpine/curl:latest",
                        "CMD", curlCommand))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);
        assertTrue(result.isSuccess());
        log.info("Parent Container execution finished");
    }

    @Tag("docker")
    @Test
    @DisplayName("Test 3: Container service calls a nested lua script service")
    public void containerCallsNestedLua() throws Exception {
        TestEnvironment.assumeDocker();
        log.info("=== Test 3: Container -> Nested Lua Script Service ===");

        String curlCommand = "curl -X POST -H 'Content-Type: application/json' " +
                "-d '{\"serviceIds\": [\"nested-lua\"], \"dynamicArgs\": {}}' " +
                "$SERVICE_TRIGGER_NESTED_URL";

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("curl-parent-container-to-lua")
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .args(Map.of(
                        "image", "alpine/curl:latest",
                        "CMD", curlCommand))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);
        assertTrue(result.isSuccess());
        log.info("Parent Container execution finished");
    }

    @Tag("docker")
    @Test
    @DisplayName("Test 4: Container service calls a nested external API service")
    public void containerCallsNestedExternalApi() throws Exception {
        TestEnvironment.assumeDocker();
        TestEnvironment.assumeHttpbin();
        log.info("=== Test 4: Container -> Nested External API Service ===");

        String curlCommand = "curl -X POST -H 'Content-Type: application/json' " +
                "-d '{\"serviceIds\": [\"nested-external\"], \"dynamicArgs\": {}}' " +
                "$SERVICE_TRIGGER_NESTED_URL";

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("curl-parent-container-to-external")
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .args(Map.of(
                        "image", "alpine/curl:latest",
                        "CMD", curlCommand))
                .build();

        ExecutionResponseDTO result = executeAndAwaitCompletion(request);
        assertTrue(result.isSuccess());
        log.info("Parent Container execution finished");
    }

    @Tag("docker")
    @Test
    @DisplayName("Test 5: Container built from Dockerfile calls nested external API")
    public void containerBuiltFromDockerfileCallsNestedExternalApi() throws Exception {
        TestEnvironment.assumeDocker();
        TestEnvironment.assumeHttpbin();
        TestEnvironment.assumeEngine();
        log.info("=== Test 5: Container (from Dockerfile) -> Nested External API Service ===");

        String dockerfileContent = "FROM alpine:latest\n" +
                "RUN apk add --no-cache curl\n" +
                "ENTRYPOINT [\"sh\", \"-c\", \"curl -X POST -H 'Content-Type: application/json' " +
                "-d '{\\\"serviceIds\\\": [\\\"nested-external\\\"], \\\"dynamicArgs\\\": {}}' " +
                "$SERVICE_TRIGGER_NESTED_URL\"]\n";

        // Upload the Dockerfile to engine storage first and use the returned storage key as fileReference.
        String storageKey = uploadToEngineStorage("Dockerfile", dockerfileContent);

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("curl-parent-container-from-dockerfile")
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .fileReference(storageKey)
                .args(Map.of())
                .build();

        try {
            ExecutionResponseDTO result = executeAndAwaitCompletion(request);
            assertTrue(result.isSuccess());
            log.info("Container built from Dockerfile execution finished");
        } finally {
            deleteFromEngineStorage(storageKey);
        }
    }

    /**
     * Uploads content to the engine's file storage (POST /engine/storage/upload) and returns
     * the storage key ("fileReference") that services register with.
     */
    private String uploadToEngineStorage(String fileName, String content) throws Exception {
        String boundary = "----ServiceExecutionTestBoundary" + System.currentTimeMillis();
        String body = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                content + "\r\n" +
                "--" + boundary + "--\r\n";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(TestEnvironment.ENGINE_URL + "/engine/storage/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Expected upload to succeed, got " + response.statusCode() + ": " + response.body());

        JsonNode json = new ObjectMapper().readTree(response.body());
        String fileReference = json.get("fileReference").asText();
        log.info("Uploaded {} to engine storage with key={}", fileName, fileReference);
        return fileReference;
    }

    private void deleteFromEngineStorage(String storageKey) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(TestEnvironment.ENGINE_URL + "/engine/storage/" + storageKey))
                    .DELETE()
                    .build();
            client.send(httpRequest, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Failed to clean up engine storage key {}: {}", storageKey, e.getMessage());
        }
    }
}
