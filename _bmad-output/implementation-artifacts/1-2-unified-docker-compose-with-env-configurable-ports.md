# Story 1.2: Unified Docker Compose with `.env`-Configurable Ports

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **foundation developer**,
I want **a single `docker-compose.yml` at the repo root that starts local PostgreSQL with host-port configuration driven by `.env` and Spring Boot local-profile auto-discovery**,
so that **pilot installers can bring up the required local service stack with one command and the backend no longer fails on startup due to missing datasource wiring**.

## Acceptance Criteria

1. **Given** a single `docker-compose.yml` at the root (no separate `docker-compose.observability.yml`), **When** `docker compose up -d` runs, **Then** every declared service starts; in Epic 1 the file declares only PostgreSQL.
2. **Given** the PostgreSQL service, **Then** it uses PostgreSQL 15+, database `deliveryline`, user `deliveryline`, password read from `.env`, and persists data to a named Docker volume `deliveryline-postgres-data`.
3. **Given** Spring Boot Docker Compose support, **When** the backend app starts with the `local` profile, **Then** it auto-discovers the PostgreSQL service connection details without manual JDBC URL duplication in `application*.yml`.
4. **Given** every host port in the compose file, **Then** each is driven by a documented `.env` variable so collisions are resolved by editing `.env` rather than `docker-compose.yml`. Epic 1 ships with at minimum `POSTGRES_HOST_PORT` defaulting to `5432`.
5. **Given** `.env.example` at the root, **Then** every port variable referenced by `docker-compose.yml` has a default value documented there with a one-line comment explaining the service it belongs to.
6. **Given** profile gating for selective startup, **Then** heavy optional services added later are placed behind compose profiles while Epic 1 PostgreSQL remains in the default profile and starts with plain `docker compose up -d`.
7. **Given** `docker compose down -v`, **When** executed, **Then** all named volumes are removed and a subsequent `up` recreates a clean state.
8. **Given** future runner image declarations from stories 3.3 and 3.4, **Then** they extend this same `docker-compose.yml` rather than creating a separate runner compose file.
9. **Given** consolidated compose maintenance, **Then** ADR `docs/adr/0001-unified-compose.md` documents the single-file compose decision and explicitly supersedes the earlier two-file proposal.
10. **Given** later doctor integration in story 1.16, **Then** this story leaves the compose file and `.env` contract in a shape that doctor can inspect for running services and resolved host ports without further compose-file restructuring.

## Tasks / Subtasks

- [x] **Task 1: Create the unified root compose file** (AC: 1, 2, 4, 6, 7, 8)
  - [x] Create root `docker-compose.yml`; do **not** create `compose.yaml`, `docker-compose.observability.yml`, or any runner-specific compose file.
  - [x] Declare only one Epic 1 service: PostgreSQL.
  - [x] Use a pinned PostgreSQL image tag that satisfies the `15+` requirement; keep it aligned with the repository's Testcontainers pin (`postgres:17` is the current safe target).
  - [x] Set `POSTGRES_DB=deliveryline` and `POSTGRES_USER=deliveryline`; read `POSTGRES_PASSWORD` from `.env` and use that exact variable name consistently in `docker-compose.yml` and `.env.example`.
  - [x] Persist data via named volume `deliveryline-postgres-data`.
  - [x] Drive the published host port from `${POSTGRES_HOST_PORT:-5432}` rather than a hardcoded literal.
  - [x] Keep PostgreSQL in the default profile by omitting a `profiles` attribute.
  - [x] Add a PostgreSQL healthcheck so Spring Boot compose startup can wait for a healthy service instead of racing raw TCP readiness.

- [x] **Task 2: Wire backend local-profile Docker Compose discovery** (AC: 3)
  - [x] Add `deliveryline-backend/src/main/resources/application-local.yml`.
  - [x] Configure the local profile for Docker Compose development-time services; prefer lifecycle settings that preserve the operator's manually started database (`start-only`) over app-managed teardown.
  - [x] Do **not** hardcode a JDBC URL, username, password, or host/port into `application.yml` or `application-local.yml`; Spring Boot service connections must remain the source of truth.
  - [x] Only add `spring.docker.compose.file` if verification proves root-file discovery fails from the intended launch command; do not guess at a relative path that breaks another launch mode.

- [x] **Task 3: Finalize the `.env` contract for pilot setup** (AC: 4, 5)
  - [x] Update `.env.example` to document `POSTGRES_PASSWORD` explicitly as the PostgreSQL password variable used by compose.
  - [x] Preserve `POSTGRES_HOST_PORT=5432` as the documented default and keep its comment clear for pilot installers.
  - [x] Do not add real secrets; only local-safe defaults or placeholders are allowed.
  - [x] Keep the reserved observability port block intact for story 3.7.

- [x] **Task 4: Document the ADR for unified compose** (AC: 1, 6, 8, 9)
  - [x] Create `docs/adr/0001-unified-compose.md`.
  - [x] Record the decision to use one `docker-compose.yml` with profile-gated optional services instead of multiple compose files.
  - [x] Explicitly note that the earlier AR25-style separate observability compose file is superseded.
  - [x] Capture the extension path for later stories: PostgreSQL now, runners in 3.3/3.4, observability in 3.7.

- [x] **Task 5: Verify compose behavior and backend startup** (AC: 1, 2, 3, 7)
  - [x] Run `docker compose config` to validate interpolation and overall compose structure.
  - [x] Run `docker compose up -d` and confirm the PostgreSQL service becomes healthy.
  - [x] From the repo root, start the backend with the `local` profile using an explicit command shape and confirm startup no longer fails with `Failed to configure a DataSource: 'url' attribute is not specified`.
  - [x] Verify Maven-run startup from the root with `./mvnw -pl deliveryline-backend spring-boot:run -Dspring-boot.run.profiles=local` (or `mvnw.cmd` on Windows).
  - [x] Verify packaged-jar startup from the root with `java -jar deliveryline-backend/target/deliveryline-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`.
  - [x] Run `docker compose down -v` and confirm the named volume is removed cleanly.

- [x] **Task 6: Regression and boundary verification** (AC: 1-10)
  - [x] Run the relevant Maven verification from the repo root after the compose/profile wiring changes.
  - [x] Confirm the implementation touches only the files needed for this story and does **not** pre-implement story 1.16 doctor output, story 1.17 scripts, story 1.3 Flyway migrations, or story 3.x optional services.
  - [x] Confirm no separate compose file was introduced anywhere in the repo.

## Dev Notes

This story is the first local-environment wiring story after the scaffold. It must solve only the local PostgreSQL + compose contract and the backend startup handoff. It must **not** spill into schema work, observability, runner lifecycle, or scripts.

**Critical scope discipline**
- Build exactly one root `docker-compose.yml` in this story.
- Epic 1 compose contains only PostgreSQL. Do not add runner services yet, even as placeholders.
- Do not create `docker-compose.observability.yml`; that architecture reference is stale and superseded by the epic acceptance criteria.
- Do not implement doctor output here; story 1.16 consumes this compose/env contract later.
- Do not add Flyway migrations or persistence entities here; story 1.3 owns schema creation.

**Story handoff from 1.1**
- Story 1.1 intentionally discarded Initializr's generated `compose.yaml`; this story owns the real root compose file.
- Story 1.1 left backend startup failing with `Failed to configure a DataSource: 'url' attribute is not specified`; the success condition here is that local-profile startup uses Docker Compose service discovery instead of manual JDBC properties.
- `.env.example` already contains `POSTGRES_HOST_PORT=5432` and reserved comments for future observability ports; extend that file instead of redesigning the env contract.
- Review hardening already pinned Testcontainers to `postgres:17`; keep the compose PostgreSQL major version aligned unless a documented reason requires divergence.

**Architecture and file targets**
- Root files to create/update in this story:
  - `docker-compose.yml`
  - `.env.example`
  - `docs/adr/0001-unified-compose.md`
  - `deliveryline-backend/src/main/resources/application-local.yml`
- Backend config ownership stays under `deliveryline-backend/src/main/resources/`; `infrastructure` owns profile wiring, not `application` or `domain`.
- The canonical backend module path is `deliveryline-backend/`, not the stale `backend/` name still present in parts of `architecture.md`.

**Developer guardrails**
- Use environment-variable interpolation in compose instead of hardcoded host ports. Docker Compose supports `${VAR:-default}` and `${VAR:?error}` patterns; use them deliberately.
- Keep PostgreSQL unprofiled so it is always enabled. Future heavy services belong behind `profiles`, not in separate files.
- Avoid manual JDBC duplication. Spring Boot Docker Compose service connections resolve by image name and mapped host port; if the app can discover the compose file, it should not need `spring.datasource.url`.
- Prefer `postgres:17` here. The official Docker image changed `PGDATA` behavior in PostgreSQL 18+, while PostgreSQL 17 and below still use `/var/lib/postgresql/data` as the correct durable mount target.
- Add a healthcheck. Spring Boot's compose support waits for started and healthy services; explicit healthchecks are the recommended readiness contract.

**Launch and verification guidance**
- Run verification commands from the **repo root**, not from `deliveryline-backend/`, unless you have explicitly proven and configured a different compose-file resolution path.
- If local-profile backend startup works from the root without `spring.docker.compose.file`, keep it that way; fewer path assumptions means less cross-OS fragility.
- If you must add `spring.docker.compose.file`, verify both the intended Maven launch path and packaged-jar launch path before committing.
- Use explicit root-level verification commands when proving the profile wiring:
  - Maven-run: `./mvnw -pl deliveryline-backend spring-boot:run -Dspring-boot.run.profiles=local` (or `mvnw.cmd` on Windows)
  - Packaged jar: `java -jar deliveryline-backend/target/deliveryline-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`

**Current official-docs specifics to follow**
- Docker Compose profiles: services without a `profiles` attribute start by default; `--profile observability` or `--profile "*"` can opt into later services.
- Docker Compose interpolation: `${VAR:-default}` supplies defaults and `${VAR:?message}` fails fast on missing required values.
- Spring Boot Docker Compose support: the compose CLI must be on `PATH`; supported service connections use the container image name and the mapped host port takes precedence over hardcoded JDBC settings.
- Spring Boot compose file discovery order includes `compose.yaml`, `compose.yml`, `docker-compose.yaml`, and `docker-compose.yml`; this story deliberately standardizes on `docker-compose.yml` because that is what the epic requires.

### Project Structure Notes

- The architecture document still shows both `docker-compose.yml` and `docker-compose.observability.yml` in the root tree. For this story, treat that as stale.
- The architecture document also still uses `backend/` in some structure sections. The canonical module created in story 1.1 is `deliveryline-backend/`; use the real repo path, not the stale architecture alias.
- `docs/adr/` does not exist yet in the live repo; this story should create it as part of the ADR work.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Story 1.2 acceptance criteria, lines 441-458]
- [Source: `_bmad-output/planning-artifacts/epics.md` - AR24-AR31 infrastructure requirements, lines 209-216]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - local-first runtime topology and compose/profile rules, lines 524-545]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - profile and implementation-sequence constraints, lines 575-594]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - resource file structure including `application-local.yml`, lines 1029-1034]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - `infrastructure` owns profile wiring, lines 1156-1160]
- [Source: `_bmad-output/planning-artifacts/architecture.md` - backend/dev server structure, lines 1343-1346]
- [Source: `_bmad-output/implementation-artifacts/1-1-initialize-maven-multi-module-project-scaffold.md` - handoff notes for `compose.yaml`, `.env.example`, and datasource failure, lines 111-124 and 156-160]
- [Source: `https://docs.docker.com/reference/compose-file/interpolation/` - official Docker Compose variable interpolation syntax (`:-` and `:?`)]
- [Source: `https://docs.docker.com/compose/how-tos/profiles/` - official Docker Compose profile behavior for default vs optional services]
- [Source: `https://docs.spring.io/spring-boot/how-to/docker-compose.html` - Spring Boot Docker Compose service-connection behavior and optional `spring.docker.compose.file` / `lifecycle-management`]
- [Source: `https://docs.spring.io/spring-boot/api/java/org/springframework/boot/docker/compose/core/DockerComposeFile.html` - Spring Boot compose file discovery order]
- [Source: `https://docs.spring.io/spring-boot/api/java/org/springframework/boot/docker/compose/core/DockerCompose.html` - Spring Boot compose startup waits for started/healthy services]
- [Source: `https://hub.docker.com/_/postgres` - official PostgreSQL image env vars and Postgres 17-and-below durable mount path guidance]

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Auto-selected next backlog story from `sprint-status.yaml`: `1-2-unified-docker-compose-with-env-configurable-ports`
- Previous-story handoff confirms `compose.yaml` was deliberately removed and backend startup remains blocked on datasource wiring until this story is implemented
- Architecture drift noted: stale `backend/` module naming and stale `docker-compose.observability.yml` reference must not be copied into implementation
- Official docs cross-check used to confirm current Docker Compose interpolation/profile behavior and Spring Boot Docker Compose discovery/service-connection constraints
- Added contract coverage in `LocalDevelopmentContractTest` for compose shape, local-profile wiring, ADR presence, Maven working directory, and packaged-jar Docker Compose inclusion
- Root-cause investigation found `spring-boot:run` defaulted to the module working directory and the Spring Boot repackager excluded Docker Compose support from fat jars by default
- End-to-end verification used `POSTGRES_HOST_PORT=55432` locally because host `5432` is occupied on this workstation; the committed contract still documents `5432` as the default

### Completion Notes List

- Added a single root `docker-compose.yml` with PostgreSQL 17, `POSTGRES_PASSWORD` env enforcement, named volume persistence, and a healthcheck
- Added `application-local.yml` with `spring.docker.compose.lifecycle-management: start-only` and kept datasource settings out of app config
- Configured `spring-boot-maven-plugin` to run from `${maven.multiModuleProjectDirectory}` and to package Docker Compose support by setting `excludeDockerCompose` to `false`
- Updated `.env.example` and added `docs/adr/0001-unified-compose.md`
- Verified `docker compose config`, `docker compose up -d`, Maven local-profile startup, packaged-jar local-profile startup, and `docker compose down -v`

### File List

- `.env.example`
- `deliveryline-backend/pom.xml`
- `deliveryline-backend/src/main/resources/application-local.yml`
- `deliveryline-backend/src/test/java/org/dradgo/LocalDevelopmentContractTest.java`
- `docker-compose.yml`
- `docs/adr/0001-unified-compose.md`

### Change Log

- **2026-04-27** - Created comprehensive implementation story for unified root Docker Compose, local-profile service discovery, `.env` contract finalization, and unified-compose ADR documentation.
- **2026-04-27** - Implemented the unified root compose contract, added local-profile Compose discovery, fixed Maven and packaged-jar launch-mode wiring, and verified full compose lifecycle behavior.
- **2026-04-27** - Code review (3-layer adversarial): all 10 ACs satisfied. 3 decisions resolved as leave-as-is, 8 patches applied (compose `name`, healthcheck localhost+literals, `start_period` 30s, test cwd-independence + assertion narrowing + structural observability check), 5 items deferred. Status → done.

### Review Findings

_Code review on 2026-04-27 — 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). All 10 ACs ✅ satisfied; below are findings beyond the AC pass._

**Decisions resolved (2026-04-27):**

- [x] [Review][Decision] `.env.example` default `POSTGRES_PASSWORD=deliveryline` → **leave as-is** (pilot installer convenience).
- [x] [Review][Decision] `<optional>true</optional>` removed from `spring-boot-docker-compose` → **leave as-is** (preserves packaged-jar startup verification; revisit when first consumer module is added).
- [x] [Review][Decision] No `restart:` policy on `postgres` → **leave unset** (default `no`; pilot-deployment ergonomics out of scope for this story).

**Patches applied (2026-04-27):**

- [x] [Review][Patch] Healthcheck uses unix-socket `pg_isready`, races initdb temporary server on first launch — added `-h localhost`. [`docker-compose.yml`:14]
- [x] [Review][Patch] Healthcheck `$$POSTGRES_USER`/`$$POSTGRES_DB` shell expansion fragile — hard-coded literals: `pg_isready -h localhost -U deliveryline -d deliveryline`. [`docker-compose.yml`:14]
- [x] [Review][Patch] `start_period: 10s` short for cold start — bumped to `30s`. [`docker-compose.yml`:18]
- [x] [Review][Patch] No top-level `name:` on the compose project — pinned `name: deliveryline`. [`docker-compose.yml`:1]
- [x] [Review][Patch] `LocalDevelopmentContractTest` SIOOBE risk on `substring` — moved `assertTrue(dockerComposeDependency >= 0)` BEFORE the substring call and added a guard for `dockerComposeDependencyEnd >= 0`. [`LocalDevelopmentContractTest.java`:65-68]
- [x] [Review][Patch] Test cwd-fragility — replaced relative `Path.of("..")` and module-relative paths with a `findRepoRoot()` helper that walks up looking for `docker-compose.yml` + `.mvn`; tests now pass from both module dir and repo root. [`LocalDevelopmentContractTest.java`:14-26]
- [x] [Review][Patch] Broad substring assertion `contains("file:")` narrowed to YAML-indent-aware `contains("\n      file:")` to avoid spurious failures on innocent edits. [`LocalDevelopmentContractTest.java`:57]
- [x] [Review][Patch] ADR test enforces structural decision: replaced `assertTrue(adr.contains("docker-compose.observability.yml"))` with `assertFalse(Files.exists(REPO_ROOT.resolve("docker-compose.observability.yml")))`. [`LocalDevelopmentContractTest.java`:81]

_Verification: `mvnw test -pl deliveryline-backend -Dtest=LocalDevelopmentContractTest` → 5/5 pass from both module dir and repo root. `docker compose config --quiet` → exit 0 with `POSTGRES_PASSWORD` set._

**Deferred (pre-existing or out-of-scope, tracked in `deferred-work.md`):**

- [x] [Review][Defer] Test class located in root `org.dradgo` package rather than an `infrastructure` subpackage — package convention question for the broader codebase. [`LocalDevelopmentContractTest.java`:1]
- [x] [Review][Defer] `${POSTGRES_PASSWORD:?...}` required form will fail any future `@SpringBootTest` in CI without `.env` — address when first SpringBootTest is added (e.g., `spring.docker.compose.skip-in-tests=true`).
- [x] [Review][Defer] No datasource fallback for non-compose contexts (production, non-`local` profiles) — out of scope for Epic 1 local-first; revisit when prod packaging is in scope. [`application.yml`]
- [x] [Review][Defer] Volume permission mismatch on Linux bind-mount overrides (UID 999 vs host UID) — pilot docs should warn against bind-mount overrides for the Postgres data dir.
- [x] [Review][Defer] AC-7 (`docker compose down -v` removes volume) is not asserted by an automated test — manually verified per dev log; consider adding a Testcontainers/integration-level check later.

**Dismissed as noise (8 items):** profile activation for `application-local.yml` (spec mandates explicit `-Dspring-boot.run.profiles=local`); `excludeDockerCompose=false` activates compose for default profile (default profile is unsupported); `${maven.multiModuleProjectDirectory}` fragility (`.mvn/wrapper/` exists at repo root → resolves correctly via mvn launcher); host-port collision (already documented in `.env.example` with override); `start-only` requires manual `compose down` (intentional per spec); story-number comment in `.env.example` (style); `realPath()` vs `normalize()` symlinks (speculative); `Files.readString` charset/BOM (Java 11+ default UTF-8); AC-10 doctor-readiness no test (forward-looking; doctor is story 1.16).
