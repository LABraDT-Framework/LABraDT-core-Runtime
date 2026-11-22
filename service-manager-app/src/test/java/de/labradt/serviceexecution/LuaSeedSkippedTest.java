package de.labradt.serviceexecution;

import de.labradt.serviceexecution.registry.ServiceRegistry;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Without a Lua interpreter on the classpath the core does not seed the three default Lua
 * services (DataInitializer). This context does not import the test-only stand-in interpreter.
 * With the Lua plugin present, DefaultServiceSeedTest covers the seeds instead.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class LuaSeedSkippedTest {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @BeforeEach
    void onlyWithoutTheLuaPlugin() {
        assumeFalse(TestEnvironment.pluginPresent(TestEnvironment.LUA_PLUGIN),
                "the Lua plugin is on the classpath; DefaultServiceSeedTest covers the seeds - skipped");
    }

    @Test
    void defaultLuaServicesAreNotSeeded() {
        assertThat(serviceRegistry.listAll()).extracting(ServiceDefinition::getServiceId)
                .doesNotContain("cooling-service", "geometry-correction-service", "energy-monitoring-service");
    }
}
