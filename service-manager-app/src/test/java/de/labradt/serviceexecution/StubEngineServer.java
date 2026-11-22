package de.labradt.serviceexecution;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in for the engine during tests, so the service manager's result callback has a
 * real endpoint that answers at once: POST /engine/result -> 200 (the body is recorded),
 * anything else (for example /engine/storage/...) -> 404. One server per test JVM, on
 * 127.0.0.1 and a free port. Started by {@link StubEngineInitializer}.
 */
public final class StubEngineServer {

    private static HttpServer server;
    private static final List<String> RESULTS = new CopyOnWriteArrayList<>();

    private StubEngineServer() {
    }

    /** Base URL of the stub, e.g. http://127.0.0.1:54321. Starts the server on first use. */
    public static synchronized String baseUrl() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException("could not start the stub engine", e);
            }
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                boolean result = "POST".equals(exchange.getRequestMethod())
                        && "/engine/result".equals(exchange.getRequestURI().getPath());
                if (result) {
                    RESULTS.add(new String(body, StandardCharsets.UTF_8));
                }
                exchange.sendResponseHeaders(result ? 200 : 404, -1);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "stub-engine");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Bodies of all POST /engine/result calls received so far (JSON ExecutionResponseDTO). */
    public static List<String> receivedResults() {
        return List.copyOf(RESULTS);
    }
}
