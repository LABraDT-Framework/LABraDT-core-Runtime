package de.labradt.serviceexecution.runtime.container.orchestrator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ContainerConfig: Configuration for container creation and execution.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ContainerConfig {

    // (Scenario A) Image reference: "nginx:latest"
    private String imageRef;

    // (Scenario B) Dockerfile for build scenario
    // If present, indicates container should be built from this file
    private File buildFile;

    // Environment variables to inject into container, All ExecutionRequestDTO.args become env vars
    private Map<String, String> envVars = new HashMap<>();

    // Container entrypoint and arguments (optional, uses image default if null)
    private String[] command;

    // Port mappings: internal port → external port
    private Map<Integer, Integer> ports = new HashMap<>();

    // Volume mounts
    // Example: ["/data:/data", "/config:/etc/config"]
    private List<String> volumes;

    // Container startup timeout in seconds
    @Builder.Default
    private long startupTimeoutSeconds = 10;

    // Container stop timeout in seconds
    @Builder.Default
    private long stopTimeoutSeconds = 30;

    public void validate() throws IllegalArgumentException {
        if (startupTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("startupTimeoutSeconds must be positive");
        }
        if (stopTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("stopTimeoutSeconds must be positive");
        }
        if (envVars == null) {
            throw new IllegalArgumentException("envVars cannot be null");
        }
    }
}