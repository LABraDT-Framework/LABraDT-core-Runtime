package de.labradt.serviceexecution.runtime.script.interpreter;

import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;

/**
 * Generic bridge pattern interface for injecting Java functions into script runtimes.
 */
public interface IScriptBridge<R> {

    /**
     * Register bridge functions into the script runtime context.
     * @param runtimeContext The runtime context to inject functions into (e.g., Lua Globals)
     */
    void registerBridgeFunctions(R runtimeContext);

    TechnologyAdapter getScriptType();
}
