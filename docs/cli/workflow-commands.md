# DeliveryLine Workflow CLI Commands

This reference covers the four Spring Shell commands shipped through story 1-18:
`deliveryline submit`, `deliveryline status`, `deliveryline history`, and `deliveryline retry`.

Each command is a thin Spring Shell adapter over an application service. Orchestration,
persistence, redaction, and approval logic live behind `WorkflowCommandService` and
`WorkflowInspectionService` — the adapter only parses options, threads MDC, and renders output.

For the cross-cutting error contract and exit-code bands, see [`README.md`](README.md).

---

## `deliveryline submit`

Submit a Linear ticket reference for governed execution. Creates the workflow run, the initial
`workflow.stateChanged` event, and an `integration_links` row in the same transaction (story 1.14
+ 1.15 wiring) — if the Linear ticket cannot be resolved the entire submit rolls back.

```
deliveryline submit \
  --ticket <ref> \
  --actor-identity <id> \
  --actor-type <type> \
  [--idempotency-key <k>] \
  [--correlation-id <c>] \
  [--verbose]
```

Successful output (single line):

```
{runId} submitted (state: Inbox)
```

When `--idempotency-key` is omitted in an interactive shell, the CLI generates a UUIDv7 and
surfaces it on stdout as `[generated-idempotency-key: <uuid>]` so the operator can replay if the
response is ever lost. In non-interactive (scripted) mode, omitting `--idempotency-key` fails fast
with `[MISSING_IDEMPOTENCY_KEY] ...` and exit code `101`.

`--verbose` additionally appends `[correlation-id: <uuid>]` (the resolved correlation ID — the
caller-supplied value if `--correlation-id` was provided, otherwise the auto-generated UUIDv7)
so the operator can copy/paste it into a `grep correlationId=<uuid> deliveryline.log` invocation
to trace this submit end-to-end. The plain (non-verbose) output never prints the correlation ID
— see [`../observability/log-schema.md`](../observability/log-schema.md) for the structured-log
surface.

---

## `deliveryline status`

Print the current state of a governed workflow run.

```
deliveryline status <runId> [--format text|json] [--correlation-id <c>] [--verbose]
```

When `--verbose` is supplied the rendered output is followed by `[correlation-id: <uuid>]` so
operators can grep the structured log surface for the resolved correlation ID. Non-verbose
output is unchanged.

Text mode emits, in order: `current state`, `current actor`, `last event type`, `last event
timestamp` (ISO-8601 UTC), `latest artifact <type> v<version>` (one line per artifact type that
has any record), `linked ticket: <type>:<externalRef>` (omitted if no active link), and a
**failure-diagnostic block** (only on `Failed` runs) carrying `failed stage`, `last successful
stage`, `failure timestamp`, `failure category`, and `last activity timestamp`, followed by the
final `next safe action: <retry | await_manual_reconciliation | await_outcome | view_only>` line.

JSON mode always emits the five failure-diagnostic fields (`failedStage`, `lastSuccessfulStage`,
`failureTimestamp`, `failureCategory`, `lastActivityTimestamp`) — `null` on non-Failed runs — so
the schema's `required` + `additionalProperties:false` contract pins a stable shape across
states.

JSON mode emits a single `application/json`-compatible object conforming to the
[`workflow-status.v1`](../../deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json)
schema.

```bash
deliveryline status run_abc1234 --format json
```

---

## `deliveryline history`

List append-only workflow events for a run in chronological order.

```
deliveryline history <runId> [--format text|json] [--since <iso-8601>] [--correlation-id <c>] [--verbose]
```

`--since` filters to events with `created_at >= since`; values must parse as `OffsetDateTime`
(ISO-8601 with an explicit zone, e.g. `2026-05-13T09:00:00Z`). Invalid values fail with
`[INVALID_TIME_RANGE] ...` and exit code `101`. The inspection layer is bounded at **1000 events
per page** — when a run exceeds that ceiling the command fails with `[HISTORY_TOO_LARGE] ...` and
the operator must pass `--since` to narrow the range.

Text mode emits one line per event:

```
<isoTimestamp> <eventType> <actorIdentity>/<actorType> <priorState>-><resultingState> [reason="..."] [[intervention]] [details={k=v, ...}]
```

JSON mode emits a single object conforming to the
[`workflow-history.v1`](../../deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json)
schema.

---

## `deliveryline retry`

Retry the last failed step of a `Failed` governed workflow run.

```
deliveryline retry <runId> \
  --actor-identity <id> \
  --actor-type <type> \
  [--idempotency-key <k>] \
  [--correlation-id <c>] \
  [--reason <text>] \
  [--verbose]
```

Successful output (single line):

```
{recoveryActionId} retry submitted (state: Executing) [runner-execution: {newRunnerExecutionId}]
```

Replay output (when the same `--idempotency-key` is reused — no second dispatch, no second
`recovery_actions` row):

```
{priorRecoveryActionId} retry submitted (state: Executing) [replayed]
```

`--verbose` appends `[correlation-id: ...]`, `[recovery-event: evt_...]`, and (when
`--idempotency-key` was auto-generated in interactive mode) `[generated-idempotency-key: ...]`.

### Exit codes

- `201` `RETRY_NOT_APPLICABLE` — the run is not in state `Failed`. Inspect with
  `deliveryline status <runId>` and verify the run actually failed before retrying.
- `201` `IDEMPOTENCY_KEY_CONFLICT` — the idempotency key was already used for a different
  workflow command; pick a fresh key.
- `101` `MISSING_IDEMPOTENCY_KEY` / `INVALID_IDEMPOTENCY_KEY` — same UX as `submit`.

### Dispatch-failure audit (`recovery.dispatchFailed`)

The recovery flow commits its prep work — workflow state transition, `recovery.retried` event
append, and `recovery_actions` insert — before invoking `RunnerBroker.dispatch(...)`. If dispatch
then fails, the typed broker error reaches the audit trail through a second appended event,
`recovery.dispatchFailed`, instead of mutating the already-committed `recovery.retried` row
(NFR4 append-only). The new event carries `prior_state=resulting_state=Executing`,
`intervention_marker=true`, and `details` with `errorCode`, `errorClass`, `failedStage`,
`recoveryActionId`, `recoveryRetriedEventId`, `idempotencyKey`, and (when present)
`correlationId`, `reason`, and `compensationFailed`. The CLI propagates the original broker
exception as the visible failure; the dispatch-failed event surfaces in `deliveryline history`
so operators can correlate the audit trail with the CLI error.

### Scope (Epic 1 baseline)

`retry` is the only recovery action in the Epic 1 CLI. Deeper recovery —
`reconcile`, `take over`, `rerun-from-arbitrary-step`, failure-taxonomy classification, the full
operator console, and the REST recovery endpoints — arrive in Epic 4. The `RecoveryService`
class was originally scope-protected by an ArchUnit rule
(`RECOVERY_SERVICE_IS_SCOPE_PROTECTED`); that lock was lifted in Epic 4 (story 4.28) and the
allowed recovery surface is now governed by
[ADR 0033](../adr/0033-recovery-service-scope-lift.md).

### `next safe action` matrix

The `status` command's `next safe action` field reflects the recommended operator action:

| Current state | Has artifact-op `failed`/`failed_orphan`? | `next safe action` |
|---|---|---|
| `Failed` | yes | `await_manual_reconciliation` |
| `Failed` | no | `retry` |
| `Inbox` / `Planned` / `Investigating` / `WaitingForSpecApproval` / `Executing` / `WaitingForReview` / `Paused` | (irrelevant) | `await_outcome` |
| `Completed` / `TakenOver` / `Reconciled` | (irrelevant) | `view_only` |

`await_manual_reconciliation` is intentionally not in the `AllowedAction` registry — it is a
CLI-surface label, not a frontend action enum. Epic 4 introduces the formal `AllowedAction` for
operator reconciliation; until then, the CLI prints the literal string and the operator follows
the Epic 4 takeover/reconcile playbook.

---

### Redaction contract for `details`

The `workflow_events.details` map is free-form and may carry caller-supplied fields. The CLI
allows only a closed set of keys to surface through `status` / `history`:

- `linearTicketReference`
- `artifactId`
- `artifactVersion`
- `contextVersion`
- `correlationId`

Every other key — including `idempotencyKey` — is dropped before render. The remaining payload
also passes through `RedactionPolicyService.redact(...)` as a defense-in-depth pass so a secret
that an operator naively pasted into a permitted field is still scrubbed before reaching stdout.

---

## JSON schema version contract

The `schemaVersion` field on both `workflow-status.v1` and `workflow-history.v1` is a const `1`.
The backward-compatibility rule:

- **Additive fields** (new optional properties) are allowed within `schemaVersion: 1`.
- **Removing** or **renaming** any existing field requires bumping `schemaVersion` to `2` and is
  a breaking change for downstream consumers.

---

## End-to-end example (with `linear-mock` + `runners.mock` profiles)

```bash
# 1. Bring up Postgres + the backend with mock profiles.
docker compose up -d
SPRING_PROFILES_ACTIVE=local,linear-mock,runners.mock \
  ./mvnw -pl deliveryline-backend spring-boot:run

# 2. From a separate terminal, submit a fixture ticket.
deliveryline submit \
  --ticket LIN-101 \
  --actor-identity alex \
  --actor-type human \
  --correlation-id quickstart-1

# 3. Inspect the run state and event history.
deliveryline status  run_<<from step 2>>
deliveryline history run_<<from step 2>> --format json
```

Story 1.22 owns the full quickstart and pilot-setup walkthrough — this page only documents the
four commands themselves.
