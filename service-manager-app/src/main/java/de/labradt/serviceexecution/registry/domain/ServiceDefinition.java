package de.labradt.serviceexecution.registry.domain;

import de.labradt.serviceexecution.core.api.enums.ExecutionCategory;
import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import de.labradt.serviceexecution.manager.registry.ServiceDefinitionView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;

import java.util.HashMap;
import java.util.Map;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDefinition implements ServiceDefinitionView {
    @Id
    private String serviceId;

    private String displayName;

    private String description;

    @Enumerated(EnumType.STRING)
    private ExecutionCategory executionCategory;

    @Convert(converter = OpenEnumConverters.AdapterConverter.class)
    private TechnologyAdapter technologyAdapter;

    private String fileReference;
    
    // The defaultArgs map is persisted as a separate join table service_default_args (service_id, arg_key, arg_value)
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "service_default_args", joinColumns = @JoinColumn(name = "service_id"))
    @MapKeyColumn(name = "arg_key")
    @Column(name = "arg_value")
    @Builder.Default
    private Map<String, String> defaultArgs = new HashMap<>();

    public Map<String, String> getDefaultArgs() {
        if (defaultArgs == null) defaultArgs = new HashMap<>();
        return defaultArgs;
    }
}
