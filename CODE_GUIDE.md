# Runtime - Code Guide

This document explains how the Runtime component is built: what each module is
responsible for, how the pieces fit together, and, for the parts that weren't obvious, why they
were designed the way they are. Every reference is a clickable link into the actual code, and the
key methods are quoted with their real signatures.

For hands-on instructions (registering a service, extending the system) see
[REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md) and
[ADDING_A_RUNTIME_TYPE_OR_ADAPTER.md](ADDING_A_RUNTIME_TYPE_OR_ADAPTER.md); to
build and run, see [README.md](README.md).

## Contents

- [The core idea](#the-core-idea)
- [Following a request end to end](#following-a-request-end-to-end)
- [`core-api`: the contracts everyone shares](#core-api-the-contracts-everyone-shares)
- [`service-manager`: the orchestrator](#service-manager-the-orchestrator)
- [One design decision used by every runtime](#one-design-decision-used-by-every-runtime-threading-matched-to-workload)
- [The three runtimes](#the-three-runtimes)
- [Nested service calls: two different mechanisms](#nested-service-calls-two-different-mechanisms)
- [`service-manager-app`: the application that wires it together](#service-manager-app-the-application-that-wires-it-together)
- [Where the behaviour is verified: tests](#where-the-behaviour-is-verified-tests)

---

## The core idea

The component takes a request to run a *service* and routes it to whatever engine can actually run
it. A service is one of three kinds: a **script** (Bash or Lua, through plugins), a **container**
(Docker, through a plugin), or an **external** REST call (through a plugin). The caller never has to care which.

Routing happens in two stages. First the request goes to the right **runtime** (script vs
container vs external); then, inside that runtime, to the right **adapter** (Lua, or
Docker, or REST). Both stages are driven by two enums that act as the system's vocabulary:
[`ExecutionCategory`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/ExecutionCategory.java)
(the three kinds) and
[`TechnologyAdapter`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/TechnologyAdapter.java)
(each concrete technology, tagged with the category it belongs to):

```java
public enum ExecutionCategory { SCRIPT, CONTAINER, EXTERNAL }

public enum TechnologyAdapter {
    LUA(ExecutionCategory.SCRIPT),
    DOCKER(ExecutionCategory.CONTAINER),
    REST(ExecutionCategory.EXTERNAL);

    public final ExecutionCategory executionCategory;   // which runtime this adapter belongs to
}
```

Every `TechnologyAdapter` carries the `ExecutionCategory` it belongs to.

```
Engine / CLI
        |  POST /services/trigger
        v
ServiceManagerController --> ServiceManager     +--> ScriptRuntime --> Lua
   (REST front door)         (the orchestrator) |
                                                +--> ContainerRuntime --> Docker
                                                |
                                                +--> ExternalApiRuntime --> REST
```

The whole thing is a Spring Boot app split into six Maven modules
([`pom.xml`](pom.xml)): `core-api`, `service-manager`, `service-manager-app`,
`runtime-script`, `runtime-container`, `runtime-external`.

---

## Following a request end to end

A normal asynchronous run looks like this:

1. `POST /services/trigger` arrives at [`ServiceManagerController.trigger`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/controller/ServiceManagerController.java#L61). Its signature is `ResponseEntity<?> trigger(@RequestBody ServiceTriggerCommand command)`: the body carries the `serviceIds` to run plus optional `dynamicArgs`.
2. [`ServiceManager.resolveAndExecute`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/ServiceManager.java#L147), `List<ExecutionAcceptedDTO> resolveAndExecute(ServiceTriggerCommand command)`, resolves each `serviceId` against the registry and merges each service's stored default args with any dynamic args the caller supplied.
3. For each resolved service, [`ExecuteServiceAsync`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/ServiceManager.java#L181), `ExecutionAcceptedDTO ExecuteServiceAsync(ExecutionRequestDTO request)`, looks up the runtime by category, dispatches, and the controller returns **202** right away with the list of triggered IDs.
4. The runtime routes to its adapter, runs the work on its own thread pool, and completes its `CompletableFuture<ExecutionResponseDTO>`.
5. [`onJobComplete`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/ServiceManager.java#L315) (on the callback pool, see below) caches the result and POSTs it back to the engine; the caller can meanwhile poll `GET /services/status` or `GET /services/result`.

The two identifiers returned in
[`ExecutionAcceptedDTO`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/dtos/ExecutionAcceptedDTO.java)
are what the caller uses next:

```java
public record ExecutionAcceptedDTO(String executionId, String serviceId, ExecutionStatus status) {}
```

`executionId` (not `serviceId`) is the key you poll with, because the same service can be running
several times at once.

---

## `core-api`: the contracts everyone shares

[core-api](core-api/) holds only interfaces, enums, and DTOs, no logic. This was
a deliberate choice: every other module depends on `core-api`, but `core-api` depends on nothing.
That keeps the runtimes from depending on the orchestrator, and the orchestrator from depending on
any concrete runtime. Everyone talks through these shared types instead.

**[`IRuntimeProvider`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/interfaces/IRuntimeProvider.java)**
is the contract every execution engine implements:

```java
public interface IRuntimeProvider {
    CompletableFuture<ExecutionResponseDTO> ExecuteAsync(ExecutionRequestDTO request);
    boolean Stop(String executionId);
    ExecutionCategory getExecutionCategory();
}
```

- `ExecuteAsync(request)` starts the work and immediately hands back a `CompletableFuture`; the runtime completes it later, off the caller's thread, when execution finishes. The `CompletableFuture` return type is the reason a slow Docker build or a hanging REST call never blocks the caller.
- `Stop(executionId)` best-effort cancels one in-flight run, keyed by the per-run `executionId`.
- `getExecutionCategory()` is the important detail: a runtime *announces* which kind it handles, rather than the orchestrator hard-coding a mapping to it. That single method is what lets `ServiceManager` stay ignorant of the concrete runtimes.

The data flowing across this interface is carried by
[`ExecutionRequestDTO`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/dtos/ExecutionRequestDTO.java)
and
[`ExecutionResponseDTO`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/dtos/ExecutionResponseDTO.java).
The request carries both routing enums, the file reference, and the merged args, and it knows how
to check and protect itself:

```java
public class ExecutionRequestDTO {
    private String serviceId;            // WHICH service (not unique per run)
    private String executionId;          // ONE run of it, stamped on by ServiceManager
    private ExecutionCategory executionCategory;
    private TechnologyAdapter technologyAdapter;
    private String fileReference;        // where the script/buildfile lives in storage
    private Map<String, String> args;

    public Map<String, String> getArgsCopy() { ... }   // defensive copy: runtimes can't mutate shared state
    public void validate() throws IllegalArgumentException { ... }  // serviceId + both enums must be set
}
```

`serviceId` and `executionId` are kept separate because the same service can be triggered again
while a previous run is still in flight, so `serviceId` alone can't key the tracking maps.
`validate()` is called before any routing so a malformed request fails fast, and `getArgsCopy()`
hands each runtime its own copy of the args so nothing downstream can corrupt shared state.

A caller doesn't build an `ExecutionRequestDTO` directly, though; it isn't the wire input. What
arrives at the trigger endpoints is the smaller
[`ServiceTriggerCommand`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/dtos/ServiceTriggerCommand.java)
(a plain command DTO, not an enum, despite the "command" name):

```java
public class ServiceTriggerCommand {
    private List<String> serviceIds;            // which registered services to run
    private Map<String, String> dynamicArgs;    // trigger-time args, applied to all of them
}
```

It carries only what a caller actually knows: *which* services to run, by id, and any dynamic args.
Everything else on `ExecutionRequestDTO` (`executionCategory`, `technologyAdapter`, `fileReference`,
the merged defaults) is looked up from the registry by `ServiceManager`, which expands one
`ServiceTriggerCommand` into one `ExecutionRequestDTO` per `serviceId`. `serviceIds` is a **list** on
purpose: a single rule firing can fan out to several services at once (the async path), while the
nested path deliberately requires the list to hold exactly one id (you can only block on one result).
The same `dynamicArgs` map is applied to every service in the list.

The result comes back as an immutable
[`ExecutionResponseDTO`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/dtos/ExecutionResponseDTO.java),
built through two static factory methods that every runtime uses so success/failure results are
shaped identically:

```java
ExecutionResponseDTO.success(String serviceId, String outputData, Long durationMs)
ExecutionResponseDTO.failure(String serviceId, String errorMessage, Long durationMs)
```

Both DTOs, and the manager's tracker, describe where a run is with
[`ExecutionStatus`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/enums/ExecutionStatus.java),
the third enum in `core-api`:

```java
public enum ExecutionStatus { ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, NOT_FOUND }
```

The first five are the lifecycle of one run: `ACCEPTED` (queued) -> `RUNNING` -> one of `COMPLETED`,
`FAILED`, or `CANCELLED` as a terminal state. `NOT_FOUND` is different: it isn't a state a run is
ever in, it's the sentinel `CheckStatus(executionId)` returns when the `executionId` is unknown
(never dispatched, or long since evicted), which is why the status endpoint can answer for any id
without throwing.

The three enums together form the full vocabulary the modules share: `ExecutionCategory` and
`TechnologyAdapter` decide *where a request is routed*, and `ExecutionStatus` describes *where a run
has got to*.

**`IServiceOrchestrator`: what it is not, and what it actually is**

[`IServiceOrchestrator`](core-api/src/main/java/isw/digitaltwin/serviceexecution/core/api/interfaces/IServiceOrchestrator.java) is not the front door of the component.
The REST controller, [`ServiceManagerController`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/controller/ServiceManagerController.java),
is wired to the concrete `ServiceManager` class, not to the interface:

```java
private final ServiceManager serviceManager;

public ServiceManagerController(ServiceManager serviceManager) {
    this.serviceManager = serviceManager;
}
```

What `IServiceOrchestrator` actually does is preserve the module architecture: every module depends
on `core-api`, never directly on another module. Sometimes a runtime needs to call back into
`ServiceManager`, for example `LuaScriptBridge` (in the Lua plugin, `runtime-plugins/lua`)
lets a running Lua script trigger another service and wait for its result, which means invoking
`ServiceManager`. But the Lua plugin depends only on `runtime-script` and `core-api`, not on `service-manager`.
Because `IServiceOrchestrator` lives in `core-api` and `ServiceManager` implements it, the bridge
holds the **interface**, not the concrete class.

Spring injects the real `ServiceManager` singleton at runtime, but at compile time the Lua plugin
only knows the `core-api` interface, so it never has to depend on the `service-manager` module. This
is the same rule the runtimes follow in the other direction: `ServiceManager` depends on
`IRuntimeProvider` from `core-api`, not on any concrete runtime module.

```java
public interface IServiceOrchestrator {
    ExecutionAcceptedDTO ExecuteServiceAsync(ExecutionRequestDTO request);
    ExecutionResponseDTO ExecuteNestedServiceSync(ExecutionRequestDTO request) throws InterruptedException;
    ExecutionResponseDTO resolveAndExecuteNested(ServiceTriggerCommand command);
    boolean StopService(String executionId);
    ExecutionStatus CheckStatus(String executionId);
}
```

`resolveAndExecuteNested(command)` is the one `LuaScriptBridge` actually calls because the bridge only knows a `serviceId` and
some dynamic args, not a fully-built `ExecutionRequestDTO` (technologyAdapter, fileReference, and
so on). `resolveAndExecuteNested` does that registry lookup first, then delegates to
`ExecuteNestedServiceSync` internally, which is what actually blocks and waits on the runtime's
`CompletableFuture`.

---

## `service-manager`: the orchestrator

[`ServiceManager`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/ServiceManager.java)
is the brain. It resolves a service, picks the right runtime, dispatches, tracks the run, and
reports the result back.

The central design decision lives in its
[constructor](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/ServiceManager.java#L94).
Spring injects a `List` of **every** bean implementing `IRuntimeProvider`, and the manager builds a
lookup map keyed by each runtime's declared `ExecutionCategory`:

```java
public ServiceManager(List<IRuntimeProvider> runtimes, ...) {
    Map<ExecutionCategory, IRuntimeProvider> runtimeMap = new EnumMap<>(ExecutionCategory.class);
    for (IRuntimeProvider runtime : runtimes) {
        runtimeMap.put(runtime.getExecutionCategory(), runtime);   // the runtime tells us its key
    }
    this.runtimeRegistry = Collections.unmodifiableMap(runtimeMap);
}
```

There is no `if (SCRIPT) ... else if (CONTAINER) ...` anywhere. Routing at request time is a single
map lookup:

```java
private IRuntimeProvider getRuntimeProvider(ExecutionCategory executionCategory) {
    IRuntimeProvider runtime = runtimeRegistry.get(executionCategory);
    if (runtime == null)
        throw new IllegalArgumentException("No runtime provider found for ExecutionCategory: " + executionCategory);
    return runtime;
}
```

This is what makes the system expandable without touching existing code: to add a new kind of
runtime you write a class that implements `IRuntimeProvider`, annotate it `@Component`, and it
registers itself at startup; the orchestrator never learns its name. That "closed for modification,
open for extension" property is the whole reason the enum-plus-auto-discovery split exists.

Before dispatching, the manager turns a `serviceId` into a runnable request. The shared helper
resolves the definition from the registry and layers the caller's dynamic args on top of the stored
defaults (so a trigger-time value wins over a registered one):

```java
private ExecutionRequestDTO resolveToRequest(String serviceId, Map<String, String> dynamicArgs) {
    ServiceDefinitionView def = serviceRegistryResolver.resolve(serviceId);
    Map<String, String> merged = new HashMap<>();
    if (def.getDefaultArgs() != null) merged.putAll(def.getDefaultArgs());
    if (dynamicArgs != null) merged.putAll(dynamicArgs);   // dynamic overrides default
    return ExecutionRequestDTO.builder()
            .serviceId(def.getServiceId())
            .executionCategory(def.getExecutionCategory())
            .technologyAdapter(def.getTechnologyAdapter())
            .fileReference(def.getFileReference())
            .args(merged)
            .build();
}
```

`ExecuteServiceAsync` then mints a fresh `executionId` per dispatch, records the run, hands off to
the runtime, and wires the completion handlers onto the returned future:

```java
String executionId = UUID.randomUUID().toString();
ExecutionRequestDTO stamped = request.toBuilder().executionId(executionId).build();
CompletableFuture<ExecutionResponseDTO> future = runtime.ExecuteAsync(stamped);
future.orTimeout(asyncTimeoutMinutes, TimeUnit.MINUTES)
      .thenAcceptAsync(response -> onJobComplete(executionId, response), callbackExecutor)
      .exceptionally(throwable -> { onJobFailed(executionId, serviceId, throwable); return null; });
return new ExecutionAcceptedDTO(executionId, serviceId, ExecutionStatus.RUNNING);
```

Execution state is split across three maps so a client can still poll for a result after the run is
finished and cleaned up:

```java
private final Map<String, ExecutionInfo> executionTracker = new ConcurrentHashMap<>();               // live status per run
private final Map<String, CompletableFuture<ExecutionResponseDTO>> resultMap = new ConcurrentHashMap<>(); // in-progress futures (for cancel / nested wait)
private final Map<String, ExecutionResponseDTO> completedResults = new ConcurrentHashMap<>();         // cached final results (for polling)
```

### Every method on `ServiceManager`

The class has a handful of public entry points, a set of private helpers behind them, and a few
query/lifecycle methods. In full:

| Method | Signature | What it does |
|---|---|---|
| **constructor** | `ServiceManager(List<IRuntimeProvider>, ObjectProvider<ServiceRegistryResolver>, RestClient)` | Auto-discovers every runtime into the `ExecutionCategory`-keyed `runtimeRegistry` (shown above), stores the registry resolver and the `RestClient` used to call the engine back. |
| `resolveAndExecute` | `List<ExecutionAcceptedDTO> resolveAndExecute(ServiceTriggerCommand)` | **Async trigger entry point** (called by `POST /services/trigger`). Validates the command, then for each `serviceId` calls `resolveToRequest` + `ExecuteServiceAsync`, returning one accepted-DTO per service. This is the fan-out. |
| `resolveAndExecuteNested` | `ExecutionResponseDTO resolveAndExecuteNested(ServiceTriggerCommand)` | **Nested/sync entry point** (called by the Lua bridge and `POST /services/triggerNested`). Requires exactly one `serviceId`, resolves it, and delegates to `ExecuteNestedServiceSync`, returning the result inline. Translates an `InterruptedException` into a failure DTO. |
| `ExecuteServiceAsync` | `ExecutionAcceptedDTO ExecuteServiceAsync(ExecutionRequestDTO)` | Dispatches **one** already-resolved request without blocking: mints the `executionId`, records `ACCEPTED`→`RUNNING`, hands off to the runtime, wires completion handlers, and returns the identity to poll (shown above). |
| `ExecuteNestedServiceSync` | `ExecutionResponseDTO ExecuteNestedServiceSync(ExecutionRequestDTO) throws InterruptedException` | Dispatches one request and **blocks** on the runtime's future up to `syncTimeoutMinutes` (default 2). On timeout it cancels the future and returns a failure DTO; on success it records the terminal status and caches the result. It intentionally does *not* echo to the engine, the parent top-level run reports the overall outcome. |
| `StopService` | `boolean StopService(String executionId)` | Cancels the pending future in `resultMap`, then asks each runtime's `Stop(executionId)` in turn; the first to return `true` owns the run, whose status is set to `CANCELLED` and echoed. Returns `false` if no runtime knew the id. |
| `CheckStatus` | `ExecutionStatus CheckStatus(String executionId)` | Reads `executionTracker`; returns `NOT_FOUND` for an unknown id (never throws), which is why the status endpoint can answer for any id. |
| `GetResult` | `ExecutionResponseDTO GetResult(String executionId)` | Returns the cached final result from `completedResults`, or `null` if the run isn't finished/known (the controller turns `null` into a 404). |
| `GetHistory` | `List<ExecutionResponseDTO> GetHistory()` | Every completed result cached since startup, across all services. |
| `GetRunningServices` | `List<RunningExecutionDTO> GetRunningServices()` | Every execution currently `ACCEPTED` or `RUNNING`, built by filtering `executionTracker`. |
| `getRuntimeRegistry` | `Map<ExecutionCategory, IRuntimeProvider> getRuntimeRegistry()` | An unmodifiable view of the runtime map, used mainly by the `WP1` discovery tests. |
| `shutdown` | `@PreDestroy void shutdown()` | On bean destruction, cancels every still-pending future and drains the `callbackExecutor` (10s grace, then force). |

The private helpers do the shared work the public methods lean on:

- `getRuntimeProvider(ExecutionCategory)` — the single map lookup that replaces any `if/else` routing; throws `IllegalArgumentException` if no runtime is registered for the category.
- `resolveToRequest(String serviceId, Map dynamicArgs)` — registry lookup + the default/dynamic arg merge (shown above), producing a ready-to-dispatch `ExecutionRequestDTO`.
- `onJobComplete(String executionId, ExecutionResponseDTO)` — the **normal** completion handler: sets the terminal status (`COMPLETED`/`FAILED`), caches the enriched result, removes the future, and echoes to the engine.
- `onJobFailed(String executionId, String serviceId, Throwable)` — the **exceptional** completion handler: unwraps `CompletionException`/`ExecutionException` to the real cause and maps it to a status (`TimeoutException`→timed out, `CancellationException`→`CANCELLED`, anything else→unexpected error), then caches and echoes.
- `postResultToEngine(serviceId, executionId, status, info)` — fire-and-forget POST of an `ExecutionResponseDTO` to the engine's result endpoint, submitted on the `callbackExecutor` so it never blocks execution; failures are logged, not propagated.


The manager depends on the registry through a narrow interface,
[`ServiceRegistryResolver`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/registry/ServiceRegistryResolver.java) /
[`ServiceDefinitionView`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/registry/ServiceDefinitionView.java),
both declared here in `service-manager`:

```java
public interface ServiceRegistryResolver {
    ServiceDefinitionView resolve(String serviceId);
}

public interface ServiceDefinitionView {
    String getServiceId();
    ExecutionCategory getExecutionCategory();
    TechnologyAdapter getTechnologyAdapter();
    String getFileReference();
    Map<String, String> getDefaultArgs();
}
```

The actual registry (a JPA entity plus database) lives up in `service-manager-app` and implements
these. This keeps the orchestration module free of any persistence dependency; it only knows
"something can resolve a serviceId into a definition," not how or where it's stored.

### The shared REST client

The core (`service-manager`) owns the one shared `RestClient` bean, named `restClient`. The
Service Manager uses it for the result callback to the engine, and runtime adapters that need
HTTP (`RestAdapter` in the Rest plugin, `runtime-plugins/rest`) inject it with `@Qualifier("restClient")`; a runtime
module must not define its own bean with that name. It is configured in
[`ExternalServiceConfiguration`](service-manager/src/main/java/isw/digitaltwin/serviceexecution/manager/config/ExternalServiceConfiguration.java)
with a stack of interceptors, added in order, for correlation IDs, logging, and retry with backoff:

```java
RestClient.builder()
    .requestFactory(clientHttpRequestFactory())                 // buffered, so logging can re-read the body
    .requestInterceptor(new RequestCorrelationInterceptor(config))
    .requestInterceptor(new HttpLoggingInterceptor())
    .requestInterceptor(new RetryInterceptor(config))
    .build();
```

Request-body logging is off by default (`logRequestBodies(false)` on the `ServiceCallConfig` bean)
so payloads aren't leaked into logs.

---

## One design decision used by every runtime: threading matched to workload

All three runtimes share the same shape: a constructor that auto-discovers its adapters into a map,
and `ExecuteAsync` that looks up the adapter by `TechnologyAdapter` and delegates. What differs, and
what was deliberately chosen per runtime, is the **threading model**:

| Runtime | Workload | Threading | Why |
|---|---|---|---|
| `ScriptRuntime` | CPU-bound (Lua interpretation runs inside the JVM) | Fixed pool of 4 platform threads | Threads are actively computing, not waiting. A bounded pool caps load; virtual threads give no benefit for CPU-bound work. |
| `ContainerRuntime` | I/O-bound (waiting on the Docker daemon) | Virtual threads + semaphore | Threads spend most of their time blocked on I/O. Virtual threads unmount from the carrier thread while waiting, so thousands of operations can be in flight without exhausting platform threads. |
| `ExternalApiRuntime` | I/O-bound (waiting on third-party HTTP responses) | Virtual threads + semaphore | Same reasoning as containers. |

The three declarations, side by side:

```java
// ScriptRuntime  - bounded platform pool for CPU-bound work
Executors.newFixedThreadPool(4, ...);

// ContainerRuntime / ExternalApiRuntime - one lightweight virtual thread per task
Executors.newVirtualThreadPerTaskExecutor();
private final Semaphore concurrencyLimit;   // caps how many run against the backend at once
```

`ServiceManager`'s own `callbackExecutor` is a fourth, separate pool (4 core/4 max platform threads,
bounded queue of 100, `CallerRunsPolicy`), used only for completion handlers (updating the tracker,
POSTing results to the engine). It's kept apart from the runtimes' pools so that posting a result
back to the engine can never be queued behind (and therefore starved by) a runtime's own execution
threads. The `CallerRunsPolicy` on a bounded queue gives backpressure: if the queue fills, the
submitting thread runs the task itself instead of the queue growing without limit.

---

## The three runtimes

### `runtime-script`

[`ScriptRuntime`](runtime-script/src/main/java/isw/digitaltwin/serviceexecution/runtime/script/ScriptRuntime.java)
runs scripts through adapters implementing
[`IScriptInterpreter`](runtime-script/src/main/java/isw/digitaltwin/serviceexecution/runtime/script/interpreter/IScriptInterpreter.java):
the interpreters are plugins: Bash (`BashInterpreter`, the internal project `runtime-plugins/bash`) and Lua
(`LuaInterpreter` on LuaJ, `runtime-plugins/lua`). The core has none, so SCRIPT services fail until one
is on the classpath; with it there, it registers itself like any other interpreter.
That adapter interface is tiny, and it's the second-level equivalent of `IRuntimeProvider`:

```java
public interface IScriptInterpreter {
    TechnologyAdapter getScriptType();                                  // announces LUA / BASH / ...
    String execute(String scriptContent, Map<String, String> args) throws Exception;   // returns captured output
}
```

The runtime discovers interpreters exactly the way `ServiceManager` discovers runtimes, one level
down: `ScriptRuntime(List<IScriptInterpreter> interpreters)` builds a
`Map<TechnologyAdapter, IScriptInterpreter>`, and `ExecuteAsync` looks up the interpreter by
`request.getTechnologyAdapter()` before calling `execute(...)`. Add a `PythonInterpreter` `@Component`
and it registers itself, no change to `ScriptRuntime`.

How the Lua interpreter sandboxes a script and injects its arguments is described in the Lua
plugin's `MODULES.md` (`runtime-plugins/lua`).

Script content itself isn't stored inline; `ScriptRuntime` fetches it by reference from engine
storage over HTTP (`resolveScriptContent(fileReference)` does a `GET /engine/storage/{key}`),
keeping large scripts out of the request payload. The engine address is
`engine.storage.base-url` in `service-manager-app/src/main/resources/application.properties`
(default `http://localhost:8080`, env var `ENGINE_STORAGE_BASE_URL`); `ContainerRuntime` uses the
same key for Dockerfiles.

### `runtime-container`

[`ContainerRuntime`](runtime-container/src/main/java/isw/digitaltwin/serviceexecution/runtime/container/ContainerRuntime.java)
runs services as containers through
[`IContainerOrchestrator`](runtime-container/src/main/java/isw/digitaltwin/serviceexecution/runtime/container/orchestrator/IContainerOrchestrator.java)
adapters. The Docker one (`DockerAdapter`, on docker-java) is a plugin: the internal project
`runtime-plugins/docker`. The container host keeps its own engine-storage client
([`StorageClientConfig`](runtime-container/src/main/java/isw/digitaltwin/serviceexecution/runtime/container/config/StorageClientConfig.java), bean `storageRestClient`).
This interface is larger than the other two because a container has a real lifecycle (prepare image,
create, start, read logs, stop):

```java
public interface IContainerOrchestrator {
    TechnologyAdapter getOrchestratorType();
    String getServiceManagerBaseUrl(String port, String contextPath);   // how a container reaches back to us
    String prepareImage(ContainerConfig config, String serviceId) throws Exception;  // pull OR build
    String createContainer(ContainerConfig config) throws Exception;
    void   startContainer(String containerId, ContainerConfig config) throws Exception;
    String getLogs(String containerId) throws Exception;
    void   stopContainer(String containerId, ContainerConfig config) throws Exception;
    ContainerStatus getStatus(String containerId) throws Exception;
}
```

Two of these signatures were shaped by *not* wanting to lock the design to Docker:

- `prepareImage` covers both pulling a pre-built image and building one from a Dockerfile, but the
  low-level `buildImage`/`pullImage` methods are **not** on the interface, only inside
  `DockerAdapter`. A future Kubernetes orchestrator has no in-cluster build primitive; forcing a
  `buildImage` method onto the interface would make it implement something it can never honor.
  Instead it can reject a build request from inside its own `prepareImage`.
- `getServiceManagerBaseUrl(port, contextPath)` lets each orchestrator decide how a container reaches
  back to the service manager, since that differs by platform (Docker Desktop's
  `host.docker.internal`, Podman's `host.containers.internal`, a Kubernetes Service DNS name).

### `runtime-external`

[`ExternalApiRuntime`](runtime-external/src/main/java/isw/digitaltwin/serviceexecution/runtime/external/ExternalApiRuntime.java)
makes outbound calls to third-party services through
[`IExternalServiceClient`](runtime-external/src/main/java/isw/digitaltwin/serviceexecution/runtime/external/client/IExternalServiceClient.java)
adapters. The REST one (`RestAdapter`) is a plugin: the internal project `runtime-plugins/rest`.
Its adapter interface is the most generic of the three:

```java
public interface IExternalServiceClient {
    TechnologyAdapter getClientType();
    String SendRequest(Map<String, String> args) throws Exception;   // raw args in, response string out
}
```

`SendRequest` just takes the raw args map, so each transport (REST today, MQTT or gRPC tomorrow)
interprets whatever keys it needs (`url`, `httpMethod`, `payload`, ...) without the runtime knowing
about any of them. `RestAdapter` has no HTTP client of its own: it injects the core's shared `restClient` bean
(see "The shared REST client" in the `service-manager` section).

---

## Nested service calls: two different mechanisms

A nested service is another registered service that a running service calls to delegate work.
"Nested calls work the same way for scripts and containers" is true in effect (both block and get
a result back), but the actual mechanism is different, and it's worth being precise about that
difference.

**From a Lua script** (with the Lua plugin, `runtime-plugins/lua`): the call stays inside the JVM. `LuaScriptBridge` injects a
`triggerNestedService(serviceId [, argsTable])` function into the script's globals; when the script
calls it, the bridge converts the Lua arguments to Java and forwards to
`orchestrator.resolveAndExecuteNested(...)`:

```java
@Override
public void registerBridgeFunctions(Globals globals) {
    globals.set("triggerNestedService", createNestedServiceTrigger());
}
// inside the function body:
ServiceTriggerCommand cmd = new ServiceTriggerCommand();
cmd.setServiceIds(List.of(serviceId));
cmd.setDynamicArgs(dynamicArgs);
ExecutionResponseDTO response = orchestrator.resolveAndExecuteNested(cmd);  // in-process call back UP
return LuaValue.valueOf(response.getOutputData() != null ? response.getOutputData() : "");
```

This is a direct in-process Java call, through the `IServiceOrchestrator` interface described earlier
(the reason the Lua plugin can call back into the orchestrator without depending on the
`service-manager` module).

**From a container:** the call leaves the process entirely. `ContainerRuntime` injects
`SERVICE_TRIGGER_NESTED_URL` as an environment variable into every container it starts. Code inside
the container (typically a shell script) does a plain `curl -X POST` to that URL, which is just
`POST /services/triggerNested` on the same running `ServiceManagerController`:

```java
@PostMapping("/triggerNested")
public ResponseEntity<ExecutionResponseDTO> triggerNested(@RequestBody ServiceTriggerCommand command) {
    ExecutionResponseDTO response = serviceManager.resolveAndExecuteNested(command);
    return ResponseEntity.ok(response);
}
```

The controller calls `serviceManager.resolveAndExecuteNested(...)` exactly as the Lua bridge does,
but the container gets there over HTTP, not through `IServiceOrchestrator`. A second URL,
`SERVICE_TRIGGER_URL`, is also injected for fire-and-forget calls that don't need a result
(`POST /services/trigger`).

**From an external REST service:** not supported. An external service runs in a third-party
environment the framework doesn't control, so there's no way to inject a callback URL or trust code
running there to call back in. If an external call needs follow-up logic, that has to be written as
a script or container that calls the external service and then triggers the next step itself.

---

## `service-manager-app`: the application that wires it together

[service-manager-app](service-manager-app/) is the Spring Boot entry point
([`ServiceManagerApplication`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/ServiceManagerApplication.java),
port 8082). It pulls in the other modules, exposes the REST API, and owns the persistent service
registry.

The REST surface is two controllers.
[`ServiceManagerController`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/controller/ServiceManagerController.java)
is the execution API; its class-level Javadoc is the canonical list of endpoints, but the handlers
map cleanly onto the `ServiceManager` methods above:

| Endpoint | Handler | Delegates to |
|---|---|---|
| `POST /services/trigger` | `trigger(ServiceTriggerCommand)` | `resolveAndExecute` -> 202 + triggered IDs |
| `POST /services/triggerNested` | `triggerNested(ServiceTriggerCommand)` | `resolveAndExecuteNested` -> inline result |
| `GET /services/status?executionId=` | `checkStatus(String)` | `CheckStatus` |
| `GET /services/result?executionId=` | `getResult(String)` | `GetResult` (404 if not cached) |
| `POST /services/stop?executionId=` | `stopService(String)` | `StopService` |

[`ServiceRegistryController`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/controller/ServiceRegistryController.java)
is the CRUD API (`GET/POST/DELETE /registry/services`) for registering, listing, and deleting
services.

The registry is
[`ServiceRegistry`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/registry/ServiceRegistry.java),
which implements the `ServiceRegistryResolver` interface the orchestrator depends on. The design
decision here is a **write-through cache**: definitions are persisted with JPA but also held in an
in-memory `ConcurrentHashMap`, kept in sync on every register/delete:

```java
@PostConstruct
public void warmCache() {                       // load the DB into memory once at startup
    repository.findAll().forEach(def -> cache.put(def.getServiceId(), def));
}

@Override
public ServiceDefinition resolve(String serviceId) {
    ServiceDefinition def = cache.get(serviceId);   // O(1), no DB round-trip on the hot path
    if (def == null) throw new ServiceNotFoundException(serviceId);
    return def;
}

public ServiceDefinition register(ServiceDefinition definition) {
    if (repository.existsByServiceId(definition.getServiceId()))
        throw new ServiceAlreadyExistsException(definition.getServiceId());
    repository.save(definition);
    cache.put(definition.getServiceId(), definition);   // keep cache in sync
    return definition;
}
```

Resolution happens on the hot path of every trigger, so serving it from the map instead of the
database is what keeps dispatch cheap. The stored record is
[`ServiceDefinition`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/registry/domain/ServiceDefinition.java),
a JPA `@Entity` that *implements* `ServiceDefinitionView`, which is why the orchestration module can
read a definition through the narrow view interface without ever depending on the JPA entity or the
persistence layer.

Registering a *service* is pure data and needs no code change; services live in the database. Only
adding a new *technology* (a new adapter or runtime) is a code change, and even that doesn't touch
the orchestrator. On first startup with an empty registry,
[`DataInitializer`](service-manager-app/src/main/java/isw/digitaltwin/serviceexecution/registry/DataInitializer.java)
seeds the three services the Engine's shipped rules call (`cooling-service`,
`geometry-correction-service`, `energy-monitoring-service`) as inline Lua scripts, so a fresh install
works end to end without network, engine storage or Docker. It does this only when a LUA interpreter
(the Lua plugin, `runtime-plugins/lua`) is on the classpath; otherwise it logs a warning and seeds
nothing, and the Engine's rule actions fail as unknown services (see
[REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md#the-three-default-rule-action-services)).

The database is an H2 file at `./data/service-registry`, relative to the working directory
(`spring.datasource.url` in `service-manager-app/src/main/resources/application.properties`). It is
runtime data and not part of the repository (this project's `.gitignore` ignores it), so a fresh clone
starts with only the seeded services (none without the Lua plugin).

---

## Where the behaviour is verified: tests

Almost every claim in this guide is backed by a test. They're grouped by "work package" (`WP*`) at
the app level, plus focused adapter tests next to the adapters (they moved with their plugins; this
project has none left).

Tests that check real plugin behaviour (Bash or Lua scripts, Docker containers, REST calls, mixed runtimes) carry the JUnit tag `plugins`
and are skipped with a reason when the plugin is not on the classpath (`TestEnvironment.requirePlugins`).
Core tests that only need "a script service runs" use a test-only stand-in interpreter,
`StubScriptInterpreterConfig` (it returns the script text and steps aside when the Lua plugin is present).

Tests that need something outside the JVM carry a JUnit tag, `docker` or `integration`. A normal
`mvn test` leaves them out; `mvn test -Pintegration` runs them, and each one first checks its
dependency (`service-manager-app`'s `TestEnvironment`) and is skipped with a reason when it is missing.
The app tests send engine callbacks and engine-storage calls to a local stub engine
(`StubEngineServer`, wired in by `StubEngineInitializer` through `META-INF/spring.factories`), so no
test waits on a missing engine; `EngineCallbackTest` checks that a finished execution really
reaches it. How to run both groups: [README.md](README.md#how-to-run-the-tests).

**Application-level (Spring Boot) tests** in
[service-manager-app/src/test](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/):

| Test | Verifies |
|---|---|
| [`WP1Tests`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/WP1Tests.java) | `ServiceManager` auto-discovers all three runtimes and routes by `ExecutionCategory`; the registry is populated and immutable. |
| [`WP2and3Tests`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/WP2and3Tests.java) | Level-2 script routing: LUA vs BASH reach the right interpreter, including concurrent mixed execution (all cases are tagged `plugins`: LUA needs the Lua plugin, BASH the Bash plugin plus bash, WSL on Windows; the mixed cases need both). |
| [`WP4Tests`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/WP4Tests.java) | Full EXTERNAL flow through orchestration for GET/POST/PUT/DELETE, including query params and payloads (needs the Rest plugin; tagged `plugins`). |
| [`WP5Tests`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/WP5Tests.java) | CONTAINER flow, both scenarios: pull a pre-built image, and build from a Dockerfile (needs the Docker plugin; tagged `plugins`). |
| [`CrossRuntimeNestedServiceIntegrationTest`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/CrossRuntimeNestedServiceIntegrationTest.java) | The nested-call mechanism across runtimes: Lua→container, Lua→Lua, Lua→external, container→container/Lua/external, the false-condition (no trigger) case, graceful handling of a failing nested service, and that a nested run gets its own pollable `executionId`. |
| [`ServiceRegistryIntegrationTest`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/ServiceRegistryIntegrationTest.java) | Registry defaults + dynamic-arg merge feed into real Lua/Docker/HTTP executions; unregistered-id and duplicate-id error paths. |
| [`ConcurrencyLoadTest`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/ConcurrencyLoadTest.java) | one test per category: 1000 concurrent SCRIPT, 100 concurrent CONTAINER, and 150 concurrent EXTERNAL requests, each asserting a high completion ratio (thread-pool / semaphore backpressure holds up under stress). |
| [`CrossCategoryIsolationTest`](service-manager-app/src/test/java/isw/digitaltwin/serviceexecution/CrossCategoryIsolationTest.java) | Categories don't starve each other: measures a baseline EXTERNAL request's latency with no other load, then fires an 800-request concurrent SCRIPT batch and measures 8 more EXTERNAL requests' latency while that batch is still in flight, asserting the under-load latency stays within a bounded multiple of baseline. |

These app tests run on an in-memory H2 database (`jdbc:h2:mem:sec-test`), set in
[`service-manager-app/src/test/resources/config/application.properties`](service-manager-app/src/test/resources/config/application.properties).
They never open the file database `./data/service-registry` that a normal run uses.
`ServiceRegistryIntegrationTest` also replaces the data source with its own embedded database.

**Module-level adapter tests** stay close to the code they exercise:

- `BashInterpreterTest` moved with the Bash plugin (`runtime-plugins/bash`), `LuaInterpreterTest` with the Lua plugin (`runtime-plugins/lua`).
- `DockerAdapterScenarioATest` (pull) and `DockerAdapterScenarioBTest` (build) moved with the Docker plugin (`runtime-plugins/docker`).
- `RestAdapterPublicUrlTest` (against httpbin.org) moved with the Rest plugin (`runtime-plugins/rest`).

Note that several of these are true integration tests, not mocks: `WP5` and
`ConcurrencyLoadTest`'s CONTAINER case need a running Docker daemon (and the Docker plugin); the REST tests,
`ConcurrencyLoadTest`'s EXTERNAL case, and `CrossCategoryIsolationTest` need httpbin.org reachable 
over the network. The goal is to prove the runtimes work against the real backends they'll use 
in production, not against stand-ins 
