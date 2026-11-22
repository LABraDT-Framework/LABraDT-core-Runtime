package de.labradt.serviceexecution.core.api.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceTriggerCommand {
    private List<String> serviceIds;
    private Map<String, String> dynamicArgs;
}
