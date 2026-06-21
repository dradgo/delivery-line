# Story 3d.5: Live + Historical Step Log Viewing (REST Stream + UI)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want to watch a step's container logs while it runs and read them after it finishes,
So that I can follow progress and diagnose behavior without waiting for a post-hoc download.

## Context

This is the **live-observability slice** of Epic 3d (per-step execution control), implementing **PRD FR65** and governed by **ADR 0025**. It is the FIRST streaming surface in the backend — there is no `SseEmitter`/`Flux`/websocket anywhere today (the `/events` endpoint is plain JSON polling). It builds entirely on **done** substrate:

- **Story 3.6 (done)** — `RunnerLogCaptureService` already captures + **post-hoc redacts** each runner stdout/stderr stream at container exit and persists the **redacted** files under a SEPARATE durable store `{DELIVERYLINE_HOME}/runner-logs/{rex}/` (`runner.stdout` + `runner.stderr`), with a typed `RunnerLogReference` (path, byteSize, classification, redactionCount) on the `runner_executions` row. `WorkflowInspectionService.getRunnerLogReference(rex)` + `findLatestRunnerExecutionId(runId)` already exist. **This is the authoritative redaction guarantee and the finished-mode source — reuse it, do NOT re-derive it.**
- **Story 3.17a/b (done)** — runner queue + worker pool; `RunnerExecutionStatus` distinguishes live (`PENDING`/`RUNNING`/`QUEUED`) from terminal (`COMPLETED`/`FAILED`/`TIMED_OUT`/`ORPHANED`/`CANCELLED_FOR_TAKEOVER`).
- **Story 3.1/3.2 (done)** — `DockerRunnerAdapter` + `DefaultDockerEngineGateway` (the ONLY docker-java importer) track `rex → containerId` in an in-process map and via the container label `deliveryline.runnerExecutionId={rex}` (recovery probe `findContainerIdByRunnerExecutionId`). docker-java 3.7.1 supports `logContainerCmd(id).withFollowStream(true)` (proven in `ClaudeRunnerImageConformanceIT`).
- **Story 1.10 (done)** — `RedactionPolicyService.redact(String payload, String classificationValue) -> RedactionResult` for **best-effort streaming** redaction (per AC2; authoritative guarantee stays the 3.6 persisted scan).
- **Story 2.14 (done)** — `AllowedAction` registry + the `RegistryContractTest` drift gate against `contracts/frontend/allowed-actions.placeholder.json`; the state×role matrix lives ONLY in `WorkflowInspectionService.computeActionMatrix`.

**What this story IS:** ONE SSE endpoint + ONE frontend `Step Execution Log Viewer` that covers **both** states — live-follows the running container's logs (Docker), and for a finished execution replays the already-persisted redacted log (3.6). Plus one new allowed-action (`view_runner_logs`).

**What this story is NOT:** NOT a new persisted log store or second raw-log table (ADR 0025 D4 — finished mode reads the 3.6 store; live mode streams from Docker, nothing new persisted). NOT a redaction-guarantee upgrade (live redaction is best-effort only; the persisted post-hoc scan remains authoritative — AC2). NOT the read-only console (story 3d-6, security-gated). NOT a separate redacted-log **download** surface — Epic 4 story 4.4 CONSUMES this viewer (AC5 de-dup).

**Dependency note:** 3d-1..3d-4 have epic-level ACs only (no story files yet); 3d-5 depends solely on the **done** 3.6 + 3.17 substrate, so it can proceed independently of its sibling 3d stories.

## Acceptance Criteria

1. **Both states, one endpoint.** Given a running runner execution, a backend streaming endpoint (SSE) follows the container's logs live; given a finished execution, the same endpoint serves the already-persisted post-hoc-redacted log (story 3.6). The viewer covers both states.
2. **Documented redaction posture (ADR 0025).** The live stream is served only over the existing localhost-only REST binding to the single local operator; **best-effort** streaming redaction is applied, but the **authoritative** redaction guarantee remains the persisted post-hoc scan (story 3.6). This is documented in the endpoint contract (OpenAPI description + the per-step-execution-control doc seam).
3. **No new persisted log store (ADR 0025 D4).** Live case streams from Docker (`logContainerCmd().withFollowStream(true)`); finished case reads the existing persisted redacted log files. No second raw-log table or store is introduced.
4. **UI Step Execution Log Viewer.** It renders within the run-detail view per step (latest runner execution for the run), shows live-follow with auto-scroll **and** a finished/static mode, and announces stream start/end via an aria-live region with a **color-independent** state signifier (icon + label, not color alone).
5. **Epic 4 de-dup.** This viewer is the surface story 4.4's failure-diagnostics view consumes; no separate redacted-log download surface is built.
6. **Allowed-action gated (story 2.14).** Viewing logs is gated by a backend-reported action; a NEW `AllowedAction` value `view_runner_logs` is added (mirrored into the frontend placeholder so the drift test passes), enforced server-side on the stream endpoint, and consumed by the frontend gate.
7. **Correlation propagation (story 1.19).** The streaming endpoint emits structured log lines carrying `correlationId`, the runner-execution id, and stream lifecycle (open / first-byte / redaction-applied(best-effort, count only) / end / client-disconnect). NEVER log streamed content or secret values.
8. **Tests.** Coverage asserts: live stream follows a running execution; finished mode serves the redacted persisted log; the persisted/export path is unchanged by the live view (no write to `runner-logs/` or `runner_executions` from the viewer); localhost-only enforcement; allowed-action gating (server-side denial when absent); UI live-region announcement; axe a11y zero `wcag2aa` violations on the viewer.

## Tasks / Subtasks

- [x] **Task 1 — Live-follow SPI port (AC1, AC3)** — keep docker-java behind the gateway (ArchUnit `application → adapters` forbidden; memory `[[application-cannot-import-adapters]]`)
  - [x] NEW port `org.dradgo.application.runner.spi.RunnerLogStreamPort`: `LiveLogSubscription followLiveLogs(String runnerExecutionId, RawLogLineSink onLine, Runnable onEnd)`. `RawLogLineSink` = `@FunctionalInterface void accept(String stream, String rawLine)` (`stream` = `"stdout"`/`"stderr"`); `LiveLogSubscription extends AutoCloseable` (`close()` stops following + releases the docker callback). Project-owned records only — no docker-java types leak across the port.
  - [x] Guard the rex arg with `PublicIdPrefixes.require(rex, RUNNER_EXECUTION)`. Return a NO-OP/empty subscription (or signal "not live") when no live container exists for the rex, so the application layer can fall back to finished-mode.
- [x] **Task 2 — Docker adapter implementation (AC1, AC3)**
  - [x] Implement `RunnerLogStreamPort` in the Docker adapter package (`adapters.runner` / `adapters.runner.docker`) — reuse the existing `rex → containerId` resolution (in-process map + label probe `findContainerIdByRunnerExecutionId`). Open `docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withFollowStream(true).withTimestamps(false)` with a `ResultCallback.Adapter<Frame>` that splits frames into lines and calls `onLine`; on stream completion call `onEnd`.
  - [x] `LiveLogSubscription.close()` MUST close the docker callback and the cmd (no leaked follow threads). Wrap in best-effort try/catch so a follow failure NEVER throws into the SSE thread — degrade to "stream ended". Distinguish "container exited / absent" (→ `onEnd`, caller switches to finished-mode) from "container live".
  - [x] `MockRunnerAdapter` path: no live container exists → port returns the not-live signal (deterministic; mirrors 3.6 Trap T4).
- [x] **Task 3 — Finished-mode redacted-content read (AC1, AC3)**
  - [x] Add `Optional<RedactedRunnerLog> readRedacted(String rex)` to `RunnerLogStore` (port) + `LocalRunnerLogStore` (impl). `RedactedRunnerLog` = capped stdout + stderr **text** (already redacted by 3.6 — safe to return). Reuse the existing containment-guard + capped/lossy-UTF-8 read patterns from `LocalRunnerLogStore`/`LocalRunnerWorkspaceStore` (cap ~8 MiB with a `[TRUNCATED]` marker). This reads the REDACTED store only — NOT the 3.6 raw workspace reads (so the `RAW_RUNNER_OUTPUT_READS_STAY_IN_RUNNER_ADAPTER` ArchUnit rule is untouched and still passes).
- [x] **Task 4 — Application orchestration service (AC1, AC2, AC7)**
  - [x] NEW `org.dradgo.application.runner.StepLogStreamService` (`@Service`). Inject `RunnerLogStreamPort`, `RunnerLogStore` (or `WorkflowInspectionService` for `getRunnerLogReference`), `RunnerExecutionService`/read port (status), `RedactionPolicyService`, and a `WorkflowInspectionService` seam to resolve `findLatestRunnerExecutionId(runId)`.
  - [x] Define an application-side sink interface `LogStreamSink { void onLine(String stream, String redactedLine, long seq); void onStatus(String phase, String rex); void onEnd(String reason); void onError(String reason); }` — the controller implements it over `SseEmitter` (keeps `SseEmitter` out of the application layer).
  - [x] `void streamRunnerLogs(String workflowRunId, String actorRole, LogStreamSink sink)`: resolve latest rex; if none → `sink.onEnd("no-runner-execution")`. If status is live (`PENDING`/`RUNNING`/`QUEUED`) → subscribe via `RunnerLogStreamPort`, apply **best-effort** per-line redaction `redactionPolicyService.redact(rawLine, DataClassification.LOCAL_ONLY.value()).sanitizedText()`, forward via `sink.onLine`; on `onEnd` close + `sink.onEnd("container-exited")`. If terminal → `readRedacted(rex)` (already-redacted, NO re-redaction) → replay lines via `sink.onLine` then `sink.onEnd("finished-replay-complete")`.
  - [x] **Raw lines stay method-local** (lambda params) and are redacted before reaching the sink — NO field stores a raw line (keeps the 3.6 raw-output boundary intact; declare as Trap T1).
- [x] **Task 5 — SSE REST endpoint (AC1, AC2, AC6)**
  - [x] NEW `org.dradgo.adapters.rest.RunnerLogStreamController` (`@RestController`; ArchUnit pins `*Controller` to `adapters.rest`). `GET /api/v1/workflows/{workflowRunId}/runner-logs/stream` produces `text/event-stream`, returns `SseEmitter` (Spring MVC). Run the follow on a bounded executor; register `emitter.onCompletion`/`onTimeout`/`onError` to close the `LiveLogSubscription` (no leaks). SSE events: `log` `{stream,line,seq}`, `status` `{phase,rex}`, `end` `{reason}`, `error` `{reason}`.
  - [x] **Server-side allowed-action enforcement:** before opening the stream, compute the allowed-actions for the run (reuse the `WorkflowInspectionService` matrix path used by `/allowed-actions`) and reject with the existing forbidden-action mechanism when `view_runner_logs` is absent (mirror how `retry`/`approve` endpoints gate; memory `[[recovery-bar-wrong-allowed-actions-role]]`). Resolve the actor via the existing `LocalActorIdentityResolver`/`ApprovalReviewerRoleResolver` seam used by `WorkflowController`.
  - [x] **Localhost-only** is inherited from `server.address=127.0.0.1` + `RestBindingGuard` (story 6.9) — add NO new binding; assert the posture in a test (AC8).
- [x] **Task 6 — `view_runner_logs` allowed-action (AC6)** — registry recipe, memory `[[new-workfloweventtype-fixture-sites]]`-adjacent drift discipline
  - [x] Add `VIEW_RUNNER_LOGS("view_runner_logs")` to `org.dradgo.domain.registry.AllowedAction`.
  - [x] Wire it into `WorkflowInspectionService.computeActionMatrix` for the states where a runner execution exists: `EXECUTING`, `FAILED`, `PAUSED`, `WAITING_FOR_REVIEW` — role-agnostic (add to EVERY role branch in those states, alongside `view_only`/`view_diagnostics`). Do NOT add it to pre-execution states (`INBOX`/`PLANNED`/`INVESTIGATING`/`WAITING_FOR_SPEC_APPROVAL`).
  - [x] Mirror `"view_runner_logs"` into `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` so `RegistryContractTest#allowedActionsStayAlignedWithFrontendPlaceholder` passes. (The endpoint resolves "latest rex" and returns `no-runner-execution` if none, so broad state coverage is safe.)
- [x] **Task 7 — OpenAPI + client regen (AC2)** — memory `[[openapi-regen-frontend-client-drift-cascade]]`
  - [x] Annotate the controller so the SSE endpoint appears in `openapi.json` with a description documenting the AC2 redaction posture (best-effort live vs authoritative persisted scan). Run `scripts/regen-openapi.sh` then `npm run generate-api` and commit `schema.d.ts` so `check:api` stays green. NOTE: the frontend consumes the stream via a hand-written `EventSource`, NOT the generated `openapi-fetch` client (SSE is not a typed REST call) — but the endpoint must still round-trip through OpenAPI/`schema.d.ts` to keep the drift gate green.
- [x] **Task 8 — Frontend Step Execution Log Viewer (AC4, AC6)**
  - [x] NEW hand-written hook `src/features/workflows/hooks/useRunnerLogStream.ts` opening `new EventSource('/api/v1/workflows/${workflowRunId}/runner-logs/stream')`, accumulating `log` events, surfacing `end`/`error`, and `close()`-ing on unmount / when not enabled. (No existing EventSource pattern — this is the first; model the shape on `useWorkflowEvents` but with EventSource, not React Query.)
  - [x] NEW `src/features/workflows/components/StepExecutionLogViewer.tsx`: renders the accumulated lines in a scroll region with auto-scroll-on-new-line (pausable), a live-follow vs finished/static mode indicator, and a stream start/end announcement via `useLiveAnnouncement` + a `state-signifiers` entry (e.g. `loading`/`success`/`stale` icon+label — color-independent, UX-DR2). Add announcement strings to `src/lib/a11y/announcements.ts` (`logStreamStarted`, `logStreamEnded`, `logStreamError`).
  - [x] Slot the viewer into the run-detail route `src/routes/workflows/$workflowRunId/index.tsx` per step (latest runner execution), near `FailureEventSurface`. **Gate** rendering on the backend action: read `useAllowedActions(workflowRunId, actorRole)` and show the viewer only when `data.actions.includes('view_runner_logs')` — gating flows through `useAllowedActions` ONLY (eslint `local-rules/no-role-based-action-gating`); do NOT infer from role. (No need to touch the Decision-Bar `DecisionAction` union — the viewer is not a Decision-Bar control; gate on the raw action array.)
- [x] **Task 9 — Frontend tests (AC4, AC8)**
  - [x] Vitest: live mode renders streamed lines (mock EventSource); finished mode renders the persisted redacted content; live-region announcement asserted via `waitFor` (memory `[[livesnnouncement-defers-one-commit-test-flake]]` — never assert synchronously); gate hides the viewer when `view_runner_logs` absent; axe zero `wcag2aa` violations.
  - [x] Playwright e2e: open a run, viewer visible when action present, lines render; JSON fixtures with `with { type: 'json' }` (memory `[[playwright-e2e-harness-wiring]]`). Run `prettier --write` before push (memory `[[prettier-gate-cascades-ci]]`).
- [x] **Task 10 — Backend tests (AC8)**
  - [x] Live follow: a `StepLogStreamService` unit test with a fake `RunnerLogStreamPort` drives lines through best-effort redaction → sink (assert a deliberately-leaky line is redacted in the streamed output).
  - [x] Finished mode: `readRedacted` serves the persisted redacted files; service replays them and completes.
  - [x] **Persisted/export unchanged:** assert the viewer path performs NO write to `runner-logs/` and NO `recordRawOutput`/`runner_executions` mutation (the live view must not touch the durable store — ADR 0025 D4).
  - [x] Localhost-only posture assertion (reuse the 6.9 `RestBindingGuard` test pattern); allowed-action gating (stream endpoint denies when `view_runner_logs` absent for the state/role); correlation log assertion (AC7) via `OutputCaptureExtension`/list-appender + an adversarial no-secret-in-logs sweep.
  - [x] `RegistryContractTest` green after the placeholder mirror (Task 6).
- [x] **Task 11 — Endpoint-contract documentation seam (AC2, AC5)**
  - [x] Document the AC2 posture (best-effort live redaction, authoritative persisted scan, localhost-only, Epic-4-4 consumes this viewer) in the OpenAPI description and leave a pointer for story 3d-10's `per-step-execution-control-walkthrough.md`. Do NOT build a separate download surface (AC5).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at: `RunnerLogStreamController` stream open/close + client-disconnect; `StepLogStreamService` live-vs-finished decision, per-stream lifecycle, best-effort redaction applied (COUNT only); `RunnerLogStreamPort` adapter follow start/stop + follow failure (`WARN`).
  - [x] Parameterized logging (`log.info("...", arg1, arg2)`) — never concatenation.
  - [x] Levels: `INFO` for stream open/close/decision, `WARN` for follow failure / no-live-container fallback / client-disconnect mid-stream, `ERROR` only for unhandled failures.
  - [x] Carry `correlationId`, `workflowRunId`, `runnerExecutionId` via MDC; **NEVER** log streamed content, secret values, tokens, or payload bytes (log redaction COUNT only — AC7).
  - [x] ≥1 focused test per new branch asserting the expected log line/level + the adversarial no-secret-in-logs sweep.

## Dev Notes

### Architecture & insertion points (verified against live code)

**Backend**
- **`AllowedAction`** — `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (11 values today; add `VIEW_RUNNER_LOGS`). Matrix in `application/workflow/WorkflowInspectionService.java#computeActionMatrix` (~620-690) — the SOLE state×role source (ArchUnit-pinned). `EXECUTING`→`{view_only, await_outcome}`; `FAILED`→owner `{retry, view_diagnostics}` / others `{view_only, view_diagnostics}`; `PAUSED`→`{view_only, view_diagnostics}`; `WAITING_FOR_REVIEW`→developer `{accept_implementation, reject_implementation, takeover_workflow, view_only}` / others `{view_only}`. Add `VIEW_RUNNER_LOGS` to each branch in those four states.
- **Drift gate** — `src/test/java/org/dradgo/contract/RegistryContractTest.java#allowedActionsStayAlignedWithFrontendPlaceholder` vs `src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (11-entry array; add `view_runner_logs`).
- **Finished-mode source (3.6)** — `WorkflowInspectionService.getRunnerLogReference(rex) -> RunnerLogReferenceResult` (~1455) + `findLatestRunnerExecutionId(runId)` (~1441). Persisted redacted files: `{DELIVERYLINE_HOME}/runner-logs/{rex}/runner.stdout` + `runner.stderr` (already redacted). `RunnerLogStore` port (`application/runner/spi/RunnerLogStore.java`) has `write` + `find` — **add `readRedacted` (Task 3)**; impl `adapters/files/LocalRunnerLogStore.java`.
- **Liveness** — `domain/registry/RunnerExecutionStatus.java`: live = `PENDING`/`RUNNING`/`QUEUED`; terminal = `COMPLETED`/`FAILED`/`TIMED_OUT`/`ORPHANED`/`CANCELLED_FOR_TAKEOVER`. `RunnerExecutionSnapshot.status()` + `completedAt` (null while live). Read via `RunnerExecutionRecordPort` (`findByPublicId`; `findByWorkflowRunPublicIdAndStatusIn` — verify exact signature against the live port).
- **Docker follow** — `adapters/runner/DockerRunnerAdapter.java`: `ConcurrentMap<String,String> rexIdToContainerId` (~90); recovery probe `DefaultDockerEngineGateway.findContainerIdByRunnerExecutionId(rex)` (label `deliveryline.runnerExecutionId`). docker-java 3.7.1; `logContainerCmd(id).withFollowStream(true).withStdOut(true).withStdErr(true).exec(ResultCallback.Adapter<Frame>)` (pattern proven in `ClaudeRunnerImageConformanceIT`). `DefaultDockerEngineGateway` is the ONLY docker-java importer (ArchUnit `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY`); existing SPI `DockerHostPort` exposes only stop/kill/rm/list — add the new follow capability via `RunnerLogStreamPort` (new port), not by leaking docker-java.
- **Redaction (best-effort, live)** — `application/security/RedactionPolicyService.redact(String, String)` → `RedactionResult.sanitizedText()`; pass `DataClassification.LOCAL_ONLY.value()`. Placeholders `[REDACTED_<CATEGORY>]`; count via `[REDACTED_` substring (no credential regex outside `application.security` — memory/3.6 Trap T2).
- **REST conventions** — `adapters/rest/WorkflowController.java` (`@RestController`, `/api/v1/workflows`); a new `RunnerLogStreamController` is cleaner than overloading it (SSE lifecycle isolation). Localhost-only: `application.yml` `server.address: 127.0.0.1` + `RestBindingGuard` (`DOCTOR_REST_BIND_UNAVAILABLE`).

**Frontend**
- **Run-detail route** — `src/routes/workflows/$workflowRunId/index.tsx` (`WorkflowDetailRoute`), composed of `RunContextStrip` → `FailureEventSurface` → artifact links → `WorkflowDecisionBar`. Slot the viewer near `FailureEventSurface`.
- **Allowed-actions gate** — `src/features/workflows/hooks/useAllowedActions.ts` returns `{ actions: string[], versionStamp }`; gate on `actions.includes('view_runner_logs')`. eslint `local-rules/no-role-based-action-gating` (error) forbids role-based gating — flow through `useAllowedActions` only.
- **Live-region** — `src/lib/a11y/useLiveAnnouncement.ts` (defers ONE commit — assert with `waitFor`); vocabulary in `src/lib/a11y/announcements.ts`; color-independent signifiers in `src/lib/state-signifiers.ts` (`STATE_SIGNIFIERS`: icon+label). UI pattern: `<div role="status" aria-live="polite" data-testid="...">{announcement}</div>`.
- **API client / drift** — `src/lib/api/schema.d.ts` regenerated by `npm run generate-api` from backend `openapi.json`; `check:api` gate. SSE consumed via hand-written `EventSource`, not `openapi-fetch`.
- **Tests** — Vitest + RTL (jsdom) + MSW + vitest-axe; Playwright in `e2e/` (production bundle, no retries). See `docs/testing/frontend-test-patterns.md` (§1 live-region flake, §2 cross-file router mock).

### Key design decisions

- **SSE, not websocket.** Read-only, one-directional log follow fits the localhost MVC posture and `EventSource`. (The read-only **console**, story 3d-6, may need a websocket/pty — out of scope here.)
- **Live = Docker, finished = persisted file (AC3/ADR 0025 D4).** Do NOT tail the bind-mounted workspace `runner.stdout` for the live case — the AC mandates the Docker follow; and do NOT re-redact the finished content (it's already authoritatively redacted by 3.6).
- **First streaming surface.** Net-new SSE infra — keep `SseEmitter` strictly in the controller; the application service speaks the `LogStreamSink` abstraction. Ensure the follow callback is released on every emitter terminal state (completion/timeout/error/client-disconnect) — no leaked docker follow threads.

### What NOT to add (scope guard)

- NO new persisted log store / table / Flyway migration (ADR 0025 D4).
- NO new `WorkflowEventType` (3d-5 only streams; the console 3d-6 records governed events). Avoids the two-fixture-site trap (memory `[[new-workfloweventtype-fixture-sites]]`).
- NO new `DomainErrorCode` unless genuinely required — prefer reusing the existing forbidden-action denial mechanism + SSE `error`/`end` events. If one is unavoidable, follow the three-site recipe (memory `[[new-domainerrorcode-three-sites]]`).
- NO change to the 3.6 raw-output boundary — the new code reads only the REDACTED store and Docker follow; declare no field holding raw output (3.6 ArchUnit rules stay green).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`.
- **Where:** controller stream open/close/disconnect (`INFO`/`WARN`), `StepLogStreamService` decision + lifecycle (`INFO`), adapter follow start/stop/fail (`INFO`/`WARN`).
- **Context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId` (MDC).
- **Forbidden in output:** streamed log content, secret values, tokens, payload bytes — log redaction COUNT only. Adversarial no-secret-in-logs sweep test required.

### Project Structure Notes

- `RunnerLogStreamPort` in `application.runner.spi`; impl in `adapters.runner`(`.docker`); `StepLogStreamService` + `LogStreamSink` in `application.runner`; `RunnerLogStreamController` in `adapters.rest`. Mirrors the existing port/adapter split (memory `[[application-cannot-import-adapters]]`).
- `readRedacted` on `RunnerLogStore`/`LocalRunnerLogStore` reads the redacted store only — distinct from the 3.6 raw workspace reads.

### References

- [Source: _bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md#Story 3d-5] — AC1-8, FR65.
- [Source: docs/adr/0025-live-observability-and-readonly-console.md] — D3 (live-stream redaction posture), D4 (no new persisted store), D5 (Epic 4 consumes), threat model.
- [Source: _bmad-output/implementation-artifacts/3-6-runner-logs-capture-and-redaction-and-classification.md] — persisted redacted store, `RunnerLogReference`, `getRunnerLogReference`, `findLatestRunnerExecutionId`, raw-output ArchUnit boundary.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md] — Epic 3d D3, Risk #1; Epic-4 de-dup (story 4.4).
- Memory: `[[application-cannot-import-adapters]]`, `[[new-domainerrorcode-three-sites]]`, `[[new-workfloweventtype-fixture-sites]]`, `[[openapi-regen-frontend-client-drift-cascade]]`, `[[livesnnouncement-defers-one-commit-test-flake]]`, `[[recovery-bar-wrong-allowed-actions-role]]`, `[[playwright-e2e-harness-wiring]]`, `[[prettier-gate-cascades-ci]]`, `[[validated-config-needs-test-yaml]]`, `[[wsl-linux-ci-reproduction]]`, `[[docker-adapter-ctor-dep-fans-out]]`.

## Declared Traps

- **T1 — raw lines stay method-local.** Live lines are redacted (best-effort) before reaching the sink; no field/column/event carries a raw line. The 3.6 raw-output ArchUnit boundary stays green.
- **T2 — live redaction is best-effort, NOT the guarantee.** Authoritative redaction remains the 3.6 persisted post-hoc scan; document this in the endpoint contract (AC2). Do not overstate live redaction.
- **T3 — release the follow callback.** Register `SseEmitter` onCompletion/onTimeout/onError to `close()` the `LiveLogSubscription`; a leaked docker follow thread is a real resource leak.
- **T4 — finished content is already redacted.** `readRedacted` returns the 3.6 store text as-is — do NOT re-run redaction; do NOT read the raw workspace store.
- **T5 — server-side gating, not just UI.** Enforce `view_runner_logs` on the endpoint (mirror retry/approve), not only in the frontend; the eslint rule blocks role-based UI gating but the backend is the real guard.
- **T6 — no new persisted store / event type / table.** ADR 0025 D4; viewer must not write to `runner-logs/` or mutate `runner_executions` (assert in tests).
- **T7 — DockerRunnerAdapter ctor dep fan-out.** If the follow impl lands on `DockerRunnerAdapter`/gateway, new ctor deps break both profile-wiring slice tests + every `new DockerRunnerAdapter(...)` site (memory `[[docker-adapter-ctor-dep-fans-out]]`). Prefer a dedicated adapter class implementing `RunnerLogStreamPort`.
- **T8 — OpenAPI/client drift.** Regen `openapi.json` → `npm run generate-api` → commit `schema.d.ts`, or the `check:api`→foundation-gate cascade reds (memory `[[openapi-regen-frontend-client-drift-cascade]]`).

## Open Questions

- **OQ-1 (live source):** AC3 mandates Docker `logContainerCmd().withFollowStream(true)` for live. Resolved — use Docker follow, not bind-mounted-file tailing.
- **OQ-2 (forbidden-action mechanism):** Confirm the existing server-side denial used by retry/approve endpoints and reuse it for the stream endpoint (avoid a net-new `DomainErrorCode` if possible).
- **OQ-3 (`since` window on follow):** Whether to seed the live follow with a small `withSince`/`withTail` backlog so the viewer shows recent context on open, vs follow-from-now. Recommend a small tail (e.g. last N lines) for usability; confirm against frame ordering.
- **OQ-4 (executor sizing):** Bounded executor for SSE follow threads (single local operator → small pool). Confirm sizing + a max-stream-duration cap.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (1M context) — `claude-opus-4-8[1m]`

### Debug Log References

- Backend unit tier: `mvnw -pl deliveryline-backend test -Djacoco.skip=true` → BUILD SUCCESS (all surefire unit tests green, including the new `StepLogStreamServiceTest`, `RunnerLogStreamControllerTest`, the `DefaultDockerEngineGatewayTest` line-buffer follow test, `LocalRunnerLogStoreTest#readRedacted*`, and the updated `WorkflowInspectionServiceAllowedActionsTest` matrix + `RegistryContractTest`).
- ArchUnit + profile wiring: `ArchitectureBoundaryTest`, `DockerRunnerProfileWiringContractTest`, `RunnerProfileWiringContractTest` → green (docker-java stays behind the gateway; AllowedAction enum stays in WorkflowInspectionService — the controller gates on the wire string via `AllowedActionsResponse`; exactly one `RunnerLogStreamPort` bean per profile).
- OpenAPI regen: `OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true` regenerated `openapi.json` (full Spring context booted with the new controller + executor); `npm run generate-api` regenerated `schema.d.ts`; `check:api` green.
- Frontend: `tsc -b` (exactOptionalPropertyTypes), `eslint --max-warnings=0`, `prettier --check`, and Vitest (`StepExecutionLogViewer.test.tsx` + `index.runnerLogs.test.tsx`, 6/6) all green.

### Completion Notes List

- **One SSE endpoint, both states (AC1).** `GET /api/v1/workflows/{id}/runner-logs/stream` (`RunnerLogStreamController`) resolves the latest runner execution and, via `StepLogStreamService`, follows the live Docker container (`RunnerLogStreamPort` → `DockerRunnerLogStreamAdapter` → gateway `followContainerLogs`) or replays the story-3.6 persisted redacted log (`RunnerLogStore.readRedacted`). A `NoLiveRunnerLogStreamAdapter` covers the `!runners.docker` (mock) profile so live status always falls back to finished mode there.
- **Redaction posture (AC2 / Traps T1/T2/T4).** Live lines are redacted best-effort per line (`RedactionPolicyService`, `LOCAL_ONLY`) before reaching the sink; raw lines stay method-local (no field/column holds one). Finished content is already authoritatively redacted by 3.6 and is replayed verbatim (never re-redacted). The OpenAPI description documents the best-effort-live-vs-authoritative-persisted posture (AC2/AC11 doc seam for 3d-10).
- **No new persisted store (AC3 / ADR 0025 D4 / Trap T6).** The viewer reads only (Docker follow + the redacted store); a unit test asserts the path never calls `RunnerLogStore.write`. No Flyway / table / event-type added.
- **Server-side gating (AC6 / Trap T5).** `VIEW_RUNNER_LOGS` added to the `AllowedAction` registry + the four runner-execution states in `computeActionMatrix` (role-agnostic) + the frontend placeholder. The controller computes the run's allowed actions and serves an `error`+`end` SSE event when the action is absent — proven by `RunnerLogStreamControllerTest` (the follow/replay is never engaged on denial). RECONCILIATION: the stream takes an `?actorRole=` query param (EventSource can't set headers) so the backend resolves the same gate the UI does.
- **Resource safety (Trap T3).** The controller registers `SseEmitter` onCompletion/onTimeout/onError → a race-safe `SubscriptionGate` that closes the live follow subscription even if the client disconnects before the follow starts; the gateway's `followContainerLogs` handle closes the docker callback + cmd. Bounded daemon executor (OQ-4) + 30-min stream cap; `@PreDestroy` shutdown.
- **Frontend (AC4).** Hand-written `useRunnerLogStream` EventSource hook (the first EventSource; closes on `end`/`error` so the browser never auto-reconnects a completed stream) + `StepExecutionLogViewer` (auto-scroll-pausable scroll region, color-independent live/finished/ended/error mode chip via `StateSignifierChip`, aria-live announcements). Slotted into the run-detail route near `FailureEventSurface`, gated ONLY on `useAllowedActions(...).actions.includes('view_runner_logs')` (no role inference).
- **Logging (AC7).** Structured SLF4J at controller open/deny/timeout/disconnect, service live-vs-finished decision + lifecycle + best-effort redaction COUNT (never content), and adapter follow start/stop/fail. `StepLogStreamServiceTest` includes an adversarial no-secret-in-logs sweep via a Logback list-appender (asserts the run/rex identity is logged but `ghp_…` / `Authorization: Bearer` never are).
- **Scope (AC5).** No separate redacted-log download surface; Epic 4 story 4.4 consumes this viewer.
- **e2e:** `runner-log-viewer.spec.ts` drives the viewer against the fixture-route mock (a modelled `text/event-stream` body); `view_runner_logs` enabled on the Failed recovery fixture run.

### File List

**Backend — new**

- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerLogStreamPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerRunnerLogStreamAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/NoLiveRunnerLogStreamAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RedactedRunnerLog.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/LogStreamSink.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/StepLogStreamService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RunnerLogStreamController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/SseLogStreamSink.java`

**Backend — modified**

- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java` (added `followContainerLogs`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java` (`followContainerLogs` impl + `LineBuffer`)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerLogStore.java` (added `readRedacted`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerLogStore.java` (`readRedacted` impl + capped lossy read)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (`VIEW_RUNNER_LOGS`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (matrix: 4 runner-execution states)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regen — new endpoint)

**Backend — tests**

- `deliveryline-backend/src/test/java/org/dradgo/application/runner/StepLogStreamServiceTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RunnerLogStreamControllerTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGatewayTest.java` (follow line-buffer test)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerLogStoreTest.java` (`readRedacted` tests)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogCaptureServiceTest.java` (stub `readRedacted`)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (matrix assertions)
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (mirror `view_runner_logs`)

**Frontend — new**

- `deliveryline-frontend/src/features/workflows/hooks/useRunnerLogStream.ts`
- `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.tsx`
- `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.test.tsx`
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.runnerLogs.test.tsx`
- `deliveryline-frontend/e2e/runner-log-viewer.spec.ts`

**Frontend — modified**

- `deliveryline-frontend/src/lib/a11y/announcements.ts` (logStream{Started,Ended,Error})
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (slot + allowed-action gate)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regen — new endpoint)
- `deliveryline-frontend/e2e/support/mockApi.ts` (modelled SSE stream + `view_runner_logs` on the recovery fixture run)

## Change Log

| Date       | Version | Description                                                                 |
| ---------- | ------- | --------------------------------------------------------------------------- |
| 2026-06-21 | 0.1     | Implemented story 3d-5 — live + historical step-log viewing (SSE stream + Step Execution Log Viewer UI); status → review. |

### Review Findings

_Adversarial code review 2026-06-22 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). Diff scoped to the 31-file story File List (unrelated uncommitted 3d-1 reviewer-model work excluded)._

- [x] [Review][Patch] Native EventSource transport-error mid-stream: keep auto-reconnect, fix the UX (resolved decision 2026-06-22 → option "resume") — **FIXED 2026-06-22**: the native-transport branch in `onError` no longer closes the source (the browser resumes the live follow after a transient localhost blip); added a distinct non-terminal `reconnecting` phase + `recovery` `StateSignifierChip` + `logStreamReconnecting` announcement, and `onStatus` resets accumulated lines when resuming from `reconnecting` so the re-seeded backlog replaces rather than duplicates. [useRunnerLogStream.ts / StepExecutionLogViewer.tsx / announcements.ts]
- [x] [Review][Patch] Uncaught best-effort redaction exception aborts the whole live follow — **FIXED 2026-06-22**: per-line `redact(...)` in `streamLive` now wrapped in try/catch; on failure it emits a safe `[REDACTION_ERROR]` placeholder (never the raw line), increments count, logs the cause only, and continues following. [StepLogStreamService.java:113-129]
- [x] [Review][Patch] UTF-8 multibyte char split across Docker frames decodes to U+FFFD (mojibake) — **FIXED 2026-06-22**: `LineBuffer` now buffers raw BYTES across frames (`ByteArrayOutputStream`) and decodes UTF-8 at the line boundary; `onNext` passes the byte payload. New test `followContainerLogsDecodesMultiByteUtf8SplitAcrossFrames` feeds `€` split across two frames. [DefaultDockerEngineGateway.java + DefaultDockerEngineGatewayTest.java]
- [x] [Review][Patch] AC8 "localhost-only enforcement" assertion missing for the new endpoint — **FIXED 2026-06-22**: added `streamEndpointAddsNoOwnBindingAndInheritsTheLoopbackGuard` asserting the endpoint introduces no binding of its own (plain mapping under `/api/v1/workflows`, no host/headers/params conditions) so it inherits the global loopback `RestBindingGuard` (authoritatively tested in `RestBindingGuardTest`). [RunnerLogStreamControllerTest.java]
- [x] [Review][Patch] Unbounded live line accumulation (browser memory/DOM) — **FIXED 2026-06-22**: `onLog` now applies a `MAX_RETAINED_LINES = 5000` ring buffer (oldest lines dropped). [useRunnerLogStream.ts]
- [x] [Review][Patch] Frontend `onLog` coerces missing fields to `"undefined"`/`NaN` — **FIXED 2026-06-22**: `onLog` now validates `stream`/`line` are strings and `seq` is a non-NaN number, skipping malformed events instead of rendering `"undefined"`/`stdout-NaN`. [useRunnerLogStream.ts]
- [x] [Review][Patch] `SseLogStreamSink` check-then-act on `dead` is racy across writer threads (Low) — **FIXED 2026-06-22**: the four callback methods are now `synchronized` on the sink, making the `dead` check-then-write atomic (no stray event after end). [SseLogStreamSink.java]

- [x] [Review][Defer] OpenAPI `actorRole` query param emits `"type":"null"` instead of `type:string` [openapi.json] — deferred: **PRE-EXISTING repo-wide convention** (the committed `WorkflowController` `actorRole` param emits the identical `type:null`), not introduced by 3d-5; no functional impact (hand-written EventSource ignores the generated client). Fixing it is a cross-cutting `@Schema` change across every `actorRole` param + a coordinated OpenAPI/`schema.d.ts` regen.
- [x] [Review][Defer] Status flips live→terminal mid-resolution can show empty live + no finished fallback [StepLogStreamService.java:104-144] — deferred: narrow timing window (container present-but-exited with logs already gone, persisted log exists), best-effort live posture; not blocking.
- [x] [Review][Defer] Finished replay materializes the full 8 MiB string + complete `split("\n")` array synchronously, no backpressure [StepLogStreamService.java:168-189] — deferred: bounded by the 8 MiB cap + single local operator.
- [x] [Review][Defer] Fixed 4-thread `streamExecutor` head-of-line starvation + `@PreDestroy shutdownNow()` doesn't close installed subscriptions/emitters [RunnerLogStreamController.java:75-76,209-212] — deferred: single-operator localhost context; shutdown leak benign (process exiting).
