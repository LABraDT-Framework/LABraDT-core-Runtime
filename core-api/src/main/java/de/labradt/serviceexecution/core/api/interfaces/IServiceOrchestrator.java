package de.labradt.serviceexecution.core.api.interfaces;

import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.dtos.ServiceTriggerCommand;

/**
 * Lets a script's nested-invocation bridge resolve and dispatch a nested service 
 * without depending on the concrete ServiceManager class
 */
public interface IServiceOrchestrator {

    ExecutionResponseDTO resolveAndExecuteNested(ServiceTriggerCommand command);
}
