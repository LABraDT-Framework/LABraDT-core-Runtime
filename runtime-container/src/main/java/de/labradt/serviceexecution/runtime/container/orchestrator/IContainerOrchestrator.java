package de.labradt.serviceexecution.runtime.container.orchestrator;

import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;

// Abstraction for different types of container orchestrators
public interface IContainerOrchestrator {

    TechnologyAdapter getOrchestratorType();

    // Base URL a container should use to reach the service manager (for SERVICE_TRIGGER_URL /
    // SERVICE_TRIGGER_NESTED_URL). Each orchestrator resolves this however fits its own network
    // model: Docker Desktop -> "http://host.docker.internal:{port}{contextPath}", 
    // Podman -> "http://host.containers.internal:{port}{contextPath}", 
    // Kubernetes adapter -> an in-cluster Service DNS URL, which doesn't fit a "hostname" shape at all
    String getServiceManagerBaseUrl(String port, String contextPath);

    /*
     * Resolves config into a ready-to-run image reference
     * Scenario A: if imageRef is provided, pulls a pre-built image from a registry
     * Scenario B: if buildFile is provided, builds an image from it locally
     *
     * Not every orchestrator can do both - Kubernetes, for example, has no in-cluster
     * build primitive. Implementations that can't build should reject a buildFile
     * request with a clear UnsupportedOperationException 
     * (see ADDING_A_RUNTIME_TYPE_OR_ADAPTER.md, "Orchestrators that can't build images")
     */
    String prepareImage(ContainerConfig config, String serviceId) throws Exception;

    String createContainer(ContainerConfig config) throws Exception;

    void startContainer(String containerId, ContainerConfig config) throws Exception;

    String getLogs(String containerId) throws Exception;

    void stopContainer(String containerId, ContainerConfig config) throws Exception;

    ContainerStatus getStatus(String containerId) throws Exception;
}
