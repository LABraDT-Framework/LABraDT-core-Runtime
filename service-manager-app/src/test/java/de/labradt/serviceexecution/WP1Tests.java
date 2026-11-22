package de.labradt.serviceexecution;

import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.core.api.interfaces.IRuntimeProvider;
import de.labradt.serviceexecution.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test validates:
 * 1. ServiceManager successfully auto-discovers all runtime providers
 * 2. Requests are routed to the correct runtime based on ExecutionCategory
 * 3. The registry is properly populated and immutable
 */
@Slf4j
@SpringBootTest
public class WP1Tests {

    @Autowired
    private ServiceManager serviceManager;

    /**
     * Test 1: Verify registry is populated with all 3 runtimes
     */
    @Test
    public void testRuntimeRegistryPopulated() {
        log.info("=== Test 1: Runtime Registry Populated ===");
        
        Map<ExecutionCategory, IRuntimeProvider> registry = serviceManager.getRuntimeRegistry();
        
        // Assert all 3 runtimes are registered
        assertEquals(3, registry.size(), "Registry should contain exactly 3 runtimes");
        
        assertNotNull(registry.get(ExecutionCategory.SCRIPT), "SCRIPT runtime should be registered");
        assertNotNull(registry.get(ExecutionCategory.CONTAINER), "CONTAINER runtime should be registered");
        assertNotNull(registry.get(ExecutionCategory.EXTERNAL), "EXTERNAL runtime should be registered");
        
        log.info("✓ All 3 runtimes successfully registered:");
        registry.forEach((type, provider) -> 
            log.info("  - {} → {}", type, provider.getClass().getSimpleName())
        );
    }

    /**
     * Test 2: Route SCRIPT execution request
     */
    @Test
    public void testRouteScriptRequest() {
        log.info("=== Test 2: Route Script Request ===");
        
        // Create a fake script execution request
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
            .serviceId("test-script-001")
            .executionCategory(ExecutionCategory.SCRIPT)
            .technologyAdapter(TechnologyAdapter.LUA)
            .fileReference("test-script.lua")
            .args(Map.of("timeout", "30s", "mode", "debug"))
            .build();
        
        log.info("Created request: serviceId={}, type={}, env={}", 
            request.getServiceId(), request.getExecutionCategory(), request.getTechnologyAdapter());
        
        // Execute the request
        try {
            var accepted = serviceManager.ExecuteServiceAsync(request);
            log.info("✓ Request routed successfully. Status: {}", accepted.status());
            assertEquals(ExecutionStatus.RUNNING, accepted.status());
        } catch (Exception e) {
            fail("Failed to route script request: " + e.getMessage());
        }
    }

    /**
     * Test 3: Route CONTAINER execution request
     */
    @Test
    public void testRouteContainerRequest() {
        log.info("=== Test 3: Route Container Request ===");
        
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
            .serviceId("test-container-001")
            .executionCategory(ExecutionCategory.CONTAINER)
            .technologyAdapter(TechnologyAdapter.DOCKER)
            .fileReference("docker-image.tar")
            .args(Map.of("image", "ubuntu:22.04"))
            .build();
        
        log.info("Created request: serviceId={}, type={}, env={}", 
            request.getServiceId(), request.getExecutionCategory(), request.getTechnologyAdapter());
        
        try {
            var accepted = serviceManager.ExecuteServiceAsync(request);
            log.info("✓ Request routed successfully. Status: {}", accepted.status());
            assertEquals(ExecutionStatus.RUNNING, accepted.status());
        } catch (Exception e) {
            fail("Failed to route container request: " + e.getMessage());
        }
    }

    /**
     * Test 4: Route EXTERNAL API execution request
     */
    @Test
    public void testRouteExternalRequest() {
        log.info("=== Test 4: Route External API Request ===");
        
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
            .serviceId("test-external-001")
            .executionCategory(ExecutionCategory.EXTERNAL)
            .technologyAdapter(TechnologyAdapter.REST)
            .fileReference("")
            .args(Map.of("endpoint", "https://test.api", "timeout", "60s"))
            .build();
        
        log.info("Created request: serviceId={}, type={}, env={}", 
            request.getServiceId(), request.getExecutionCategory(), request.getTechnologyAdapter());
        
        try {
            var accepted = serviceManager.ExecuteServiceAsync(request);
            log.info("✓ Request routed successfully. Status: {}", accepted.status());
            assertEquals(ExecutionStatus.RUNNING, accepted.status());
        } catch (Exception e) {
            fail("Failed to route external request: " + e.getMessage());
        }
    }

    /**
     * Test 5: Request validation
     */
    @Test
    public void testRequestValidation() {
        log.info("=== Test 5: Request Validation ===");
        
        // Request with missing serviceId
        ExecutionRequestDTO invalidRequest = ExecutionRequestDTO.builder()
            .executionCategory(ExecutionCategory.SCRIPT)
            .technologyAdapter(TechnologyAdapter.LUA)
            .build();
        
        assertThrows(IllegalArgumentException.class, () -> {
            serviceManager.ExecuteServiceAsync(invalidRequest);
        }, "Should fail when serviceId is missing");
        
        log.info("✓ Validation correctly rejected request with missing serviceId");
    }

    /**
     * Test 6: Registry Immutability - Runtime registry cannot be modified after initialization
     */
    @Test
    public void testRegistryImmutability() {
        log.info("=== Test 6: Registry Immutability ===");
        
        var registry = serviceManager.getRuntimeRegistry();
        
        log.info("Registry contains {} runtimes", registry.size());
        
        assertThrows(UnsupportedOperationException.class, () -> {
            registry.put(ExecutionCategory.SCRIPT, null);
        }, "Registry should be immutable");
        
        log.info("✅ Registry is properly immutable");
    }

    /**
     * Test 7: Multiple Sequential Requests
     * Verify ServiceManager can handle multiple different request types in sequence
     */
    @Test
    public void testMultipleSequentialRequests() {
        log.info("=== Test 7: Multiple Sequential Requests ===");
        log.info("📝 Sending 3 different execution requests sequentially");
        
        // Request 1: Script
        ExecutionRequestDTO req1 = ExecutionRequestDTO.builder()
            .serviceId("test7-multi-001")
            .executionCategory(ExecutionCategory.SCRIPT)
            .technologyAdapter(TechnologyAdapter.LUA)
            .fileReference("script1.lua")
            .build();
        
        var accepted1 = serviceManager.ExecuteServiceAsync(req1);
        log.info("Request 1 (Script): {}", accepted1.status());
        assertEquals(ExecutionStatus.RUNNING, accepted1.status());
        
        // Request 2: Container
        ExecutionRequestDTO req2 = ExecutionRequestDTO.builder()
            .serviceId("test7-multi-002")
            .executionCategory(ExecutionCategory.CONTAINER)
            .technologyAdapter(TechnologyAdapter.DOCKER)
            .fileReference("ubuntu:22.04")
            .build();
        
        var accepted2 = serviceManager.ExecuteServiceAsync(req2);
        log.info("Request 2 (Container): {}", accepted2.status());
        assertEquals(ExecutionStatus.RUNNING, accepted2.status());
        
        // Request 3: External
        ExecutionRequestDTO req3 = ExecutionRequestDTO.builder()
            .serviceId("test7-multi-003")
            .executionCategory(ExecutionCategory.EXTERNAL)
            .technologyAdapter(TechnologyAdapter.REST)
            .build();
        
        var accepted3 = serviceManager.ExecuteServiceAsync(req3);
        log.info("Request 3 (External): {}", accepted3.status());
        assertEquals(ExecutionStatus.RUNNING, accepted3.status());
        
        log.info("✅ All 3 sequential requests routed successfully");
    }
}
