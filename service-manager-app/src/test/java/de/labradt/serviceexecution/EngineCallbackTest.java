package de.labradt.serviceexecution;

import org.springframework.context.annotation.Import;
import de.labradt.serviceexecution.core.api.dtos.ExecutionAcceptedDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.ServiceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real result callback path: the service manager POSTs the execution's state to
 * engine.result.endpoint (ACCEPTED first, then COMPLETED when it has finished).
 * In tests that endpoint is the local {@link StubEngineServer}.
 */
@Import(StubScriptInterpreterConfig.class)
@SpringBootTest
class EngineCallbackTest {

    @Autowired
    private ServiceManager serviceManager;

    @Test
    void finishedExecutionIsPostedToTheEngine() throws Exception {
        assumeTrue(TestEnvironment.ENGINE_URL == null, "a real engine is configured (-Dtest.engine.url); the stub is not used");

        ExecutionAcceptedDTO accepted = serviceManager.ExecuteServiceAsync(ExecutionRequestDTO.builder()
                .serviceId("engine-callback-check")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .args(Map.of("testContent", "return 'callback-ok'"))
                .build());
        String executionId = accepted.executionId();

        Optional<String> posted = Optional.empty();
        long deadline = System.currentTimeMillis() + 15_000;
        while (posted.isEmpty() && System.currentTimeMillis() < deadline) {
            posted = StubEngineServer.receivedResults().stream()
                    .filter(b -> b.contains(executionId) && b.contains("\"status\":\"COMPLETED\""))
                    .findFirst();
            if (posted.isEmpty()) {
                Thread.sleep(100);
            }
        }

        assertTrue(posted.isPresent(), "no COMPLETED POST /engine/result for execution " + executionId
                + "; received: " + StubEngineServer.receivedResults());
        String body = posted.get();
        assertTrue(body.contains("\"status\":\"COMPLETED\""), body);
        assertTrue(body.contains("callback-ok"), body);
        assertTrue(body.contains("\"serviceId\":\"engine-callback-check\""), body);
    }
}
