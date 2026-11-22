package de.labradt.serviceexecution;

import org.junit.jupiter.api.Tag;
import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScriptRuntime Level 2 Routing Tests
 * 
 * 1. Auto-discovers all script interpreters
 * 2. Routes to the correct interpreter based on TechnologyAdapter
 */
@Slf4j
@SpringBootTest
public class WP2and3Tests {

    @Autowired
    private ServiceManager serviceManager;

    @Test
    @DisplayName("Route LUA script to LuaInterpreter")
    @Tag("plugins")
    public void testRouteLuaScript() {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
        log.info("=== Test 2: Route LUA Script ===");

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("test-lua-routing-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("x", "5", "y", "10", "testContent", "return 'LUA routing test successful'"))
                .build();

        log.info("📝 Request created:");
        log.info("   Script type: {}", request.getTechnologyAdapter());
        log.info("   Args: {}", request.getArgs());

        // Submit to ServiceManager (proper entry point for service execution)
        try {
            ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
            assertTrue(accepted.status() == ExecutionStatus.ACCEPTED || accepted.status() == ExecutionStatus.RUNNING,
                    "Expected ACCEPTED or RUNNING, got " + accepted.status());
            log.info("✓ Request submitted to ServiceManager with status: {}", accepted.status());

            // Retrieve the execution future from ServiceManager and wait for completion
            CompletableFuture<ExecutionResponseDTO> future = getExecutionFuture(accepted.executionId());
            ExecutionResponseDTO response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertNotNull(response, "Response should not be null");
            assertTrue(response.isSuccess(), "LUA script execution should be successful");
            log.info("✓ LUA script routed successfully to LuaInterpreter");
        } catch (Exception e) {
            fail("Failed to route LUA script: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Route BASH script to BashInterpreter")
    @Tag("plugins")
    public void testRouteBashScript() {
        TestEnvironment.assumeBash();
        log.info("=== Test 3: Route BASH Script ===");

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("test-bash-routing-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.BASH)
                .args(Map.of("env", "production", "verbose", "true", "testContent", "echo 'BASH routing test successful'"))
                .build();

        log.info("📝 Request created:");
        log.info("   Script type: {}", request.getTechnologyAdapter());
        log.info("   Args: {}", request.getArgs());

        // Submit to ServiceManager (proper entry point for service execution)
        try {
            ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
            assertTrue(accepted.status() == ExecutionStatus.ACCEPTED || accepted.status() == ExecutionStatus.RUNNING,
                    "Expected ACCEPTED or RUNNING, got " + accepted.status());
            log.info("✓ Request submitted to ServiceManager with status: {}", accepted.status());

            // Retrieve the execution future from ServiceManager and wait for completion
            CompletableFuture<ExecutionResponseDTO> future = getExecutionFuture(accepted.executionId());
            ExecutionResponseDTO response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertNotNull(response, "Response should not be null");
            assertTrue(response.isSuccess(), "BASH script execution should be successful");
            log.info("✓ BASH script routed successfully to BashInterpreter");
        } catch (Exception e) {
            fail("Failed to route BASH script: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Handle multiple requests with different interpreter types")
    @Tag("plugins")
    public void testMultipleInterpreterRequests() {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
        TestEnvironment.assumeBash();
        log.info("=== Test 5: Multiple Different Interpreter Types ===");

        // Request 1: LUA
        ExecutionRequestDTO luaRequest = ExecutionRequestDTO.builder()
                .serviceId("test-multi-lua-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", """
                        print("Hello from LUA script 1!")
                        return "LUA script 1 executed"
                        """))
                .build();

        // Request 2: BASH
        ExecutionRequestDTO bashRequest = ExecutionRequestDTO.builder()
                .serviceId("test-multi-bash-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.BASH)
                .args(Map.of("testContent", """
                        #!/bin/bash
                        echo "Hello from BASH script!"
                        """))
                .build();

        // Request 3: LUA again
        ExecutionRequestDTO luaRequest2 = ExecutionRequestDTO.builder()
                .serviceId("test-multi-lua-002")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", """
                        print("Hello from LUA script 2!")
                        return "LUA script 2 executed"
                        """))
                .build();

        try {
            log.info("Executing LUA request 1...");
            ExecutionAcceptedDTO accepted1 = serviceManager.ExecuteServiceAsync(luaRequest);
            CompletableFuture<ExecutionResponseDTO> f1 = getExecutionFuture(accepted1.executionId());

            log.info("Executing BASH request...");
            ExecutionAcceptedDTO accepted2 = serviceManager.ExecuteServiceAsync(bashRequest);
            CompletableFuture<ExecutionResponseDTO> f2 = getExecutionFuture(accepted2.executionId());

            log.info("Executing LUA request 2...");
            ExecutionAcceptedDTO accepted3 = serviceManager.ExecuteServiceAsync(luaRequest2);
            CompletableFuture<ExecutionResponseDTO> f3 = getExecutionFuture(accepted3.executionId());

            // Wait for all to complete
            f1.get(5, java.util.concurrent.TimeUnit.SECONDS);
            f2.get(5, java.util.concurrent.TimeUnit.SECONDS);
            f3.get(5, java.util.concurrent.TimeUnit.SECONDS);

            log.info("✓ All 3 requests routed successfully to their respective interpreters");
        } catch (Exception e) {
            fail("Failed to handle multiple interpreter requests: " + e.getMessage());
        }
    }

    /**
     * Full flow - LUA script execution
     */
    @Test
    @DisplayName("Full Flow: Execute LUA script with calculations")
    @Tag("plugins")
    public void testFullFlowLuaScriptExecution() throws Exception {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
        log.info("=== Test 5: Full Flow - LUA Script Execution ===");

        String luaScript = """
                -- Calculate sum and product
                local sum = tonumber(x) + tonumber(y)
                local product = tonumber(x) * tonumber(y)
                local avg = (sum) / 2
                            
                print("LUA Script Results:")
                print("x = " .. x)
                print("y = " .. y)
                print("sum = " .. sum)
                print("product = " .. product)
                print("average = " .. avg)
                            
                return "Calculation complete"
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("flow-test-lua-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("x", "15", "y", "25", "testContent", luaScript))
                .build();

        log.info("📋 Testing LUA Script Flow:");
        log.info("   Script: Lua calculation script");
        log.info("   Args: x=15, y=25");
        log.info("   Expected: Output with sum=40, product=375, avg=20.0");

        try {
            // Submit to ServiceManager (proper entry point for service execution)
            ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
            assertTrue(accepted.status() == ExecutionStatus.ACCEPTED || accepted.status() == ExecutionStatus.RUNNING,
                    "Expected ACCEPTED or RUNNING, got " + accepted.status());
            log.info("✓ Request submitted to ServiceManager with status: {}", accepted.status());

            // Retrieve the execution future from ServiceManager and wait for completion
            CompletableFuture<ExecutionResponseDTO> future = getExecutionFuture(accepted.executionId());
            ExecutionResponseDTO response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertNotNull(response, "Response should not be null");
            assertEquals(request.getServiceId(), response.getServiceId(), "Service ID should match");
            assertTrue(response.isSuccess(), "Execution should be successful");

            String output = response.getOutputData();
            assertNotNull(output, "Output should not be null");

            log.info("✓ Script executed successfully");
            log.info("   Status: {}", response.getStatus());
            log.info("   Duration: {}ms", response.getExecutionDurationMs());
            log.info("   Output:\n{}", output);

            assertTrue(output.contains("sum = 40"), "Output should contain sum = 40");
            assertTrue(output.contains("product = 375"), "Output should contain product = 375");
            assertTrue(output.contains("average = 20"), "Output should contain average = 20");
            assertTrue(output.contains("Calculation complete"), "Output should contain return value");

            // Verify execution duration was recorded
            assertNotNull(response.getExecutionDurationMs(), "Execution duration should be recorded");
            assertTrue(response.getExecutionDurationMs() > 0, "Execution duration should be positive");

            log.info("✓ All assertions passed - Full LUA flow test successful!");

        } catch (Exception e) {
            fail("LUA script execution failed: " + e.getMessage());
        }
    }

    /**
     * Full flow - BASH script execution
     */
    @Test
    @DisplayName("Full Flow: Execute BASH script")
    @Tag("plugins")
    public void testFullFlowBashScriptExecution() throws Exception {
        TestEnvironment.assumeBash();
        log.info("=== Test 6: Full Flow - BASH Script Execution ===");

        // Bash script that performs system operations
        String bashScript = """
                #!/bin/bash
                echo "BASH Script Results:"
                echo "Environment: $ENVIRONMENT"
                echo "Verbose Mode: $VERBOSE"
                            
                # Simple calculations in bash
                result=$((NUM1 + NUM2))
                echo "Calculation: $NUM1 + $NUM2 = $result"
                            
                # Check variables are set
                if [ -n "$ENVIRONMENT" ]; then
                    echo "Environment variable is properly set"
                fi
                            
                # Print current working directory
                echo "Working directory: $(pwd)"
                            
                echo "BASH execution completed successfully"
                """;

        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("flow-test-bash-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.BASH)
                .args(Map.of(
                        "ENVIRONMENT", "production",
                        "VERBOSE", "true",
                        "NUM1", "100",
                        "NUM2", "50",
                        "testContent", bashScript
                ))
                .build();

        log.info("📋 Testing BASH Script Flow:");
        log.info("   Script: Bash system operations script");
        log.info("   Args: ENVIRONMENT=production, VERBOSE=true, NUM1=100, NUM2=50");
        log.info("   Expected: Output with calculation result=150 and environment confirmation");

        try {
            // Submit to ServiceManager (proper entry point for service execution)
            ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
            assertTrue(accepted.status() == ExecutionStatus.ACCEPTED || accepted.status() == ExecutionStatus.RUNNING,
                    "Expected ACCEPTED or RUNNING, got " + accepted.status());
            log.info("✓ Request submitted to ServiceManager with status: {}", accepted.status());

            // Retrieve the execution future from ServiceManager and wait for completion
            CompletableFuture<ExecutionResponseDTO> future = getExecutionFuture(accepted.executionId());
            ExecutionResponseDTO response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertNotNull(response, "Response should not be null");
            assertEquals(request.getServiceId(), response.getServiceId(), "Service ID should match");
            assertTrue(response.isSuccess(), "Execution should be successful");

            String output = response.getOutputData();
            assertNotNull(output, "Output should not be null");

            log.info("✓ Script executed successfully");
            log.info("   Status: {}", response.getStatus());
            log.info("   Duration: {}ms", response.getExecutionDurationMs());
            log.info("   Output:\n{}", output);

            assertTrue(output.contains("BASH Script Results"), "Output should contain header");
            assertTrue(output.contains("production"), "Output should contain environment value");
            assertTrue(output.contains("true"), "Output should contain verbose value");
            assertTrue(output.contains("100 + 50 = 150"), "Output should contain calculation result");
            assertTrue(output.contains("Environment variable is properly set"), "Output should confirm variable");
            assertTrue(output.contains("BASH execution completed successfully"), "Output should contain completion message");

            // Verify execution duration was recorded
            assertNotNull(response.getExecutionDurationMs(), "Execution duration should be recorded");
            assertTrue(response.getExecutionDurationMs() > 0, "Execution duration should be positive");

            log.info("✓ All assertions passed - Full BASH flow test successful!");

        } catch (Exception e) {
            fail("BASH script execution failed: " + e.getMessage());
        }
    }

    /**
     * Full flow - Concurrent LUA and BASH execution
     * Verifies: both interpreters handle concurrent requests correctly
     */
    @Test
    @DisplayName("Full Flow: Concurrent LUA and BASH script execution")
    @Tag("plugins")
    public void testFullFlowConcurrentExecution() throws Exception {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
        TestEnvironment.assumeBash();
        log.info("=== Test 7: Full Flow - Concurrent Execution ===");

        String luaScript = """
                print("LUA: Starting calculation")
                result = tonumber(A) * tonumber(B)
                print("LUA: Result = " .. result)
                return result
                """;

        String bashScript = """
                #!/bin/bash
                echo "BASH: Starting operation"
                value=$(( X + Y ))
                echo "BASH: Result = $value"
                echo "BASH: Completed"
                """;

        ExecutionRequestDTO luaRequest = ExecutionRequestDTO.builder()
                .serviceId("concurrent-lua-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("A", "8", "B", "7", "testContent", luaScript))
                .build();

        ExecutionRequestDTO bashRequest = ExecutionRequestDTO.builder()
                .serviceId("concurrent-bash-001")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.BASH)
                .args(Map.of("X", "30", "Y", "15", "testContent", bashScript))
                .build();

        log.info("📋 Testing Concurrent Execution Flow:");
        log.info("   Submitting LUA script (A=8, B=7)");
        log.info("   Submitting BASH script (X=30, Y=15)");

        try {
            log.info("Submitting LUA script...");
            ExecutionAcceptedDTO acceptedLua = serviceManager.ExecuteServiceAsync(luaRequest);
            CompletableFuture<ExecutionResponseDTO> luaFuture = getExecutionFuture(acceptedLua.executionId());

            log.info("Submitting BASH script...");
            ExecutionAcceptedDTO acceptedBash = serviceManager.ExecuteServiceAsync(bashRequest);
            CompletableFuture<ExecutionResponseDTO> bashFuture = getExecutionFuture(acceptedBash.executionId());

            // Wait for both with timeout
            ExecutionResponseDTO luaResponse = luaFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
            ExecutionResponseDTO bashResponse = bashFuture.get(20, java.util.concurrent.TimeUnit.SECONDS);

            // Verify both responses
            assertNotNull(luaResponse, "LUA response should not be null");
            assertNotNull(bashResponse, "BASH response should not be null");

            assertTrue(luaResponse.isSuccess(), "LUA execution should be successful");
            assertTrue(bashResponse.isSuccess(), "BASH execution should be successful");

            log.info("✓ Both scripts executed successfully in parallel");

            // Verify LUA output
            assertTrue(luaResponse.getOutputData().contains("56"), "LUA should calculate 8*7=56");

            // Verify BASH output  
            assertTrue(bashResponse.getOutputData().contains("45"), "BASH should calculate 30+15=45");

            log.info("✓ All assertions passed - Concurrent flow test successful!");

        } catch (Exception e) {
            log.error("Concurrent test exception: {}", e.getClass().getName(), e);
            fail("Concurrent execution failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    // ==================== ERROR HANDLING ====================

    @Test
    @DisplayName("[ERROR HANDLING] ServiceManager propagates a LUA script failure")
    @Tag("plugins")
    public void testServiceManagerHandlesInvalidLuaScript() throws InterruptedException {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
        // Deliberately invalid Lua: unmatched "end" is a compile/syntax error, so
        // LuaInterpreter throws before the script ever runs.
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId("error-invalid-lua-script")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", "end"))
                .build();

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        assertEquals(ExecutionStatus.RUNNING, accepted.status());

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ExecutionStatus currentStatus = serviceManager.CheckStatus(accepted.executionId());
            if (currentStatus == ExecutionStatus.FAILED) {
                ExecutionResponseDTO result = serviceManager.GetResult(accepted.executionId());
                assertNotNull(result);
                assertNotNull(result.getErrorMessage());
                assertFalse(result.getErrorMessage().isEmpty());
                log.info("✓ [ERROR HANDLING] ServiceManager correctly propagated invalid LUA script as failure: {}",
                        result.getErrorMessage());
                return;
            }
            Thread.sleep(100);
        }

        fail("Expected service to fail due to invalid LUA script");
    }

    /**
     * Helper: Retrieve the execution future from ServiceManager's internal result map
     * Uses reflection to access the private resultMap since it's not exposed via public API
     * This is a testing pattern necessary for integration tests.
     */
    @SuppressWarnings("java:S3011")  // Suppress SonarQube warning about reflection accessibility
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
