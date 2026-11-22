package de.labradt.serviceexecution;

import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests thread pool behavior and concurrent requests across all execution categories
 * (SCRIPT, CONTAINER, EXTERNAL)
 *
 * CONTAINER/EXTERNAL batch sizes are deliberately smaller than the SCRIPT test's 1000
 * requests: both ContainerRuntime and ExternalApiRuntime gate real I/O (Docker daemon /
 * outbound HTTP) behind a 10-permit semaphore by default, so a larger batch there mostly
 * measures queueing behind that semaphore rather than exercising anything new
 */
@Tag("plugins")
@Slf4j
@Tag("integration")
@SpringBootTest
@DisplayName("Load Testing - Service Execution Under Stress")
class ConcurrencyLoadTest {

    @BeforeEach
    void requiresRuntimePlugins() {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
    }

    @Autowired
    private ServiceManager serviceManager;

    private static final int NUM_REQUESTS = 1000;
    private static final int NUM_CONTAINER_REQUESTS = 100;
    private static final int NUM_EXTERNAL_REQUESTS = 150;

    // Real Docker/network I/O behind a small semaphore means some transient failures
    // are expected; require a high pass rate, not 100%
    private static final double MIN_COMPLETION_RATIO = 0.90;

    private static final long CONTAINER_POLL_TIMEOUT_SECONDS = 300;
    private static final long EXTERNAL_POLL_TIMEOUT_SECONDS = 120;

    @BeforeEach
    void logStart(TestInfo testInfo) {
        log.info("========================================================================");
        log.info("TEST START: {} @ {}", testInfo.getDisplayName(), Instant.now());
        log.info("========================================================================");
    }

    @AfterEach
    void logEnd(TestInfo testInfo) {
        log.info("========================================================================");
        log.info("TEST END: {} @ {}", testInfo.getDisplayName(), Instant.now());
        log.info("========================================================================");
    }

    @Test
    @DisplayName("Execute 1000 concurrent SCRIPT requests and wait for completion")
    void loadTest_ScriptCategory() throws InterruptedException {

        CountDownLatch submissionLatch = new CountDownLatch(NUM_REQUESTS);
        AtomicInteger submittedCount = new AtomicInteger(0);
        AtomicInteger failedSubmissions = new AtomicInteger(0);
        List<String> submittedExecutionIds = Collections.synchronizedList(new ArrayList<>());

        log.info("=== SCRIPT Load Test: {} requests ===", NUM_REQUESTS);

        // Phase 1: Submit all requests
        for (int i = 1; i <= NUM_REQUESTS; i++) {
            try {
                ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                        .serviceId("completion-test-service-" + i)
                        .executionCategory(ExecutionCategory.SCRIPT)
                        .technologyAdapter(TechnologyAdapter.LUA)
                        .args(Map.of(
                                "requestId", String.valueOf(i),
                                "timestamp", String.valueOf(System.currentTimeMillis()),
                                "testContent", "return 'Service " + i + " executed'"
                        ))
                        .build();

                ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);

                if (accepted.status() == ExecutionStatus.RUNNING) {
                    submittedCount.incrementAndGet();
                    submittedExecutionIds.add(accepted.executionId());
                    log.debug("Request {} submitted successfully", i);
                } else {
                    failedSubmissions.incrementAndGet();
                    log.error("Request {} submission failed with status: {}", i, accepted.status());
                }

            } catch (Exception e) {
                failedSubmissions.incrementAndGet();
                log.error("Request {} threw exception during submission: {}", i, e.getMessage(), e);
            } finally {
                submissionLatch.countDown();
            }
        }

        // Phase 2: Wait for all submitted services to complete
        log.info("=== SCRIPT Waiting for Execution Phase ===");
        boolean allCompleted = false;
        while (!allCompleted) {
            allCompleted = true;
            // Check status of all submitted services
            for (String executionId : submittedExecutionIds) {
                ExecutionStatus status = serviceManager.CheckStatus(executionId);

                if (status == ExecutionStatus.RUNNING) {
                    allCompleted = false;
                    break; // Found one still running, wait and retry
                }
            }

            // If still running, wait a bit before checking again
            if (!allCompleted) {
                Thread.sleep(100);
            }
        }

        // Final status check
        int[] counts = tally(submittedExecutionIds);
        int completedCount = counts[0];
        int failedExecutions = counts[1];

        // Give async echo notifications time to complete before test ends
        Thread.sleep(500);

        log.info("=== SCRIPT Load Test Results ===");
        log.info("Total requests submitted: {}", submittedCount.get());
        log.info("Successfully completed: {}", completedCount);
        log.info("Failed executions: {}", failedExecutions);
        log.info("Test complete");
    }

    @Tag("docker")
    @Test
    @DisplayName("Execute 100 concurrent CONTAINER requests and wait for completion")
    void loadTest_ContainerCategory() throws InterruptedException {
        TestEnvironment.assumeDocker();
        log.info("=== CONTAINER Load Test: {} requests ===", NUM_CONTAINER_REQUESTS);

        List<String> submittedExecutionIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger submittedCount = new AtomicInteger(0);
        AtomicInteger failedSubmissions = new AtomicInteger(0);

        long submitStart = System.currentTimeMillis();

        for (int i = 1; i <= NUM_CONTAINER_REQUESTS; i++) {
            try {
                ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                        .serviceId("container-load-" + i)
                        .executionCategory(ExecutionCategory.CONTAINER)
                        .technologyAdapter(TechnologyAdapter.DOCKER)
                        .args(Map.of(
                                "image", "hello-world:latest",
                                "startupTimeout", "30",
                                "stopTimeout", "30"
                        ))
                        .build();

                ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);

                if (accepted.status() == ExecutionStatus.RUNNING) {
                    submittedCount.incrementAndGet();
                    submittedExecutionIds.add(accepted.executionId());
                } else {
                    failedSubmissions.incrementAndGet();
                    log.error("Container request {} submission failed with status: {}", i, accepted.status());
                }
            } catch (Exception e) {
                failedSubmissions.incrementAndGet();
                log.error("Container request {} threw exception during submission: {}", i, e.getMessage(), e);
            }
        }

        long submitEnd = System.currentTimeMillis();
        log.info("=== CONTAINER Submission Phase Complete ({}ms) ===", submitEnd - submitStart);
        log.info("Submitted: {}, Failed submissions: {}", submittedCount.get(), failedSubmissions.get());

        log.info("=== CONTAINER Waiting for Execution Phase ===");
        boolean allTerminal = pollUntilAllTerminal(submittedExecutionIds, CONTAINER_POLL_TIMEOUT_SECONDS);
        long executionEnd = System.currentTimeMillis();
        log.info("All CONTAINER executions reached a terminal state: {} (total wall time from submission start: {}ms)",
                allTerminal, executionEnd - submitStart);

        int[] counts = tally(submittedExecutionIds);
        int completedCount = counts[0];
        int failedExecutions = counts[1];

        log.info("=== CONTAINER Load Test Results ===");
        log.info("Total requests submitted: {}", submittedCount.get());
        log.info("Successfully completed: {}", completedCount);
        log.info("Failed executions: {}", failedExecutions);
        log.info("Test complete");

        assertTrue(allTerminal, "Not all CONTAINER executions reached a terminal state within "
                + CONTAINER_POLL_TIMEOUT_SECONDS + "s");

        double completionRatio = submittedCount.get() == 0 ? 0 : (double) completedCount / submittedCount.get();
        assertTrue(completionRatio >= MIN_COMPLETION_RATIO,
                () -> String.format("Expected at least %.0f%% of CONTAINER executions to COMPLETE, got %.1f%% (%d/%d)",
                        MIN_COMPLETION_RATIO * 100, completionRatio * 100, completedCount, submittedCount.get()));
    }

    @Test
    @DisplayName("Execute 150 concurrent EXTERNAL requests and wait for completion")
    void loadTest_ExternalCategory() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        log.info("=== EXTERNAL Load Test: {} requests ===", NUM_EXTERNAL_REQUESTS);

        List<String> submittedExecutionIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger submittedCount = new AtomicInteger(0);
        AtomicInteger failedSubmissions = new AtomicInteger(0);

        long submitStart = System.currentTimeMillis();

        for (int i = 1; i <= NUM_EXTERNAL_REQUESTS; i++) {
            try {
                ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                        .serviceId("external-load-" + i)
                        .executionCategory(ExecutionCategory.EXTERNAL)
                        .technologyAdapter(TechnologyAdapter.REST)
                        .args(Map.of(
                                "url", TestEnvironment.HTTPBIN_URL + "/get",
                                "httpMethod", "GET"
                        ))
                        .build();

                ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);

                if (accepted.status() == ExecutionStatus.RUNNING) {
                    submittedCount.incrementAndGet();
                    submittedExecutionIds.add(accepted.executionId());
                } else {
                    failedSubmissions.incrementAndGet();
                    log.error("External request {} submission failed with status: {}", i, accepted.status());
                }
            } catch (Exception e) {
                failedSubmissions.incrementAndGet();
                log.error("External request {} threw exception during submission: {}", i, e.getMessage(), e);
            }
        }

        long submitEnd = System.currentTimeMillis();
        log.info("=== EXTERNAL Submission Phase Complete ({}ms) ===", submitEnd - submitStart);
        log.info("Submitted: {}, Failed submissions: {}", submittedCount.get(), failedSubmissions.get());

        log.info("=== EXTERNAL Waiting for Execution Phase ===");
        boolean allTerminal = pollUntilAllTerminal(submittedExecutionIds, EXTERNAL_POLL_TIMEOUT_SECONDS);
        long executionEnd = System.currentTimeMillis();
        log.info("All EXTERNAL executions reached a terminal state: {} (total wall time from submission start: {}ms)",
                allTerminal, executionEnd - submitStart);

        int[] counts = tally(submittedExecutionIds);
        int completedCount = counts[0];
        int failedExecutions = counts[1];

        log.info("=== EXTERNAL Load Test Results ===");
        log.info("Total requests submitted: {}", submittedCount.get());
        log.info("Successfully completed: {}", completedCount);
        log.info("Failed executions: {}", failedExecutions);
        log.info("Test complete");

        assertTrue(allTerminal, "Not all EXTERNAL executions reached a terminal state within "
                + EXTERNAL_POLL_TIMEOUT_SECONDS + "s");

        double completionRatio = submittedCount.get() == 0 ? 0 : (double) completedCount / submittedCount.get();
        assertTrue(completionRatio >= MIN_COMPLETION_RATIO,
                () -> String.format("Expected at least %.0f%% of EXTERNAL executions to COMPLETE, got %.1f%% (%d/%d)",
                        MIN_COMPLETION_RATIO * 100, completionRatio * 100, completedCount, submittedCount.get()));
    }

    /**
     * Polls CheckStatus for every executionId until none remain ACCEPTED/RUNNING
     */
    private boolean pollUntilAllTerminal(List<String> executionIds, long timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            boolean allTerminal = true;
            for (String executionId : executionIds) {
                ExecutionStatus status = serviceManager.CheckStatus(executionId);
                if (status == ExecutionStatus.ACCEPTED || status == ExecutionStatus.RUNNING) {
                    allTerminal = false;
                    break;
                }
            }
            if (allTerminal) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /**
     * @return {completedCount, failedCount}
     */
    private int[] tally(List<String> executionIds) {
        int completed = 0;
        int failed = 0;
        for (String executionId : executionIds) {
            ExecutionStatus status = serviceManager.CheckStatus(executionId);
            if (status == ExecutionStatus.COMPLETED) {
                completed++;
            } else if (status == ExecutionStatus.FAILED) {
                failed++;
            }
        }
        return new int[]{completed, failed};
    }
}
