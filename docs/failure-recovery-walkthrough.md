# Failure-Recovery Walkthrough (Epic 1 baseline)

This walkthrough shows what an operator does when a governed run hits `Failed` in the **CLI
minimum-viable-recovery baseline** shipped in story 1.18. Story 1.22 will polish this into the
full pilot-ops handbook; for now this is the CLI subset.

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

The `recovery_actions` table itself is not yet exposed via a CLI command — story 1.22 will
extend the operator surface, and Epic 4 ships the `reconcile` / `takeover` audit views.

---

## What is NOT in Epic 1

The Epic-1 baseline ships only one recovery action: `retry`. The `RecoveryService` class is
scope-protected by an ArchUnit rule (`RECOVERY_SERVICE_IS_SCOPE_PROTECTED`) so a future
contributor cannot stealth-add Epic-4 methods. The following arrive in Epic 4:

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
