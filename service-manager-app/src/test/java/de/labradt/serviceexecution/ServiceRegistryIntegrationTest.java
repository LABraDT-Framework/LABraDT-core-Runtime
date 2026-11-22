package de.labradt.serviceexecution;

import de.labradt.serviceexecution.controller.ServiceManagerController;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.dtos.ServiceTriggerCommand;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.registry.ServiceRegistry;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import de.labradt.serviceexecution.registry.exception.ServiceAlreadyExistsException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.http.HttpStatus.OK;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ServiceRegistryIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServiceRegistry serviceRegistry;

    private String luaScript;

    @Test
    void testUpdateDefinitionPersistsAndRefreshesResolverCache() {
        var updated=ServiceDefinition.builder().serviceId("coolingService").displayName("Updated cooling")
            .executionCategory(ExecutionCategory.SCRIPT).technologyAdapter(TechnologyAdapter.LUA)
            .defaultArgs(Map.of("testContent","return threshold","threshold","65")).build();
        restTemplate.put("/registry/services/coolingService",updated);
        assertThat(serviceRegistry.resolve("coolingService").getDefaultArgs().get("threshold")).isEqualTo("65");
        var fetched=restTemplate.getForEntity("/registry/services/coolingService",ServiceDefinition.class);
        assertThat(fetched.getStatusCode()).isEqualTo(OK);
        assertThat(fetched.getBody().getDisplayName()).isEqualTo("Updated cooling");
        assertThat(serviceRegistry.listAll().stream().filter(s->s.getServiceId().equals("coolingService")).count()).isEqualTo(1);
    }

    @BeforeEach
    void setUp() {
        luaScript = """
                if currentTemp > threshold then
                  return "ALERT: " .. currentTemp .. " " .. unit .. " exceeds " .. threshold .. " " .. unit
                else
                  return "OK: within threshold"
                end""";

        registerScriptService();
        registerContainerService();
        registerExternalService();
    }

    @AfterEach
    void tearDown() {
        deleteIfPresent("coolingService");
        deleteIfPresent("containerService");
        deleteIfPresent("externalService");
    }

    @Test
    @DisplayName("SCRIPT runtime: registry defaults and dynamic args are injected into real Lua execution")
    @Tag("plugins")
    void testScriptServiceRealExecution() throws InterruptedException {
        TestEnvironment.requirePlugins(TestEnvironment.LUA_PLUGIN);
        Map<String, String> dynamicArgs = new HashMap<>();
        dynamicArgs.put("currentTemp", "95");

        ExecutionResponseDTO result = triggerAndWait("coolingService", dynamicArgs);

        assertThat(result.isSuccess())
                .as("Lua execution failed: %s", result.getErrorMessage())
                .isTrue();
        assertThat(result.getServiceId()).isEqualTo("coolingService");
        // currentTemp(95) > threshold(80) → Lua takes the ALERT branch; output proves both args were injected
        assertThat(result.getOutputData())
                .contains("ALERT")
                .contains("95")
                .contains("80")
                .contains("celsius");
    }

    @Tag("plugins")
    @Tag("docker")
    @Test
    @DisplayName("CONTAINER runtime: registry defaults produce a real Docker execution with hello-world output")
    void testContainerServiceRealExecution() throws InterruptedException {
        TestEnvironment.assumeDocker();
        ExecutionResponseDTO result = triggerAndWait("containerService", new HashMap<>());

        assertThat(result.isSuccess())
                .as("Container execution failed: %s", result.getErrorMessage())
                .isTrue();
        assertThat(result.getServiceId()).isEqualTo("containerService");
        assertThat(result.getOutputData())
                .isNotBlank()
                .contains("Hello from Docker!");
    }

    @Tag("integration")
    @Test
    @DisplayName("EXTERNAL runtime: dynamic payload overrides registry default in real HTTP call to httpbin")
    @Tag("plugins")
    void testExternalServiceRealExecution() throws InterruptedException {
        TestEnvironment.assumeHttpbin();
        Map<String, String> dynamicArgs = new HashMap<>();
        dynamicArgs.put("payload", "{\"test\":\"overridden\"}");

        ExecutionResponseDTO result = triggerAndWait("externalService", dynamicArgs);

        assertThat(result.isSuccess())
                .as("External call failed: %s", result.getErrorMessage())
                .isTrue();
        assertThat(result.getServiceId()).isEqualTo("externalService");
        // httpbin echoes back the request body - confirms the overridden payload reached the endpoint
        assertThat(result.getOutputData()).contains("overridden");
    }

    @Test
    @DisplayName("Triggering an unregistered service ID returns a 5xx error response")
    void testTriggerUnknownServiceReturnsError() {
        ServiceTriggerCommand command = new ServiceTriggerCommand(List.of("unknownService"), Map.of());
        ResponseEntity<String> response = restTemplate.postForEntity("/services/trigger", command, String.class);

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(response.getBody()).contains("SERVICE_ERROR");
    }

    @Test
    @DisplayName("Registering a duplicate service ID throws ServiceAlreadyExistsException")
    void testDuplicateRegistrationThrows() {
        ServiceDefinition duplicate = ServiceDefinition.builder()
                .serviceId("coolingService")
                .displayName("Duplicate")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .defaultArgs(Map.of("testContent", luaScript))
                .build();

        assertThatThrownBy(() -> serviceRegistry.register(duplicate))
                .isInstanceOf(ServiceAlreadyExistsException.class)
                .hasMessageContaining("coolingService");
    }

    private ExecutionResponseDTO triggerAndWait(String serviceId, Map<String, String> dynamicArgs) throws InterruptedException {
        ServiceTriggerCommand command = new ServiceTriggerCommand(List.of(serviceId), dynamicArgs);

        ResponseEntity<ServiceManagerController.TriggerAcknowledgmentDTO> accepted =
                restTemplate.postForEntity("/services/trigger", command, ServiceManagerController.TriggerAcknowledgmentDTO.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).isNotNull();
        assertThat(accepted.getBody().triggered()).hasSize(1);
        String executionId = accepted.getBody().triggered().get(0).executionId();

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 15000) {
            ResponseEntity<ServiceManagerController.StatusResponse> statusResponse =
                    restTemplate.getForEntity("/services/status?executionId=" + executionId, ServiceManagerController.StatusResponse.class);
            assertThat(statusResponse.getBody()).isNotNull();
            ExecutionStatus status = statusResponse.getBody().status();

            if (status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED) {
                ResponseEntity<ExecutionResponseDTO> resultResponse =
                        restTemplate.getForEntity("/services/result?executionId=" + executionId, ExecutionResponseDTO.class);
                assertThat(resultResponse.getStatusCode()).isEqualTo(OK);
                assertThat(resultResponse.getBody()).isNotNull();
                return resultResponse.getBody();
            }
            Thread.sleep(100);
        }

        fail("Execution " + executionId + " for serviceId=" + serviceId + " did not reach a terminal status within 15 seconds");
        return null; // unreachable
    }

    private void registerScriptService() {
        serviceRegistry.register(ServiceDefinition.builder()
                .serviceId("coolingService")
                .displayName("Cooling Service")
                .description("Service for cooling operations")
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .defaultArgs(Map.of("threshold", "80", "unit", "celsius", "testContent", luaScript))
                .build());
    }

    private void registerContainerService() {
        serviceRegistry.register(ServiceDefinition.builder()
                .serviceId("containerService")
                .displayName("Container Service")
                .description("Service for container operations")
                .executionCategory(ExecutionCategory.CONTAINER)
                .technologyAdapter(TechnologyAdapter.DOCKER)
                .defaultArgs(Map.of("image", "hello-world:latest", "startupTimeout", "30"))
                .build());
    }

    private void registerExternalService() {
        serviceRegistry.register(ServiceDefinition.builder()
                .serviceId("externalService")
                .displayName("External Service")
                .description("Service for external REST calls")
                .executionCategory(ExecutionCategory.EXTERNAL)
                .technologyAdapter(TechnologyAdapter.REST)
                .defaultArgs(Map.of(
                        "url", TestEnvironment.HTTPBIN_URL + "/post",
                        "httpMethod", "POST",
                        "payload", "{\"test\":\"value\"}"
                ))
                .build());
    }

    private void deleteIfPresent(String serviceId) {
        try {
            serviceRegistry.delete(serviceId);
        } catch (RuntimeException ignored) {
        }
    }
}
