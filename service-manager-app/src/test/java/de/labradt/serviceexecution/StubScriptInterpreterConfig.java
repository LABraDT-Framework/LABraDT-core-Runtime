package de.labradt.serviceexecution;

import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.runtime.script.interpreter.IScriptInterpreter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Test-only stand-in for a script interpreter, for core tests that only need "a script
 * service runs" (result callback, recovery after a failure). It registers for LUA because
 * the core tests use LUA requests, but it does not run Lua: it returns the script text as
 * the output. It steps aside when the real Lua plugin (runtime-plugins/lua) is on the
 * classpath. Tests that check real Lua behaviour carry the tag "plugins" instead.
 * Import it with {@code @Import(StubScriptInterpreterConfig.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubScriptInterpreterConfig {

    @Bean
    @ConditionalOnMissingClass(TestEnvironment.LUA_PLUGIN)
    IScriptInterpreter stubLuaInterpreter() {
        return new IScriptInterpreter() {
            @Override
            public TechnologyAdapter getScriptType() {
                return TechnologyAdapter.LUA;
            }

            @Override
            public String execute(String scriptContent, Map<String, String> args) {
                return scriptContent;
            }
        };
    }
}
