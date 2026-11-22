# Registering and Running Services from the Command Line

This guide explains how to **register, trigger, inspect, and stop services directly from a terminal**. The examples use `curl` to call the running Service Manager's HTTP API. `curl` is a command-line program, **not a Python script**, and can be used from Bash, Git Bash, macOS Terminal, Linux, or Windows PowerShell (use `curl.exe` on Windows).

> **Implementation basis:** Execution and argument-merging behavior are checked against the supplied `ServiceManager.java`. The REST controller, service-registry DTOs, Spring Boot application launcher, and individual runtime-plugin implementations were not supplied, so this guide does not invent Java startup flags, unverified response fields, or additional REST endpoints. Plugin-specific argument details are retained from the existing service guide.

## 1. Prerequisites and endpoints

- The **Service Manager application must already be running** with the relevant runtime plugins installed. Default API base: `http://localhost:8082`.
- For uploading script files or Dockerfiles, the **Engine storage API** must also be running. Default base: `http://localhost:8080`.
- Docker services need the Docker runtime plugin and a functioning Docker daemon. Lua, Bash, and external REST services need their respective runtime plugins.

`ServiceManager.java` is a Spring `@Service`, **not a `main()` program** and not a command-line parser. Launching the *server application* itself is a separate step using its packaged Spring Boot application, according to its deployment README. The examples here start **registered service executions** once that server is running.

Set the API base address as appropriate for your deployment. In the commands below, replace `localhost:8082` if the manager is remote or listens on another port. If the application has authentication in your deployment, add your required headers; authentication configuration is not established by the provided source.

### Quick start: execute an already registered service

In **Bash / Git Bash**:

```bash
curl -sS -X POST 'http://localhost:8082/services/trigger' \
  -H 'Content-Type: application/json' \
  -d '{"serviceIds":["cooling-service"],"dynamicArgs":{"temperature":"85","max_temp":"75"}}'
```

In **Windows PowerShell**, use the actual curl executable:

```powershell
curl.exe -sS -X POST "http://localhost:8082/services/trigger" `
  -H "Content-Type: application/json" `
  --data-raw '{"serviceIds":["cooling-service"],"dynamicArgs":{"temperature":"85","max_temp":"75"}}'
```

The response contains an `executionId` for each accepted execution. **Save that ID** for the status and result calls in section 5. One `serviceId` identifies the reusable registration; a new `executionId` identifies each individual run.

---

## 2. API command reference

| Task | HTTP method and path | Inputs |
|---|---|---|
| Register service | `POST /registry/services` | JSON service definition (section 3) |
| List registered services | `GET /registry/services` | none |
| Inspect one registration | `GET /registry/services/{serviceId}` | registered service ID in URL |
| Delete registration | `DELETE /registry/services/{serviceId}` | registered service ID in URL |
| Trigger one or more services | `POST /services/trigger` | JSON `serviceIds` and optional `dynamicArgs` |
| Check execution status | `GET /services/status?executionId=...` | execution ID, not service ID |
| Read a completed result | `GET /services/result?executionId=...` | execution ID |
| List running executions | `GET /services/running` | none |
| List completed history | `GET /services/history` | none |
| Stop an execution | `POST /services/stop?executionId=...` | execution ID |
| Upload a local file to Engine storage | `POST http://localhost:8080/engine/storage/upload` | multipart form part named `file`, optional part `key` |

Examples for listing and deleting:

```bash
curl -sS http://localhost:8082/registry/services
curl -sS http://localhost:8082/registry/services/my-container
curl -i -X DELETE http://localhost:8082/registry/services/my-container
```

For the same commands in PowerShell, replace `curl` with `curl.exe`.

**Be careful with deletion.** Unlike the Python wrapper, these direct API calls do not first inspect Engine AASX rules for references, nor automatically delete the associated uploaded Engine-storage file. Check dependencies and storage references yourself before deleting.

---

## 3. Registering a service

Registration sends one JSON object to `POST /registry/services`. Its field names are the **actual API names** (`executionCategory` and `technologyAdapter`), not the friendly `type` and `runtime` labels from the old interactive menu.

| JSON field | Purpose | Examples |
|---|---|---|
| `serviceId` | Unique ID used later in `serviceIds` when triggering | `my-script`, `my-container` |
| `displayName` | Human-readable name | `Machine temperature check` |
| `description` | Optional explanation | `Validates a sensor threshold` |
| `executionCategory` | Execution category | `SCRIPT`, `CONTAINER`, `EXTERNAL` |
| `technologyAdapter` | Runtime implementation used for the category | `LUA`, `BASH`, `DOCKER`, `REST` |
| `defaultArgs` | Optional object mapping argument names to **string** values; merged into each execution | `{"startupTimeout":"30"}` |
| `fileReference` | Reference returned by Engine storage after uploading a script or Dockerfile; omit if not applicable | storage-provided value |

The registration examples below retain the accepted category/runtime combinations documented in the original service guide:

| `executionCategory` | `technologyAdapter` | Description |
|---|---|---|
| `SCRIPT` | `LUA` | Execute a Lua script through the Lua plugin |
| `SCRIPT` | `BASH` | Execute a shell script through the Bash plugin |
| `CONTAINER` | `DOCKER` | Run a pre-built Docker image or uploaded Dockerfile |
| `EXTERNAL` | `REST` | Invoke an external HTTP endpoint |

### Easiest cross-platform method: a JSON request file

Create `register-service.json` in any text editor. Example for a **pre-built Docker image**:

```json
{
  "serviceId": "my-container",
  "displayName": "Example container",
  "description": "Runs an existing local image",
  "executionCategory": "CONTAINER",
  "technologyAdapter": "DOCKER",
  "defaultArgs": {
    "image": "alpine:3.21",
    "startupTimeout": "30"
  }
}
```

Then run from **Bash**:

```bash
curl -i -X POST 'http://localhost:8082/registry/services' \
  -H 'Content-Type: application/json' \
  --data-binary @register-service.json
```

Or **PowerShell**:

```powershell
curl.exe -i -X POST "http://localhost:8082/registry/services" `
  -H "Content-Type: application/json" `
  --data-binary "@register-service.json"
```

Using a JSON file avoids nested quoting problems and works without shipping any helper scripts. To verify:

```bash
curl -sS http://localhost:8082/registry/services/my-container
```

### Register an external REST service

Save this as `register-external.json`:

```json
{
  "serviceId": "external-httpbin",
  "displayName": "External REST example",
  "description": "Sends a sample payload to an external endpoint",
  "executionCategory": "EXTERNAL",
  "technologyAdapter": "REST",
  "defaultArgs": {
    "url": "https://httpbin.org/post",
    "httpMethod": "POST",
    "payload": "{\"test\":\"value\"}"
  }
}
```

Register with:

```bash
curl -i -X POST http://localhost:8082/registry/services \
  -H 'Content-Type: application/json' \
  --data-binary @register-external.json
```

The REST plugin's documented argument keys are:

| `defaultArgs` key | Required | Description | Documented default |
|---|---|---|---|
| `url` | yes | Target HTTP URL | none |
| `httpMethod` | no | `GET`, `POST`, `PUT`, or `DELETE` | `POST` |
| `payload` | no | Request body string | `{}` |
| `queryParams` | no | JSON object encoded as a string, e.g. `"{\"k\":\"v\"}"` | none |

### Register a Lua or Bash script

A script file is first uploaded to the **Engine** storage API. Run from the directory containing the file:

```bash
curl -sS -X POST 'http://localhost:8080/engine/storage/upload' \
  -F 'file=@test-files/lua-hello.lua'
```

The Engine returns JSON containing a `fileReference`. Insert its **actual returned value** in `register-lua.json`:

```json
{
  "serviceId": "my-script",
  "displayName": "Lua hello",
  "description": "Example Lua service",
  "executionCategory": "SCRIPT",
  "technologyAdapter": "LUA",
  "fileReference": "REPLACE_WITH_RETURNED_REFERENCE",
  "defaultArgs": {
    "machine_id": "machine-01",
    "warn_threshold": "75"
  }
}
```

Register the definition using the same `POST /registry/services` command shown above, changing the filename to `register-lua.json`. For Bash, use `technologyAdapter: "BASH"` and upload a compatible script instead. **The example only establishes how the CLI passes arguments; exact Bash variable binding depends on the Bash runtime plugin.**

The original Lua plugin documentation describes arguments as injected global variables, with numeric strings converted to Lua numbers:

```lua
local machine_id = machine_id or "fallback"
local threshold  = warn_threshold or 75.0
```

### Register a Dockerfile instead of an image

Upload the Dockerfile to Engine storage:

```bash
curl -sS -X POST 'http://localhost:8080/engine/storage/upload' \
  -F 'file=@test-files/Dockerfile.standalone'
```

Create a registration with `executionCategory: "CONTAINER"`, `technologyAdapter: "DOCKER"`, the returned `fileReference`, and appropriate `defaultArgs`. The Docker runtime plugin must be present and Docker must be running.

For an **already-built image**, omit `fileReference` and supply `image` in `defaultArgs`. The original service guide specifies that the Docker runtime does **not** automatically build an unuploaded local Dockerfile; if you choose the pre-built-image path, build/tag it first:

```bash
docker build -f test-files/Dockerfile.standalone -t my-image:latest .
```

#### Docker runtime arguments

These runtime options come from the original service documentation (they are not implemented directly in `ServiceManager.java`):

| `defaultArgs` key | Meaning | Documented default |
|---|---|---|
| `image` | Locally available image tag for a pre-built-image service | none |
| `startupTimeout` | Container startup wait, seconds | `10` |
| `stopTimeout` | Graceful stop wait, seconds | `30` |
| `CMD` | Shell command override, run with `sh -c` | image default |
| `CMD_ARGS` | JSON array command override, passed without shell wrapping; takes precedence over `CMD` | image default |
| `ports` | Comma-separated host:container mappings, e.g. `8080:8080` | none |
| `volumes` | Comma-separated volume mappings, e.g. `/data:/data` | none |

Other service arguments are exposed to the container as environment variables according to the original Docker runtime guide. It also documents automatic `SERVICE_TRIGGER_URL` (asynchronous) and `SERVICE_TRIGGER_NESTED_URL` (synchronous) injections.

---

## 4. Starting a service: trigger parameters

After registration, services run by sending `POST /services/trigger`. You **do not** pass the service's category, runtime, or file reference again. `ServiceManager.resolveToRequest()` resolves the registration and merges run-specific args with stored defaults before dispatching.

| Request field | Required | Effect |
|---|---|---|
| `serviceIds` | yes | **Nonempty array** of registered service IDs. One entry starts one run. Multiple IDs start separately dispatched runs. Repeated IDs can start concurrent instances of the same registration. |
| `dynamicArgs` | no | Object of string key/value pairs for this invocation; keys override matching `defaultArgs`, while unrelated defaults remain in force. |

Save this as `trigger-service.json`:

```json
{
  "serviceIds": ["my-script"],
  "dynamicArgs": {
    "machine_id": "machine-07",
    "warn_threshold": "80"
  }
}
```

Run from Bash:

```bash
curl -i -X POST 'http://localhost:8082/services/trigger' \
  -H 'Content-Type: application/json' \
  --data-binary @trigger-service.json
```

Or PowerShell:

```powershell
curl.exe -i -X POST "http://localhost:8082/services/trigger" `
  -H "Content-Type: application/json" `
  --data-binary "@trigger-service.json"
```

An acceptance response containing a `triggered` list with **one `executionId` per dispatch**, such as:

```json
{
  "triggered": [
    {"executionId": "<generated-execution-id>", "serviceId": "my-script", "status": "RUNNING"}
  ]
}
```

This is an **illustrative response excerpt**, not an exact promise about every response envelope. The supplied `ServiceManager` constructs an `ExecutionAcceptedDTO` per run with the generated ID, service ID, and running status. Work continues asynchronously after the trigger response; an acceptance is **not** a completed result.

### `defaultArgs` vs `dynamicArgs`

`ServiceManager.resolveToRequest()` implements the merge as: start with `defaultArgs` from the registration, then overwrite those keys with the request's `dynamicArgs`.

```text
Registered defaultArgs:  {"machine_id": "machine-01", "warn_threshold": "75"}
Trigger dynamicArgs:     {"machine_id": "machine-07"}
Effective args:         {"machine_id": "machine-07", "warn_threshold": "75"}
```

The values in the supplied API types are strings (`Map<String,String>`). The selected runtime interprets their contents.

---

## 5. Monitoring, results, and stopping

Replace `<execution-id>` below with the ID returned by the trigger response. These examples are all direct terminal calls, not Python operations:

```bash
# Check one execution: ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, NOT_FOUND, etc.
curl -sS 'http://localhost:8082/services/status?executionId=<execution-id>'

# Retrieve the completed ExecutionResponseDTO (if available)
curl -sS 'http://localhost:8082/services/result?executionId=<execution-id>'

# View in-flight executions
curl -sS http://localhost:8082/services/running

# View completed execution results retained by the running Service Manager
curl -sS http://localhost:8082/services/history

# Ask the runtime to stop/cancel one execution
curl -i -X POST 'http://localhost:8082/services/stop?executionId=<execution-id>'
```

PowerShell: use `curl.exe` instead of `curl`. Do not include angle brackets around a real ID; `<execution-id>` is only a placeholder. If an ID or key contains reserved URL characters, URL-encode it.

**Important execution semantics from `ServiceManager.java`:**

- `serviceId` can run multiple times; **`executionId` is generated anew** on every dispatch and keys the tracking and result caches.
- `ExecuteServiceAsync()` starts work without blocking until completion. An `ACCEPTED` or `RUNNING` response is not final.
- On normal completion, a response is cached with `COMPLETED` or `FAILED`; the response can contain `outputData`, `errorMessage`, and execution timing, as available from the runtime.
- Async timeout defaults to **30 minutes**, configurable by `service.manager.async-timeout-minutes`; nested synchronous timeout defaults to **2 minutes**, configurable by `service.manager.sync-timeout-minutes`. These are **server-side Spring properties**, not trigger-request arguments.
- `GetHistory()` reads the Service Manager's **in-memory completed result cache**. It is not evidence of durable storage across restarts.
- Stopping calls `future.cancel(true)` and each runtime's `Stop(executionId)`; whether a running task can be physically terminated depends on the runtime implementation. Do not assume a successful HTTP request guarantees an external process was stopped if the runtime does not support it.

---

## 6. Calling other services from a service (nested execution)

The nested execution path executes exactly **one** registered target service and waits for its result. Its default server-side timeout is controlled by `service.manager.sync-timeout-minutes` (2 minutes in the supplied Java class).

### Lua

The existing Lua plugin guide describes this bridge:

```lua
local result = triggerNestedService("other-service-id")
local with_args = triggerNestedService("other-service-id", {key = "value"})
```

### Docker / shell inside a container

The existing Docker runtime guide describes `SERVICE_TRIGGER_NESTED_URL`, automatically injected into containers. A nested call is synchronous and returns result JSON:

```sh
RESPONSE=$(curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -d '{"serviceIds":["other-service-id"],"dynamicArgs":{"key":"value"}}' \
  "$SERVICE_TRIGGER_NESTED_URL")
```

Use `SERVICE_TRIGGER_URL` instead for asynchronous fire-and-forget execution. Ensure your image contains `curl` (and `jq` if you need to parse the result). The original guide states that nested service calls are not supported from an `EXTERNAL` REST service.

---

## 7. Example files and rule-action services

The repository's [`test-files/`](test-files/) folder contains the same example artifacts as before:

| File | Purpose |
|---|---|
| `lua-hello.lua` | Simple Lua script with injected args |
| `lua-calls-lua.lua` | Lua-to-Lua nested execution |
| `lua-calls-docker-file.lua` | Lua invoking a Dockerfile-backed service |
| `lua-calls-docker-image.lua` | Lua invoking a pre-built image service |
| `lua-calls-external.lua` | Lua invoking an external REST service |
| `sensor_check.lua` | Compare `temperature` to `max_temp` |
| `Dockerfile.standalone` | Standalone container |
| `Dockerfile.calls-lua` | Container invoking Lua |
| `Dockerfile.calls-external` | Container invoking REST |
| `Dockerfile.cooling-service` | Temperature correction example |
| `Dockerfile.energy-monitoring-service` | Axis-load correction example |
| `Dockerfile.geometry-correction-service` | Axis-position correction example |

The earlier project documentation describes these three initially registered services (when Service Manager seeding is enabled and Lua runtime is available):

| Service ID | Engine rule target | Expected example output |
|---|---|---|
| `cooling-service` | `Temperature` | `70.0` |
| `geometry-correction-service` | `XAxisPos` | `0,0,0` |
| `energy-monitoring-service` | `XAxisLoad` | `25.0` |

They are illustrative service definitions, not Java program command-line flags. Confirm that they exist with `GET /registry/services` before attempting to trigger them. The AASX rule system may invoke them automatically, or you can invoke them manually via `POST /services/trigger`.

---

## 8. Troubleshooting

| Problem | What to check |
|---|---|
| Connection refused to `localhost:8082` | Start the Service Manager application and verify the actual listening address and port. |
| `curl` behaves oddly in PowerShell | Use `curl.exe` instead of the PowerShell `curl` alias. |
| Invalid JSON / HTTP 400 | Save the payload to a `.json` file and use `--data-binary @filename.json`; use the real API property names. |
| Registration succeeded, trigger fails | Check `GET /registry/services/{serviceId}` and ensure the matching runtime plugin is installed. |
| Script/Dockerfile cannot be found | A **local path is not a `fileReference`**. Upload the file via the Engine API, then store the returned `fileReference` in the registration. |
| Docker image unavailable | Build or pull the required image on the Docker daemon used by the runtime. |
| Status/result gives no output | Check the **execution ID**, not the service ID. A result may not yet exist while the execution runs. |
| `Stop` does not end external work | Cancellation depends on the runtime plugin's actual `Stop` implementation. |
| Deleted registration breaks Engine rules | Review AASX rule `action` references before deletion; direct curl operations perform no cross-project safety check. |

The commands in this document replace the Python CLI workflow; **they do not require shipping Python**. They assume that the documented HTTP endpoints exist in the deployed application. The supplied Java class establishes execution behavior but is not itself the REST controller, and the endpoints have not been exercised against a live server.
