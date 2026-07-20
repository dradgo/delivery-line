# Failure-Recovery Walkthrough (Epic 1 baseline)

> **Pilot-installer validator:** `_____________________________` (to be named before Epic 1 close)

This walkthrough is the **Epic-1 pilot-ops handbook for failed governed runs**. Epic 4 will
extend it with the operator console plus `reconcile` / `takeover` / `resume` / `rerun` actions;
for now, `retry` is the only CLI-driven recovery action shipped (story 1.18 minimum-viable
baseline). It pairs with [`quickstart.md`](quickstart.md) (the happy path) as the two docs the
pilot installer is expected to read end-to-end.

For the command syntax see [`cli/workflow-commands.md`](cli/workflow-commands.md). For the full
exit-code matrix see [`cli/README.md`](cli/README.md).

---

## TL;DR

```bash
# 1. Discover that a run failed.
deliveryline status run_abc1234

# 2. Inspect the failure-diagnostic block on the status output. Note the:
#    failed stage, last successful stage, failure category, last activity timestamp,
#    and `next safe action`.

# 3. If `next safe action: retry`, retry it.
deliveryline retry run_abc1234 --actor-identity alex --actor-type human

# 4. If `next safe action: await_manual_reconciliation`, do NOT retry — open an Epic-4
#    operator-reconciliation ticket and wait. Retrying through a partial artifact write
#    can corrupt the artifact lineage.
```

---

## Step 1 — Discover the failure

When a runner stage crashes, times out, or is detected as orphaned, the workflow transitions
`Executing → Failed` and `deliveryline status` reflects the new posture:

```text
current state: Failed
current actor: amelia/agent
last event type: workflow.stateChanged
last event timestamp: 2026-05-15T10:00:00Z
linked ticket: linear:LIN-101
failed stage: execution
last successful stage: Executing
failure timestamp: 2026-05-15T10:00:00Z
failure category: runner_timeout
last activity timestamp: 2026-05-15T09:59:30Z
next safe action: retry
```

JSON mode emits the same fields under the `workflow-status.v1` schema (the five failure-diagnostic
fields are always present — `null` on non-Failed runs — so the schema shape is stable).

`failed stage` and `last activity timestamp` come from the most recent failed
`runner_executions` row; `last successful stage`, `failure timestamp`, and `failure category`
come from the `workflow.stateChanged → Failed` event row.

---

## Step 2 — Decide: retry vs. await_manual_reconciliation

The `next safe action` line is the operator's decision aid. Two safe values; everything else is
informational:

| `next safe action` | Meaning | What to do |
|---|---|---|
| `retry` | The run failed but no artifact write is in flight. A clean retry is safe. | Run `deliveryline retry`. |
| `await_manual_reconciliation` | An `artifact_operations` row for this run is in `failed` or `failed_orphan` state. A partial artifact write may be on disk; blind retry risks dual-writes. | Do NOT retry. File an Epic-4 reconciliation ticket. |
| `await_outcome` | The run is still progressing (Inbox / Executing / WaitingForReview / Paused / etc.). | Watch; no action needed. |
| `view_only` | The run is terminal (Completed / TakenOver / Reconciled). | No recovery action; for audit only. |

The `await_manual_reconciliation` label is intentionally **not** in the `AllowedAction` registry
yet — it is a CLI-surface label that signals "operator must intervene; the CLI cannot help
further". Epic 4 introduces the formal `reconcile` and `takeover` surfaces.

---

## How to interpret each `failure category`

The `failure category` value on a `Failed` run identifies **why** the run failed — the
classification axis lives in the `FailureCategory` registry
(`deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java`). Each
value below maps to a one-line "what it means" plus a one-line "operator action". The per-category
advice is **subordinate to `next safe action`** — if Step 2's `next safe action` says
`await_manual_reconciliation`, do not retry regardless of category. Use the categories to
understand *why* the run failed; use `next safe action` to decide *what to do next*.

- **`runner_timeout`** — the runner stage exceeded its configured timeout (default 600s per
  `application.yml`'s `deliveryline.runner.stage-timeouts`). **Operator action:** retry — no
  artifact write is in flight, the timeout fires from the timeout-scan loop before the runner
  can post output.
- **`runner_crash`** — the runner process exited abnormally without posting a result.
  **Operator action:** retry — the workflow state machine wrote no partial artifact.
- **`runner_contract_violation`** — the runner posted output but it failed the runner-contract
  v1 schema validation (forbidden field, path traversal, oversized payload, metadata spoof,
  stale metadata). **Operator action:** do **not** blind-retry — the runner adapter or the
  upstream prompt is misbehaving. File a ticket; if `next safe action` says `retry` you can
  retry once to rule out transient corruption, but a second `runner_contract_violation`
  warrants triage.
- **`runner_non_zero_exit`** — the runner exited non-zero without violating the contract.
  **Operator action:** retry if `next safe action` is `retry` — these are typically transient
  (adapter glitch, sub-process kill). If two retries in a row report the same category, file a
  ticket.
- **`runner_late_result`** — the runner posted a result **after** the stale-result threshold
  (`stale-threshold-multiplier × stage-timeout`). The result is rejected. **Operator action:**
  obey `next safe action` first. If it says `await_manual_reconciliation`, do not retry — a
  partial artifact may be on disk. If it says `retry`, the inspection service has already
  confirmed there is no in-flight artifact write; a single retry is safe, but a second
  `runner_late_result` warrants triage (clock skew or persistent network partition).
- **`runner_duplicate_result`** — the runner posted two results for the same execution.
  **Operator action:** do **not** retry, even if `next safe action: retry` appears — the runner
  adapter has an at-least-once delivery bug. File a ticket; Epic 4 reconciliation will resolve
  the artifact lineage.
- **`runner_malformed_output`** — the runner output failed JSON parsing or schema validation
  with a non-contract-violation root cause (UTF-8 corruption, truncated stream).
  **Operator action:** file a ticket. Retry once if `next safe action` is `retry`; persistent
  failures here indicate runner-side I/O issues.
- **`orphan`** — the runner execution is detected as orphaned (no recent activity, no posted
  result) by the orphan-scan loop. **Operator action:** retry — orphan detection itself rolls
  the workflow back to `Failed` without a partial write, so a fresh dispatch is safe.
- **`artifact_payload_unavailable`** *(surfaced as a `DomainErrorCode`, not a `FailureCategory`
  enum value)* — the runner reported success but the persisted artifact payload could not be
  fetched (storage outage, lost write, corrupted reference). This appears in the `errorCode`
  field of the `workflow.stateChanged → Failed` event details, not in the `failure category`
  column. **Operator action:** treat as `await_manual_reconciliation` — do not retry. The
  inspection service flags partial-artifact-write risk and Epic 4's `reconcile` command will
  resolve the lineage. Listed here per AC3(b) to keep the operator's mental model complete;
  the `FailureCategory` registry itself is the runtime source of truth for the dropdown of
  enum-typed categories.

Categories listed above are sourced from `FailureCategory.java` (with the
`artifact_payload_unavailable` `DomainErrorCode` cross-listed for completeness). If a CLI
output line shows a `failure category` value not listed here, the registry was extended
without an update to this doc — file an issue.

---

## Decision tree: retry vs wait

The two questions to ask in order:

1. **What does `next safe action` say?** Trust that field first — it is computed by the
   inspection service from the workflow state + artifact-operation history, not from the
   `failure category` alone.
   - `retry` → safe to retry. Continue to question 2.
   - `await_manual_reconciliation` → **do not retry**. An `artifact_operations` row is in
     `failed` or `failed_orphan` state; partial bytes may be on disk. Open an Epic-4
     reconciliation ticket and wait.
   - `await_outcome` → the run is still progressing — watch, don't act.
   - `view_only` → terminal run; nothing to do.

2. **What does `failure category` say?** Use the table above to understand *why* the run
   failed; the table never overrides Q1's `next safe action`. Even when Q1 says `retry`,
   certain categories warrant immediate triage rather than mechanical re-dispatch:
   `runner_contract_violation`, `runner_duplicate_result`, and `runner_malformed_output`
   should not be retried more than once — the same category recurring means the underlying
   adapter bug needs investigation, not a fresh dispatch.

The full `next safe action` matrix (current state × artifact-op state → recommended action)
lives in [`cli/workflow-commands.md`](cli/workflow-commands.md#next-safe-action-matrix). Read
it once when this doc still feels new; defer to that table over prose if they ever diverge.

---

## Step 3 — Retry (when `next safe action: retry`)

```bash
deliveryline retry run_abc1234 \
  --actor-identity alex \
  --actor-type human \
  --correlation-id ops-2026-05-15-1
```

Successful output:

```text
rcv_recov-aaaaa retry submitted (state: Executing) [runner-execution: rex_new1-bbbbb]
```

What happened under the hood:

1. The workflow state flipped `Failed → Executing` via the same `WorkflowCommandService.retryWorkflow(...)`
   path that future Epic-4 recovery actions will use, preserving the
   `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE` invariant.
2. A `recovery.retried` event was appended to `workflow_events` with `intervention_marker=true`
   so the audit trail clearly distinguishes operator-driven recovery from runner-driven progress.
3. A `recovery_actions` row was inserted (`action_type=retry`, `result_status=succeeded`).
4. `RunnerBroker.dispatch(...)` was called with a fresh `runner_execution_id` and a fresh
   `context_bundle_version`, using a derived idempotency key (`<key>:runner`) so the broker's
   idempotency record does not collide with the recovery action's idempotency record.

**Append-only invariant (NFR4):** retry never UPDATEs or DELETEs prior `workflow_events`,
`artifacts`, `runner_executions`, or `approvals` rows. Each retry adds exactly two new
`workflow_events` rows (the `workflow.stateChanged` from the transition service plus the
`recovery.retried` from the recovery service), one new `recovery_actions` row, and one new
`runner_executions` row.

### What happens if the broker dispatch itself fails

Once `recovery.retried` commits, the workflow state is already `Executing`. If
`RunnerBroker.dispatch(...)` then fails (network outage, runner-adapter rejection, etc.):

1. The `recovery_actions` row is flipped from `result_status=pending` to `result_status=failed`
   in its own short transaction so the failed attempt is durable.
2. A second event — `recovery.dispatchFailed` — is appended (also `intervention_marker=true`,
   prior=resulting=`Executing`) carrying the typed broker error in `details`:
   `errorCode`, `errorClass`, `failedStage`, `recoveryActionId`, `recoveryRetriedEventId`,
   plus `correlationId` and `reason` when present, plus `compensationFailed` when the
   `markFailed` compensation also tripped. (The stored row also contains `idempotencyKey`, but
   the CLI history allow-list intentionally strips it — operators see the key in the original
   `retry` stdout, never in subsequent `history` output.)
3. The original broker exception is propagated back to the CLI so the operator sees the real
   cause; the audit-trail append never overwrites it.

The workflow stays in `Executing` without a running runner — the next retry call (or the
broker's startup-recovery scan) corrects the dangling state. The append-only invariant holds:
the already-committed `recovery.retried` event is never mutated; the dispatch failure surfaces
through a second appended event.

### Replay (idempotent retry with the same key)

If you re-run the same `retry` invocation with the same `--idempotency-key`, no new dispatch
happens, no new event is appended, and no new `recovery_actions` row is inserted:

```text
rcv_recov-aaaaa retry submitted (state: Executing) [replayed]
```

This is the correct behavior — the previous attempt's outcome is preserved and the operator does
not need to worry about a double-spend.

---

## Step 4 — When NOT to retry

If `deliveryline status` shows `next safe action: await_manual_reconciliation`, the artifact
operation log carries at least one `failed` or `failed_orphan` row for this run. Blind retry can:

- Replay the runner stage that wrote a partial artifact, producing a duplicate write or worse.
- Mask the underlying classification incident (e.g. an export-classification violation that the
  reconciliation step is supposed to investigate).

The Epic-1 CLI does not include an `--force` flag for this scenario by design: the only safe path
is to open an Epic-4 operator-reconciliation ticket. Until Epic 4 ships the `reconcile` /
`takeover` commands, the workflow stays in `Failed` and the operator coordinates remediation
out-of-band.

---

## Step 5 — Inspect the recovery audit trail

`deliveryline history run_abc1234` shows every workflow event in chronological order, including
the `recovery.retried` event with the `[intervention]` marker:

```text
2026-05-15T09:00:00Z workflow.stateChanged alex/human (none)->Inbox reason="workflow submitted" details={linearTicketReference=LIN-101, correlationId=ops-2026-05-15-1}
…
2026-05-15T10:00:00Z workflow.stateChanged system/system Executing->Failed reason="runner timeout"
2026-05-15T10:05:00Z workflow.stateChanged alex/human Failed->Executing reason="retry from failed execution"
2026-05-15T10:05:00Z recovery.retried alex/human Failed->Executing reason="retry from failed execution" [intervention]
```

On the dispatch-failure branch a third event — `recovery.dispatchFailed` — sits at the tail of
the history with `Executing->Executing` and the typed broker error stamped into details. The
allow-listed audit keys (`errorCode`, `errorClass`, `failedStage`, `recoveryActionId`,
`recoveryRetriedEventId`, optional `compensationFailed`, plus `correlationId` and `reason`
when present) are rendered alongside the standard event fields so operators can triage from
`history` without grep'ing structured logs:

```text
2026-05-15T10:05:00Z recovery.dispatchFailed alex/human Executing->Executing reason="broker dispatch failed: RUNNER_CONTRACT_VIOLATION" [intervention] details={failedStage=execution, recoveryActionId=rcv_recov-aaaaa, recoveryRetriedEventId=evt_recret-bbbbb, errorCode=RUNNER_CONTRACT_VIOLATION, errorClass=DomainException, correlationId=ops-2026-05-15-1}
```

The `recovery_actions` table itself is not yet exposed via a CLI command — Epic 4 ships the
`reconcile` / `takeover` audit views and the operator-console surface.

---

## What is NOT in Epic 1

The Epic-1 baseline ships only one recovery action: `retry`. The `RecoveryService` class was
originally scope-protected by an ArchUnit rule (`RECOVERY_SERVICE_IS_SCOPE_PROTECTED`) so a future
contributor could not stealth-add Epic-4 methods; that lock was lifted in Epic 4 (story 4.28) once
its deeper-recovery scope landed — the allowed surface is now governed by
[ADR 0033](adr/0033-recovery-service-scope-lift.md). The following arrive in Epic 4:

- `resume(...)` — pick up a paused or interrupted run
- `rerun(...)` — replay a specific stage with a fresh context
- `reconcile(...)` — operator-driven artifact reconciliation
- `pause(...)` — operator-driven pause (mid-flight halt)
- `classifyFailure(...)` — taxonomy classification of the failure
- `takeover(...)` — operator takes ownership of a stuck run
- REST endpoints `/api/v1/workflows/{id}/retry`, `/api/v1/workflows/{id}/resume`, etc.
- The full operator console UI

If you need any of these in Epic 1, the right answer is to escalate to a product / architecture
review before opening an Epic-4 story.
