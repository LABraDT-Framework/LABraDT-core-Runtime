package de.labradt.serviceexecution.registry.exception;

public class ServiceAlreadyExistsException extends RuntimeException {
    public ServiceAlreadyExistsException() {
        super("Service already exists");
    }

    public ServiceAlreadyExistsException(String serviceId) {
        super("Service already exists: " + serviceId);
    }
}
