package de.labradt.serviceexecution.registry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import de.labradt.serviceexecution.runtime.script.interpreter.IScriptInterpreter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Seeds an empty registry with the three services the Engine's shipped rules call
 * (the {@code action} of each rule in the Engine's MachineTool.aasx, submodel Rules_SM).
 *
 * <p>Each one is an inline Lua script that returns a fixed corrected value, so a fresh
 * install works end to end without network access, engine storage files or Docker.
 * The ids and values match the first three services of the UI seed script
 * ({@code scripts/service_seed.py} in the ui project), which also registers further
 * demo services. Nothing is seeded when the registry already holds services, or when
 * {@code runtime.seed.enabled=false} (env RUNTIME_SEED_ENABLED=false; the LABraDT UI starts empty).
 *
 * <p>The seeds need a Lua interpreter (the internal Lua plugin, runtime-plugins/lua). Without
 * one on the classpath they are skipped with one warning. Any bean that reports LUA counts,
 * including the test-only stand-in {@code StubScriptInterpreterConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "runtime.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {
    private final ServiceRegistry serviceRegistry;
    private final List<IScriptInterpreter> scriptInterpreters;

    @Override
    public void run(String... args) {
        if (!serviceRegistry.listAll().isEmpty()) {
            return;
        }
        if (scriptInterpreters.stream().noneMatch(i -> i.getScriptType() == TechnologyAdapter.LUA)) {
            log.warn("No Lua interpreter on the classpath (Lua plugin runtime-plugins/lua): the default services "
                    + "cooling-service, geometry-correction-service and energy-monitoring-service are not seeded, "
                    + "so the Engine's shipped rule actions fail until they are registered.");
            return;
        }

        registerLua("cooling-service", "Cooling Service (Engine)",
                "Deterministic correction used by the bundled Engine Temperature rule.",
                "return '70.0'");
        registerLua("geometry-correction-service", "Geometry Correction Service (Engine)",
                "Deterministic correction used by the bundled Engine position rule.",
                "return '0,0,0'");
        registerLua("energy-monitoring-service", "Energy Monitoring Service (Engine)",
                "Deterministic correction used by the bundled Engine XAxisLoad rule.",
                "return '25.0'");
    }

    private void registerLua(String serviceId, String displayName, String description, String script) {
        serviceRegistry.register(ServiceDefinition.builder()
                .serviceId(serviceId)
                .displayName(displayName)
                .description(description)
                .executionCategory(ExecutionCategory.SCRIPT)
                .technologyAdapter(TechnologyAdapter.LUA)
                .fileReference("")
                .defaultArgs(Map.of("testContent", script))
                .build());
    }
}
