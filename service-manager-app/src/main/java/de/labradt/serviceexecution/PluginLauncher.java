package de.labradt.serviceexecution;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Starts the application with the plugins of the project's {@code plugins/} folder.
 *
 * <p>Every JAR below {@code plugins/} (one sub-folder per plugin, as the LABraDT UI writes them) joins the
 * classpath through a child class loader that Spring uses for component scanning and auto-configuration, so
 * {@code mvn spring-boot:run} or {@code java -jar} picks the plugins up without any pom change. The
 * {@code *.properties} files directly in {@code plugins/} provide defaults for system properties (e.g. the
 * switch that enables a protocol adapter); real system properties and environment variables win.
 *
 * <p>The folder is {@code labradt.plugins.dir} / {@code LABRADT_PLUGINS_DIR}, else {@code plugins} or
 * {@code ../plugins} relative to the working directory (the project root or its app module).
 */
public final class PluginLauncher {

    private PluginLauncher() {
    }

    public static ConfigurableApplicationContext run(Class<?> application, String[] args) {
        SpringApplication app = new SpringApplication(application);
        Path dir = pluginsDir();
        if (dir != null) {
            applyProperties(dir);
            List<URL> jars = jars(dir);
            if (!jars.isEmpty()) {
                ClassLoader loader = new URLClassLoader("labradt-plugins", jars.toArray(URL[]::new), application.getClassLoader());
                Thread.currentThread().setContextClassLoader(loader);
                app.setResourceLoader(new DefaultResourceLoader(loader));
            }
            System.out.println("[LABraDT] Plugins from " + dir.toAbsolutePath().normalize() + ": " + jars.size() + " JAR(s)");
        }
        return app.run(args);
    }

    static Path pluginsDir() {
        String configured = System.getProperty("labradt.plugins.dir", System.getenv("LABRADT_PLUGINS_DIR"));
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured.trim()));
        candidates.add(Path.of("plugins"));
        candidates.add(Path.of("..", "plugins"));
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(null);
    }

    static List<URL> jars(Path dir) {
        try (Stream<Path> files = Files.walk(dir, 3)) {
            return files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(PluginLauncher::url)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the plugins folder " + dir, e);
        }
    }

    static void applyProperties(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
                for (String key : properties.stringPropertyNames()) {
                    String env = key.toUpperCase().replace('.', '_').replace('-', '_');
                    if (System.getProperty(key) == null && System.getenv(env) == null) {
                        System.setProperty(key, properties.getProperty(key));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the plugin properties in " + dir, e);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(path.toString(), e);
        }
    }
}
