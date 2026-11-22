# Extending the Service Layer

This guide is for developers who want to add new capabilities to the system. There are two kinds of extension:

- **Adding a new tech adapter** plugs a new technology into an existing type (for example, a Python interpreter inside the existing Script type).
- **Adding a new service type** creates an entirely new execution category (for example, a "Serverless" type alongside the existing Script, Container, and External types).

Read the section that matches what you want to do. **No separate Python registration client is needed.** Extensions are compiled into the Java Service Manager; service definitions are then registered and triggered directly through its HTTP API using `curl` (or `curl.exe` in PowerShell). The manager runs on `http://localhost:8082` in the default setup. API examples assume the Service Manager is already running.

---

## Background: how the service layer is structured

The service layer has two levels.

**Level 1: Service types**

A service type defines how a service runs. The three built-in types are defined in [`ExecutionCategory.java`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/ExecutionCategory.java):

```java
public enum ExecutionCategory {
    SCRIPT,
    CONTAINER,
    EXTERNAL
}
```

Each type has one runtime class that implements [`IRuntimeProvider`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/interfaces/IRuntimeProvider.java). The service manager talks to every runtime through that single interface, so it never needs to know what type it is dealing with.

**Level 2: Tech adapters**

Inside each runtime, there is a second level of routing. For example, the Script runtime has two interpreter plugins: Bash (`runtime-plugins/bash`) and Lua (`runtime-plugins/lua`). Each language is a separate adapter that implements a type-specific interface (`IScriptInterpreter`). The runtime auto-discovers all adapters at startup through Spring dependency injection.

The mapping between type and adapter is in [`TechnologyAdapter.java`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/TechnologyAdapter.java):

```java
public enum TechnologyAdapter {
    LUA(ExecutionCategory.SCRIPT),
    BASH(ExecutionCategory.SCRIPT),
    DOCKER(ExecutionCategory.CONTAINER),
    REST(ExecutionCategory.EXTERNAL);
    ...
}
```

---

## Part 1: Adding a new tech adapter to an existing runtime type

Use this when an existing type (Script, Container, or External) fits what you need, but the specific technology you want is not yet supported. For example, adding a Python interpreter to the Script type.

The code change consists of a `TechnologyAdapter` enum entry and an adapter implementation (plus an optional language bridge for nested invocation). **Deploying** the change also requires making the adapter bean available in the Service Manager's Spring application and rebuilding/restarting it. No separate interactive client must be changed.

### Step 1: Add an entry to TechnologyAdapter

Open [`TechnologyAdapter.java`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/TechnologyAdapter.java) and add your new adapter:

```java
public enum TechnologyAdapter {
    LUA(ExecutionCategory.SCRIPT),
    BASH(ExecutionCategory.SCRIPT),
    PYTHON(ExecutionCategory.SCRIPT),      // new
    DOCKER(ExecutionCategory.CONTAINER),
    REST(ExecutionCategory.EXTERNAL);
    ...
}
```

### Step 2: Write the adapter class

Implement the interface for your chosen type and annotate it with `@Component`.

| Type you are extending | Interface to implement | Reference implementation |
|------------------------|------------------------|--------------------------|
| SCRIPT | `IScriptInterpreter` | `BashInterpreter.java` in the Bash plugin (internal project `runtime-plugins/bash`); `LuaInterpreter.java` in `runtime-plugins/lua` |
| CONTAINER | `IContainerOrchestrator` | `DockerAdapter.java` in the Docker plugin (internal project `runtime-plugins/docker`) |
| EXTERNAL | `IExternalServiceClient` | `RestAdapter.java` in the Rest plugin (internal project `runtime-plugins/rest`) |

An adapter that needs HTTP injects the core's shared client, `@Qualifier("restClient") RestClient`
(defined in `service-manager`); don't define another bean named `restClient`.

Example for a Python interpreter:

```java
@Slf4j
@Component
public class PythonInterpreter implements IScriptInterpreter {

    @Override
    public TechnologyAdapter getScriptType() {
        return TechnologyAdapter.PYTHON;
    }

    @Override
    public String execute(String scriptContent, Map<String, String> args) throws Exception {
        // create the Python runtime, inject args, run the script, return its output
        ...
    }
}
```

The runtime class (`ScriptRuntime`) iterates all `IScriptInterpreter` beans at startup and registers them by their `TechnologyAdapter` key. Your class will be picked up automatically without any changes to the runtime.

### Supporting nested service invocation

For script adapters, supporting nested service invocation also requires a matching implementation of `IScriptBridge` for the scripting language's runtime context. This bridge injects the language-level nested-invocation function and connects it back to the Runtime's nested invocation path.

Create a bridge for the language runtime and annotate it with `@Component`. It should return the adapter it supports, inject the language-level function, and call `IServiceOrchestrator.resolveAndExecuteNested(...)` when that function runs:

```java
@Component
public class PythonScriptBridge implements IScriptBridge<PythonRuntimeContext> {

    @Override
    public TechnologyAdapter getScriptType() {
        return TechnologyAdapter.PYTHON;
    }

    @Override
    public void registerBridgeFunctions(PythonRuntimeContext context) {
        // register triggerNestedService(serviceId, dynamicArgs)
        // convert arguments to Map<String, String>
        // call orchestrator.resolveAndExecuteNested(...)
        // convert the response to a Python value
        ...
    }
}
```

Then inject this bridge into the interpreter and register it each time a new language runtime context is created:

```java
public String execute(String scriptContent, Map<String, String> args) throws Exception {
    PythonRuntimeContext context = createRuntimeContext();
    injectArguments(context, args);
    pythonScriptBridge.registerBridgeFunctions(context);
    // execute scriptContent in context
    ...
}
```

The working reference for the bridge flow is `LuaScriptBridge.java` in the Lua plugin (internal project `runtime-plugins/lua`).

### Step 3: Package and activate the adapter

Build and install the plugin project according to its README, add the compiled plugin artifact as a dependency of `service-manager-app/pom.xml`, and rebuild/restart the Service Manager. Make sure the new adapter is registered as a Spring bean (`@Component` **within an actually scanned package**, or the plugin's Spring auto-configuration). The `ScriptRuntime` only discovers adapter instances that Spring supplies to it.

From the **services** project root, after installing the plugin dependency:

```bash
mvn clean install -DskipTests
cd service-manager-app
mvn spring-boot:run
```

### Step 4: Register and test without a Python CLI

The Service Manager accepts service definitions directly via `POST /registry/services`. Use the new **API enum values** (`executionCategory` and `technologyAdapter`), not a hard-coded client-side picker.

For a Python interpreter, first upload a test script to the Engine's file-storage endpoint (the Engine must be running); the response provides a `fileReference`:

```bash
curl -sS -X POST 'http://localhost:8080/engine/storage/upload' \
  -F 'file=@path/to/test.py'
```

Save the following as `register-python.json` and substitute the exact returned reference:

```json
{
  "serviceId": "python-demo",
  "displayName": "Python interpreter smoke test",
  "executionCategory": "SCRIPT",
  "technologyAdapter": "PYTHON",
  "fileReference": "REPLACE_WITH_RETURNED_REFERENCE",
  "defaultArgs": {
    "message": "hello"
  }
}
```

Register it with `curl`:

```bash
curl -i -X POST 'http://localhost:8082/registry/services' \
  -H 'Content-Type: application/json' \
  --data-binary @register-python.json
```

Then trigger a run, overriding or supplementing the registered `defaultArgs` with `dynamicArgs`:

```bash
curl -sS -X POST 'http://localhost:8082/services/trigger' \
  -H 'Content-Type: application/json' \
  -d '{"serviceIds":["python-demo"],"dynamicArgs":{"message":"from-terminal"}}'
```

The trigger response provides a distinct `executionId` for the run. Inspect it with:

```bash
curl -sS 'http://localhost:8082/services/status?executionId=REPLACE_WITH_EXECUTION_ID'
curl -sS 'http://localhost:8082/services/result?executionId=REPLACE_WITH_EXECUTION_ID'
```

**PowerShell:** Use `curl.exe`, and prefer `--data-binary "@register-python.json"` when posting a JSON file. The engine-storage upload syntax is the same with `curl.exe`. A Python adapter must actually implement the uploaded-file handling and its execution semantics; the interface snippet above is illustrative, not a complete implementation.

---

## Part 2: Adding a new service type

Use this when none of the existing types (Script, Container, External) fits what you need.

### Step 1: Add the type to the enum

Open [`ExecutionCategory.java`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/ExecutionCategory.java) and add your type:

```java
public enum ExecutionCategory {
    SCRIPT,
    CONTAINER,
    EXTERNAL,
    SERVERLESS   // your new type
}
```

### Step 2: Add a TechnologyAdapter entry for each adapter

Open [`TechnologyAdapter.java`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/TechnologyAdapter.java) and add at least one adapter for your new type:

```java
public enum TechnologyAdapter {
    LUA(ExecutionCategory.SCRIPT),
    BASH(ExecutionCategory.SCRIPT),
    DOCKER(ExecutionCategory.CONTAINER),
    REST(ExecutionCategory.EXTERNAL),
    AWS_LAMBDA(ExecutionCategory.SERVERLESS);   // your first adapter
    ...
}
```

### Step 3: Create the adapter interface

Create a new interface in your runtime module. This is the contract every adapter for your type must implement. Follow the same pattern used by the existing types:

| Type | Adapter interface |
|------|-------------------|
| SCRIPT | [`IScriptInterpreter`](runtime-script/src/main/java/isw/digitaltwin/serviceexecution/runtime/script/interpreter/IScriptInterpreter.java) |
| CONTAINER | [`IContainerOrchestrator`](runtime-container/src/main/java/isw/digitaltwin/serviceexecution/runtime/container/orchestrator/IContainerOrchestrator.java) |
| EXTERNAL | [`IExternalServiceClient`](runtime-external/src/main/java/isw/digitaltwin/serviceexecution/runtime/external/client/IExternalServiceClient.java) |

Example for a serverless type:

```java
public interface IServerlessInvoker {
    TechnologyAdapter getInvokerType();
    String invoke(ExecutionRequestDTO request) throws Exception;
}
```

### Step 4: Write the runtime class

Create a class that implements `IRuntimeProvider`. It should:

1. Accept a `List<IServerlessInvoker>` (or whatever your interface is) in the constructor.
2. Build an internal registry keyed by `TechnologyAdapter` at startup. Spring injects all implementations of your interface automatically, so you do not need any manual wiring.
3. Return `ExecutionCategory.SERVERLESS` from `getExecutionCategory()` (the method used by `ServiceManager` to discover runtimes).
4. In `ExecuteAsync`, look up the right adapter from the registry and delegate.

Look at [`ScriptRuntime.java`](runtime-script/src/main/java/isw/digitaltwin/serviceexecution/runtime/script/ScriptRuntime.java) or [`ExternalApiRuntime.java`](runtime-external/src/main/java/isw/digitaltwin/serviceexecution/runtime/external/ExternalApiRuntime.java) as a template. The constructor pattern is the same in both:

```java
@Component
public class ServerlessRuntime implements IRuntimeProvider {

    private final Map<TechnologyAdapter, IServerlessInvoker> invokerRegistry;

    public ServerlessRuntime(List<IServerlessInvoker> invokers) {
        Map<TechnologyAdapter, IServerlessInvoker> temp = new HashMap<>();
        for (IServerlessInvoker invoker : invokers) {
            temp.put(invoker.getInvokerType(), invoker);
        }
        this.invokerRegistry = Collections.unmodifiableMap(temp);
    }

    @Override
    public ExecutionCategory getExecutionCategory() {
        return ExecutionCategory.SERVERLESS;
    }

    @Override
    public CompletableFuture<ExecutionResponseDTO> ExecuteAsync(ExecutionRequestDTO request) {
        // look up adapter, call invoke(), return result
    }

    @Override
    public boolean Stop(String executionId) { ... }
}
```

### Step 5: Write and deploy at least one adapter

Implement `IServerlessInvoker` (or your interface) for your first real adapter. Register the adapter and its runtime as Spring beans; for code shipped from a separate plugin, make sure it is discovered through component scanning or plugin auto-configuration. Build/install the plugin, include its dependency in `service-manager-app/pom.xml`, then rebuild and restart the Service Manager. The new `SERVERLESS` runtime will become available when the application boots and `ServiceManager` discovers its `IRuntimeProvider` bean.

### Step 6: Register and trigger the new type using HTTP

No client-side `EXECUTION_CATEGORIES` or adapter list has to be updated. Once the Java enums, runtime, adapter implementation and Spring wiring are deployed, register a service using the Service Manager API. For example, save `register-serverless.json`:

```json
{
  "serviceId": "serverless-demo",
  "displayName": "Serverless execution example",
  "description": "Uses the new SERVERLESS execution category",
  "executionCategory": "SERVERLESS",
  "technologyAdapter": "AWS_LAMBDA",
  "defaultArgs": {}
}
```

```bash
curl -i -X POST 'http://localhost:8082/registry/services' \
  -H 'Content-Type: application/json' \
  --data-binary @register-serverless.json

curl -sS -X POST 'http://localhost:8082/services/trigger' \
  -H 'Content-Type: application/json' \
  -d '{"serviceIds":["serverless-demo"],"dynamicArgs":{}}'
```

The `defaultArgs` keys required for a real `AWS_LAMBDA` invocation depend on **your implementation** (for example, how it identifies the remote function and supplies credentials). This example demonstrates the registry and dispatch contract; it is not a working Lambda deployment recipe. Confirm the trigger response and poll `/services/status?executionId=...` or `/services/result?executionId=...` with the returned ID.

In Windows PowerShell, use `curl.exe` and `--data-binary "@register-serverless.json"`. For the trigger body, you can also save the JSON to a file and post it with `--data-binary "@trigger.json"` to avoid shell quoting issues.

---

## Notes for `IContainerOrchestrator` adapters

### Running commands in shell-less or non-Linux images

`ContainerRuntime` supports two ways to specify the container's command, via `ExecutionRequestDTO.args`:

- **`CMD`**: a plain shell string, e.g. `CMD=echo hello && curl https://example.com`. This is auto-wrapped as `["sh", "-c", "..."]` before being passed to the orchestrator. It's a convenience default for ordinary Linux images and covers the common case.
- **`CMD_ARGS`**: a JSON array of strings, e.g. `CMD_ARGS=["cmd", "/c", "echo hello"]` or `CMD_ARGS=["/mytool", "--flag"]`. This is passed to the container **exactly as given**, with no wrapping at all.

Use `CMD_ARGS` whenever the target image doesn't have a POSIX shell: Windows containers (`cmd /c ...` or `powershell -Command ...`), or distroless/minimal images (`gcr.io/distroless/*`) that ship no shell whatsoever. `ContainerRuntime` doesn't try to guess the right invocation convention for every possible image; the person registering the service knows what's inside their own image, so they supply the exact argv it needs. If both `CMD` and `CMD_ARGS` are supplied, `CMD_ARGS` wins and a warning is logged, since supplying both is more likely a mistake than an intentional fallback.

### Orchestrators that can't build images

`IContainerOrchestrator.prepareImage(config, serviceId)` covers two scenarios: pulling a pre-built image (`config.imageRef`) or building one locally from a Dockerfile (`config.buildFile`). Not every orchestrator can do both. **Kubernetes has no in-cluster build primitive**; images must be built and pushed to a registry externally and only ever pulled by the cluster.

`buildImage`/`pullImage` are deliberately **not** declared on `IContainerOrchestrator`; only `DockerAdapter` has them, as private implementation details of its own `prepareImage`. This means a `KubernetesOrchestrator` doesn't have to implement a `buildImage` method it can never honor; it only needs to implement `prepareImage`, and can reject a build request cleanly:

```java
@Override
public String prepareImage(ContainerConfig config, String serviceId) throws Exception {
    if (config.getBuildFile() != null) {
        throw new UnsupportedOperationException(
            "Kubernetes cannot build images, register this service with a pre-built "
            + "image reference (args['image']) instead of a Dockerfile");
    }
    return pullFromRegistry(config.getImageRef());
}
```

---

## Summary

| What you want | Code and deployment changes | Terminal verification |
|---|---|---|
| New service type | `ExecutionCategory.java`, `TechnologyAdapter.java`, new `IRuntimeProvider` implementation, new adapter interface and at least one adapter; make them Spring beans, add the plugin dependency and rebuild | `POST /registry/services` using new category and adapter values; `POST /services/trigger`; check execution by ID |
| New adapter in an existing type | `TechnologyAdapter.java`, a new adapter class, and optional bridge (for nested script calls); make the adapter bean available, add the plugin dependency and rebuild | Register with the existing category and the new technology adapter; trigger and inspect results |

Spring performs runtime/adapter discovery when the Service Manager starts. **There is no dependency on a separate Python registration program and no client-side list to keep synchronized.** A registered service still needs the appropriate compiled plugin on the Service Manager classpath before it can execute. The API accepts definitions, but it does not compile, install or enable a missing runtime plugin.

For the full command-line registration and runtime-argument reference, see [REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md). The API examples in this guide are based on the provided Service Manager and registration examples; the new Python and Serverless implementations shown here are design examples, not runnable plugins or integration-tested commands.
