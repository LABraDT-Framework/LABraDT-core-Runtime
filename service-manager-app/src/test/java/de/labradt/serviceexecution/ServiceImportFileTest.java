package de.labradt.serviceexecution;

import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.registry.ServiceImportFile;
import de.labradt.serviceexecution.registry.ServiceRegistry;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** config/services.json handed over by the LABraDT UI (project import) is registered once, then renamed. */
@SpringBootTest(properties = "runtime.seed.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ServiceImportFileTest {

    @Autowired
    private ServiceImportFile importFile;

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Test
    void importsArraysAndReplacingObjectsOnce() throws Exception {
        Path dir = Files.createTempDirectory("services-import");
        Path file = dir.resolve("services.json");
        ReflectionTestUtils.setField(importFile, "configured", file.toString());

        Files.writeString(file, "[{\"serviceId\":\"a\",\"executionCategory\":\"SCRIPT\",\"technologyAdapter\":\"RUBY\",\"defaultArgs\":{\"x\":\"1\"}},"
                + "{\"serviceId\":\"b\",\"executionCategory\":\"EXTERNAL\",\"technologyAdapter\":\"REST\"}]");
        importFile.run();
        assertThat(serviceRegistry.listAll()).extracting(ServiceDefinition::getServiceId).contains("a", "b");
        assertThat(serviceRegistry.resolve("a").getDefaultArgs()).containsEntry("x", "1"); // resolvable for triggers
        assertThat(file).doesNotExist();
        assertThat(dir.resolve("services.imported.json")).exists();
        importFile.run(); // nothing to import any more

        Files.writeString(file, "{\"replace\":true,\"services\":[{\"serviceId\":\"c\",\"executionCategory\":\"CONTAINER\",\"technologyAdapter\":\"DOCKER\"}]}");
        importFile.run();
        assertThat(serviceRegistry.listAll()).extracting(ServiceDefinition::getServiceId).containsExactly("c");
        assertThat(serviceRegistry.resolve("c")).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> serviceRegistry.resolve("a"))
                .isInstanceOf(de.labradt.serviceexecution.registry.exception.ServiceNotFoundException.class);
        assertThat(serviceRegistry.listAll().get(0).getExecutionCategory()).isEqualTo(ExecutionCategory.CONTAINER);
    }
}
