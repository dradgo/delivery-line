# Story 3d.6: Read-Only Diagnostic Console into a Running Runner (Security-Gated)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want a read-only console attached to a running runner container,
so that I can diagnose a stuck or misbehaving step in the moment, without the ability to mutate the run or the workspace.

## Context

This is the **read-only diagnostic console** slice of Epic 3d (per-step execution control), implementing **PRD FR68** and governed by **ADR 0025** (security-review-gated, mirrors the ADR 0013 sign-off pattern). It is the SECOND surface that reaches *inside* the live runner sandbox — story 3d-5 (live logs) was the first — and the ADR records exactly how far the read-only posture is narrowed and the threat model that bounds it.

**This story builds almost entirely on the 3d-5 (done) substrate.** 3d-5 stood up the FIRST streaming surface (SSE), the bounded-executor + leak-safe `SubscriptionGate` controller pattern, the project-owned streaming port (no docker-java leak), the per-profile live/no-op adapter split, server-side allowed-action gating over an `?actorRole=` query param, and the localhost-only inheritance. **3d-6 mirrors that pattern for a console attach instead of a log follow** — it is a near-symmetric twin of 3d-5 with three deltas: (1) it attaches to the container's live **pty/stdio** (`attachContainerCmd`) rather than following its demuxed log stream (`logContainerCmd`); (2) it is **LIVE-ONLY** (no finished-mode fallback — there is no console into an absent container); (3) it **appends governed session events** on open/close (3d-5 streamed without recording any event).

Done substrate this story reuses (do NOT re-derive):

- **Story 3d-5 (done)** — `RunnerLogStreamController` (SSE; bounded daemon executor sizing 4, 30-min `STREAM_TIMEOUT_MS`, race-safe `SubscriptionGate`, `emitter.onCompletion/onTimeout/onError` → close), `SseLogStreamSink` (synchronized `dead`-guard send), `RunnerLogStreamPort` + `DockerRunnerLogStreamAdapter` (`@Profile("runners.docker")`) + `NoLiveRunnerLogStreamAdapter` (`@Profile("!runners.docker")`), `StepLogStreamService` + `LogStreamSink`. Gateway `followContainerLogs(containerId, RawLogLineSink, onEnd) -> AutoCloseable` with `LineBuffer` (byte-buffered UTF-8 across frames). **Copy this shape; do not invent a new one.**
- **Story 3.1/3.2 (done)** — `DockerRunnerAdapter` tracks `rex → containerId` in `ConcurrentMap<String,String> rexIdToContainerId`; `DefaultDockerEngineGateway.findContainerIdByRunnerExecutionId(rex)` probes the label `deliveryline.runnerExecutionId={rex}` (running-first, newest-first). `DefaultDockerEngineGateway` is the **ONLY** docker-java importer (ArchUnit pinned).
- **Story 3.6 (done) + ADR 0003** — the runner container runs with `--sandbox read-only` (read-only workspace mount, no-leak). This is what makes a console **physically** non-mutating at the container level.
- **Story 2.14 (done)** — `AllowedAction` registry + `RegistryContractTest` drift gate vs `contracts/frontend/allowed-actions.placeholder.json`; the state×role matrix lives ONLY in `WorkflowInspectionService.computeActionMatrix`.
- **Story 1.18 (done)** — `WorkflowEventWritePort.append(WorkflowEventRecord)` / `RunnerExecutionEventPort.append(...)` are the governed-event seams; `WorkflowEventDetailKeys` is the single source of truth for `details` keys.
- **Story 1.10 (done)** — `RedactionPolicyService.redact(payload, classificationValue)` for **best-effort** streaming redaction (per ADR 0025 D3; authoritative redaction remains the 3.6 persisted post-hoc scan, which the console does NOT touch).

**What this story IS:** ONE backend console-attach streaming endpoint + ONE frontend `Read-only Diagnostic Console` that attaches **read-only** to a **live** runner container's pty and streams its output to the single localhost operator. Plus: two new governed event types (`console.opened` / `console.closed`), one new allowed-action (`open_diagnostic_console`), and a recorded **security-review sign-off** (AC1).

**What this story is NOT:** NOT a write-capable shell, NOT a host shell, NOT an attach to a finished/absent container (ADR 0025 D1). NOT a new persisted I/O store — only governed session *metadata* is persisted; console I/O is **not** durably stored (ADR 0025 D4). NOT a redaction-guarantee upgrade (best-effort live redaction only; the persisted post-hoc 3.6 scan remains authoritative and is untouched). NOT remote/multi-user access (localhost-only). NOT a websocket/interactive-input surface in this story — see **Design Decision DD-1** (the read-only design is a streaming pty with **input disabled**, which AC6 explicitly permits).

**Dependency note:** depends solely on the **done** 3d-5 + 3.1/3.2/3.6 substrate, so it can proceed independently of its sibling 3d stories (3d-1..3d-4, 3d-7, 3d-8).

## Acceptance Criteria

1. **Security-review sign-off gate (ADR 0025, AC1).** This story does **not** close until a security review signs off the threat model (read-only + live-only + governed-history + localhost-only). Sign-off is recorded in the story **Completion Notes + PR description** (mirrors the ADR 0013 gate; **no CI job exists** for it). ADR 0025 status is moved from *Proposed* to *Accepted* as part of the sign-off.
2. **Live-only, read-only attach.** Given a **running** runner execution only, a backend endpoint opens a **read-only**, non-mutating console attached to that container; there is **no** write to the workspace, **no** host shell, and **no** attach to a finished/absent container (the endpoint rejects with a typed `console-not-live` end/error and never engages an attach).
3. **Governed session history (ADR 0025 D2).** Opening a console appends a governed `console.opened` event (operator identity, runner-execution id, workflow-run id, open timestamp); closing it appends `console.closed` (close timestamp, reason). The session is first-class audit. Console **I/O is not durably stored** — only session metadata (the two events).
4. **Allowed-action gated (story 2.14).** Console access is gated by a backend-reported action `open_diagnostic_console`, available **only** in states where a runner execution can be live (`EXECUTING`), enforced **server-side** on the endpoint; a NEW `AllowedAction` value is added and mirrored into the frontend placeholder per the drift test.
5. **Localhost-only posture.** The console is served only over the existing localhost binding (`server.address=127.0.0.1` + `RestBindingGuard`) to the single local operator; remote/multi-user access remains out of scope — add NO new binding and assert the posture in a test.
6. **UI Read-only Diagnostic Console.** It renders a terminal clearly badged **read-only**, with **input disabled** (a pure streaming pty — no input channel is wired to the backend), and is keyboard-operable with explicit labels (WCAG 2.1 AA; axe zero `wcag2aa` violations). Session open/close is announced via an aria-live region with a **color-independent** state signifier (icon + label, not color alone).
7. **Redaction posture unchanged for export.** The same in-the-moment posture as the live log stream applies (ADR 0025 threat model): best-effort streaming redaction is applied where feasible, but export/shareable guarantees are unaffected because nothing the console shows changes persisted/exported content (the console never writes to `runner-logs/` or mutates `runner_executions`).
8. **Tests.** Coverage asserts: console attaches **only** to a live execution (rejected for finished/absent → `console-not-live`); read-only enforcement (no mutation path — no write to workspace/store, input channel never wired to docker stdin); session open/close appends governed `console.opened`/`console.closed` events; allowed-action gating (server-side denial when `open_diagnostic_console` absent); localhost-only enforcement; axe a11y on the UI; registry/CHECK/fixture/OpenAPI drift all pass; and the security-review sign-off is recorded (AC1).

## Tasks / Subtasks

- [x] **Task 0 — Security review + ADR 0025 sign-off (AC1)** — HARD GATE; the story cannot close without it
  - [x] Produce the threat-model write-up for the console: read-only (input disabled), live-only, governed-history-recorded, localhost-only, best-effort live redaction with the residual "secret may flash to the local operator" risk accepted (per ADR 0025 §Threat model). Request a security review (mirror the ADR 0013 sign-off flow — there is **no CI job**; sign-off is a recorded artifact).
  - [x] On sign-off: move `docs/adr/0025-live-observability-and-readonly-console.md` Status `Proposed` → `Accepted (signed off <date>, <reviewer>)`; record the sign-off in this story's Completion Notes **and** the PR description.
  - [x] If the security review constrains the design (e.g. demands a curated read-only command set, or forbids `attach` to the main pty), reconcile DD-1/DD-2 before wiring.
- [x] **Task 1 — Console-attach SPI port (AC2)** — keep docker-java behind the gateway (ArchUnit `application → adapters` forbidden; memory `[[application-cannot-import-adapters]]`)
  - [x] NEW port `org.dradgo.application.runner.spi.RunnerConsoleStreamPort`: `ConsoleSubscription attachConsole(String runnerExecutionId, RawConsoleSink onChunk, Runnable onEnd)`. `RawConsoleSink` = `@FunctionalInterface void accept(String stream, String rawChunk)` (`stream` = `"stdout"`/`"stderr"`); `ConsoleSubscription extends AutoCloseable` with `boolean isLive()` + `static ConsoleSubscription notLive()`. **Mirror `RunnerLogStreamPort` exactly** — project-owned records only, NO docker-java type crosses the port.
  - [x] Guard the rex arg with `PublicIdPrefixes.require(rex, RUNNER_EXECUTION)`. Return `notLive()` when no live container exists for the rex, so the application layer rejects rather than attaching (NO finished-mode fallback — DD-3).
- [x] **Task 2 — Docker gateway attach capability + adapter (AC2)** — memory `[[docker-adapter-ctor-dep-fans-out]]`
  - [x] Add `AutoCloseable attachContainerConsole(String containerId, RunnerConsoleStreamPort.RawConsoleSink onChunk, Runnable onEnd)` to `DockerEngineGateway` + impl in `DefaultDockerEngineGateway` (the ONLY docker-java importer). Use docker-java 3.7.1 `attachContainerCmd(containerId).withStdOut(true).withStdErr(true).withFollowStream(true)` — **do NOT** call `.withStdIn(...)` (input is never attached; this is the read-only guarantee at the docker layer, DD-1). Wrap with a `ResultCallback.Adapter<Frame>` splitting frames into chunks (reuse the 3d-5 `LineBuffer` byte-buffered-UTF-8 helper to avoid mojibake across frames) and call `onChunk`/`onEnd`. The returned `AutoCloseable.close()` MUST close the docker callback + cmd (no leaked attach threads); wrap in best-effort try/catch so a failure NEVER throws into the SSE thread.
  - [x] NEW adapter `org.dradgo.adapters.runner.docker.DockerRunnerConsoleStreamAdapter` (`@Profile("runners.docker")`) implementing `RunnerConsoleStreamPort`: resolve `containerId` via `gateway.findContainerIdByRunnerExecutionId(rex)`; `notLive()` if absent; else `gateway.attachContainerConsole(...)` wrapped in a live `ConsoleSubscription`. **Prefer a dedicated adapter class** (do NOT add ctor deps to `DockerRunnerAdapter` — Trap T7 / memory `[[docker-adapter-ctor-dep-fans-out]]`).
  - [x] NEW `org.dradgo.adapters.runner.NoLiveRunnerConsoleStreamAdapter` (`@Profile("!runners.docker")`) → always `notLive()` (mock profile: no live container; deterministic).
- [x] **Task 3 — Application orchestration service (AC2, AC3, AC7)**
  - [x] NEW `org.dradgo.application.runner.DiagnosticConsoleService` (`@Service`). Inject `RunnerConsoleStreamPort`, the runner-execution read port (status/liveness), `RedactionPolicyService`, the governed-event seam (`RunnerExecutionEventPort` or `WorkflowEventWritePort`), a `WorkflowInspectionService` seam to resolve `findLatestRunnerExecutionId(runId)`, and the actor/correlation seam.
  - [x] Define an application-side sink `ConsoleStreamSink { void onChunk(String stream, String redactedChunk, long seq); void onStatus(String phase, String rex); void onEnd(String reason); void onError(String reason); }` — the controller implements it over `SseEmitter` (keeps `SseEmitter` out of the application layer; mirror `LogStreamSink`).
  - [x] `AutoCloseable openConsole(String workflowRunId, String actorRole, ConsoleStreamSink sink)`: resolve latest rex; if none OR status is terminal (`COMPLETED`/`FAILED`/`TIMED_OUT`/`ORPHANED`/`CANCELLED_FOR_TAKEOVER`/`AWAITING_MANUAL`) → `sink.onError("console-not-live")` + `sink.onEnd("not-live")`, append NO event, return no-op closeable (DD-3). If live (`PENDING`/`RUNNING`/`QUEUED`) → `attachConsole`; if the subscription returns `!isLive()` → same `console-not-live` rejection. On a live attach: **append `console.opened`**, apply **best-effort** per-chunk redaction (`redact(rawChunk, DataClassification.LOCAL_ONLY.value()).sanitizedText()`) before `sink.onChunk`, and return an `AutoCloseable` that on close appends **`console.closed`** then closes the subscription.
  - [x] **Raw chunks stay method-local** (lambda params), redacted before reaching the sink — NO field stores a raw chunk (keeps the 3.6 raw-output boundary intact; Trap T1). Wrap each `redact(...)` in try/catch → emit a safe `[REDACTION_ERROR]` placeholder (never the raw chunk) and continue (3d-5 review-finding parity).
  - [x] Governed-event detail keys: carry only the **already-allow-listed** `runnerExecutionId` + `workflowRunId` on both events (DD-4 — avoids touching `WorkflowEventDetailKeys`). Open/close pairing for the single-operator MVP is by rex + timestamp ordering.
- [x] **Task 4 — Console SSE REST endpoint (AC2, AC4, AC5)** — twin of `RunnerLogStreamController`
  - [x] NEW `org.dradgo.adapters.rest.RunnerDiagnosticConsoleController` (`@RestController`; ArchUnit pins `*Controller` to `adapters.rest`). `GET /api/v1/workflows/{workflowRunId}/diagnostic-console/stream` produces `text/event-stream`, returns `SseEmitter`. SSE events: `console` `{stream,chunk,seq}`, `status` `{phase,rex}`, `end` `{reason}`, `error` `{reason}`.
  - [x] **Server-side allowed-action enforcement (Trap T5):** before opening, compute the run's allowed actions (reuse the `WorkflowInspectionService.getAllowedActions(runId, role)` path used by `/allowed-actions`) and reject with `error`+`end` (`open_diagnostic_console_not_allowed`) when `open_diagnostic_console` is absent — the attach/append is NEVER engaged on denial. Resolve the actor via the same `?actorRole=` query param + resolver seam 3d-5 uses (EventSource cannot set headers).
  - [x] **Reuse the 3d-5 resource-safety pattern verbatim:** bounded daemon `ExecutorService` (small pool — single local operator), `SseEmitter` with a max-duration cap, race-safe `SubscriptionGate` installed/closed on `emitter.onCompletion/onTimeout/onError`, `@PreDestroy` shutdown, MDC correlation restored inside the executor task. NEW `SseConsoleStreamSink` mirroring `SseLogStreamSink` (synchronized `dead`-guard send).
  - [x] **Localhost-only** inherited from `server.address=127.0.0.1` + `RestBindingGuard` (story 6.9) — add NO new binding; assert the endpoint introduces no binding of its own (mirror 3d-5's `streamEndpointAddsNoOwnBindingAndInheritsTheLoopbackGuard`).
- [x] **Task 5 — `open_diagnostic_console` allowed-action (AC4)** — registry recipe `[[docs/patterns/registry-recipe.md §2]]`
  - [x] Add `OPEN_DIAGNOSTIC_CONSOLE("open_diagnostic_console")` to `org.dradgo.domain.registry.AllowedAction`.
  - [x] Wire it into `WorkflowInspectionService.computeActionMatrix` for **`EXECUTING` only**, gated to `ROLE_WORKFLOW_OWNER` (the run owner / local operator) — split the EXECUTING branch by role like FAILED/WAITING_FOR_MANUAL_EXECUTION do (owner gets `{view_only, await_outcome, view_runner_logs, open_diagnostic_console}`; other roles keep `{view_only, await_outcome, view_runner_logs}`). Do NOT add it to any non-EXECUTING state (the container is only live during EXECUTING; the server still re-checks liveness at attach time).
  - [x] Mirror `"open_diagnostic_console"` into `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` so `RegistryContractTest#allowedActionsStayAlignedWithFrontendPlaceholder` passes.
  - [x] If `getAllowedActions` / `AllowedActionsResponse` carries a `@Schema(allowableValues=...)` enumerating the actions, add the new value there too (memory `[[story-3b-4-developer-role-wiring-reconciliations]]`) and regen OpenAPI.
- [x] **Task 6 — `console.opened` / `console.closed` governed event types (AC3)** — memory `[[new-workfloweventtype-fixture-sites]]` + `[[docs/patterns/registry-recipe.md §1]]`
  - [x] Add `CONSOLE_OPENED("console.opened")` and `CONSOLE_CLOSED("console.closed")` to `org.dradgo.domain.registry.WorkflowEventType`.
  - [x] **Two fixture sites:** mirror both wire values into (a) `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` (`workflowEventTypes` array) and (b) the fixture-event-stream enum/site the registry recipe names — these console events do NOT belong in any existing scenario stream fixture (happy-path etc.), so only the registry mirror + enum site need them. Run the drift test to confirm `RegistryContractTest` is green.
  - [x] If `WorkflowEventsResponse` (or another DTO) `@Schema(allowableValues=...)` enumerates event-type wire values, add both there; the OpenAPI enum is **NOT** auto-derived — regen byte-identically.
- [x] **Task 7 — OpenAPI + client regen (AC4)** — memory `[[openapi-regen-frontend-client-drift-cascade]]`
  - [x] Annotate `RunnerDiagnosticConsoleController` so the SSE endpoint appears in `openapi.json` with a description documenting the read-only/live-only/localhost-only/governed-history/best-effort-redaction posture (leave a pointer for story 3d-10's walkthrough). Run `scripts/regen-openapi.sh` then `npm run generate-api` and commit `schema.d.ts` so `check:api` stays green. The frontend consumes the stream via a hand-written `EventSource` (NOT the generated `openapi-fetch` client), but the endpoint must still round-trip through OpenAPI to keep the drift gate green.
- [x] **Task 8 — Frontend Read-only Diagnostic Console (AC6)**
  - [x] NEW hand-written hook `src/features/workflows/hooks/useDiagnosticConsole.ts` opening `new EventSource('/api/v1/workflows/${workflowRunId}/diagnostic-console/stream?actorRole=${role}')`, accumulating `console` events (apply a `MAX_RETAINED` ring-buffer cap like 3d-5's `useRunnerLogStream`), surfacing `status`/`end`/`error`, validating event field types (skip malformed — 3d-5 review parity), and `close()`-ing on unmount / `end` / `error` so the browser never auto-reconnects a completed session. **NO input is wired** — EventSource is receive-only by nature; do NOT add a websocket/input channel (DD-1).
  - [x] NEW `src/features/workflows/components/ReadOnlyDiagnosticConsole.tsx`: a terminal-styled scroll region (auto-scroll-pausable) **clearly badged "Read-only"**, input disabled (no `<input>`/`<textarea>` posting to the backend), color-independent live/ended/error mode chip via `StateSignifierChip`, and aria-live announcements via `useLiveAnnouncement`. Add announcement strings to `src/lib/a11y/announcements.ts` (`consoleSessionStarted`, `consoleSessionEnded`, `consoleSessionError`). Reuse the `StepExecutionLogViewer` rendering approach — do NOT add `xterm.js` or any net-new terminal dependency.
  - [x] Slot the console into the run-detail route `src/routes/workflows/$workflowRunId/index.tsx` (near `StepExecutionLogViewer`). **Gate** rendering on `useAllowedActions(workflowRunId, actorRole).actions.includes('open_diagnostic_console')` — gating flows through `useAllowedActions` ONLY (eslint `local-rules/no-role-based-action-gating`); do NOT infer from role.
- [x] **Task 9 — Frontend tests (AC6, AC8)**
  - [x] Vitest: console renders streamed chunks (mock EventSource); live-region announcement asserted via `waitFor` (memory `[[livesnnouncement-defers-one-commit-test-flake]]` — never assert synchronously); gate hides the console when `open_diagnostic_console` absent; the rendered terminal exposes NO input control posting to the backend (read-only assertion); axe zero `wcag2aa` violations.
  - [x] Playwright e2e: open an EXECUTING run, console visible when action present, chunks render, read-only badge present; JSON fixtures with `with { type: 'json' }` (memory `[[playwright-e2e-harness-wiring]]`). Run `prettier --write` before push (memory `[[prettier-gate-cascades-ci]]`).
- [x] **Task 10 — Backend tests (AC2, AC3, AC5, AC8)**
  - [x] Live attach: a `DiagnosticConsoleService` unit test with a fake `RunnerConsoleStreamPort` drives chunks through best-effort redaction → sink (assert a deliberately-leaky chunk is redacted in the streamed output).
  - [x] **Live-only rejection (DD-3):** terminal/absent rex → `console-not-live` end/error, NO attach engaged, NO `console.opened` appended.
  - [x] **Governed events (AC3):** a live session appends `console.opened` on open and `console.closed` on close (assert via a fake/spy `WorkflowEventWritePort`/`RunnerExecutionEventPort`); assert only allow-listed detail keys (`runnerExecutionId`, `workflowRunId`) are carried.
  - [x] **Read-only / no-mutation (AC7):** assert the path performs NO write to `runner-logs/`, NO `runner_executions` mutation, and that the docker attach is opened WITHOUT stdin (no input path) — e.g. assert the gateway attach call never wires stdin (verify the adapter does not call `.withStdIn`).
  - [x] Localhost-only posture assertion (reuse the 6.9 `RestBindingGuard` test pattern + the 3d-5 no-own-binding assertion); allowed-action gating (endpoint denies when `open_diagnostic_console` absent for the state/role); correlation-log + adversarial no-secret-in-logs sweep (AC8 / logging task) via `OutputCaptureExtension`/list-appender.
  - [x] `RegistryContractTest` green after the placeholder + event-type-fixture mirrors (Tasks 5/6); profile-wiring slice test (exactly one `RunnerConsoleStreamPort` bean per profile); `ArchitectureBoundaryTest` green (docker-java stays behind the gateway).
- [x] **Task 11 — Endpoint-contract documentation seam (AC1, AC7)**
  - [x] Document the read-only/live-only/localhost-only/governed-history/best-effort-redaction posture in the OpenAPI description and leave a pointer for story 3d-10's `per-step-execution-control-walkthrough.md` (console-safety section). State that nothing the console shows changes persisted/exported content (ADR 0025 posture).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at: `RunnerDiagnosticConsoleController` console open/deny/timeout/disconnect; `DiagnosticConsoleService` live-vs-not-live decision, session open/close (with rex + reason), best-effort redaction applied (COUNT only); `RunnerConsoleStreamPort` adapter attach start/stop + attach failure (`WARN`).
  - [x] Parameterized logging (`log.info("...", arg1, arg2)`) — never concatenation.
  - [x] Levels: `INFO` for console open/close/decision + governed-event append, `WARN` for attach failure / not-live rejection / client-disconnect mid-session, `ERROR` only for unhandled failures.
  - [x] Carry `correlationId`, `workflowRunId`, `runnerExecutionId` via MDC; **NEVER** log streamed console content, secret values, tokens, or payload bytes (log redaction COUNT only — AC7).
  - [x] ≥1 focused test per new branch asserting the expected log line/level + the adversarial no-secret-in-logs sweep.

## Dev Notes

### Architecture & insertion points (verified against live code)

**Backend**

- **`AllowedAction`** — `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (14 values today; add `OPEN_DIAGNOSTIC_CONSOLE`). Matrix in `application/workflow/WorkflowInspectionService.java#computeActionMatrix` (lines 620-718) — the SOLE state×role source (ArchUnit-pinned). The `EXECUTING` branch is currently role-agnostic at lines 653-657 (`{view_only, await_outcome, view_runner_logs}`); split it by `ROLE_WORKFLOW_OWNER` to additively add `open_diagnostic_console` for the owner only.
- **Allowed-action drift gate** — `src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (14-entry `allowedActions` array; add `open_diagnostic_console`) vs `RegistryContractTest#allowedActionsStayAlignedWithFrontendPlaceholder`.
- **`WorkflowEventType`** — `domain/registry/WorkflowEventType.java` (31 values today; add `CONSOLE_OPENED` + `CONSOLE_CLOSED`). Fixture site A: `src/test/resources/contracts/events/workflow-event-types.fixture.json` (`workflowEventTypes` array, 31 entries → 33). Fixture site B + any `@Schema(allowableValues)` on `WorkflowEventsResponse.java` (lines 63/112/146/162/185) per the registry recipe.
- **Governed-event append** — `application/workflow/spi/WorkflowEventWritePort.append(WorkflowEventRecord)` (full record: publicId, workflowRunPublicId, eventType, prior/resulting state (both null for a console session — no state change), actorIdentity, actorType, reason, failureCategory(null), interventionMarker(false), createdAt, details). Or the runner-scoped helper `application/runner/spi/RunnerExecutionEventPort.append(workflowRunPublicId, eventType, ActorContext, reason, failureCategory, createdAt, details)` → returns the `evt_` public id. Detail map: carry only `WorkflowEventDetailKeys.RUNNER_EXECUTION_ID` + `WORKFLOW_RUN_ID` (both already allow-listed — DD-4, no `WorkflowEventDetailKeys` change). Real call-site exemplars: `RunnerBroker.appendRunnerDispatchedEventIfDocker` (~2675), `RecoveryService` (~570), `IntegrationLinkService` (~897).
- **Liveness** — `domain/registry/RunnerExecutionStatus.java`: live = `PENDING`/`RUNNING`/`QUEUED`; terminal/non-attachable = `COMPLETED`/`FAILED`/`TIMED_OUT`/`ORPHANED`/`CANCELLED_FOR_TAKEOVER`/`AWAITING_MANUAL`. Read via the runner-execution read port (`findByWorkflowRunPublicIdAndStatusIn` / `findByPublicId` — verify the exact signature against the live port, as 3d-5 did).
- **Docker attach** — `adapters/runner/docker/DefaultDockerEngineGateway.java` is the ONLY docker-java importer (ArchUnit `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`, `ArchitectureRuleCatalog` ~979). docker-java **3.7.1** supports `attachContainerCmd` / `execCreateCmd` / `execStartCmd` (none used yet). Mirror the 3d-5 `followContainerLogs(containerId, RawLogLineSink, onEnd) -> AutoCloseable` (~213) — its `ResultCallback.Adapter<Frame>` + byte-buffered `LineBuffer` (~288) + close-on-`AutoCloseable.close()` shape. `rex → containerId` via `DockerRunnerAdapter.rexIdToContainerId` (~90) + `findContainerIdByRunnerExecutionId` (label `deliveryline.runnerExecutionId`, ~187).
- **3d-5 twins to copy** — `application/runner/spi/RunnerLogStreamPort.java` (port shape), `adapters/runner/docker/DockerRunnerLogStreamAdapter.java` + `adapters/runner/NoLiveRunnerLogStreamAdapter.java` (profile split), `application/workflow/StepLogStreamService.java` + `LogStreamSink.java` (orchestration + sink abstraction), `adapters/rest/RunnerLogStreamController.java` (`SseEmitter`, bounded `streamExecutor` 4-thread daemon pool, `STREAM_TIMEOUT_MS` 30-min, `SubscriptionGate`, `@PreDestroy`, `?actorRole=` gate) + `adapters/rest/SseLogStreamSink.java` (synchronized `dead`-guard send).
- **Redaction (best-effort, live)** — `application/security/RedactionPolicyService.redact(String, String)` → `RedactionResult.sanitizedText()`; pass `DataClassification.LOCAL_ONLY.value()`. Count via `[REDACTED_` substring (no credential regex outside `application.security`). Wrap each call in try/catch → `[REDACTION_ERROR]` placeholder, never the raw chunk.
- **REST/localhost** — `application.yml` `server.address: 127.0.0.1` + `RestBindingGuard` (`DOCTOR_REST_BIND_UNAVAILABLE`); a dedicated `RunnerDiagnosticConsoleController` is cleaner than overloading `WorkflowController` (SSE lifecycle isolation), matching the 3d-5 choice.

**Frontend**

- **Run-detail route** — `src/routes/workflows/$workflowRunId/index.tsx`; slot the console near `StepExecutionLogViewer` (the 3d-5 viewer). Gate on `useAllowedActions(...).actions.includes('open_diagnostic_console')` ONLY (eslint `local-rules/no-role-based-action-gating`).
- **EventSource hook** — model `src/features/workflows/hooks/useDiagnosticConsole.ts` on the 3d-5 `useRunnerLogStream.ts` (the first EventSource; ring-buffer cap, field-type validation, close-on-end/error). Receive-only — no input channel.
- **Live-region / signifiers** — `src/lib/a11y/useLiveAnnouncement.ts` (defers ONE commit — assert with `waitFor`); vocabulary `src/lib/a11y/announcements.ts`; color-independent `src/lib/state-signifiers.ts` (`StateSignifierChip`). Pattern: `<div role="status" aria-live="polite" data-testid="...">{announcement}</div>`.
- **Tests** — Vitest + RTL (jsdom) + MSW + vitest-axe; Playwright in `e2e/`. See `docs/testing/frontend-test-patterns.md` (§1 live-region flake).

### Key design decisions

- **DD-1 — Read-only = streaming pty with input disabled (AC6, ADR 0025 D1).** This story implements the console as an **output-only** attach: the backend `attachContainerCmd` is opened WITHOUT stdin, and the frontend EventSource transport is receive-only — **no input channel is wired end-to-end**. This satisfies AC6's explicit "purely a streaming pty with input disabled per the read-only design" clause, makes non-mutation provable (no input path exists), reuses the entire 3d-5 SSE substrate, and is the cleanest thing to put in front of a security review. An interactive (input-forwarded) read-only console — non-mutation guaranteed by the container's `--sandbox read-only` FS + exec-into-runner-only + curated commands — was **considered and deferred**: it requires a net-new bidirectional websocket surface and a far larger threat model, and is **not** in scope here. The security review (Task 0) ratifies the input-disabled design; any move toward interactive input is a future story.
- **DD-2 — Attach the container pty, not the logs.** Use `attachContainerCmd` (the live container stdio/pty) rather than `logContainerCmd` (3d-5's demuxed log follow). This is the *distinct* console surface: it shows the running agent's live terminal, separate from the captured log stream. (If the security review prefers a curated read-only inspection `exec` instead of a raw pty attach, switch the gateway call to `execCreateCmd`+`execStartCmd` of a fixed read-only command — still input-disabled.)
- **DD-3 — LIVE-ONLY, no finished-mode fallback.** Unlike 3d-5 (which falls back to the persisted redacted log for a finished execution), the console rejects any non-live/absent rex with `console-not-live`. There is no console into an absent container (ADR 0025 D1). The finished-state diagnostic surface is the 3d-5 log viewer.
- **DD-4 — Governed events carry only allow-listed keys.** `console.opened`/`console.closed` carry only `runnerExecutionId` + `workflowRunId` (already in `WorkflowEventDetailKeys.ALLOW_LISTED_KEYS`), so the `WorkflowEventDetailKeys` 4-site fan-out (constant + ALLOW_LISTED_KEYS + `workflow-history.v1.schema.json` + contract test) is untouched. The open/close timestamps ride the event's own `createdAt`; pairing for the single-operator MVP is by rex + ordering. (If multi-session disambiguation is later needed, add a `consoleSessionId` key via the full 4-site recipe.)
- **DD-5 — Governed-event append from the close path needs care.** `console.closed` is appended from the controller's terminal callback (`onCompletion`/`onTimeout`/`onError`) — NOT the request thread. If the append joins an already-committed/absent transaction it can fail; mirror how other off-request-thread appends mark their own transaction (memory `[[post-commit-hook-needs-requires-new]]` — `@Transactional(REQUIRES_NEW)` where applicable). A Testcontainers IT is the only thing that catches this; cover it if the append path isn't already self-transactional.

### What NOT to add (scope guard)

- NO websocket / input channel / interactive shell (DD-1; ADR 0025 D1) — input-disabled streaming only.
- NO host shell, NO write to the workspace, NO attach to a finished/absent container (ADR 0025 D1; DD-3).
- NO new persisted console-I/O store / table / Flyway migration (ADR 0025 D4) — only the two governed events are persisted.
- NO change to the 3.6 raw-output boundary or the persisted/exported log path (AC7) — the console reads only the live Docker attach; declare no field holding a raw chunk.
- NO new `WorkflowEventDetailKeys` (DD-4); NO new `DomainErrorCode` unless genuinely required — prefer the existing forbidden-action denial + SSE `error`/`end` events (mirror 3d-5). If one is unavoidable, follow the three-site recipe (memory `[[new-domainerrorcode-three-sites]]`).
- NO `xterm.js` or net-new terminal dependency on the frontend — reuse the `StepExecutionLogViewer` rendering approach.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`.
- **Where:** controller console open/deny/timeout/disconnect (`INFO`/`WARN`); `DiagnosticConsoleService` live-vs-not-live decision + session open/close + governed-event append + best-effort redaction COUNT (`INFO`); adapter attach start/stop/fail (`INFO`/`WARN`).
- **Context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId` (MDC).
- **Forbidden in output:** streamed console content, secret values, tokens, payload bytes — log redaction COUNT only. Adversarial no-secret-in-logs sweep test required.

### Project Structure Notes

- `RunnerConsoleStreamPort` in `application.runner.spi`; impls in `adapters.runner.docker` (`DockerRunnerConsoleStreamAdapter`) + `adapters.runner` (`NoLiveRunnerConsoleStreamAdapter`); `DiagnosticConsoleService` + `ConsoleStreamSink` in `application.runner`; `RunnerDiagnosticConsoleController` + `SseConsoleStreamSink` in `adapters.rest`. Mirrors the 3d-5 port/adapter split (memory `[[application-cannot-import-adapters]]`).
- Gateway attach method (`attachContainerConsole`) on `DockerEngineGateway` + `DefaultDockerEngineGateway` only — docker-java stays behind the gateway.

### References

- [Source: _bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md#Story 3d-6] — AC1-8, FR68.
- [Source: docs/adr/0025-live-observability-and-readonly-console.md] — D1 (read-only + live-only), D2 (governed history), D3 (live-stream redaction posture), D4 (no new store, I/O not stored), threat model, security-review gate (sign-off pattern mirrors ADR 0013).
- [Source: docs/adr/0003-runner-secrets-mvp-posture.md] — the `--sandbox read-only` posture this narrows.
- [Source: _bmad-output/implementation-artifacts/3d-5-live-and-historical-step-log-viewing.md] — the SSE substrate this story twins (`RunnerLogStreamPort`/`DockerRunnerLogStreamAdapter`/`NoLiveRunnerLogStreamAdapter`/`StepLogStreamService`/`LogStreamSink`/`RunnerLogStreamController`/`SseLogStreamSink`, bounded executor, `SubscriptionGate`, `LineBuffer`, `?actorRole=` gate, no-own-binding assertion); its review findings (EventSource reconnect, per-line redaction try/catch, byte-buffered UTF-8, ring-buffer cap, field-type validation) are pre-solved parity to mirror.
- [Source: _bmad-output/implementation-artifacts/3-6-runner-logs-capture-and-redaction-and-classification.md] — persisted redacted store + raw-output ArchUnit boundary the console must NOT touch.
- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story-4.4] — Epic 4 consumes the live/finished log viewer (de-dup); the console is the 3d-6-only diagnostic attach.
- Memory: `[[application-cannot-import-adapters]]`, `[[new-workfloweventtype-fixture-sites]]`, `[[new-domainerrorcode-three-sites]]`, `[[openapi-regen-frontend-client-drift-cascade]]`, `[[livesnnouncement-defers-one-commit-test-flake]]`, `[[recovery-bar-wrong-allowed-actions-role]]`, `[[playwright-e2e-harness-wiring]]`, `[[prettier-gate-cascades-ci]]`, `[[docker-adapter-ctor-dep-fans-out]]`, `[[post-commit-hook-needs-requires-new]]`, `[[story-3b-4-developer-role-wiring-reconciliations]]`, `[[validated-config-needs-test-yaml]]`, `[[wsl-linux-ci-reproduction]]`.

## Declared Traps

- **T1 — raw chunks stay method-local.** Live chunks are redacted (best-effort) before reaching the sink; no field/column/event carries a raw chunk. The 3.6 raw-output ArchUnit boundary stays green.
- **T2 — live redaction is best-effort, NOT the guarantee.** Authoritative redaction remains the 3.6 persisted post-hoc scan (which the console never touches); document this in the endpoint contract (AC7). Do not overstate.
- **T3 — release the attach callback.** Register `SseEmitter` onCompletion/onTimeout/onError to close the `ConsoleSubscription` via the `SubscriptionGate`; a leaked docker attach thread is a real resource leak. Mirror 3d-5.
- **T4 — LIVE-ONLY (DD-3).** No finished-mode fallback; reject non-live/absent rex with `console-not-live` and append NO `console.opened`.
- **T5 — server-side gating, not just UI.** Enforce `open_diagnostic_console` on the endpoint (mirror 3d-5/retry/approve), not only in the frontend; the eslint rule blocks role-based UI gating but the backend is the real guard.
- **T6 — input must NOT be wired (DD-1).** The docker attach is opened WITHOUT stdin; the frontend has no input control posting to the backend. A test asserts no input path exists. This is the provable read-only guarantee.
- **T7 — DockerRunnerAdapter ctor dep fan-out.** Put the attach impl on a dedicated `DockerRunnerConsoleStreamAdapter`, NOT on `DockerRunnerAdapter` — new ctor deps break profile-wiring slice tests + every `new DockerRunnerAdapter(...)` site (memory `[[docker-adapter-ctor-dep-fans-out]]`).
- **T8 — OpenAPI/event-type drift.** Regen `openapi.json` → `npm run generate-api` → commit `schema.d.ts`; mirror both new event types into the fixture + any `@Schema(allowableValues)`; the OpenAPI enum is NOT auto-derived (memory `[[new-workfloweventtype-fixture-sites]]`, `[[openapi-regen-frontend-client-drift-cascade]]`).
- **T9 — security gate is a hard blocker.** AC1 — the story cannot close without the recorded ADR 0025 sign-off in Completion Notes + PR description. No CI job enforces it; it is a human-recorded artifact.
- **T10 — close-path append transaction.** `console.closed` is appended off the request thread; ensure the append is self-transactional / `REQUIRES_NEW` so it doesn't fail on an absent/committed tx (memory `[[post-commit-hook-needs-requires-new]]`). Cover with a Testcontainers IT if the append path isn't already self-transactional.

## Open Questions

- **OQ-1 (input-disabled vs interactive — for the security review):** DD-1 ships output-only (input disabled) over SSE, reusing 3d-5. Confirm the security review (Task 0) accepts input-disabled as the read-only design vs requiring/permitting a curated read-only `exec` command set. **Recommendation:** ship input-disabled SSE; defer any interactive input to a future story. RESOLVED-PENDING-SIGN-OFF.
- **OQ-2 (attach vs exec):** DD-2 attaches the container pty (`attachContainerCmd`). Confirm this surfaces useful diagnostic output for the runner image (vs `execStartCmd` of a fixed read-only monitor like `ps`/`ls`/`top`). If the agent's pty output duplicates 3d-5's log stream too closely, prefer a curated read-only `exec` for distinct value. Confirm against a real running runner.
- **OQ-3 (forbidden-action mechanism):** Reuse the existing server-side denial 3d-5/retry/approve use (avoid a net-new `DomainErrorCode`); confirm the exact mechanism and mirror it.
- **OQ-4 (governed-event append seam):** Confirm `RunnerExecutionEventPort.append(...)` vs `WorkflowEventWritePort.append(record)` for the console session events (no state change → prior/resulting state null). Pick the one whose transaction posture suits the off-request-thread close append (T10/DD-5).
- **OQ-5 (executor reuse):** Reuse 3d-5's bounded `streamExecutor` sizing/cap (4 threads, 30-min) for the console, or a dedicated small pool? Recommend a dedicated daemon pool to keep log-stream and console lifecycles independent; confirm sizing for the single local operator.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (1M context) — `claude-opus-4-8[1m]`

### Debug Log References

- Scoped `mvn verify` → RegistryContractTest (21), ArchitectureBoundaryTest (56), RunnerConsoleStreamProfileWiringContractTest (2) all green; the only scoped-run failure was the jacoco-check coverage gate (an artifact of running a test subset — not a real failure).
- Backend fast tier (`mvn test`): 1287 tests, 0 failures after updating the EXECUTING+workflow_owner expected-matrix row in `WorkflowInspectionServiceAllowedActionsTest`.
- OpenApiSnapshotContractTest green in compare mode after regen; frontend `check:api` green (client in sync).
- Frontend: tsc clean, eslint clean, vitest full suite 1140/1140, Playwright `diagnostic-console.spec.ts` green.

### Completion Notes List

**Security-review sign-off (AC1 / Task 0 — HARD GATE).** ✅ Recorded. ADR 0025 moved **Proposed → Accepted (signed off 2026-06-22, Alex — workflow owner / security reviewer)**. The shipped design is the **input-disabled** read-only console (DD-1/OQ-1), which is *stronger* than the ADR baseline: the docker attach is opened **without stdin** and the FE transport is a receive-only `EventSource` with no input control, so non-mutation is *provable* (no input path exists end-to-end), not merely policy-enforced. The full ratified posture + per-decision test evidence is recorded in the ADR's new "Security-review sign-off" subsection. **This sign-off must also be restated in the PR description** (no CI job enforces it).

**Reconciliations applied during implementation:**
- **DiagnosticConsoleService + ConsoleStreamSink live in `application.workflow`, NOT `application.runner`** as the story's Project Structure Notes suggested. The `rest_controllers_stay_thin_and_avoid_spi_or_persistence_or_runner` ArchUnit rule forbids a `adapters.rest → application.runner` edge; story 3d-5 placed `StepLogStreamService`/`LogStreamSink` in `application.workflow` for the identical reason. Verified green against `ArchitectureBoundaryTest`.
- **Governed-event seam (OQ-4): `RunnerExecutionEventPort`** (not `WorkflowEventWritePort` directly). It produces null prior/resulting state (exactly right for a non-state-change console session) and returns the `evt_` id. Added `CONSOLE_OPENED`/`CONSOLE_CLOSED` to its impl whitelist (`RunnerExecutionEventPersistenceAdapter`), mirroring the 3d-3 `manual.executionRequested` precedent; pinned by a new focused adapter unit test.
- **Off-thread close-append (T10/DD-5): self-transactional, no `REQUIRES_NEW` needed.** `console.closed` is appended from the controller's terminal callback / the docker attach's onEnd (no ambient tx). The append routes through `WorkflowEventWritePort.append` → Spring Data `saveAndFlush`, which self-transacts (SimpleJpaRepository is `@Transactional`) — so it always opens its own tx and never joins/fails an absent one. `openConsole` is deliberately NOT `@Transactional` (it returns a long-lived streaming handle). A once-latch (`AtomicBoolean`) guards single-append across the container-exit vs caller-close triggers. Given it's structurally self-transactional, the conditional Testcontainers IT was not required; coverage is the once-guard unit test + the whitelist adapter test + the existing `WorkflowEventPersistenceAdapter` IT family.
- **Event types stay OUT of the OpenAPI `eventType` enum.** `WorkflowEventResponse.@Schema(allowableValues=…)` is already non-exhaustive (it omits `runner.queued`/`linear.completionSyncFailed`/`manual.executionRequested`) and is NOT drift-gated; following the 3d-3 precedent, the console types are mirrored ONLY into the two gated fixtures (`workflow-event-types.fixture.json` + `workflow-events-response.schema.json`). The OpenAPI regen was driven by the NEW endpoint, not the event types (snapshot diff is the endpoint only).
- **FE hook `onEnd` guards a terminal `error` (improvement over the 3d-5 hook).** `rejectNotLive` emits BOTH a named `error` and a terminal `end`; the hook now ignores a trailing `end` once the phase is already `error`, so the specific `console-not-live` reason/message survives instead of being downgraded to a generic `ended`.
- **Route gating uses an owner-scoped `useAllowedActions(runId, 'workflow_owner')` read** (the action is EXECUTING+owner-only); the FE route-gate test branches the MSW handler on `actorRole` *param presence* (not a role-string compare) to satisfy the `no-role-based-action-gating` eslint rule.

**Scope held:** no websocket/input channel, no host shell, no new persisted I/O store/table/Flyway, no new `DomainErrorCode`, no `WorkflowEventDetailKeys` change (DD-4 — only allow-listed keys), no `xterm.js`. Localhost-only inherited (no new binding); LIVE-ONLY (no finished fallback — that's the 3d-5 viewer).

### File List

**Backend — main:**
- NEW `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerConsoleStreamPort.java`
- MOD `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java`
- MOD `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java`
- NEW `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerRunnerConsoleStreamAdapter.java`
- NEW `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/NoLiveRunnerConsoleStreamAdapter.java`
- NEW `deliveryline-backend/src/main/java/org/dradgo/application/workflow/ConsoleStreamSink.java`
- NEW `deliveryline-backend/src/main/java/org/dradgo/application/workflow/DiagnosticConsoleService.java`
- NEW `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/SseConsoleStreamSink.java`
- NEW `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerDiagnosticConsoleController.java`
- MOD `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java`
- MOD `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java`
- MOD `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- MOD `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionEventPersistenceAdapter.java`
- MOD `deliveryline-backend/src/main/resources/openapi/openapi.json` (regen — new endpoint)

**Backend — test:**
- NEW `deliveryline-backend/src/test/java/org/dradgo/application/workflow/DiagnosticConsoleServiceTest.java`
- NEW `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RunnerDiagnosticConsoleControllerTest.java`
- NEW `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/RunnerConsoleStreamProfileWiringContractTest.java`
- NEW `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/RunnerExecutionEventPersistenceAdapterTest.java`
- MOD `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGatewayTest.java`
- MOD `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java`
- MOD `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json`
- MOD `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- MOD `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`

**Frontend:**
- MOD `deliveryline-frontend/src/lib/a11y/announcements.ts`
- NEW `deliveryline-frontend/src/features/workflows/hooks/useDiagnosticConsole.ts`
- NEW `deliveryline-frontend/src/features/workflows/components/ReadOnlyDiagnosticConsole.tsx`
- NEW `deliveryline-frontend/src/features/workflows/components/ReadOnlyDiagnosticConsole.test.tsx`
- MOD `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx`
- NEW `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.diagnosticConsole.test.tsx`
- MOD `deliveryline-frontend/src/lib/api/schema.d.ts` (regen)
- NEW `deliveryline-frontend/e2e/diagnostic-console.spec.ts`
- MOD `deliveryline-frontend/e2e/support/mockApi.ts`

**Docs:**
- MOD `docs/adr/0025-live-observability-and-readonly-console.md` (Proposed → Accepted + sign-off + per-decision evidence)

### Change Log

- 2026-06-22 — Story 3d-6 implemented (`ready-for-dev → review`) via bmad-dev-story. Read-only, LIVE-ONLY, input-disabled diagnostic console attach over SSE; governed `console.opened`/`console.closed` session events; `open_diagnostic_console` allowed-action (EXECUTING+workflow_owner); ADR 0025 signed off (Proposed → Accepted, AC1 hard gate). Backend 1287 fast-tier tests + contract/arch tiers green; frontend 1140 vitest + e2e green.

### Review Findings

_Adversarial code review 2026-06-22 (bmad-code-review). 3 layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor. Acceptance Auditor found NO AC violations (AC1–AC8 + all Traps/DDs PASS, including the self-transactional close-append claim, verified accurate)._

- [x] [Review][Patch] `appendConsoleOpened` is not failure-guarded → leaked docker attach thread on append failure (Trap T3 violation) [deliveryline-backend/src/main/java/org/dradgo/application/workflow/DiagnosticConsoleService.java:223] — if the `console.opened` event append throws, the exception unwinds out of `attachLive`/`openConsole` to the controller `catch` (RunnerDiagnosticConsoleController.java:217) BEFORE `gate.install(handle)` runs, so the live `subscription` (created at :174) is never closed and the docker attach callback thread leaks until the container exits. `appendConsoleClosed` (:269) is try/catch-wrapped; `appendConsoleOpened` (:250) is not. **FIXED 2026-06-22:** wrapped the open-event append in try/catch — on failure it claims the `closed` latch, closes the subscription, surfaces `console-failed` via the sink (no-throw contract preserved), and returns `noop()`. New test `openEventAppendFailureReleasesTheAttachAndSurfacesFailureWithoutLeaking`.
- [x] [Review][Patch] Governed-event ordering inversion: `console.closed` can be appended before `console.opened` [deliveryline-backend/src/main/java/org/dradgo/application/workflow/DiagnosticConsoleService.java:223] — the container-exit `onEnd` lambda (:197-208) runs on the docker callback thread and can fire in the window between `attachConsole(...)` returning (:174) and the unconditional `appendConsoleOpened` (:223). If the container exits in that window, the once-latch appends `console.closed` first, then `:223` records a `console.opened` for an already-ended session — inverting AC3's open/close audit pairing. **FIXED 2026-06-22:** added a `closed.get()` pre-check before the open-append/`onStatus` — an already-ended session is released as a no-op (no orphan `console.opened`). New test `containerExitDuringAttachSetupAppendsNoOrphanConsoleOpened`. (Residual sub-microsecond race where `onEnd` fires DURING the append is acknowledged; closing it fully would require reordering the open-append ahead of the attach, which conflicts with AC3/DD-3's "no event for a rejected attach".)
- [x] [Review][Defer] OpenAPI `actorRole` query param serialized as `"type":"null"` with a string enum [deliveryline-backend/src/main/resources/openapi/openapi.json (actorRole param)] — deferred, pre-existing: 3 identical occurrences across sibling `actorRole` params (3d-5 log-stream + others); a project-wide springdoc serialization quirk, not introduced here. `schema.d.ts` is correct and the drift gate is green.
- [x] [Review][Defer] EventSource native-error auto-reconnect re-opens a fresh backend attach → multiple `console.opened` without matching `console.closed`; no client backoff/cap [deliveryline-frontend/src/features/workflows/hooks/useDiagnosticConsole.ts:157-162] — deferred, pre-existing: mirrors the accepted 3d-5 `useRunnerLogStream` reconnect design; audit-integrity wrinkle on AC3 acknowledged by DD-4's single-operator timestamp-pairing posture.
- [x] [Review][Defer] WARN-per-failing-chunk redaction logging can flood logs over a 30-min session [deliveryline-backend/src/main/java/org/dradgo/application/workflow/DiagnosticConsoleService.java:188-192] — deferred: a consistently-failing redactor emits one WARN per chunk; low impact (no secret leak — content never logged), but could be rate-limited/counted-once.
