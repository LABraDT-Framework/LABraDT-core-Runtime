package de.labradt.serviceexecution.core.api.dtos;

import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.jackson.Jacksonized;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Jacksonized
public class ExecutionRequestDTO {

    // Identifies which service definition to run (not unique per invocation)
    private String serviceId;

    // Unique identifier for a single dispatch of this request, stamped on by ServiceManager
    // Null on inbound client requests.
    private String executionId;

    private ExecutionCategory executionCategory;

    private TechnologyAdapter technologyAdapter;

    // Reference to a file in the File Storage
    private String fileReference;

    // Key-value pairs passed to the service at execution time
    private Map<String, String> args;

    // Returns a copy of arguments to prevent external modification.
    @JsonIgnore
    public Map<String, String> getArgsCopy() {
        return args == null ? new HashMap<>() : new HashMap<>(args);
    }

    // Validates the DTO for consistency before routing
    public void validate() throws IllegalArgumentException {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId cannot be null or blank");
        }
        if (executionCategory == null) {
            throw new IllegalArgumentException("ExecutionCategory must be specified (SCRIPT, CONTAINER, or EXTERNAL)");
        }
        if (technologyAdapter == null) {
            throw new IllegalArgumentException("TechnologyAdapter must be specified for routing");
        }
    }
}
