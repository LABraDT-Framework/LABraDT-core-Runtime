package de.labradt.serviceexecution.registry.repository;

import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.registry.domain.ServiceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceDefinitionRepository extends JpaRepository<ServiceDefinition, String> {
    List<ServiceDefinition> findByExecutionCategory(ExecutionCategory executionCategory);

    boolean existsByServiceId(String serviceId);
}
