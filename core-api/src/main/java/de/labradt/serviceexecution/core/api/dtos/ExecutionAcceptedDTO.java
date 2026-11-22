package de.labradt.serviceexecution.core.api.dtos;

import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;

// Returned when a service dispatch is accepted; executionId is the key to poll
public record ExecutionAcceptedDTO(String executionId, String serviceId, ExecutionStatus status) {
}
