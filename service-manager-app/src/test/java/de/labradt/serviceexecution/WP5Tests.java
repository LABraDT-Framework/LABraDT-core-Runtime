package de.labradt.serviceexecution;

import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Container execution through ServiceManager
 * 
 * Tests both scenarios:
 * - Scenario A: Pull existing image (imageRef)
 * - Scenario B: Build image from Dockerfile
 * 
 * Requests are submitted as ExecutionRequestDTO to the ServiceManager.
 * The ServiceManager routes them through the registered ContainerRuntime,
 * which builds ContainerConfig and delegates to DockerAdapter for orchestration.
 */
@Slf4j
@Tag("docker")
@Tag("plugins")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WP5Tests {

    @BeforeEach
    void requireDocker() {
        TestEnvironment.assumeDocker();
    }

    @Autowired
    private ServiceManager serviceManager;
    
    private String lastExecutionId;

    @AfterEach
    void tearDown() {
        // Clean up any running executions via the service manager
        if (lastExecutionId != null) {
            try {
                serviceManager.StopService(lastExecutionId);
                log.info("✓ Cleaned up execution: {}", lastExecutionId);
            } catch (Exception e) {
                log.warn("Failed to clean up execution {}: {}", lastExecutionId, e.getMessage());
            }
        }
    }

    /**
     * Scenario A: Container execution with pre-existing image reference
     * 
     * Flow:
     * 1. Build ExecutionRequestDTO with ExecutionCategory.CONTAINER and imageRef
     * 2. Submit to ServiceManager which routes through ContainerRuntime
     * 3. ContainerRuntime builds ContainerConfig from request args
     * 4. DockerAdapter prepares (pulls) image
     * 5. DockerAdapter creates, starts container
     * 6. DockerAdapter captures logs
     * 7. DockerAdapter stops and removes container
     * 8. Response returned with output/logs
     */
    @Test
    void testScenarioAPullImageAndRun() throws Exception {
        log.info("=== Starting Scenario A: Pull Image and Run ===");
        
        String serviceId = "container-scenario-a-" + System.currentTimeMillis();

        // Build request with image reference
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId(serviceId)
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .args(Map.of(
                    "image", "nginx:alpine",
                    "startupTimeout", "10",
                    "stopTimeout", "30"
                ))
                .build();

        log.info("Submitting Scenario A request: serviceId={}, image=nginx:alpine", serviceId);

        // Submit to ServiceManager (proper entry point for service execution)
        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        lastExecutionId = accepted.executionId();
        assertTrue(accepted.status() == ExecutionStatus.ACCEPTED || accepted.status() == ExecutionStatus.RUNNING,
                "Expected ACCEPTED or RUNNING, got " + accepted.status());
        log.info("✓ Request submitted to ServiceManager with status: {}", accepted.status());

        // Retrieve the execution future from ServiceManager and wait for completion
        CompletableFuture<ExecutionResponseDTO> future = getExecutionFuture(accepted.executionId());
        assertNotNull(future, "Execution future should be available");
        
        ExecutionResponseDTO response = future.get(60, TimeUnit.SECONDS);
        
        assertNotNull(response, "Response should not be null");
        assertEquals(serviceId, response.getServiceId(), "Service ID should match");
        assertTrue(response.isSuccess(), "Execution should be successful. Error: " + response.getErrorMessage());
        assertNotNull(response.getOutputData(), "Output/logs should not be null");
        assertTrue(response.getOutputData().length() > 0, "Output should contain logs");
        
        log.info("✓ Scenario A completed successfully");
        log.info("  - Service ID: {}", serviceId);
        log.info("  - Status: {}", response.isSuccess() ? "SUCCESS" : "FAILED");
        log.info("  - Output length: {} bytes", response.getOutputData().length());
    }

    /**
     * Scenario B: Container execution with Dockerfile build
     * 
     * Flow:
     * 1. Create temporary Dockerfile
     * 2. Build ExecutionRequestDTO with fileReference pointing to Dockerfile
     * 3. Submit to ServiceManager which routes through ContainerRuntime
     * 4. ContainerRuntime reads Dockerfile and builds ContainerConfig
     * 5. DockerAdapter prepares image (builds from Dockerfile)
     * 6. DockerAdapter creates, starts container
     * 7. DockerAdapter captures logs
     * 8. DockerAdapter stops and removes container
     * 9. Response returned with output/logs
     */
    @Test
    void testScenarioBBuildAndRun(@TempDir Path tempDir) throws Exception {
        log.info("=== Starting Scenario B: Build from Dockerfile and Run ===");
        
        String serviceId = "container-scenario-b-" + System.currentTimeMillis();

        // Create temporary Dockerfile
        Path dockerfilePath = tempDir.resolve("Dockerfile");
        String dockerfileContent = "FROM nginx:alpine\n" +
                                   "EXPOSE 80\n" +
                                   "CMD [\"nginx\", \"-g\", \"daemon off;\"]\n";
        Files.writeString(dockerfilePath, dockerfileContent);
        log.info("✓ Created Dockerfile at: {}", dockerfilePath);

        // Build request with fileReference to Dockerfile
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId(serviceId)
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .fileReference(dockerfilePath.toString())
                .args(Map.of(
                        "image", "nginx:alpine",
                    "startupTimeout", "10",
                    "stopTimeout", "30"
                ))
                .build();

        log.info("Submitting Scenario B request: serviceId={}, dockerfile={}", serviceId, dockerfilePath);

        // Submit to ServiceManager (proper entry point for service execution)
        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        lastExecutionId = accepted.executionId();
        assertTrue(accepted.status() == ExecutionStatus.ACCEPTED || accepted.status() == ExecutionStatus.RUNNING,
                "Expected ACCEPTED or RUNNING, got " + accepted.status());
        log.info("✓ Request submitted to ServiceManager with status: {}", accepted.status());

        // Retrieve the execution future from ServiceManager and wait for completion
        CompletableFuture<ExecutionResponseDTO> future = getExecutionFuture(accepted.executionId());
        assertNotNull(future, "Execution future should be available");
        
        ExecutionResponseDTO response = future.get(120, TimeUnit.SECONDS);
        
        assertNotNull(response, "Response should not be null");
        assertEquals(serviceId, response.getServiceId(), "Service ID should match");
        assertTrue(response.isSuccess(), "Execution should be successful. Error: " + response.getErrorMessage());
        assertNotNull(response.getOutputData(), "Output/logs should not be null");
        assertTrue(response.getOutputData().length() > 0, "Output should contain logs");
        
        log.info("✓ Scenario B completed successfully");
        log.info("  - Service ID: {}", serviceId);
        log.info("  - Status: {}", response.isSuccess() ? "SUCCESS" : "FAILED");
        log.info("  - Output length: {} bytes", response.getOutputData().length());
    }

    // ==================== ERROR HANDLING ====================

    /**
     * Container execution with a deliberately invalid image reference
     */
    @Test
    @DisplayName("[ERROR HANDLING] ServiceManager propagates an invalid Docker image as a failure")
    void testServiceManagerHandlesInvalidImage() throws InterruptedException {
        String serviceId = "error-invalid-image-" + System.currentTimeMillis();

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId(serviceId)
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .args(Map.of(
                        "image", "this-image-definitely-does-not-exist:latest",
                        "startupTimeout", "10",
                        "stopTimeout", "30"
                ))
                .build();

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        lastExecutionId = accepted.executionId();
        assertEquals(ExecutionStatus.RUNNING, accepted.status());

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(accepted.executionId());
            if (currentStatus == ExecutionStatus.FAILED) {
                ExecutionResponseDTO result = serviceManager.GetResult(accepted.executionId());
                assertNotNull(result);
                assertNotNull(result.getErrorMessage());
                assertFalse(result.getErrorMessage().isEmpty());
                log.info("✓ [ERROR HANDLING] ServiceManager correctly propagated invalid image as failure: {}",
                        result.getErrorMessage());
                return;
            }
            Thread.sleep(100);
        }

        fail("Expected service to fail due to invalid Docker image");
    }

    /**
     * Helper: Retrieve the execution future from ServiceManager's internal result map
     * Uses reflection to access the private resultMap since it's not exposed via public API
     * This is a testing pattern necessary for integration tests.
     */
    @SuppressWarnings("java:S3011")  // Suppress warning about reflection accessibility
    private CompletableFuture<ExecutionResponseDTO> getExecutionFuture(String executionId) throws Exception {
        // Access the private resultMap via reflection
        java.lang.reflect.Field resultMapField = ServiceManager.class.getDeclaredField("resultMap");
        resultMapField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<ExecutionResponseDTO>> resultMap =
            (Map<String, CompletableFuture<ExecutionResponseDTO>>) resultMapField.get(serviceManager);

        CompletableFuture<ExecutionResponseDTO> future = resultMap.get(executionId);
        assertNotNull(future, "Future should exist in resultMap for executionId: " + executionId);
        return future;
    }
}

