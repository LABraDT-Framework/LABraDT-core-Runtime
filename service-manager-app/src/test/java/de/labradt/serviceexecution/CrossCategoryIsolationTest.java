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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that heavy SCRIPT-category load does not delay EXTERNAL-category execution, 
 * by that ScriptRuntime's dedicated thread pool and ExternalApiRuntime's dedicated 
 * semaphore/executor actually isolate the two categories from one another
 *
 * Method: 
 * 1. measure a baseline EXTERNAL request's submit-to-COMPLETED latency with no other load running 
 * 2. fire a large concurrent SCRIPT batch 
 * 3. shortly after that batch starts, measure several more EXTERNAL requests' individual 
 *    latencies while the SCRIPT batch is still in flight
 * If the categories are properly isolated, the under-load latencies should stay within a small multiple of baseline
 */
@Tag("plugins")
@Slf4j
@Tag("integration")
@SpringBootTest
@DisplayName("Load Testing - Cross-Category Isolation Under Concurrent Load")
class CrossCategoryIsolationTest {

    @BeforeEach
    void requiresRuntimePlugins() {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
    }

    @Autowired
    private ServiceManager serviceManager;

    private static final String EXTERNAL_URL = TestEnvironment.HTTPBIN_URL + "/get";

    private static final int SCRIPT_BATCH_SIZE = 800;
    private static final int EXTERNAL_UNDER_LOAD_COUNT = 8;

    private static final long SCRIPT_BATCH_HEAD_START_MS = 200;

    private static final long EXTERNAL_PROBE_TIMEOUT_SECONDS = 60;
    private static final long SCRIPT_BATCH_TERMINAL_TIMEOUT_SECONDS = 180;

    // How much slower an EXTERNAL probe is allowed to be under SCRIPT load vs. baseline
    // before we conclude the categories are NOT properly isolated. Loose on purpose: this
    // is meant to catch gross contention, not normal network jitter against a public API.
    private static final double MAX_SLOWDOWN_MULTIPLE = 3.0;

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
    @DisplayName("EXTERNAL latency under concurrent SCRIPT load stays within a bounded multiple of baseline")
    void crossCategoryIsolation_ExternalUnaffectedBySCRIPTLoad() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        // ---- Phase 1: baseline ----
        log.info("=== Phase 1: Baseline EXTERNAL request (no concurrent load) ===");
        Instant baselineWindowStart = Instant.now();
        long baselineMs = executeExternalAndMeasure("baseline-external");
        Instant baselineWindowEnd = Instant.now();
        log.info("Baseline EXTERNAL request completed in {}ms (window {} -> {})",
                baselineMs, baselineWindowStart, baselineWindowEnd);

        // ---- Phase 2: kick off a large concurrent SCRIPT batch on a background thread ----
        // Submitted from a background thread (not the test thread) because ScriptRuntime's
        // executor uses CallerRunsPolicy once its bounded queue fills: without this, a large
        // sequential submission loop would start running scripts inline on the submitting
        // thread and block us from firing the EXTERNAL probes "shortly after" as intended
        log.info("=== Phase 2: Starting concurrent SCRIPT batch ({} requests) in the background ===", SCRIPT_BATCH_SIZE);
        List<String> scriptExecutionIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger scriptSubmitted = new AtomicInteger(0);
        AtomicInteger scriptFailedSubmissions = new AtomicInteger(0);

        Instant scriptBatchWindowStart = Instant.now();
        long scriptBatchStartMs = System.currentTimeMillis();
        Thread scriptBatchThread = new Thread(() -> {
            for (int i = 1; i <= SCRIPT_BATCH_SIZE; i++) {
                try {
                    ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                            .serviceId("isolation-script-load-" + i)
                            .executionCategory(ExecutionCategory.SCRIPT)
                            .technologyAdapter(TechnologyAdapter.LUA)
                            .args(Map.of("testContent", "return 'script load " + i + "'"))
                            .build();

                    ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
                    if (accepted.status() == ExecutionStatus.RUNNING) {
                        scriptSubmitted.incrementAndGet();
                        scriptExecutionIds.add(accepted.executionId());
                    } else {
                        scriptFailedSubmissions.incrementAndGet();
                    }
                } catch (Exception e) {
                    scriptFailedSubmissions.incrementAndGet();
                    log.error("SCRIPT load request {} threw during submission: {}", i, e.getMessage());
                }
            }
        }, "script-batch-submitter");
        scriptBatchThread.start();

        Thread.sleep(SCRIPT_BATCH_HEAD_START_MS);

        // ---- Phase 3: fire EXTERNAL probes while the SCRIPT batch is still in flight ----
        log.info("=== Phase 3: {} EXTERNAL probes while SCRIPT batch is in flight (batch thread alive: {}) ===",
                EXTERNAL_UNDER_LOAD_COUNT, scriptBatchThread.isAlive());
        Instant underLoadWindowStart = Instant.now();
        List<Long> underLoadTimingsMs = new ArrayList<>();
        for (int i = 1; i <= EXTERNAL_UNDER_LOAD_COUNT; i++) {
            long elapsed = executeExternalAndMeasure("under-load-external-" + i);
            underLoadTimingsMs.add(elapsed);
            log.info("Under-load EXTERNAL probe {}/{} completed in {}ms (script batch thread alive: {})",
                    i, EXTERNAL_UNDER_LOAD_COUNT, elapsed, scriptBatchThread.isAlive());
        }
        Instant underLoadWindowEnd = Instant.now();

        // ---- Phase 4: let the SCRIPT batch finish submitting and reach a terminal state ----
        scriptBatchThread.join();
        long scriptSubmissionElapsedMs = System.currentTimeMillis() - scriptBatchStartMs;
        log.info("=== Phase 4: SCRIPT batch submission finished in {}ms (submitted={}, failedSubmissions={}) ===",
                scriptSubmissionElapsedMs, scriptSubmitted.get(), scriptFailedSubmissions.get());

        log.info("Waiting for SCRIPT batch to reach a terminal state for all {} submissions...", scriptSubmitted.get());
        boolean scriptBatchAllTerminal = pollUntilAllTerminal(scriptExecutionIds, SCRIPT_BATCH_TERMINAL_TIMEOUT_SECONDS);
        Instant scriptBatchWindowEnd = Instant.now();
        int[] scriptTally = tally(scriptExecutionIds);

        // ---- Results ----
        double avgUnderLoadMs = underLoadTimingsMs.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxUnderLoadMs = underLoadTimingsMs.stream().mapToLong(Long::longValue).max().orElse(0);
        double avgSlowdown = baselineMs == 0 ? 0 : avgUnderLoadMs / baselineMs;
        double maxSlowdown = baselineMs == 0 ? 0 : (double) maxUnderLoadMs / baselineMs;

        log.info("========================================================================");
        log.info("=== Cross-Category Isolation Test: Results ===");
        log.info("Baseline window:        {} -> {}", baselineWindowStart, baselineWindowEnd);
        log.info("SCRIPT batch window:    {} -> {}", scriptBatchWindowStart, scriptBatchWindowEnd);
        log.info("EXTERNAL-under-load window: {} -> {}", underLoadWindowStart, underLoadWindowEnd);
        log.info("Baseline EXTERNAL latency: {}ms", baselineMs);
        log.info("Under-load EXTERNAL latencies (ms): {}", underLoadTimingsMs);
        log.info("Under-load average: {}ms ({}x baseline), max: {}ms ({}x baseline)",
                String.format("%.1f", avgUnderLoadMs), String.format("%.2f", avgSlowdown),
                maxUnderLoadMs, String.format("%.2f", maxSlowdown));
        log.info("SCRIPT batch: submitted={}, completed={}, failed={}, all terminal within timeout={}",
                scriptSubmitted.get(), scriptTally[0], scriptTally[1], scriptBatchAllTerminal);
        log.info("========================================================================");

        assertTrue(scriptBatchAllTerminal, "Not all SCRIPT batch executions reached a terminal state within "
                + SCRIPT_BATCH_TERMINAL_TIMEOUT_SECONDS + "s");

        assertTrue(maxUnderLoadMs <= baselineMs * MAX_SLOWDOWN_MULTIPLE,
                () -> String.format(
                        "Expected EXTERNAL max latency under SCRIPT load (%dms) to stay within %.1fx baseline (%dms) i.e. <= %dms - "
                                + "categories do not appear to be isolated",
                        maxUnderLoadMs, MAX_SLOWDOWN_MULTIPLE, baselineMs, (long) (baselineMs * MAX_SLOWDOWN_MULTIPLE)));
    }

    /**
     * Submits a single EXTERNAL request and polls until it reaches a terminal state
     */
    private long executeExternalAndMeasure(String serviceIdPrefix) throws InterruptedException {
        ExecutionRequestDTO request = ExecutionRequestDTO.builder()
                .serviceId(serviceIdPrefix + "-" + System.nanoTime())
                .executionCategory(ExecutionCategory.EXTERNAL)
                .technologyAdapter(TechnologyAdapter.REST)
                .args(Map.of(
                        "url", EXTERNAL_URL,
                        "httpMethod", "GET"
                ))
                .build();

        long start = System.currentTimeMillis();
        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(request);
        assertEquals(ExecutionStatus.RUNNING, accepted.status(), "EXTERNAL probe should be accepted immediately");

        ExecutionStatus status;
        long deadline = start + EXTERNAL_PROBE_TIMEOUT_SECONDS * 1000;
        do {
            Thread.sleep(20);
            status = serviceManager.CheckStatus(accepted.executionId());
        } while (status != ExecutionStatus.COMPLETED && status != ExecutionStatus.FAILED
                && System.currentTimeMillis() < deadline);

        long elapsed = System.currentTimeMillis() - start;
        assertEquals(ExecutionStatus.COMPLETED, status,
                "EXTERNAL probe " + serviceIdPrefix + " should complete successfully (status=" + status + ")");
        return elapsed;
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
