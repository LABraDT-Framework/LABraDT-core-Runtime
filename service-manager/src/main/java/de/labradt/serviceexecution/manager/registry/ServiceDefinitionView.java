package de.labradt.serviceexecution.manager.registry;

import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;

import java.util.Map;

public interface ServiceDefinitionView {
    String getServiceId();
    ExecutionCategory getExecutionCategory();
    TechnologyAdapter getTechnologyAdapter();
    String getFileReference();
    Map<String, String> getDefaultArgs();
}
