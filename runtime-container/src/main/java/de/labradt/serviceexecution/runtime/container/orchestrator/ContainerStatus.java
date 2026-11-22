package de.labradt.serviceexecution.runtime.container.orchestrator;

/**
 * Represents the lifecycle state of a container.
 */
public enum ContainerStatus {
    RUNNING,      // Container is executing
    EXITED,       // Container completed normally
    FAILED,       // Container failed or crashed
    UNKNOWN       // State cannot be determined
}
