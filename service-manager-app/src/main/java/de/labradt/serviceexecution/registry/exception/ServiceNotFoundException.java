package de.labradt.serviceexecution.registry.exception;

public class ServiceNotFoundException extends RuntimeException {
    public ServiceNotFoundException() {
        super("Service not found");
    }

    public ServiceNotFoundException(String serviceId) {
        super("Service not found: " + serviceId);
    }
}
