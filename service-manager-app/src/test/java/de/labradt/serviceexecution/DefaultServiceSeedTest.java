package de.labradt.serviceexecution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import de.labradt.serviceexecution.controller.ServiceManagerController;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.dtos.ServiceTriggerCommand;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.registry.ServiceRegistry;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The registry seeded by DataInitializer on an empty database: exactly the three
 * services the Engine's shipped rules call, all inline Lua, nothing that needs the
 * network, engine storage or Docker.
 */
@Tag("plugins")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class DefaultServiceSeedTest {

    @BeforeEach
    void requiresRuntimePlugins() {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
    }

    private static final Map<String, String> EXPECTED = Map.of(
            "cooling-service", "70.0",
            "geometry-correction-service", "0,0,0",
            "energy-monitoring-service", "25.0");

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("An empty registry is seeded with the three Engine rule services as inline Lua")
    void seedsTheEngineRuleServices() {
        List<ServiceDefinition> all = serviceRegistry.listAll();
        assertThat(all).extracting(ServiceDefinition::getServiceId)
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());

        for (ServiceDefinition def : all) {
            assertThat(def.getExecutionCategory()).isEqualTo(ExecutionCategory.SCRIPT);
            assertThat(def.getTechnologyAdapter()).isEqualTo(TechnologyAdapter.LUA);
            assertThat(def.getFileReference()).isNullOrEmpty();
            assertThat(def.getDefaultArgs()).containsKey("testContent");
            assertThat(def.getDefaultArgs().values()).noneMatch(v -> v.contains("http"));
        }
    }

    @Test
    @DisplayName("Each seeded service runs and returns its fixed corrected value")
    void seededServicesRunOffline() throws InterruptedException {
        for (Map.Entry<String, String> expected : EXPECTED.entrySet()) {
            ExecutionResponseDTO result = triggerAndWait(expected.getKey());
            assertThat(result.getStatus()).as(expected.getKey()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.getOutputData()).as(expected.getKey()).contains(expected.getValue());
        }
    }

    private ExecutionResponseDTO triggerAndWait(String serviceId) throws InterruptedException {
        ServiceTriggerCommand command = new ServiceTriggerCommand(List.of(serviceId), Map.of());
        ResponseEntity<ServiceManagerController.TriggerAcknowledgmentDTO> accepted =
                restTemplate.postForEntity("/services/trigger", command, ServiceManagerController.TriggerAcknowledgmentDTO.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).isNotNull();
        String executionId = accepted.getBody().triggered().get(0).executionId();

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 15000) {
            ExecutionStatus status = restTemplate.getForEntity("/services/status?executionId=" + executionId,
                    ServiceManagerController.StatusResponse.class).getBody().status();
            if (status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED) {
                return restTemplate.getForEntity("/services/result?executionId=" + executionId,
                        ExecutionResponseDTO.class).getBody();
            }
            Thread.sleep(100);
        }
        fail("Execution for " + serviceId + " did not finish within 15 seconds");
        return null;
    }
}
