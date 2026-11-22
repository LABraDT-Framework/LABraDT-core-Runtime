package de.labradt.serviceexecution.runtime.external.client;

import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;

import java.util.Map;

/**
 * IExternalServiceClient: Generic interface for external API calls.
 *
 * Receives the raw args map from ExecutionRequestDTO, so each adapter (REST, MQTT, gRPC, ...) is free to
 * interpret whatever keys its own transport needs, without the runtime having to know about any of them
 */
public interface IExternalServiceClient {

    // Returns the type of client 
    TechnologyAdapter getClientType();

    // Send a generic external service request
    String SendRequest(Map<String, String> args) throws Exception;
}
