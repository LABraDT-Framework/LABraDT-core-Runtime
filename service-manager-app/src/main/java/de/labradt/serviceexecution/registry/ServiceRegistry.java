package de.labradt.serviceexecution.registry;

import de.labradt.serviceexecution.manager.registry.ServiceRegistryResolver;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import de.labradt.serviceexecution.registry.exception.ServiceAlreadyExistsException;
import de.labradt.serviceexecution.registry.exception.ServiceNotFoundException;
import de.labradt.serviceexecution.registry.repository.ServiceDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Transactional
public class ServiceRegistry implements ServiceRegistryResolver {
    private final ServiceDefinitionRepository repository;
    private final Map<String, ServiceDefinition> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void warmCache() {
        cache.clear(); // also called after services were imported from config/services.json
        repository.findAll().forEach(def -> 
            cache.put(def.getServiceId(), def));
    }
    
    @Override
    @Transactional(readOnly = true)
    public ServiceDefinition resolve(String serviceId) {
        ServiceDefinition def = cache.get(serviceId); // average-case constant-time lookup
        if (def == null) throw new ServiceNotFoundException(serviceId);
        return def;
    }

    public ServiceDefinition register(ServiceDefinition definition) {
        if (repository.existsByServiceId(definition.getServiceId())) {
            throw new ServiceAlreadyExistsException(definition.getServiceId());
        }

        ServiceDefinition saved = repository.save(definition);
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.put(saved.getServiceId(), saved);
                }
            }
        );
        return definition;
    }

    public ServiceDefinition update(String serviceId, ServiceDefinition definition) {
        if (!repository.existsByServiceId(serviceId)) throw new ServiceNotFoundException(serviceId);
        if (!serviceId.equals(definition.getServiceId())) throw new IllegalArgumentException("Service ID is immutable");
        ServiceDefinition saved = repository.save(definition);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.put(serviceId, saved); }
        });
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ServiceDefinition> listAll() {
        return repository.findAll();
    }

    public void delete(String serviceId) {
        if (!repository.existsByServiceId(serviceId)) {
            throw new ServiceNotFoundException(serviceId);
        }
        repository.deleteById(serviceId);
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(serviceId);
                }
            }
        );
    }
}
