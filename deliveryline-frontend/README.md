# deliveryline-frontend

Vite + React 18 + TypeScript frontend module for **deliveryline**. The module is wired
into the Maven reactor: `mvn -pl deliveryline-frontend clean package` runs the full
`frontend-maven-plugin` pipeline (Node install → `npm ci` → `npm run build` → `target/dist/`)
and the backend module copies the bundle into the Spring Boot executable jar under
`BOOT-INF/classes/static/`.

## Quick start

```bash
npm install
npm run dev          # Vite dev server on http://localhost:5173, /api/* → localhost:8080
```

Override the port (5173 collides with another service):

- **POSIX / Git Bash:** `PORT=5174 npm run dev`
- **PowerShell:**      `$env:PORT=5174; npm run dev`

## Build

```bash
npm run build        # tsc -b && vite build → target/dist/
npm run preview      # serve the production bundle locally
```

## Maven integration

```bash
# From the repo root — runs Node install + npm ci + npm run build via the
# frontend-maven-plugin. First run is slow (~3-6 min) due to cold Node download;
# subsequent runs are ~30 s.
./mvnw -pl deliveryline-frontend clean package

# Skip the frontend build while iterating on backend code locally:
./mvnw -pl deliveryline-backend package -Dfrontend-maven-plugin.skip=true

# Full reactor — produces the executable jar containing the SPA:
./mvnw clean install
```

CI runs `mvn -pl deliveryline-frontend clean package` on both `ubuntu-latest` and
`windows-latest` via the `frontend-build-tests` matrix; Windows failures are
build-blocking, not warnings.

## Dev server port + proxy

`vite.config.ts` reads `PORT` from `process.env` (default `5173`) and proxies
`/api/*` → `http://localhost:8080`. The proxy is wired so the SPA's API calls
land on the local Spring Boot backend without CORS configuration.

`server.host: true` binds the dev server on all interfaces, so WSL2 / Docker
Desktop containers can reach the dev server via host-side `localhost` forwarding.

When `/api/*` hits the dev server with the backend running, a `404` from
`http://localhost:8080/api/health` is the success signal that the proxy
forwarded the request correctly — story 2.1 doesn't ship `/api/health` yet.
If the backend is down or unreachable, Vite will log `ECONNREFUSED`; that is a
target-availability problem, not proof that the proxy itself is wired
correctly.

## Windows-specific notes

- **Long paths (`MAX_PATH = 260`).** Long-path support is validated for the
  story-2.1 dependency tree — no path under `node_modules/` exceeds 260 chars.
  If a future transitive dep does, enable Windows 10+ long-paths support via
  `reg add HKLM\SYSTEM\CurrentControlSet\Control\FileSystem /v LongPathsEnabled /t REG_DWORD /d 1 /f`
  (admin required) and restart the shell.
- **Line endings.** This module ships a `.gitattributes` declaring `* text=auto eol=lf`
  for source files plus explicit LF for lockfiles, config, and snapshot-bearing files.
  Cloning on Windows with `core.autocrlf=true` is safe — `.gitattributes` overrides.
- **Port conflicts.** If `5173` is in use, set `PORT=5174` (see Quick start above).
- **Antivirus interference.** If `npm ci` errors with `EPERM` or `EBUSY` on file
  rename operations, your antivirus is likely scanning `node_modules/`. Add the
  module path to the exclusions list.

## Scripts

| Script           | Purpose                                                  |
|------------------|----------------------------------------------------------|
| `npm run dev`    | Vite dev server with HMR + `/api/*` proxy                |
| `npm run build`  | TypeScript project build + Vite production build         |
| `npm run preview`| Serve the `target/dist/` bundle locally for a smoke test |

Linting (`npm run lint`) ships with story 2.31; the Vitest test runner ships with
story 2.27.

## Pinned versions

- **React 18.3.x** — pinned (NOT React 19) because Epic 2 downstream stories
  assume React 18 hook semantics + TanStack Router/Query compatibility matrices.
- **TypeScript** — strict mode enabled including `noUncheckedIndexedAccess` and
  `exactOptionalPropertyTypes` (`tsconfig.app.json`).
- **Node v20.19.0** — pinned in `deliveryline-frontend/pom.xml`'s
  `frontend-maven-plugin` config (keep in sync with `docs/supported-environments.md`).
