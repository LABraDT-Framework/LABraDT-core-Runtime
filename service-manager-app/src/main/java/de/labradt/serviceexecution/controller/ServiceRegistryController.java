package de.labradt.serviceexecution.controller;

import de.labradt.serviceexecution.registry.ServiceRegistry;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/registry")
@RequiredArgsConstructor
public class ServiceRegistryController {
    private final ServiceRegistry serviceRegistry;

    @GetMapping("/services")
    public List<ServiceDefinition> listAll() {
        return serviceRegistry.listAll();
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDefinition register(@RequestBody ServiceDefinition definition) {
        return serviceRegistry.register(definition);
    }

    @PutMapping("/services/{serviceId}")
    public ServiceDefinition update(@PathVariable String serviceId, @RequestBody ServiceDefinition definition) {
        return serviceRegistry.update(serviceId, definition);
    }

    @GetMapping("/services/{serviceId}")
    public ServiceDefinition resolve(@PathVariable String serviceId) {
        return serviceRegistry.resolve(serviceId);
    }

    @DeleteMapping("/services/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String serviceId) {
        serviceRegistry.delete(serviceId);
    }
}
