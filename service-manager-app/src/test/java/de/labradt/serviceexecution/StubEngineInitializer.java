package de.labradt.serviceexecution;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Test-only: points the engine callback and engine storage at {@link StubEngineServer} before
 * any bean is created, so no test waits on a missing engine. Registered for every test context
 * in src/test/resources/META-INF/spring.factories. With -Dtest.engine.url=... the stub is not
 * used and the test config sends the calls to that real engine instead.
 */
public class StubEngineInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (TestEnvironment.ENGINE_URL != null) {
            return;
        }
        String stub = StubEngineServer.baseUrl();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("stubEngine", Map.of(
                "engine.result.endpoint", stub + "/engine/result",
                "engine.storage.base-url", stub)));
    }
}
