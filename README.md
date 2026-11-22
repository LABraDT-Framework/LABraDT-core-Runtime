# How to run the Runtime component

From the root of the **services** project folder:

```bash
mvn clean install -DskipTests
cd service-manager-app
mvn spring-boot:run
```

The Service Manager then listens on **http://localhost:8082** by default. Keep this terminal running while making requests from another terminal.

The core contains the runtime hosting interfaces, but execution implementations are supplied as plugins in separate internal projects:

| Execution category | Technology adapter | Internal project | Maven artifact |
|---|---|---|---|
| `SCRIPT` | `BASH` | `runtime-plugins/bash` | `runtime-bash` |
| `SCRIPT` | `LUA` | `runtime-plugins/lua` | `runtime-lua` |
| `CONTAINER` | `DOCKER` | `runtime-plugins/docker` | `runtime-docker` |
| `EXTERNAL` | `REST` | `runtime-plugins/rest` | `runtime-rest` |

Add the required artifacts as dependencies in `service-manager-app/pom.xml` and rebuild. Each plugin's README provides its dependency snippet and any additional requirements. Docker execution requires a running Docker daemon. **Without the Lua plugin, the three default rule-action services are not seeded.**

---

# How to use the Runtime from the command line

Once the Service Manager is running, use its REST endpoints directly with `curl` (on Windows PowerShell, use `curl.exe`). The following examples assume `http://localhost:8082`.

## 1. Check which services are registered

```bash
curl -sS http://localhost:8082/registry/services
```

You can inspect one registration by its `serviceId`:

```bash
curl -sS http://localhost:8082/registry/services/cooling-service
```

## 2. Register a service

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

Register the JSON file from **Bash, Git Bash, or Linux/macOS Terminal**:

```bash
curl -i -X POST http://localhost:8082/registry/services \
  -H 'Content-Type: application/json' \
  --data-binary @register-service.json
```

From **Windows PowerShell**:

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
| `defaultArgs` | Optional key-value object; values are strings and are passed on every execution |
| `fileReference` | Engine-storage reference for an uploaded Lua/Bash script or Dockerfile (when applicable) |

**File-based services:** To upload a script or Dockerfile, the Engine storage API must be running on port 8080. Upload the file first, then use the returned `fileReference` when registering the service:

```bash
curl -i -X POST http://localhost:8080/engine/storage/upload \
  -F 'file=@path/to/script.lua'
```

For a pre-built Docker image, no upload is needed: register `executionCategory: "CONTAINER"`, `technologyAdapter: "DOCKER"`, and set `defaultArgs.image` to an image already available to Docker. Runtime-specific arguments are documented in [REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md).

## 3. Trigger a registered service

The request body for `POST /services/trigger` has two fields:

| Parameter | Required | Description |
|---|---|---|
| `serviceIds` | Yes | Nonempty array of registered service IDs; multiple services may be triggered in one request |
| `dynamicArgs` | No | String key-value object supplied at execution time; matching keys override `defaultArgs` |

Example using an already registered `cooling-service`:

```bash
curl -sS -X POST http://localhost:8082/services/trigger \
  -H 'Content-Type: application/json' \
  -d '{"serviceIds":["cooling-service"],"dynamicArgs":{"temperature":"85","max_temp":"75"}}'
```

For **PowerShell**, avoid quoting differences by saving this as `trigger-service.json`:

```json
{
  "serviceIds": ["cooling-service"],
  "dynamicArgs": {
    "temperature": "85",
    "max_temp": "75"
  }
}
```

Then run:

```powershell
curl.exe -sS -X POST http://localhost:8082/services/trigger `
  -H "Content-Type: application/json" `
  --data-binary "@trigger-service.json"
```

**Execution is asynchronous.** The response contains an `executionId` for each accepted run. This is different from the service's reusable `serviceId`; use the `executionId` to inspect or stop that specific run.

## 4. Check progress, retrieve output, or stop a run

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

## 5. Delete a service registration

```bash
curl -i -X DELETE http://localhost:8082/registry/services/external-example
```

This deletes the **registration**; it is not the same as stopping an ongoing execution. Review any rules or nested-service calls that reference the service before deleting it.

For additional registration examples, Docker parameters (`image`, `startupTimeout`, `CMD`, `CMD_ARGS`, `ports`, `volumes`), Lua/Bash argument injection, external REST parameters, and nested-service calls, see [REGISTERING_A_SERVICE.md](REGISTERING_A_SERVICE.md).

---

# How to run the tests

From the root of the **services** project folder:

- `mvn test`: The normal run. It needs no Docker, internet, or running Engine; application tests send their Engine calls to a local stub. Tests tagged `plugins` exercise runtime plugins from their internal projects (for example Lua in `runtime-plugins/lua`); without the relevant plugin on the classpath, they are skipped with a reason. Bash tests also require Bash (on Windows, WSL with a Linux distribution).
- `mvn test -Pintegration`: Also runs tests tagged `docker` (requires a Docker daemon) and `integration` (may require internet for httpbin.org, fixed port 8082, or extended load-test execution). Tests whose dependencies are unavailable are skipped with a reason rather than failed.

Options for the integration run:

| Property | Default | Used for |
|---|---|---|
| `-Dtest.httpbin.url` | `https://httpbin.org` | External REST tests; needs the REST plugin (`runtime-plugins/rest`) |
| `-Dtest.engine.url` | Not set (local stub Engine) | A real Engine, e.g. `http://localhost:8080`, needed by the Dockerfile-plus-Engine-storage test |

Docker is found through `DOCKER_HOST` or the platform default (such as Docker Desktop). Docker tests also need the Docker runtime plugin (`runtime-plugins/docker`) on the classpath.

---

# Further reading

- [Code Guide](CODE_GUIDE.md) — architecture walkthrough: modules, interfaces, and request flow.
- [Registering a service from the command line](REGISTERING_A_SERVICE.md) — direct HTTP/`curl` service registration, parameters, and nested-service calls (no Python CLI required).
- [Adding a new runtime type or adapter](ADDING_A_RUNTIME_TYPE_OR_ADAPTER.md) — developer guide for extending the service layer with new execution types and technology adapters.

## Plugins folder and imported services

`ServiceManagerApplication` starts through `PluginLauncher`. Every JAR below `plugins/` (one sub-folder per runtime, e.g. `plugins/runtime-python/`) joins the classpath, so `mvn spring-boot:run` in `service-manager-app` finds the runtimes (Lua, Python, Docker, REST, …) by component scan without any pom change. The LABraDT UI writes this folder (System → Project → Apply to Projects, and at every Runtime start). Use `LABRADT_PLUGINS_DIR` for another folder.

`config/services.json` hands service definitions over, e.g. from a project imported in the UI. `ServiceImportFile` registers them at startup and then renames the file to `services.imported.json`. The file is either a JSON array of definitions, which are upserted, or `{"replace": true, "services": [...]}`, which replaces the registry.
