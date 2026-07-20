# Integration Conflict Handling (Story 4.18)

The **conflict-handling layer** is the second half of Epic-4's integration-conflict pair. Where the
[detection sweep](./conflict-detection.md) (story 4.17) is a pure producer that records disagreements
in `integration_conflicts`, story 4.18 **surfaces** those conflicts to operators and **acts** on the
high-severity ones so state can never silently advance (NFR19). It adds **no** table, migration, or
registry value — it is five thin wirings over the already-shipped `IntegrationConflictService`,
`RecoveryService.pause` (story 4.8), and `RecoveryService.reconcile` (story 4.6):

1. **REST inspection** — `GET /api/v1/integration-conflicts` (list) + `/{conflictId}` (detail).
2. **Auto-pause** — pause the run when a new high-severity conflict is detected.
3. **Dispatch gate** — refuse EXECUTION dispatch while a high-severity conflict is unresolved.
4. **Operator-queue indicator** — an unresolved-conflict count per queue row.
5. **Linear reopen-notification suppression** — hold the "run reopened" comment until reconcile.

## REST inspection surface

| Operation | Method + path | Notes |
|-----------|---------------|-------|
| `listIntegrationConflicts` | `GET /api/v1/integration-conflicts` | Keyset-paginated (newest first) + global unresolved/resolved counts. |
| `getIntegrationConflict` | `GET /api/v1/integration-conflicts/{conflictId}` | Both state snapshots + safety-ranked reconciliation suggestions. `CONFLICT_NOT_FOUND` (404) when absent. |

**List query params:** `category`, `integration` (`linear` \| `github` — mapped to the persisted
`github_pr`), `workflowRunId`, `since` (ISO-8601 lower detection-time bound), `resolved`
(`true` = resolved only, `false` = unresolved only, omitted = both), `limit` (default 50, clamped to
`[1,200]`), and an opaque `cursor` (mirrors `AuditController` conventions). Malformed filter/cursor
values raise `INVALID_COMMAND_PAYLOAD` (400). The response also carries `totalUnresolved`,
`totalResolved`, and the `totalUnresolvedByCategory` / `totalUnresolvedByIntegration` breakdowns
(global, filter-independent — mirroring the Prometheus gauge).

**Detail** maps `IntegrationConflictService.findConflictForResolution` (`ConflictResolutionView`,
both snapshots as raw JSON strings + `resolvedAt`) and attaches the per-category **safety-ranked**
`ReconciliationDecision` suggestions (`ConflictReconciliationSuggester`) that the story-4.23
reconciliation dialog pre-fills from. Each suggestion carries a coarse `safety` label
(`safe` / `risky`); safe options are ranked first.

These are read-only GETs — no idempotency key, no actor identity, no CLI equivalence (the epic
mandates CLI only for the mutation endpoints 4.10–4.14).

## Auto-pause on high-severity conflicts (NFR21)

When the detection sweep records a **new** conflict (post-insert, exactly once per
`(link, category)` via the dedup), `ConflictAutoPauseHandler.maybeAutoPause(...)` pauses the run if
the conflict's category is in the configured auto-pause set. The pause is stamped with the **system**
actor (`ActorContext.SYSTEM`) and a deterministic idempotency key
(`autopause-conflict-<conflictId>`) so a re-invocation replays instead of double-pausing, with
`reason = auto_paused_on_state_conflict`.

- **`reviewer_role = system`.** `RecoveryService.pause` now derives `reviewer_role` from the actor
  (`SYSTEM → system`, else `workflow_owner`), so the `recovery_actions` audit row distinguishes an
  auto-pause from an operator pause. `recovery_actions.reviewer_role` is free `text` (no CHECK) — no
  migration.
- **Best-effort.** The pause runs on the sweep's lock-free phase-2 path, after the conflict-write
  transaction commits. A non-pausable run (terminal / already `Paused` / `TakenOver`) is rejected by
  `pause`'s own `PAUSABLE_SOURCE_STATES` gate (`PAUSE_NOT_APPLICABLE`), which the handler swallows
  with a WARN — the sweep is never aborted. The handler does **not** re-check the run's state.

### Configuration

Auto-pause categories bind under the **existing** detection namespace
`deliveryline.integration.conflict-detection` (there is no bare `deliveryline.integration.conflict.*`
namespace — the epic's `conflict.auto-pause-on-categories` is a documentation variance):

| Key | Default | Meaning |
|-----|---------|---------|
| `auto-pause-on-categories` | `[external_state_advanced, external_state_reverted]` | Categories that auto-pause the run. An **unset** key defaults to the two state-drift categories; an **explicitly empty** list (`[]`) opts a pilot out of auto-pause entirely; unknown category tokens are skipped (never throw). |

```yaml
deliveryline:
  integration:
    conflict-detection:
      auto-pause-on-categories:
        - external_state_advanced
        - external_state_reverted
```

## Conflict-driven dispatch gate

`WorkflowOrchestrationService.dispatchExecutionInternal` (the shared body for `dispatchPlanGeneration`
/ `dispatchImplementation` and their retry twins) refuses to enqueue an EXECUTION dispatch while the
run has an unresolved **high-severity** conflict, raising
`DISPATCH_BLOCKED_BY_UNRESOLVED_CONFLICT` (409, non-retryable). The gate fires after `requireRun` and
before `enqueueDispatch`; because these dispatch methods never transition the run, throwing here
inherently leaves the run in its prior state — the "transient orchestration failure, workflow stays
put" contract (AC6). The operator must reconcile (story 4.6) before the run can proceed.

- **High-severity set** = `{external_state_advanced, external_state_reverted}` — a **fixed** set,
  **independent** of `auto-pause-on-categories`, so emptying the auto-pause config to opt out of
  auto-pause does **not** also open the dispatch gate.

## Operator-queue indicator

`OperatorRunRow` / `OperatorRunRowResponse` carry a discrete `unresolvedConflictCount` (a run can be
`FAILED` **and** conflicted, so it is **not** overloaded onto `operatorSignifier`). It is sourced via
one batch grouped query (`IntegrationConflictReadPort.unresolvedCountByRun`) over the page's run ids —
never per-row. The FE renders a non-color "Conflict" chip when the count is `> 0` (story 4.2/4.22).

The `reconcile_conflict` allowed-action is already surfaced for the workflow owner on a conflicted
non-terminal run (story 4.6's `appendConflictOverlay`); the conflict id + suggested decision travel
via the new REST list/detail, not via the flat allowed-actions list.

## Linear reopen-notification suppression

`WorkflowOrchestrationService.notifyLinearRunReopened` (the story-4.7 rerun-reopen comment,
`REQUIRES_NEW`, best-effort, never throws) skips posting the "run reopened" comment while the run has
**any** unresolved conflict on its Linear ticket link — a premature notification would mislead. The
guard is skip-with-log (WARN). It gates on any unresolved Linear-link conflict (a closed Linear
ticket is `external_resource_removed`, **not** an auto-pause category), not on "auto-pause fired".

> **Provisional reach.** Story 4.17 emits little Linear state-drift today (Linear ticket status is not
> yet cached), so this guard rarely fires in practice; it is implemented and tested against a seeded
> Linear conflict so it is correct when Linear state-drift detection lands.

## Error codes

| Code | Status | Raised by |
|------|--------|-----------|
| `DISPATCH_BLOCKED_BY_UNRESOLVED_CONFLICT` | 409 (non-retryable) | The dispatch gate (new to 4.18). |
| `CONFLICT_NOT_FOUND` | 404 | `GET /{conflictId}` for an unknown id (reused). |
| `INVALID_COMMAND_PAYLOAD` | 400 | Malformed list filter / cursor (reused). |
| `PAUSE_NOT_APPLICABLE` | 409 | Swallowed internally by the auto-pause handler (reused). |
