package de.labradt.serviceexecution.runtime.script.interpreter;

import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;

import java.util.Map;

// Abstraction for different types of script interpreters
public interface IScriptInterpreter {

    TechnologyAdapter getScriptType();

    String execute(String scriptContent, Map<String, String> args) throws Exception;
}
