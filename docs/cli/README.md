# DeliveryLine CLI

The workflow commands (`submit`, `status`, `history`, `retry`) are documented in
[`workflow-commands.md`](workflow-commands.md).

For the failure-recovery walkthrough — when a run hits `Failed` and the operator decides between
`retry`, `await_manual_reconciliation`, and Epic-4 deeper-recovery options — see
[`../failure-recovery-walkthrough.md`](../failure-recovery-walkthrough.md).

The diagnostic `doctor` command is documented in
[`doctor.md`](doctor.md).

The operator fleet-view command `operator status` is documented in the
[`## deliveryline operator status`](#deliveryline-operator-status) section below.

The `sync-completion` command — and the Linear completion-sync feature it re-triggers (write a
merge-ready summary back to the source Linear ticket when a run completes) — is documented in
[`../integrations/linear-completion-sync.md`](../integrations/linear-completion-sync.md).

The supported-environment matrix (OS / shell / container-runtime / Java / Node) consumed by the
`doctor` `supported-environment` check is documented in
[`../supported-environments.md`](../supported-environments.md).

The CI pipeline that gates every merge into `main` is documented in
[`../ci-pipeline.md`](../ci-pipeline.md). Operational steps for marking `foundation-gate` as a
required status check on `main` branch protection are in
[`../ci-branch-protection.md`](../ci-branch-protection.md).

## Exit Code Bands

DeliveryLine shell commands keep successful output unchanged. When a command fails with a governed `DomainException`, the CLI emits a single-line machine-readable error in this format:

`[{code}] {detail}`

The non-zero exit code band communicates the error family:

- `1xx`: client-like request or validation errors
- `2xx`: concurrency or idempotency conflicts
- `3xx`: runner or integration execution failures
- `4xx`: infrastructure or unexpected internal failures

Current mappings in the foundation slice:

- `101`: stable client-side failures such as `INVALID_COMMAND_PAYLOAD`, `ILLEGAL_TRANSITION`, `RUN_NOT_FOUND`, `MISSING_IDEMPOTENCY_KEY`, `INVALID_IDEMPOTENCY_KEY`, `INVALID_TIME_RANGE`, `HISTORY_TOO_LARGE`, and other governed non-retry transport errors
- `201`: `IDEMPOTENCY_KEY_CONFLICT`, `STALE_IDEMPOTENCY_RESERVATION`, `CONCURRENT_TRANSITION_CONFLICT`, `APPROVAL_VERSION_MISMATCH`, `EXPORT_CLASSIFICATION_VIOLATION`, `RETRY_NOT_APPLICABLE`
- `301`: `RUNNER_TIMEOUT`, `RUNNER_CONTRACT_VIOLATION`, `ARTIFACT_PAYLOAD_UNAVAILABLE`
- `401`: infrastructure and safety failures including `DOCTOR_*` codes and `INTERNAL_ERROR`

## Idempotency Keys

`deliveryline submit` accepts `--idempotency-key` explicitly in all modes.

- Interactive CLI: if `--idempotency-key` is omitted, DeliveryLine generates a UUIDv7 key locally and always surfaces it inline as `[generated-idempotency-key: ...]` so the operator can replay if the response is lost in transit.
- Non-interactive/scripted CLI: an explicit idempotency key is required.
- `--verbose`: retained as a no-op for backward compatibility; the auto-generated key is now surfaced regardless.

## Correlation IDs (story 1.19)

Every CLI command and REST request carries a stable `correlationId` that flows through structured logs end-to-end.

- **CLI:** `submit`, `status`, `history`, `retry`, and `doctor` accept `--correlation-id <uuid>` (any UUID version). If omitted, a fresh UUIDv7 is generated. When `--verbose` is supplied, the resolved value is appended to stdout as `[correlation-id: <uuid>]` so operators can grep the log surface for it (`grep correlationId=<uuid> deliveryline.log`).
- **REST:** clients can supply `X-Correlation-Id: <uuid>` on any request; the value is echoed on the response header and stamped on the structured-log MDC. Invalid or absent values trigger generation of a fresh UUIDv7.
- **Problem Details:** any `application/problem+json` response carries a top-level `correlationId` extension populated from MDC at the catch site. The `instance` field stays as the request path.
- **Log schema:** see [`../observability/log-schema.md`](../observability/log-schema.md) for the demo-profile JSON shape and the stable MDC key surface.

## deliveryline operator status

Story 4.1 (FR — Epic 4). A **read-only fleet view** of every run in a non-happy operator state
across all workflows, with diagnostic summaries — the CLI analogue of `deliveryline workers status`
and a fleet extension of the per-run `deliveryline status`. It spots patterns (e.g. "3 runs stalled
on the same stage in the last hour") without opening each run individually.

```
deliveryline operator status \
  [--state failed,stalled,orphaned,takenover,overridden] \
  [--since 1h|24h|7d] \
  [--format text|json] \
  [--limit N] \
  [--correlation-id <uuid>] [--verbose]
```

### Flags

- `--state` — comma-separated **operator-state** tokens (default `failed,stalled,orphaned`). This is
  a distinct operator vocabulary, **not** the `WorkflowState` registry — there is no `Stalled`,
  `Orphaned`, or `Overridden` state. Each token maps to a derived predicate:
  | token | matches |
  |---|---|
  | `failed` | `currentState = Failed` |
  | `orphaned` | `Failed` **and** the latest Failed transition carried `failureCategory = orphan` |
  | `takenover` | `currentState = TakenOver` |
  | `stalled` | an active run (`Investigating` / `Executing` / `WaitingForManualExecution`) with no transition inside the stall window |
  | `overridden` | latest event set `interventionMarker = true` and the current state is non-terminal (provisional — see below) |
  Selecting multiple tokens returns the **union**, deduped by run. An unknown token →
  `INVALID_COMMAND_PAYLOAD`.
- `--since` — a **relative** activity window `(\d+)(m|h|d|w)` (e.g. `30m`, `1h`, `24h`, `7d`, `2w`);
  only runs whose last transition falls inside the window are listed. This is **not** ISO-8601
  `Duration.parse` (`1h`, not `PT1H`). An invalid value → `INVALID_COMMAND_PAYLOAD`.
- `--format` — `text` (default) or `json`. An invalid value → `INVALID_COMMAND_PAYLOAD`.
- `--limit` — maximum runs listed (default `100`, clamped to `[1, 500]`). The `--limit` caps only the
  `runs[]` page; the `byState` / `byFailureCategory` histograms, `total`, and `oldestEntryAt` always
  reflect the **full** matching set.

### Output

Text mode renders a header (per-state and per-failure-category histograms, total, oldest-entry
timestamp) followed by one grep-safe line per run. Color coding (red for failed/orphaned, yellow for
stalled, dim for takenover) is emitted **only** for an interactive TTY; a non-color signifier — a
leading UPPERCASE bracketed state label like `[FAILED]` / `[STALLED]` / `[ORPHANED]` / `[TAKENOVER]`
— always precedes each row so the output stays readable when color is stripped (story 2.3 AC5).

### JSON schema

`--format json` emits the stable `operator-run-summary.v1` schema
(`deliveryline-backend/src/main/resources/schemas/cli/operator-run-summary.v1.schema.json`), leading
with `schemaVersion: 1`. **Backward-compatibility contract:** additive fields are allowed within v1;
any field removal or rename bumps the schema to v2 (same contract as `workflow-status.v1`).

### Notes

- **Read-only.** The command touches no write path, transition, queue, or recovery service. In Epic
  4's deferred-RBAC posture it is invocable by any local user; a `view_operator_status` allowed
  action is **deferred to E5+ role-based access** (story 2.14 mechanism) and is intentionally not
  registered in this story.
- **`overridden` is provisional (OQ-1).** No first-class "overridden" concept exists yet; the token
  currently matches runs whose latest event set `interventionMarker = true` in a non-terminal state.
  The binding may change before the UI queue (story 4.2) consumes the same vocabulary.
