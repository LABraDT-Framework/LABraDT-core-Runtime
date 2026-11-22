package de.labradt.serviceexecution.manager.registry;

public interface ServiceRegistryResolver {
    ServiceDefinitionView resolve(String serviceId);
}
