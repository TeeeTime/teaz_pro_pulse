# teaz_pro_pulse
Teaz Pulse, at `https://pulse.teaz.pro`: status and control of every Docker
container in the Teaz ecosystem (apps, Traefik, Keycloak, Postgres).

- Shows each container's status, health check, uptime and restarts, grouped by app.
- Starts, stops and restarts containers (stop/restart ask for a second click).
- Serves `GET /api/health`, the health of every app, for other apps (Portal's status tile).

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

## Docker access

The backend never mounts the Docker socket. It talks to
[`wollomatic/socket-proxy`](https://github.com/wollomatic/socket-proxy)
(service `docker-proxy` in `compose.yml`) on an internal network, and the proxy
only lets through:

- `GET /v1.x/containers/json` and `GET /v1.x/containers/<id>/json`
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
  - `/api/containers/**` (list, start, stop, restart) needs the Keycloak realm
    role **`pulse-admin`**.
  - `/api/health` is open to any logged-in user, also cross-origin from the
    origins in `pulse.cors.allowed-origins` (`application-prod.properties`,
    currently `https://portal.teaz.pro`).
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
