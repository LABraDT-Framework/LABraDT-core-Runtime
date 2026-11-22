package de.labradt.serviceexecution.core.api.dtos;

import de.labradt.serviceexecution.core.api.enums.ExecutionStatus;
import lombok.Builder;
import lombok.Value;

import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionResponseDTO {

    // Enables correlation between requests and responses
    private String serviceId;

    // Identifies the specific dispatch this response belongs to
    private String executionId;

    private ExecutionStatus status;
    private boolean success;
    private String outputData;
    private String errorMessage;
    private Long completedAt;
    private Long executionDurationMs;

    // Creates a successful response with output data
    public static ExecutionResponseDTO success(String serviceId, String outputData, Long durationMs) {
        return ExecutionResponseDTO.builder()
                .serviceId(serviceId)
                .status(ExecutionStatus.COMPLETED)
                .success(true)
                .outputData(outputData)
                .completedAt(System.currentTimeMillis())
                .executionDurationMs(durationMs)
                .build();
    }

    // Creates a failed response with error information
    public static ExecutionResponseDTO failure(String serviceId, String errorMessage, Long durationMs) {
        return ExecutionResponseDTO.builder()
                .serviceId(serviceId)
                .status(ExecutionStatus.FAILED)
                .success(false)
                .errorMessage(errorMessage)
                .completedAt(System.currentTimeMillis())
                .executionDurationMs(durationMs)
                .build();
    }

    // Creates an in-progress status response
    public static ExecutionResponseDTO running(String serviceId) {
        return ExecutionResponseDTO.builder()
                .serviceId(serviceId)
                .status(ExecutionStatus.RUNNING)
                .success(false)
                .build();
    }
}