package de.labradt.serviceexecution.registry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import de.labradt.serviceexecution.registry.repository.ServiceDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Imports service definitions handed over in the project folder: {@code config/services.json}, written by the
 * LABraDT UI when a project is imported. It is either a JSON array of service definitions (as the registry REST
 * API returns them; existing services with the same id are replaced) or {@code {"replace": true, "services": [...]}}
 * (the registry is cleared first, so it holds exactly the imported services). The file
 * is consumed once: afterwards it is renamed to {@code services.imported.json}, so services deleted later are not
 * brought back at the next start.
 *
 * <p>The file is {@code runtime.services-file}, else {@code config/services.json} or {@code ../config/services.json}
 * relative to the working directory (the project root or service-manager-app).
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class ServiceImportFile implements CommandLineRunner {

    private final ServiceDefinitionRepository repository;
    private final ServiceRegistry serviceRegistry;

    @Value("${runtime.services-file:}")
    private String configured;

    @Override
    public void run(String... args) throws Exception {
        Path file = locate();
        if (file == null) return;
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode root = mapper.readTree(file.toFile());
        boolean replace = root.isObject() && root.path("replace").asBoolean(false);
        JsonNode items = root.isObject() ? root.path("services") : root;
        List<ServiceDefinition> services = Arrays.asList(mapper.treeToValue(items.isMissingNode() ? mapper.createArrayNode() : items, ServiceDefinition[].class));
        if (replace) {
            repository.deleteAll();
        }
        for (ServiceDefinition service : services) {
            if (service.getServiceId() == null || service.getServiceId().isBlank()
                    || service.getExecutionCategory() == null || service.getTechnologyAdapter() == null) {
                log.warn("Skipped an incomplete service definition in {}: {}", file, service);
                continue;
            }
            repository.save(service);
        }
        serviceRegistry.warmCache(); // the registry resolves services from its cache
        Path done = file.resolveSibling("services.imported.json");
        Files.move(file, done, StandardCopyOption.REPLACE_EXISTING);
        log.info("Imported {} service definition(s) from {}{} (now {})", services.size(), file, replace ? ", replacing the registry" : "", done.getFileName());
    }

    private Path locate() {
        Stream<Path> candidates = configured != null && !configured.isBlank()
                ? Stream.of(Path.of(configured.trim()))
                : Stream.of(Path.of("config", "services.json"), Path.of("..", "config", "services.json"));
        return candidates.filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
