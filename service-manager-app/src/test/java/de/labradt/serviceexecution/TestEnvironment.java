package de.labradt.serviceexecution;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks for what the tagged tests need (runtime plugins on the classpath, Docker, internet,
 * a running engine, bash, a free port). A missing dependency skips the test with a clear reason instead of failing it.
 * Normal {@code mvn test} excludes the tags {@code docker} and {@code integration};
 * {@code mvn test -Pintegration} runs them too. Addresses come from system properties.
 */
public final class TestEnvironment {

    /** Public HTTP echo service used by the EXTERNAL tests. Override: -Dtest.httpbin.url=... */
    public static final String HTTPBIN_URL = System.getProperty("test.httpbin.url", "https://httpbin.org");

    /** A running engine, only for the tests that upload to engine storage. Set -Dtest.engine.url=http://localhost:8080 */
    public static final String ENGINE_URL = System.getProperty("test.engine.url");

    /** Adapter class of the Lua plugin (internal project runtime-plugins/lua). */
    public static final String LUA_PLUGIN = "de.labradt.serviceexecution.runtime.script.interpreter.lua.LuaInterpreter";

    /** Adapter class of the Bash plugin (internal project runtime-plugins/bash). */
    public static final String BASH_PLUGIN = "de.labradt.serviceexecution.runtime.script.interpreter.bash.BashInterpreter";

    /** Adapter class of the Docker plugin (internal project runtime-plugins/docker). */
    public static final String DOCKER_PLUGIN = "de.labradt.serviceexecution.runtime.container.orchestrator.DockerAdapter";

    /** Adapter class of the Rest plugin (internal project runtime-plugins/rest). */
    public static final String REST_PLUGIN = "de.labradt.serviceexecution.runtime.external.client.RestAdapter";

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private TestEnvironment() {
    }

    /** Needs the Docker plugin on the classpath and a reachable Docker daemon. */
    public static void assumeDocker() {
        requirePlugins(DOCKER_PLUGIN);
        assumeTrue(CACHE.computeIfAbsent("docker", k -> pingDocker()),
                "Docker not available (DOCKER_HOST=" + System.getenv("DOCKER_HOST")
                        + "); start Docker Desktop or set DOCKER_HOST - skipped");
    }

    /** Needs the Rest plugin on the classpath and a reachable httpbin. */
    public static void assumeHttpbin() {
        requirePlugins(REST_PLUGIN);
        String probe = HTTPBIN_URL + "/get";
        assumeTrue(CACHE.computeIfAbsent(probe, TestEnvironment::answers200),
                "httpbin not reachable at " + HTTPBIN_URL + " (offline or proxy; set -Dtest.httpbin.url) - skipped");
    }

    public static void assumeEngine() {
        assumeTrue(ENGINE_URL != null && CACHE.computeIfAbsent(ENGINE_URL + "/engine/state", TestEnvironment::answers200),
                "no engine at -Dtest.engine.url (" + ENGINE_URL + "); start the engine and set the property - skipped");
    }

    /** Needs the Bash plugin on the classpath and a working bash (WSL on Windows). */
    public static void assumeBash() {
        requirePlugins(BASH_PLUGIN);
        assumeTrue(CACHE.computeIfAbsent("bash", k -> runsOk("echo ok")),
                (IS_WINDOWS ? "WSL with a default Linux distribution" : "/bin/bash") + " not available - skipped");
    }

    /** Used by @EnabledIf on the test that binds the fixed port 8082. */
    public static boolean port8082Free() {
        try (ServerSocket socket = new ServerSocket(8082)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Pings the Docker daemon with docker-java. docker-java comes with the Docker plugin, so it is
     * loaded by reflection: the core tests compile without it, and this only runs when the plugin
     * is on the classpath (see assumeDocker). An unreachable daemon (the call itself throws)
     * means "not available"; any other reflection problem means the docker-java API changed and
     * fails the test instead of silently skipping it.
     */
    private static boolean pingDocker() {
        try {
            Class<?> configClass = Class.forName("com.github.dockerjava.core.DefaultDockerClientConfig");
            Object configBuilder = configClass.getMethod("createDefaultConfigBuilder").invoke(null);
            Object config = configBuilder.getClass().getMethod("build").invoke(configBuilder);
            Object dockerHost = config.getClass().getMethod("getDockerHost").invoke(config);
            Object sslConfig = config.getClass().getMethod("getSSLConfig").invoke(config);
            Class<?> httpBuilderClass = Class.forName("com.github.dockerjava.zerodep.ZerodepDockerHttpClient$Builder");
            Object httpBuilder = httpBuilderClass.getConstructor().newInstance();
            httpBuilder = httpBuilderClass.getMethod("dockerHost", URI.class).invoke(httpBuilder, dockerHost);
            httpBuilder = httpBuilderClass.getMethod("sslConfig", Class.forName("com.github.dockerjava.transport.SSLConfig")).invoke(httpBuilder, sslConfig);
            httpBuilder = httpBuilderClass.getMethod("connectionTimeout", Duration.class).invoke(httpBuilder, Duration.ofSeconds(3));
            httpBuilder = httpBuilderClass.getMethod("responseTimeout", Duration.class).invoke(httpBuilder, Duration.ofSeconds(5));
            Object http = httpBuilderClass.getMethod("build").invoke(httpBuilder);
            Class<?> clientImpl = Class.forName("com.github.dockerjava.core.DockerClientImpl");
            Object client = clientImpl.getMethod("getInstance", Class.forName("com.github.dockerjava.core.DockerClientConfig"),
                    Class.forName("com.github.dockerjava.transport.DockerHttpClient")).invoke(null, config, http);
            try {
                Object ping = client.getClass().getMethod("pingCmd").invoke(client);
                ping.getClass().getMethod("exec").invoke(ping);
                return true;
            } finally {
                ((AutoCloseable) client).close();
            }
        } catch (InvocationTargetException e) {
            return false;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new AssertionError("docker-java API mismatch in TestEnvironment.pingDocker", e);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean answers200(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs a command the same way the Bash plugin's BashInterpreter does (wsl /bin/bash on Windows, /bin/bash elsewhere). */
    private static boolean runsOk(String bashCommand) {
        try {
            ProcessBuilder pb = IS_WINDOWS
                    ? new ProcessBuilder("wsl", "/bin/bash", "-c", bashCommand)
                    : new ProcessBuilder("/bin/bash", "-c", bashCommand);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 && out.endsWith("ok");
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the class of a runtime plugin is on the test classpath. */
    public static boolean pluginPresent(String className) {
        try {
            Class.forName(className, false, TestEnvironment.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Skips the test (with a reason) unless every listed runtime plugin is on the classpath.
     * Tests that check real plugin behaviour carry the tag "plugins" and call this first.
     */
    public static void requirePlugins(String... classNames) {
        for (String className : classNames) {
            assumeTrue(pluginPresent(className), "needs the runtime plugin " + className
                    + " on the classpath (internal plugin project) - skipped");
        }
    }
}
