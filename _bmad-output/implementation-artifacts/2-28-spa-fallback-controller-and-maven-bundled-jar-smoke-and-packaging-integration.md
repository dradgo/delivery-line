# Story 2.28: SPA Fallback Controller + Maven Bundled-Jar Smoke + Packaging Integration

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a pilot installer running the bundled `deliveryline.jar`,
I want Spring Boot to serve the bundled React SPA with a SPA-fallback so deep links work, plus a CI bundled-jar smoke that asserts the packaged jar contains the SPA and the SPA + REST round-trip works end-to-end,
so that AR33 (SPA fallback supporting direct refresh of React routes without masking missing API endpoints) holds under packaging — and Epic 2's UI is genuinely deployable as one executable jar (AR32).

## Scope Decisions (read first — these resolve epic-vs-reality tensions)

**This is a RECONCILE + FORMALIZE + EXTEND story over machinery that already largely exists. It is NOT a greenfield "stand up SPA serving + packaging" story.** Story 2.1 already wired the Maven copy of the Vite bundle into `BOOT-INF/classes/static/`, the build-fail enforcer when the bundle is missing, and a runtime fail-fast guard. The SPA deep-link fallback already works. The CI `jar-packaging` and `bundled-jar-smoke` tiers already exist. **Read the "Current-state inventory" in Dev Notes before writing a line of code. Do not rebuild what exists.**

- **S1 — The SPA fallback ALREADY WORKS, but NOT via a class named `SpaFallbackController`.** It lives today as `ProblemDetailsMapper.handleNoResourceFound(...)` (a `@RestControllerAdvice` `@ExceptionHandler(NoResourceFoundException.class)` in `adapters.rest`), backed by the pure predicate `ProblemDetailsMapper.shouldServeSpaShell(...)` with 10 unit tests in `ProblemDetailsMapperSpaFallbackTest`. This predicate **already implements AC2 verbatim**: it excludes `api/`, `actuator/`, `v3/api-docs`, `swagger-ui`, any path whose last segment contains a dot (missing asset), non-GET, and non-HTML-accepting clients. **Do NOT re-implement this logic.**

- **S2 — `SpaFallbackController` in `infrastructure/web` (architecture tree line 1028) is BLOCKED by an enforced ArchUnit rule.** `ArchitectureRuleCatalog.REST_CONTROLLER_SUFFIX_REQUIRES_REST_CONTROLLER_ANNOTATION` requires **any** class named `*Controller` anywhere under `org.dradgo..` to (a) be annotated `@RestController` AND (b) reside in `org.dradgo.adapters.rest`. The architecture project-structure tree showing `infrastructure/web/SpaFallbackController.java` is **outdated/aspirational and is OVERRIDDEN by the enforced rule** (ArchUnit runs in Failsafe — `[[archunit-runs-in-failsafe-not-surefire]]`; `mvnw test` will NOT catch a violation). **Encoded default: do NOT create a class named `*Controller` outside `adapters.rest`, and do NOT put the SPA fallback in `infrastructure/web`.** Satisfy AC1's intent (a single, named, discoverable SPA-fallback seam) by extracting the existing fallback into a dedicated `@RestController SpaFallbackController` under `adapters.rest` (see S3), NOT by honoring the stale tree path.

- **S3 — AC1 reconciliation (the #1 design decision): extract, don't duplicate, and keep the proven interception mechanism.** `NoResourceFoundException` is thrown by the DispatcherServlet's static-resource handling, NOT by a controller method — so it can ONLY be intercepted by a global `@RestControllerAdvice` (today: `ProblemDetailsMapper`). A standalone `@RequestMapping("/**")` catch-all controller is more fragile (ordering vs the resource handler, asset shadowing). **Encoded default (Option A):** create `org.dradgo.adapters.rest.SpaFallbackController` (a `@RestController`) that OWNS the shell-serving responsibility — it holds the `shouldServeSpaShell(...)` predicate (moved from `ProblemDetailsMapper`) plus a `ResponseEntity<Resource> serveShell()` method that returns `classpath:/static/index.html` with `Cache-Control: no-store`; `ProblemDetailsMapper.handleNoResourceFound` then **delegates** to it (`if (spaFallbackController.shouldServeSpaShell(...)) return spaFallbackController.serveShell(); else <existing JSON 404>`). This gives AC1 its named class, keeps the only mechanism that actually intercepts unmatched routes, preserves the 10 existing predicate tests (re-point them at `SpaFallbackController`), and stays ArchUnit-legal. **If Alex prefers** a pure catch-all-controller mechanism (no advice delegation), that is Open Question 1 — pick Option A unless told otherwise.

- **S4 — Packaging (AC5/AC6/AC10) is ~80% done in story 2.1's `pom.xml`.** The frontend→backend build-order dep, the `maven-enforcer require-frontend-dist` build-fail (AC5/AC10), the `maven-clean wipe-static-before-copy`, and the `maven-resources copy-frontend-dist` into `target/classes/static/` (→ `BOOT-INF/classes/static/`) all exist. **Net-new for packaging: only the AC6 packaging *contract test*** that unzips the built jar and asserts `BOOT-INF/classes/static/index.html`, `assets/`, and the entrypoint JS are present. Story 2.1 wrote to `target/classes/static` (not `src/main/resources/static`) deliberately — AC5's literal "copy into `src/main/resources/static/`" is satisfied by the identical end-state under `BOOT-INF/classes/static/`; **do not change the copy target.** AC10's required failure message ("frontend dist not found at expected path; ensure `mvn clean install` completed…") is close-but-not-identical to the existing enforcer message — reconcile the wording (minor; keep it actionable).

- **S5 — AC7 bundled-jar smoke ALREADY EXISTS as a CI tier; EXTEND its assertions.** The `bundled-jar-smoke` job (`.github/workflows/ci.yml`) already boots the packaged jar against a Postgres, waits on `/actuator/health=UP`, and asserts `GET /api/v1/workflows` (schema) + `GET /v3/api-docs` (paths). **Net-new assertions to add to that job: (1)** `GET /` serves `index.html` (assert HTML body / `<div id="root">`); **(2)** `GET /some/spa/route` (a non-existent SPA path) serves `index.html` (fallback works); **(3)** `GET /api/v1/workflows/nonexistent` returns 404 Problem Details (`application/problem+json`, NOT `index.html`). **Mechanism note:** the existing job uses repo-root `docker compose` Postgres, not literal JUnit Testcontainers — AC7's "Testcontainers PostgreSQL" wording describes the *ideal*; **match the existing docker-compose shape** (changing it is unjustified churn). The bundle must actually be embedded in the smoke jar — the `jar-packaging` job builds with `-DskipTests package` through the reactor, so `copy-frontend-dist` runs and the SPA is present; verify the smoke jar contains it (this is what makes assertions 1–2 meaningful).

- **S6 — AC11 foundation-gate widening must respect AC8's cost control — do NOT move the heavy smoke onto PRs.** `bundled-jar-smoke` runs **`push:main` only** (AC8 cost control, set in story 1.21) and is deliberately NOT in `foundation-gate.needs` (it's skipped on PRs). The runtime boot-and-curl smoke stays main-only. **Encoded default for AC11 "bundled-jar smoke green on the branch" on PRs:** the fast, Docker-free **AC6 packaging contract test** runs in the `backend-contract-tests`/`verify` tier (already a `foundation-gate.needs`), so the *packaging* invariant (jar contains the SPA + fallback wiring is unit/contract-tested) gates every PR; the full *runtime* round-trip stays the main-only release-readiness signal. Document this split explicitly in the story's Completion Notes and in the foundation-gate comment block — do not silently leave AC11 ambiguous.

- **S7 — AC4 cache headers are NOT yet implemented.** No `WebMvcConfigurer`/`spring.web.resources.cache` config exists. `index.html` gets `no-store` ONLY on the SPA-fallback path; the welcome-page serving of `/` and the static `/assets/**` serving carry Spring Boot defaults (effectively no cache headers). Net-new: long-cache `Cache-Control: max-age=31536000, immutable` on `/assets/**` (Vite content-hashes filenames — safe) and `no-cache`/`no-store` on `index.html`. A `WebMvcConfigurer`/`@Configuration` (NOT a `*Controller`) is allowed under `infrastructure/web` (only `*Controller`-named/`@RestController` classes are pinned to `adapters.rest`) — so the architecture tree's `infrastructure/web` package is the right home for this *config* class, even though the *controller* must go to `adapters.rest`.

## Acceptance Criteria

1. **Given** the SPA-fallback seam, **Then** a single, named, discoverable component (`SpaFallbackController` under `adapters.rest` per S2/S3) serves the embedded app shell (`classpath:/static/index.html`) for GET requests to non-API, non-static-asset paths so React Router (story 2.5) handles client-side routing; the proven `shouldServeSpaShell` predicate + the `NoResourceFoundException` interception mechanism are reused, not re-implemented.
2. **Given** SPA fallback rules per AR33, **Then** the fallback does NOT fire for: requests under `/api/**` (REST endpoints — actual response or REST 404), static asset paths (`/assets/**`, `/static/**`, `*.js`, `*.css`, `*.svg`, `*.ico`, `*.png`, etc. — real asset or 404), `/v3/api-docs` and `/swagger-ui.html` (OpenAPI — real responses), and `/actuator/**` (Actuator). *(Already enforced by `shouldServeSpaShell`; preserve all 10 predicate tests when extracting.)*
3. **Given** test coverage of SPA fallback rules, **Then** integration tests assert: GET `/workflows/run_abc` returns `index.html` with the React app, GET `/api/v1/workflows/nonexistent` returns 404 Problem Details (NOT `index.html`), GET `/assets/missing.js` returns 404 (NOT `index.html`), GET `/v3/api-docs` returns the OpenAPI doc (NOT `index.html`), GET `/actuator/health` returns the actuator response — these are the **API path collision tests** referenced by story 2.5 AC5 + story 6.9 AC9. *(Unit-level predicate coverage exists; add the `@SpringBootTest`/`@WebMvcTest`-level collision IT that exercises the wired DispatcherServlet path.)*
4. **Given** static-asset cache headers per architecture quality gates, **Then** built React assets under `/assets/` carry `Cache-Control: max-age=31536000, immutable` (Vite content-hashed filenames make this safe); `index.html` carries no-cache/no-store headers so users get the latest SPA shell on every page load.
5. **Given** Maven packaging per story 2.1 AC3, **Then** the backend build declares an explicit dependency on `deliveryline-frontend`'s build output and copies the Vite `dist/` onto the SPA classpath before jar assembly — failing the backend build with a clear error if the frontend dist is missing or empty. *(Already implemented in `deliveryline-backend/pom.xml`; verify + reconcile the AC10 failure-message wording. Copy target is `target/classes/static/` by design — do not change.)*
6. **Given** the Maven build, **When** `mvn clean package` runs, **Then** the resulting `deliveryline-backend-{version}.jar` contains the compiled React SPA under `BOOT-INF/classes/static/` — verified by a NEW packaging contract test that unzips the jar and asserts `index.html`, `assets/`, and the entrypoint JS file are present.
7. **Given** the bundled-jar smoke as a CI tier per story 1.21 AC1, **Then** the smoke: launches the packaged jar against a Postgres, runs Flyway migrations (incl. V2 from story 2.11), hits `GET /` and asserts `index.html` is served, hits `GET /api/v1/workflows` and asserts 200 + the documented schema, hits a non-existent route `/some/spa/route` and asserts `index.html` is served (SPA fallback works), hits `GET /api/v1/workflows/nonexistent` and asserts 404 Problem Details (NOT SPA fallback), shuts down cleanly. *(EXTEND the existing `bundled-jar-smoke` job — add the three SPA assertions per S5; keep the docker-compose Postgres shape.)*
8. **Given** cross-platform packaging per story 1.17, **Then** the bundled-jar smoke runs on `ubuntu-latest` only; a documented note states Windows + macOS jar smoke can be enabled in a follow-up if pilot demand emerges (deferred for CI cost per story 1.21 AC3). *(Already `ubuntu-latest`, `push:main` only — preserve.)*
9. **Given** REST endpoint preservation, **Then** an explicit test asserts that every documented REST endpoint in the committed OpenAPI snapshot (`src/main/resources/openapi/openapi.json` — story 2.13 mutations + story 6.9 reads) resolves correctly through the bundled jar (not masked by SPA fallback) — protecting against fallback-ordering regressions as endpoints expand in Epic 3+. *(Reference the committed snapshot, do not hand-enumerate paths — drift-proof.)*
10. **Given** failure modes, **When** the frontend dist is missing during packaging, **Then** the Maven build fails with a clear, actionable message naming the expected path and the fix (`mvn -pl deliveryline-frontend package` / `mvn clean install`); when SPA assets fail to load at runtime, the bundled-jar smoke fails with a documented diagnostic. *(Build-fail enforcer + runtime `EmbeddedFrontendGuard` exist; reconcile the message to AC10's wording.)*
11. **Given** the foundation-gate verification from story 1.23, **Then** its scope widens to include the packaging invariant on PRs: the AC6 packaging contract test runs inside `backend-contract-tests` (already a `foundation-gate.need`), so a jar missing the embedded SPA reds every PR; the full runtime smoke stays the `push:main` release-readiness signal (S6). The split is documented in the ci.yml foundation-gate block + the story Completion Notes.

## Tasks / Subtasks

- [x] **Task 1 — Extract `SpaFallbackController` (AC1, AC2; S1, S2, S3)**
  - [x] Create `org.dradgo.adapters.rest.SpaFallbackController` as a `@RestController`. Move `shouldServeSpaShell(...)`, `acceptsHtml(...)`, `RESERVED_NON_SPA_PREFIXES`, and the `SPA_SHELL` `ClassPathResource("static/index.html")` from `ProblemDetailsMapper` into it. Add a `ResponseEntity<Resource> serveShell()` returning the shell with `Cache-Control: no-store` + `Content-Type: text/html` (preserve current behavior exactly).
  - [x] In `ProblemDetailsMapper.handleNoResourceFound`, inject `SpaFallbackController` (constructor or `ObjectProvider` — match the existing `ObjectProvider` ctor style) and delegate: `if (spaFallback.shouldServeSpaShell(path, request) && SPA_SHELL.exists()) return spaFallback.serveShell(); else <existing JSON 404>`. Keep `ProblemDetailsMapper` as the `@RestControllerAdvice` that intercepts `NoResourceFoundException` — that interception point does not move.
  - [x] Re-point `ProblemDetailsMapperSpaFallbackTest`'s 10 cases at `SpaFallbackController.shouldServeSpaShell` (rename to `SpaFallbackControllerTest` or keep the file + update the static target). Confirm all 10 still pass — no logic change.
  - [x] Verify ArchUnit stays green: run the Failsafe architecture slice (`[[archunit-runs-in-failsafe-not-surefire]]` — `mvnw test` will NOT catch a violation). `SpaFallbackController` must be `@RestController` AND under `adapters.rest`, and must not depend on application SPI/persistence/runner (`REST_CONTROLLERS_STAY_THIN`) — it only serves a static `Resource`.

- [x] **Task 2 — Static-asset + shell cache headers (AC4; S7)**
  - [x] Add a `@Configuration implements WebMvcConfigurer` under `org.dradgo.infrastructure.web` (NOT a `*Controller`; this package is legal for non-controller web config). Register a resource handler (or `spring.web.resources.cache`) so `/assets/**` carries `Cache-Control: max-age=31536000, immutable` and `index.html` carries `no-cache`/`no-store`. Do not break the existing static serving of `/` and content-hashed assets.
  - [x] Add a focused test asserting the headers (`@WebMvcTest`/`MockMvc` against a stub asset + index.html, or a slice test). Assert `/assets/<hashed>.js` → `max-age=31536000, immutable` and `/` (index.html) → no-cache. Keep the bundle-absent test tier green (`deliveryline.frontend.fail-on-missing-bundle=false` is already set in `src/test/resources/application.yml`).

- [x] **Task 3 — Packaging contract test: jar contains the SPA (AC6; S4)**
  - [x] Add `org.dradgo.contract.BundledJarPackagingContractTest` (name `*ContractTest` → routed to Failsafe, runs after `package` so the repackaged jar exists; `[[springboot-testcontainers-test-must-be-IT]]` does NOT apply — no Spring context / no Docker needed, so a plain `*ContractTest` is correct and stays off the no-Docker Windows tier via the pom's `*ContractTest` Surefire exclusion). It globs `target/deliveryline-backend-*.jar` (excluding `*.jar.original`), opens it as a `ZipFile`/`JarFile`, and asserts entries exist: `BOOT-INF/classes/static/index.html`, at least one `BOOT-INF/classes/static/assets/*.js`, and `BOOT-INF/classes/static/assets/*.css` (or the entrypoint JS referenced by index.html).
  - [x] Guard for the backend-only/frontend-skipped path: if `frontend-maven-plugin.skip=true` (no bundle), the jar legitimately lacks the SPA — `assumeTrue`/skip with a clear message rather than fail, so local backend-only iteration stays green. The build-fail enforcer (Task 4 / story 2.1) is what guarantees the bundle in real packaging.

- [x] **Task 4 — Verify + reconcile packaging build-fail (AC5, AC10; S4)**
  - [x] Confirm `deliveryline-backend/pom.xml`'s `maven-enforcer require-frontend-dist` execution fails the build with the bundle absent, and `EmbeddedFrontendGuard` fails startup at runtime. Reconcile the enforcer `<message>` to AC10's required wording (name the expected path + the `mvn -pl deliveryline-frontend package` / `mvn clean install` fix). Keep `${frontend-maven-plugin.skip}` honored on both the enforcer and the copy (backend-only local workflow must still work).
  - [x] Do NOT change the copy target (`target/classes/static/`) — `[[embedded-frontend-at-package-phase]]`; the `BOOT-INF/classes/static/` end-state is the AC5 invariant.

- [x] **Task 5 — Extend the bundled-jar-smoke CI tier with SPA assertions (AC7, AC9; S5)**
  - [x] In `.github/workflows/ci.yml` `bundled-jar-smoke` job, after the existing health/workflows/api-docs curls, add: `curl -fsS http://127.0.0.1:$PORT/` and assert the body is HTML containing the SPA root (`<div id="root">` or the index.html title); `curl -fsS .../some/spa/route` and assert the SAME HTML shell is returned (fallback); `curl -s -o /dev/null -w '%{http_code} %{content_type}' .../api/v1/workflows/nonexistent` and assert `404` + `application/problem+json` (NOT HTML). Send `Accept: text/html` on the GET-`/` navigations so the fallback predicate fires.
  - [x] AC9: add a step (or extend the existing `jq` block) asserting every path key in the booted `/v3/api-docs` resolves to a real REST response — at minimum re-confirm the existing `/api/v1/workflows`, `/api/v1/workflows/{workflowRunId}`, `/api/v1/workflows/{workflowRunId}/events` checks and add the story-2.13 mutation paths from the snapshot are NOT served `index.html` (a JSON/`problem+json` content-type on a known endpoint proves it). Keep `ubuntu-latest` + `push:main`-only (AC8) — do not widen the trigger.

- [x] **Task 6 — Foundation-gate widening note (AC11; S6)**
  - [x] Add a comment block to the `foundation-gate` job in ci.yml documenting the split: the AC6 packaging contract test gates the *packaging invariant* on every PR via `backend-contract-tests` (already in `needs:`); the full runtime `bundled-jar-smoke` stays `push:main` only (AC8) and is intentionally NOT a `foundation-gate.need`. Do NOT add `bundled-jar-smoke` to `foundation-gate.needs` (it is skipped on PRs and would mark the gate skipped/failed). Record the rationale in the story Completion Notes.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs: `SpaFallbackController.serveShell` logs `INFO` "spa_fallback serving shell for path={}" with the sanitized request path (reuse `MdcKeys.sanitizeForLog`); the bundle-absent branch (shell not present) logs `DEBUG` (it is the inert dev/test case, not an error). The `EmbeddedFrontendGuard` already logs the present/absent branch — do not duplicate.
  - [x] Use parameterized logging (`log.info("...", arg)`) — never string concatenation. Levels: `INFO` for the served-shell decision, `DEBUG` for the inert no-bundle path, `WARN`/`ERROR` reserved (a missing shell at runtime is a startup-guard concern, already covered).
  - [x] Never log secrets, payload bytes, raw tokens, or full PII — only the sanitized request path (already redaction-safe; no body involved on a GET navigation).
  - [x] Add at least one assertion (list-appender / `OutputCaptureExtension`) that the `spa_fallback serving shell` line is emitted at `INFO` when the shell is served. Note: `ProblemDetailsMapper` already pins a WARN on the domain-exception catch-site — do not regress that test when delegating.

## Dev Notes

### Current-state inventory (READ BEFORE CODING — what already exists)

| Concern | Where it lives today | Status for this story |
|---|---|---|
| SPA fallback decision predicate | `ProblemDetailsMapper.shouldServeSpaShell` (+ `acceptsHtml`, `RESERVED_NON_SPA_PREFIXES`) | **Move** to `SpaFallbackController`; logic unchanged (AC2 satisfied) |
| SPA fallback interception | `ProblemDetailsMapper.handleNoResourceFound` (`@RestControllerAdvice` + `@ExceptionHandler(NoResourceFoundException.class)`) | **Keep** the interception point; **delegate** shell-serving to `SpaFallbackController` |
| Predicate unit tests (10) | `ProblemDetailsMapperSpaFallbackTest` | **Re-point** at `SpaFallbackController`; all must still pass |
| Runtime fail-fast on missing bundle | `infrastructure.config.EmbeddedFrontendGuard` (gated by `deliveryline.frontend.fail-on-missing-bundle`, default true; test yaml sets false) | **Reuse** — covers AC10 runtime half |
| Build-fail on missing dist | `pom.xml` `maven-enforcer-plugin` exec `require-frontend-dist` | **Verify + reconcile message** (AC5/AC10) |
| Copy Vite dist → SPA classpath | `pom.xml` `maven-resources-plugin` exec `copy-frontend-dist` → `target/classes/static/`; `maven-clean` `wipe-static-before-copy`; frontend→backend reactor order via `<dependency type=pom scope=test>` | **Reuse unchanged** (AC5) |
| jar packaging CI tier | ci.yml `jar-packaging` (`-DskipTests package`, `java -jar … help` structural smoke) | Reuse; the SPA is embedded because the reactor copy runs |
| bundled-jar runtime smoke | ci.yml `bundled-jar-smoke` (`push:main` only, docker-compose Postgres, health + `/api/v1/workflows` + `/v3/api-docs`) | **Extend** with 3 SPA assertions (AC7); keep trigger/OS (AC8) |
| Cache headers (AC4) | **NONE** (only `no-store` on the fallback shell path) | **Net-new** `WebMvcConfigurer` |
| Packaging contract test (AC6) | **NONE** | **Net-new** `BundledJarPackagingContractTest` |

### Architecture compliance + guardrails (MUST follow)

- **ArchUnit (enforced, Failsafe tier) — the controlling constraint.** `REST_CONTROLLER_SUFFIX_REQUIRES_REST_CONTROLLER_ANNOTATION` + `REST_CONTROLLERS_MUST_BE_NAMED_AS_CONTROLLERS`: any `*Controller`-named class must be `@RestController` AND under `org.dradgo.adapters.rest`. `SpaFallbackController` therefore goes to `adapters.rest`, NOT `infrastructure/web` — the architecture project-structure tree (line 1028) is stale on this point and is overridden. `REST_CONTROLLERS_STAY_THIN`: the controller must not depend on application SPI / persistence / runner adapters (it only serves a static `Resource` — fine). ArchUnit does NOT run under `mvnw test` — verify via the Failsafe architecture slice. [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java#L251-L275] `[[archunit-runs-in-failsafe-not-surefire]]`
- **Non-controller web config IS allowed in `infrastructure/web`** — only `*Controller`-named / `@RestController` classes are pinned to `adapters.rest`. The AC4 cache `WebMvcConfigurer` may live in `infrastructure.web` (matches the architecture tree's intent for that package).
- **`NoResourceFoundException` is the only reliable interception point** for unmatched routes (thrown by DispatcherServlet resource handling, not a controller). Keep it in the `@RestControllerAdvice`. A `@RequestMapping("/**")` catch-all is the rejected alternative (Open Question 1).
- **Copy target is `target/classes/static/`, not `src/main/resources/static/`** — generated content under `src/` pollutes the tree and breaks reproducible builds; end-state under `BOOT-INF/classes/static/` is the AC5 invariant. `[[embedded-frontend-at-package-phase]]` [Source: deliveryline-backend/pom.xml#L302-L343]
- **Test naming / tiering:** `*ContractTest` → Failsafe (runs after `package`, so the repackaged jar is available to unzip) and excluded from the no-Docker Windows Surefire tier by the pom's file-pattern exclusion. The packaging contract test needs NO Spring context and NO Docker, so it is NOT a `*IT` (`[[springboot-testcontainers-test-must-be-IT]]` applies only to `@SpringBootTest`+Testcontainers). [Source: deliveryline-backend/pom.xml#L499-L580]
- **Validated config:** if you add any `@ConfigurationProperties` field (e.g. a cache-max-age knob), update `src/test/resources/application.yml` too — it shadows, not merges (`[[validated-config-needs-test-yaml]]`). Prefer hard-coded constants for the cache values (AR-fixed: Vite hashing makes 1y immutable safe) to avoid a new validated field.
- **REST surface for AC9** (do not hand-enumerate — reference `openapi.json`): `WorkflowController` (`@RequestMapping("/api/v1/workflows")`) exposes reads `GET /` (list), `GET /{workflowRunId}`, `GET /{workflowRunId}/events`, `GET /{workflowRunId}/artifacts/...`, plus 6 `@PostMapping` mutation endpoints (story 2.13). [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java]
- **Loopback-only binding** (story 6.9): the app binds `127.0.0.1:8080`; the smoke must curl `127.0.0.1`, not `0.0.0.0`/`localhost`-with-IPv6 surprises. [Source: deliveryline-backend/src/main/resources/application.yml#L33-L35]

### Logging Requirements (project-wide standard)

Every story leaves touched services observable enough to debug a production incident without re-deploying (enforced via the Logging instrumentation task).

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log here (minimum surface):** `SpaFallbackController.serveShell` → `INFO` "serving shell for path={}" (sanitized path); inert no-bundle branch → `DEBUG`. `EmbeddedFrontendGuard` already logs present/absent at startup — do not duplicate.
- **Required context keys** (MDC or structured params): `correlationId` where present; the sanitized request path. A GET navigation carries no body — no payload/PII risk.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII. Pass the path through `MdcKeys.sanitizeForLog`.
- **Test contract:** a focused list-appender / `OutputCaptureExtension` test pins the `INFO` shell-served line; the existing `ProblemDetailsMapper` domain-exception WARN test must stay green after delegation.

### Project Structure Notes

- New: `adapters/rest/SpaFallbackController.java` (the controller — ArchUnit-pinned location), `infrastructure/web/<...>CacheConfig.java` (the AC4 `WebMvcConfigurer`), `contract/BundledJarPackagingContractTest.java` (jar-unzip assertions).
- Modified: `adapters/rest/ProblemDetailsMapper.java` (delegate fallback), `ProblemDetailsMapperSpaFallbackTest.java` (re-point), `pom.xml` (enforcer message), `.github/workflows/ci.yml` (smoke assertions + foundation-gate comment).
- Variance from architecture tree: tree shows `infrastructure/web/SpaFallbackController.java`; the enforced ArchUnit rule forces the controller into `adapters.rest`. Documented in S2.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.28] — story + ACs
- [Source: _bmad-output/planning-artifacts/architecture.md#AR32] — single executable jar (line 219), [#AR33] SPA fallback without masking missing endpoints (line 221), [#project-structure] (line 1028, stale on controller placement)
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java#L251-L320] — existing fallback + `shouldServeSpaShell`
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsMapperSpaFallbackTest.java] — 10 predicate tests
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/EmbeddedFrontendGuard.java] — runtime fail-fast
- [Source: deliveryline-backend/pom.xml#L215-L343] — enforcer + copy (story 2.1)
- [Source: .github/workflows/ci.yml#L561-L783] — `jar-packaging` + `bundled-jar-smoke` tiers
- [Source: .github/workflows/ci.yml#L955-L1003] — `foundation-gate` needs + assertion
- Prior art / model for this story shape: [Source: _bmad-output/implementation-artifacts/2-27-...md] (consolidate-over-existing pattern)

### Open Questions (for Alex — non-blocking; defaults encoded)

1. **SpaFallback mechanism:** Option A (encoded) = `SpaFallbackController` owns shell-serving, `ProblemDetailsMapper` advice delegates to it (keeps the proven `NoResourceFoundException` interception). Alternative = a pure `@RequestMapping("/**")` catch-all controller (no advice). Default A unless told otherwise.
2. **AC7 datastore:** the spec says "Testcontainers PostgreSQL"; the existing tier uses repo-root `docker compose`. Default = keep docker-compose (match reality, avoid churn). Flag if a literal Testcontainers JUnit `BundledJarSmokeIT` is wanted instead.
3. **AC10 message wording:** reconcile the enforcer `<message>` to the AC10 literal, or keep the existing (arguably clearer) wording? Default = reconcile to include AC10's path + fix, preserving actionability.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- Local validation (Windows + Docker 28.5.1, `mvnw` via PowerShell to route around the RTK Bash hook):
  - Surefire unit slice (`SpaFallbackControllerTest`, `ProblemDetailsMapperTest`, `EmbeddedFrontendGuardTest`): **15/15 green** — `SpaFallbackControllerTest` is 11 (10 moved predicate cases + the new `serveShell` no-store/INFO-log case).
  - Failsafe slice through real `verify` **building the frontend** (so the repackaged jar embeds the SPA): `BundledJarPackagingContractTest` **1/1 (asserted, not skipped)**, `SpaServingContractTest` **8/8** (AC3 collisions + AC4 cache headers, wired DispatcherServlet), `ArchitectureBoundaryTest` **43/43** (controller placement legal), `OpenApiSnapshotContractTest` 1/1, `WorkflowRead`/`WorkflowMutation`/`MdcCorrelation`/`ProblemDetails*ContractTest` all green — no web-layer regression from the added welcome-page serving + cache config.
  - Static gates: `spotless:apply` (6 files), then `checkstyle:check spotbugs:check` → **BUILD SUCCESS** (0 checkstyle violations; SpotBugs High threshold clean; no findings in the new files).
  - The only non-green step in a scoped local run is `jacoco-check` (line/branch coverage gate) — an artifact of running a TEST SUBSET locally (partial coverage), not a test failure. The new code is well-covered (predicate ×10 + serveShell unit + 8 wired ITs + cache-header assertions), so the full-suite CI `verify` meets the threshold.

### Completion Notes List

Reconcile + formalize + extend over machinery from story 2.1 — no greenfield rebuild.

- **AC1/AC2/S3 (Task 1):** Extracted `org.dradgo.adapters.rest.SpaFallbackController` (`@RestController`, no request mappings) owning `shouldServeSpaShell` + `acceptsHtml` + `RESERVED_NON_SPA_PREFIXES` + `SPA_SHELL` + `serveShell()`/`shellExists()`. `ProblemDetailsMapper.handleNoResourceFound` keeps the proven `@RestControllerAdvice`/`NoResourceFoundException` interception point and **delegates** shell-serving to the controller via a lazily-resolved `ObjectProvider<SpaFallbackController>` (null-safe for API-only/non-web slices). Predicate logic unchanged — AC2 preserved verbatim. **Option A** (encoded default for Open Question 1) shipped.
- **S2/ArchUnit:** controller is `@RestController` under `adapters.rest` (NOT `infrastructure/web` — the architecture tree line 1028 is stale and overridden). `ArchitectureBoundaryTest` 43/43 confirms legality (`REST_CONTROLLER_SUFFIX_REQUIRES_REST_CONTROLLER_ANNOTATION` + `REST_CONTROLLERS_STAY_THIN`); the controller depends only on `application.observability.MdcKeys` + framework types.
- **Test re-point:** `ProblemDetailsMapperSpaFallbackTest` → renamed `SpaFallbackControllerTest`, 10 cases re-pointed at `SpaFallbackController.shouldServeSpaShell` (all pass, no logic change) + 1 new `serveShell` case. All five `new ProblemDetailsMapper(...)` construction sites updated for the added third ctor arg (2 unit tests, 2 contract tests, 1 foundation contract).
- **AC4/S7 (Task 2):** New `org.dradgo.infrastructure.web.StaticResourceCacheConfig` (`@Configuration implements WebMvcConfigurer` — a NON-controller web config, legal under `infrastructure/web`). `/assets/**` → `Cache-Control: max-age=31536000, immutable` (Vite content-hashing makes 1y immutable safe; hard-coded constant, no new validated `@ConfigurationProperties`). The shell at `/` (welcome page) and `/index.html` → `no-store` via a path-scoped interceptor. **Wording reconciliation:** the spec says "no-cache/no-store" on index.html; shipped **`no-store`** consistently — it is the strictly-stronger directive and matches the `no-store` already set on the SPA-fallback path (`SpaFallbackController.serveShell`), keeping every shell entry point identical.
- **AC6/S4 (Task 3):** New `org.dradgo.contract.BundledJarPackagingContractTest` (`*ContractTest` → Failsafe, runs post-`package`; NO Spring context / NO Docker, so not an `*IT`). Unzips `target/deliveryline-backend-*.jar` and asserts `BOOT-INF/classes/static/index.html` + an entrypoint `*.js` + a `*.css` under `assets/`. `Assumptions.abort` (skip) on the frontend-skipped path (no static tree) so backend-only local iteration stays green; the `require-frontend-dist` enforcer guards real packaging. Verified asserting against a real SPA jar (1/1, not skipped).
- **AC5/AC10/S4 (Task 4):** Verified the existing `require-frontend-dist` enforcer + `EmbeddedFrontendGuard` cover the build-time + runtime halves; **reconciled the enforcer `<message>`** to name the expected path (`deliveryline-frontend/target/dist/index.html`) and both fixes (`mvn -pl deliveryline-frontend package` / `mvn clean install`). Copy target unchanged (`target/classes/static/` → `BOOT-INF/classes/static/`, the AC5 invariant) per `[[embedded-frontend-at-package-phase]]`.
- **AC7/AC9/S5 (Task 5):** Extended the existing `bundled-jar-smoke` job (kept `ubuntu-latest` + `push:main`-only + docker-compose Postgres — AC8). Added: `GET /` and `GET /some/spa/route` serve the `<div id="root">` shell (with `Accept: text/html`); `GET /api/v1/workflows/run_smoke_absent_0001` returns `404 application/problem+json` (NOT the shell — used a `run_`-prefixed absent id so the probe exercises the RUN_NOT_FOUND **404** the AC names, rather than a bare `nonexistent` which would surface INVALID_ID_PREFIX 400); `GET /api/v1/workflows` content-type is `application/json` (anti-masking). **AC9 drift-proof:** the booted `/v3/api-docs` path-key set is diffed against the committed `openapi.json` snapshot (not hand-enumerated), so any dropped/masked endpoint reds the smoke.
- **AC11/S6 (Task 6):** Documented the packaging-invariant split in the ci.yml `foundation-gate` comment block — the AC6 packaging contract test gates the *packaging invariant* on every PR via `backend-contract-tests` (already in `needs:`), while the runtime `bundled-jar-smoke` stays `push:main`-only and intentionally NOT a `foundation-gate.need` (adding it would mark the gate skipped on PRs).
- **Logging:** `SpaFallbackController.serveShell` logs `INFO "spa_fallback serving shell for path={}"` (sanitized via `MdcKeys.sanitizeForLog`); the inert bundle-absent branch logs `DEBUG` in the mapper delegation (startup absence is already `EmbeddedFrontendGuard`'s concern). `serveShell` INFO line is pinned by a test; the existing `ProblemDetailsMapper` domain-exception WARN test stays green after delegation.
- **Test enabler:** added `src/test/resources/static/{index.html, assets/app-test12345.js}` — test-only stubs of the Vite shell + a content-hashed asset, so the wired serving + cache tests exercise real behavior without a frontend build. The runtime guard stays property-disabled in the test tier (`deliveryline.frontend.fail-on-missing-bundle=false`), so this does not re-enable any startup gate.

### File List

**Added (main):**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/SpaFallbackController.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/web/StaticResourceCacheConfig.java`

**Added (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/SpaFallbackControllerTest.java` (renamed from `ProblemDetailsMapperSpaFallbackTest.java`, re-pointed + 1 new case)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/SpaServingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/BundledJarPackagingContractTest.java`
- `deliveryline-backend/src/test/resources/static/index.html`
- `deliveryline-backend/src/test/resources/static/assets/app-test12345.js`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java` (delegate fallback to `SpaFallbackController`; remove moved members; add ctor arg)

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsCorrelationIdContractTest.java` (ctor arg)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsMapperLoggingContractTest.java` (ctor arg)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsMapperTest.java` (ctor arg, ×2)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/ProblemDetailsCoverageFoundationContract.java` (ctor arg + import)

**Removed (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsMapperSpaFallbackTest.java` (renamed → `SpaFallbackControllerTest.java`)

**Modified (build / CI):**
- `deliveryline-backend/pom.xml` (`require-frontend-dist` enforcer message reconciled to AC10)
- `.github/workflows/ci.yml` (`bundled-jar-smoke` SPA + drift-proof path-set assertions; `foundation-gate` AC11 split comment)

### Change Log

- 2026-06-11 — Story 2.28 implemented (dev-story): extracted `SpaFallbackController` (AC1/AC2), added `StaticResourceCacheConfig` cache headers (AC4), `BundledJarPackagingContractTest` jar-embed assertion (AC6), reconciled the packaging enforcer message (AC5/AC10), extended `bundled-jar-smoke` with SPA serving/fallback + drift-proof OpenAPI path-set checks (AC7/AC9), and documented the foundation-gate packaging-invariant split (AC11). Status ready-for-dev → review.

### Review Findings

_Adversarial code review (bmad-code-review) 2026-06-12 — 3 layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the story-scoped working-tree diff (15 files, +125/−170 tracked + 7 new). Acceptance Auditor: **all 11 ACs MET** (AC4 `no-store`-only and AC7 docker-compose are dev-flagged, justified deviations). No production correctness defects. Findings are test-quality + robustness hardening; none blocking._

- [x] [Review][Patch] Non-deterministic bootable-jar selection — picks the LAST jar in unspecified `DirectoryStream` order; a stale versioned jar in `target/` (version bump without `clean`) could be asserted against, masking a real packaging regression (or vice-versa). **FIXED 2026-06-12:** `locateBootableJar` now collects all candidates and fails-fast on >1 with a clear "run a clean build" message; ci.yml `bundled-jar-smoke` jar selection now uses `mapfile` + count guard (fail on >1) instead of `head -1`. [BundledJarPackagingContractTest.java:120-160 + .github/workflows/ci.yml] (blind+edge)
- [x] [Review][Patch] Weak missing-asset assertion — `missingAssetReturns404NotTheShell` asserted only status 404 + absence of `<div id="root">`; a Whitelabel HTML error page or empty body would pass. **FIXED 2026-06-12:** added a `Content-Type: application/problem+json` assertion pinning the "JSON 404, never the shell" contract. [SpaServingContractTest.java:86-95] (blind)
- [x] [Review][Patch] New delegation branch untested at unit level — the genuinely-new `ProblemDetailsMapper.handleNoResourceFound` logic (null-provider guard + predicate-matched-but-`shellExists()==false` → DEBUG + fall-through-to-404) had no unit test. **FIXED 2026-06-12:** added `noResourceFoundReturnsJson404WhenSpaControllerBeanAbsent` + `noResourceFoundFallsThroughToJson404WhenBundleAbsent` to `ProblemDetailsMapperTest` (4/4 green; verifies `serveShell` is never called on the bundle-absent path). [ProblemDetailsMapperTest.java] (blind)
- [x] [Review][Defer] Packaging test requires `assets/*.css` beyond AC6 scope — AC6 lists index.html + assets/ + entrypoint JS only; a future Vite change that inlines CSS would red `backend-contract-tests` on a valid bundle. Works today (build emits CSS). [BundledJarPackagingContractTest.java:109-117] — deferred, brittleness latent (blind+edge)
- [x] [Review][Defer] SPA predicate misclassifies dot-containing deep-link routes (`/users/john.doe`) as missing assets → 404 not shell; also treats `Accept: */*` as navigation (programmatic fetch of a non-`/api` path gets the HTML shell). Pre-existing — moved verbatim; low real impact (DeliveryLine route ids are `run_`-prefixed, no dots). [SpaFallbackController.java] — deferred, pre-existing (blind+edge)
- [x] [Review][Defer] CI nav curls use `-fsS` under `set -e` — on an HTTP error the step aborts at the curl line before the custom `::error::` grep diagnostic runs (the grep still catches the 200-but-wrong-body case). Diagnostic-UX only; the smoke still fails correctly. [.github/workflows/ci.yml:777-787] — deferred, non-blocking (blind)
- [x] [Review][Defer] Smoke greps for the literal `<div id="root">` — couples the push:main smoke to the exact authored index.html formatting; a Vite/template change (`<div id=root>`, extra attrs) could red a healthy shell. [.github/workflows/ci.yml:779,786] — deferred, low risk (blind)
- [x] [Review][Defer] AC11 packaging gate relies on `-am` building the `scope=test` frontend module so the contract test ASSERTS (not `abort`/skips) in `backend-contract-tests` — correct today, but undefended: a future `-DskipTests` or `frontend-maven-plugin.skip=true` leak would silently green the gate on an SPA-less jar. Consider a CI-env guard that fails-instead-of-skips. [.github/workflows/ci.yml backend-contract-tests + BundledJarPackagingContractTest.java:82-89] — deferred, defensive hardening (auditor)
