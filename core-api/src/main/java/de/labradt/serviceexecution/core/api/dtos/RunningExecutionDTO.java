package de.labradt.serviceexecution.core.api.dtos;

import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;

public record RunningExecutionDTO(String executionId, String serviceId, ExecutionStatus status) {
}
