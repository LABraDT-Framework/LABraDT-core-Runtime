# LABraDT Runtime — Service Execution and Management

**Component:** Service Manager  
**Port:** 8082  
**Overview of all LABraDT projects and the architecture diagram:** [README.md](https://github.com/LABraDT-Framework)

The Runtime component manages and executes external services. Its Service Manager provides a service registry and handles service invocation. The component supports different execution environments through the `IRuntimeProvider` interface, including scripts, containerized applications and external services. Services are triggered by the Engine and return their results through REST interfaces.

---

## Quick Start

**Prerequisites:** Java 21+, Maven 3.8+. Docker Desktop is required for container-based services. Bash is required for Bash scripts (on Windows, use WSL with a Linux distribution).

From the root of the **services** project folder:

```bash
mvn clean install -DskipTests
cd service-manager-app
mvn spring-boot:run
```

The Service Manager then listens on **http://localhost:8082** by default. Keep this terminal running while making requests from another terminal.

---

## How It Works

### Plugin Architecture

The core contains the runtime hosting interfaces, but execution implementations are supplied as plugins in separate internal projects:

| Execution category | Technology adapter | Internal project | Maven artifact |
|---|---|---|---|
| `SCRIPT` | `BASH` | `runtime-plugins/bash` | `runtime-bash` |
| `SCRIPT` | `LUA` | `runtime-plugins/lua` | `runtime-lua` |
| `CONTAINER` | `DOCKER` | `runtime-plugins/docker` | `runtime-docker` |
| `EXTERNAL` | `REST` | `runtime-plugins/rest` | `runtime-rest` |

Add the required artifacts as dependencies in `service-manager-app/pom.xml` and rebuild. Each plugin's README provides its dependency snippet and any additional requirements. Docker execution requires a running Docker daemon. **Without the Lua plugin, the three default rule-action services are not seeded.**

### Is It Static?

Each execution category (`SCRIPT`, `CONTAINER`, `EXTERNAL`) is fixed. Plugins extend the Runtime by adding new technology adapters within those categories (e.g. a new scripting language). The service registry is dynamic: services can be registered, triggered, and deleted at runtime through the REST API without restarting the Service Manager.

---

## Tutorial

### 1. Check Which Services Are Registered

```bash
curl -sS http://localhost:8082/registry/services
```

Inspect one registration by its `serviceId`:

```bash
curl -sS http://localhost:8082/registry/services/cooling-service
```

### 2. Register a Service

Send a service definition to `POST /registry/services`. Save the following as `register-service.json` to register an **external REST service** (the REST plugin must be installed):

```json
{
  "serviceId": "external-example",
  "displayName": "External REST Example",
  "description": "Call an HTTP endpoint through the REST runtime",
  "executionCategory": "EXTERNAL",
  "technologyAdapter": "REST",
  "defaultArgs": {
    "url": "https://httpbin.org/post",
    "httpMethod": "POST",
    "payload": "{\"source\":\"service-manager\"}"
  }
}
```

**From Bash, Git Bash, or Linux/macOS Terminal:**

```bash
curl -i -X POST http://localhost:8082/registry/services \
  -H 'Content-Type: application/json' \
  --data-binary @register-service.json
```

**From Windows PowerShell:**

```powershell
curl.exe -i -X POST http://localhost:8082/registry/services `
  -H "Content-Type: application/json" `
  --data-binary "@register-service.json"
```

The registration request uses these fields:

| Field | Description |
|---|---|
| `serviceId` | Unique ID used when triggering the service |
| `displayName` | Human-readable name |
| `description` | Optional description |
| `executionCategory` | `SCRIPT`, `CONTAINER`, or `EXTERNAL` |
| `technologyAdapter` | `LUA` / `BASH` for scripts, `DOCKER` for containers, or `REST` for external HTTP |
| `defaultArgs` | Optional key-value object; values are strings passed on every execution |
| `fileReference` | Engine-storage reference for an uploaded Lua/Bash script or Dockerfile (when applicable) |

**File-based services:** To upload a script or Dockerfile, the Engine storage API must be running on port 8080. Upload the file first, then use the returned `fileReference` when registering the service:

```bash
curl -i -X POST http://localhost:8080/engine/storage/upload \
  -F 'file=@path/to/script.lua'
```

For a pre-built Docker image, no upload is needed: register `executionCategory: "CONTAINER"`, `technologyAdapter: "DOCKER"`, and set `defaultArgs.image` to an image already available to Docker.

Runtime-specific arguments are documented in [REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md).

### 3. Trigger a Registered Service

The request body for `POST /services/trigger` has two fields:

| Parameter | Required | Description |
|---|---|---|
| `serviceIds` | Yes | Non-empty array of registered service IDs; multiple services may be triggered in one request |
| `dynamicArgs` | No | String key-value object supplied at execution time; matching keys override `defaultArgs` |

**From Bash:**
```bash
curl -sS -X POST http://localhost:8082/services/trigger \
  -H 'Content-Type: application/json' \
  -d '{"serviceIds":["cooling-service"],"dynamicArgs":{"temperature":"85","max_temp":"75"}}'
```

**From PowerShell** (save as `trigger-service.json` to avoid quoting issues):
```json
{
  "serviceIds": ["cooling-service"],
  "dynamicArgs": {
    "temperature": "85",
    "max_temp": "75"
  }
}
```
```powershell
curl.exe -sS -X POST http://localhost:8082/services/trigger `
  -H "Content-Type: application/json" `
  --data-binary "@trigger-service.json"
```

**Execution is asynchronous.** The response contains an `executionId` for each accepted run. This is different from the service's reusable `serviceId`; use the `executionId` to inspect or stop that specific run.

### 4. Check Progress, Retrieve Output, or Stop a Run

Replace `<executionId>` with the value returned by the trigger call:

```bash
# Current status
curl -sS 'http://localhost:8082/services/status?executionId=<executionId>'

# Completed result (including output or error)
curl -sS 'http://localhost:8082/services/result?executionId=<executionId>'

# Currently running executions
curl -sS http://localhost:8082/services/running

# Completed execution history since the manager started
curl -sS http://localhost:8082/services/history

# Request cancellation of a specific execution
curl -i -X POST 'http://localhost:8082/services/stop?executionId=<executionId>'
```

Completed results and execution history are maintained in the Service Manager's in-memory state and are **not guaranteed to survive a restart**. Stopping a run is a cancellation request; it may not immediately terminate every runtime action.

### 5. Delete a Service Registration

```bash
curl -i -X DELETE http://localhost:8082/registry/services/external-example
```

This deletes the **registration**; it is not the same as stopping an ongoing execution. Review any rules or nested-service calls that reference the service before deleting it.

For additional registration examples, Docker parameters (`image`, `startupTimeout`, `CMD`, `CMD_ARGS`, `ports`, `volumes`), Lua/Bash argument injection, external REST parameters, and nested-service calls, see [REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md).

---

## Extension Points

A project or JAR counts as a runtime plugin when it has a class that implements `IScriptInterpreter`, `IContainerOrchestrator` or `IExternalServiceClient` and returns a `TechnologyAdapter`. That can be a built-in one (`return TechnologyAdapter.LUA;`) or a new one defined by the plugin:

```java
public static final TechnologyAdapter RUBY = TechnologyAdapter.define("RUBY", ExecutionCategory.SCRIPT);
```

`TechnologyAdapter` is an open enum, so the registry stores and routes adapters it does not know at compile time. `ExecutionCategory` stays a plain enum.

For a developer guide on implementing a new runtime type or adapter, see [ADDING_A_RUNTIME_TYPE_OR_ADAPTER.md](ADDING_A_RUNTIME_TYPE_OR_ADAPTER.md).

---

## How to Run the Tests

From the root of the **services** project folder:

- **`mvn test`** — The normal run. Needs no Docker, internet, or running Engine; application tests send their Engine calls to a local stub. Tests tagged `plugins` exercise runtime plugins from their internal projects (for example Lua in `runtime-plugins/lua`); without the relevant plugin on the classpath, they are skipped with a reason. Bash tests also require Bash (on Windows, WSL with a Linux distribution).
- **`mvn test -Pintegration`** — Also runs tests tagged `docker` (requires a Docker daemon) and `integration` (may require internet for httpbin.org, fixed port 8082, or extended load-test execution). Tests whose dependencies are unavailable are skipped rather than failed.

Options for the integration run:

| Property | Default | Used for |
|---|---|---|
| `-Dtest.httpbin.url` | `https://httpbin.org` | External REST tests; needs the REST plugin (`runtime-plugins/rest`) |
| `-Dtest.engine.url` | Not set (local stub Engine) | A real Engine, e.g. `http://localhost:8080`, needed by the Dockerfile-plus-Engine-storage test |

Docker is found through `DOCKER_HOST` or the platform default (such as Docker Desktop). Docker tests also need the Docker runtime plugin (`runtime-plugins/docker`) on the classpath.

---

## Plugins Folder and Imported Services

`ServiceManagerApplication` starts through `PluginLauncher`. Every JAR below `plugins/` (one sub-folder per runtime, e.g. `plugins/runtime-python/`) joins the classpath, so `mvn spring-boot:run` in `service-manager-app` finds the runtimes (Lua, Python, Docker, REST, …) by component scan without any pom change. The LABraDT UI writes this folder (System → Project → Apply to Projects, and at every Runtime start). Use `LABRADT_PLUGINS_DIR` for another folder.

`config/services.json` hands service definitions over, e.g. from a project imported in the UI. `ServiceImportFile` registers them at startup and then renames the file to `services.imported.json`. The file is either a JSON array of definitions (upserted) or `{"replace": true, "services": [...]}` (replaces the registry).

---

## Where Is X?

| I am looking for | It is here |
|---|---|
| Runtime provider interface (southbound contract) | `runtime-core/.../IRuntimeProvider.java` |
| Script interpreter interface | `runtime-core/.../IScriptInterpreter.java` |
| Container orchestrator interface | `runtime-core/.../IContainerOrchestrator.java` |
| External service client interface | `runtime-core/.../IExternalServiceClient.java` |
| Service registry | `service-manager-app/.../registry/ServiceRegistry.java` |
| Trigger and execution lifecycle | `service-manager-app/.../execution/ServiceExecutionController.java` |
| Plugin discovery and classpath loading | `service-manager-app/.../PluginLauncher.java` |
| Import file handling | `service-manager-app/.../ServiceImportFile.java` |
| Service definition model | `runtime-core/.../model/ServiceDefinition.java` |

For a full architecture walkthrough, see [CODE_GUIDE.md](CODE_GUIDE.md).

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `serviceId` not found on trigger | Service not registered | Check `GET /registry/services`; verify the `serviceId` spelling |
| Execution stays in RUNNING indefinitely | Plugin crash or Docker daemon unavailable | Check the Service Manager terminal log; verify Docker is running for CONTAINER services |
| Plugin not loaded | JAR not in `plugins/` subfolder, or classpath not updated | Restart the Service Manager after adding a plugin; check `PluginLauncher` log output |
| Lua services not seeded | Lua plugin not installed | Add `runtime-lua` as a dependency and rebuild |
| Docker build fails | Docker daemon not running or Dockerfile error | Verify `docker info` succeeds; check the build output in the Service Manager log |
| Callback not received by Engine | Wrong `callbackUrl` in `dynamicArgs` | Ensure the Engine is running on port 8080 and the URL in `dynamicArgs.callbackUrl` is reachable from the runtime |
