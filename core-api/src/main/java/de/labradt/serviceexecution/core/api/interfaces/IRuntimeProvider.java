package de.labradt.serviceexecution.core.api.interfaces;

import de.labradt.serviceexecution.core.api.dtos.ExecutionRequestDTO;
import de.labradt.serviceexecution.core.api.dtos.ExecutionResponseDTO;
import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;

import java.util.concurrent.CompletableFuture;


// ServiceManager treats all runtimes identically through this interface
public interface IRuntimeProvider {

    CompletableFuture<ExecutionResponseDTO> ExecuteAsync(ExecutionRequestDTO request);

    boolean Stop(String executionId);

    ExecutionCategory getExecutionCategory();
}
