# teaz_pro_pulse
Teaz Pulse, at `https://pulse.teaz.pro`: status and control of every Docker
container in the Teaz ecosystem (apps, Traefik, Keycloak, Postgres).

- Shows each container's status, health check, uptime and restarts, grouped by app.
- Starts, stops and restarts containers (stop/restart ask for a second click).
- **Performance** (second view, switch at the top): the server's CPU, memory
  and disk, the Teaz containers' share of it, the last hour as charts, and
  each Teaz container's CPU, memory, network and disk I/O.
- Serves `GET /api/health`, the health of every app, and `GET /api/load`, the
  server's CPU and memory charts, for other apps (Portal's tiles).

Monorepo: `frontend/` (Vue 3 + TypeScript) and `backend/` (Spring Boot, `pro.teaz:pulse`).

Stack, conventions, style guide and color tokens shared by every Teaz app
live in **`teaz_pro`**: start with `docs/TECH_STACK.md` there. This
README only covers what is specific to this app.

## Shared UI

`TopBar`, `useTheme()` and the theme tokens come from **`teaz_pro_ui`**
(pinned in `frontend/package.json`), see `teaz_pro/docs/UI_LIBRARY.md`.

## Which containers

Pulse only sees containers that opt in with labels:

| Label | Value | Effect |
| ----- | ----- | ------ |
| `pro.teaz.pulse.app` | App display name, short and without "Teaz": `Portal`, `Study`, `Keycloak` | Container is managed by Pulse and grouped under that name. |
| `pro.teaz.pulse.protected` | `true` | Restart only, no stop. |

Protect everything Pulse itself depends on: stopping Traefik, Keycloak,
Keycloak's Postgres or Pulse would leave no way back in through Pulse.

Every app's `compose.yml` labels its services (the app template does this
already), e.g.:

```yaml
    labels:
      - pro.teaz.pulse.app=Portal
```

For the shared services, add to their compose files on the VPS:

```yaml
  traefik:
    labels:
      - pro.teaz.pulse.app=Traefik
      - pro.teaz.pulse.protected=true
  keycloak:
    labels:
      - pro.teaz.pulse.app=Keycloak
      - pro.teaz.pulse.protected=true
  postgres:            # Keycloak's database
    labels:
      - pro.teaz.pulse.app=Keycloak
      - pro.teaz.pulse.protected=true
```

Labels only apply to newly created containers: run `docker compose up -d`
in that project afterwards.

### Status

| Container | Status |
| --------- | ------ |
| running, health check `healthy` or none | `good` (Operational) |
| running with health check `starting`, `restarting`, `paused` | `warning` (Degraded) |
| running with health check `unhealthy`, or not running at all | `critical` (Down) |

An app's status is the worst of its containers. "Down" includes containers
stopped on purpose.

## Performance

The view switch at the top of the page (`?view=performance` in the URL)
changes between the container list and this view. Every 10 seconds
(`pulse.metrics.interval`), whether anyone is looking or not, the backend
(`metrics/MetricsSampler.java`) measures:

| What | From |
| ---- | ---- |
| The server: CPU (all cores together), load average, RAM, disk | `/proc/stat`, `/proc/loadavg`, `/proc/meminfo` inside Pulse's container. Docker doesn't isolate those files, so they show the whole server; no mount or extra privilege needed. Disk is the filesystem holding Docker's data (usually the root disk). |
| Each running Teaz container: CPU, memory, network, disk I/O | Docker's stats (`GET /containers/<id>/stats`, one reading per round), only for containers with `pro.teaz.pulse.app`. |
| Everything else | Not looked at one by one: "other" is the server's value minus the Teaz containers. |

- **CPU** is always a share of the whole server (all cores = 100%), so the
  Teaz containers and "other" add up to the server's value. CPU and the I/O
  rates are differences between two rounds, so they're empty for the first
  10 seconds after Pulse (or a container) starts.
- **Memory** of a container is what `docker stats` shows: its usage without
  the page cache the kernel can drop. The server's is total minus available.
- **History:** the last hour (`pulse.metrics.history`), in memory only.
  Gone after Pulse restarts; nothing is written anywhere.
- Locally, `MockDockerClient` and `MockHostProbe` make up a 4-core, 8 GB
  server with moving values.

`GET /api/metrics` (role `pulse-admin`, not shared cross-origin) returns the
last measurement and the history; `503` until the first one after startup.
Fields: see `metrics/MetricsView.java`.

## Docker access

The backend never mounts the Docker socket. It talks to
[`wollomatic/socket-proxy`](https://github.com/wollomatic/socket-proxy)
(service `docker-proxy` in `compose.yml`) on an internal network, and the proxy
only lets through:

- `GET /v1.x/containers/json`, `GET /v1.x/containers/<id>/json` and
  `GET /v1.x/containers/<id>/stats` (resource usage, read only)
- `POST /v1.x/containers/<id>/start|stop|restart`

Everything else (creating containers, exec, images, volumes, ...) is
refused, so a compromised backend can't take over the host. On top of
that, the backend only acts on containers carrying `pro.teaz.pulse.app`.

The Docker Engine API version is set by `pulse.docker.api-version`
(default `v1.44`, Docker 25+).

## Auth

- Frontend `frontend/src/auth/`: Keycloak (`.env.production`: `auth.teaz.pro`,
  realm `teazpro`, client `pulse`) in builds, mock user in `npm run dev`.
- Backend `backend/src/main/java/pro/teaz/pulse/security/`, with the `prod` profile:
  - `/api/containers/**` (list, start, stop, restart) and `/api/metrics`
    (Performance) need the Keycloak realm role **`pulse-admin`**.
  - `/api/health` and `/api/load` are open to any logged-in user, also
    cross-origin from the origins in `pulse.cors.allowed-origins`
    (`application-prod.properties`, currently `https://portal.teaz.pro`).
  - Without the `prod` profile everything is permitted.

Keycloak setup:

1. Client `pulse`, as in the Auth section of `teaz_pro/docs/TECH_STACK.md`.
2. **Realm roles → Create role** `pulse-admin`, then assign it to the admins
   (**Users → user → Role mapping → Assign role**). Users without it see a
   "needs the pulse-admin role" message in Pulse.

## API for other apps

`GET https://pulse.teaz.pro/api/health`, with the caller's Keycloak access
token (any Teaz app's token works, same realm):

```json
{
  "status": "warning",
  "checkedAt": "2026-10-17T11:31:50.728Z",
  "apps": [
    { "name": "Keycloak", "status": "good", "running": 2, "total": 2 },
    { "name": "Portal", "status": "warning", "running": 2, "total": 2 }
  ]
}
```

`status` is `good` / `warning` / `critical`, the same values as `StatusRow`
in `teaz_pro_ui`, so a Portal tile maps `apps` directly:
`apps.map(a => ({ label: a.name, state: a.status }))` into `StatusListTile`.

```ts
const token = await useAuth().getToken()
const res = await fetch('https://pulse.teaz.pro/api/health', {
  headers: { Authorization: `Bearer ${token}` },
})
```

A new app that should read it: add its origin to `pulse.cors.allowed-origins`.
If Docker is unreachable, Pulse answers `503`.

### Load

`GET https://pulse.teaz.pro/api/load`, same token and origins as `/api/health`:
the data behind the Performance view's CPU and memory charts, without any
container details (`metrics/LoadSummary.java`).

```json
{
  "sampledAt": "2026-10-23T12:00:00Z",
  "intervalSeconds": 10,
  "historySeconds": 3600,
  "cores": 4,
  "memoryTotalBytes": 8589934592,
  "host": { "cpuPercent": 34.2, "memoryBytes": 3650722201 },
  "teaz": { "cpuPercent": 12.8, "memoryBytes": 1288490188 },
  "history": [
    { "at": "2026-10-23T11:00:10Z", "hostCpuPercent": 31.0, "teazCpuPercent": 11.5,
      "hostMemoryBytes": 3600000000, "teazMemoryBytes": 1270000000 }
  ]
}
```

- `host` is the whole server, `teaz` the Teaz containers' part of it.
- CPU is a share of all cores together (0–100); chart it against `100`.
  Memory in bytes; chart it against `memoryTotalBytes`.
- `history`: one point every `intervalSeconds`, oldest first, at most
  `historySeconds` back (empty right after Pulse started, then filling up).
  Use `historySeconds` as the x axis' width so a short history doesn't stretch.
- `cpuPercent` is `null` for the first 10 seconds after Pulse starts; before
  the first measurement at all, Pulse answers `503`.

## Running locally

```sh
cd backend && ./mvnw spring-boot:run
cd frontend && npm run dev
```

Or use the IntelliJ run configs in `.idea/runConfigurations/`. Locally
there's no Docker involved: without `pulse.docker.url`, the backend uses
`MockDockerClient`, a fixed set of fake containers (Traefik, Keycloak,
Portal, ...) whose state changes when you start/stop/restart them. Auth is
mocked on both sides as well. The Vite dev server forwards `/api/*` to the
backend on `localhost:8080`.

To try a real engine locally, set `pulse.docker.url` (e.g. Docker Desktop's
`http://localhost:2375`, if exposed).

## Deployment

Once on the VPS, before the first start, tell the socket proxy which group
may read the Docker socket (`.env` is gitignored):

```sh
echo "DOCKER_GID=$(getent group docker | cut -d: -f3)" > .env
```

Then, as for every app:

```sh
git pull && docker compose up -d --build
```

`compose.yml` routes `pulse.teaz.pro` to the frontend (nginx) and
`pulse.teaz.pro/api/*` to the backend, via the shared Traefik (network
`proxy`, cert resolver `le`). The backend additionally sits on the
internal network `docker-api` with the socket proxy.
