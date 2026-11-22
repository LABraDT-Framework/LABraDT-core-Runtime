package de.labradt.serviceexecution.runtime.container.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// The container host's HTTP client for engine storage (ContainerRuntime fetches Dockerfiles with it).
// It stays in the core; the Docker client itself is part of the Docker plugin (runtime-plugins/docker).
@Slf4j
@Configuration
public class StorageClientConfig {

    // Named distinctly from the core's "restClient" bean (service-manager); both live in the
    // service-manager-app context, so the same name would collide.
    @Bean
    public RestClient storageRestClient() {
        log.info("Initializing RestClient for storage endpoint communication");
        return RestClient.create();
    }
}
