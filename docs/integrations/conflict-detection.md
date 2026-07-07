# Integration Conflict Detection (Story 4.17)

The **integration-conflict-detection sweep** is the DETECTION half of Epic-4's integration-conflict
pair. It is a **pure producer**: on a schedule it compares internal workflow state against the
cached-vs-fresh **external** Linear ticket / GitHub PR state and records any disagreement in the
`integration_conflicts` table — **never a silent overwrite** (NFR19). It builds no resolve path, REST
endpoint, auto-pause, or orchestration gate; those are stories **4.6** (`RecoveryService.reconcile`,
writes `resolved_at` / `resolved_by_action_id`) and **4.18** (operator surfacing, `GET
/api/v1/integration-conflicts`, conflict-driven pause + dispatch gate), both currently `backlog`.

## Cadence & configuration

The `@Scheduled` trigger lives in `infrastructure.config.IntegrationConflictDetectionConfiguration`
and delegates to the framework-trigger-free `application.integration.conflict
.IntegrationConflictDetectionService.sweep()`. Namespace `deliveryline.integration.conflict-detection`:

| Key           | Default  | Meaning                                                                 |
|---------------|----------|-------------------------------------------------------------------------|
| `enabled`     | see yaml | Master switch. The trigger is `@ConditionalOnProperty`-gated on it — when `false`/absent no scheduled bean registers (tests call `sweep()` directly). |
| `interval-ms` | `300000` | Fixed-delay between ticks (5 min). `<=0` normalized to the default.      |
| `batch-limit` | `100`    | Max active links scanned **per integration type per tick**. A full batch WARNs (no silent truncation); the keyset cursor advances so the remainder is scanned on subsequent ticks. |

## Sweep discipline (mirrors story 3f-8's split-rollup sweep)

1. **Two-phase, no-I/O-under-lock.** Phase 1 takes `pg_advisory_xact_lock` (key `ICON` = `0x49434F4E`,
   distinct from `RDEP`) as the first statement of a short-lived transaction, reads both scan windows,
   and commits — releasing the lock **before** any external HTTP. Phase 2 does the Linear/GitHub
   fetches + conflict writes lock-free, so a slow/throttled external host never pins the pooled
   connection or the advisory lock (an earlier single-outer-transaction shape risked idle-in-transaction
   aborts + cross-instance lock contention).
2. **Keyset-paginated, archived-excluded scan.** A non-locking, join-fetched projection
   (`IntegrationConflictScanPort.scanActiveLinksByType`) reads up to `batch-limit` active links
   (`archived_at IS NULL AND sync_status <> 'superseded'`) per type, ordered by and keyed on
   `integration_links.id > cursor`, join-fetching `workflow_runs` for `current_state` and surfacing
   `integration_links.project_id`. A per-type in-memory cursor advances each tick (wrapping to the
   oldest link at the tail) so links beyond a single batch are covered across ticks — no bare-`LIMIT`
   tail starvation. The cursor is process-local; on restart or across instances the scan simply
   re-reads from the oldest link, and the insert-or-skip dedup makes those re-reads a no-op.
3. **Per-link `REQUIRES_NEW` + swallow-and-WARN.** Each conflict WRITE (and each best-effort baseline
   snapshot) runs in its own transaction so one bad link never aborts the sweep; per-link errors are
   WARNed and retried next tick.
4. **Insert-or-skip dedup.** The write is `INSERT … ON CONFLICT DO NOTHING` against the partial-unique
   index `uq_integration_conflicts_unresolved (integration_link_id, conflict_category) WHERE
   resolved_at IS NULL AND archived_at IS NULL`. Without it the 5-minute sweep would spam a fresh row
   + event every tick for the same standing conflict. The `integration.conflictDetected` event is
   emitted **only on a real insert**.

## Conflict categories (`IntegrationConflictCategory`)

| Category                   | GitHub trigger (cached `prState` vs fresh `getPullRequestByRef`) | Linear |
|----------------------------|------------------------------------------------------------------|--------|
| `external_state_advanced`  | fresh `merged=true` while cached `open` (merged externally)      | provisional (OQ-1) |
| `external_state_reverted`  | fresh `open` while cached `closed` (reopened)                    | provisional (OQ-1) |
| `external_resource_removed`| fresh `Optional.empty()` (deleted) / permanent not-found failure | fresh `Optional.empty()`; permanent not-found |
| `metadata_drift`           | branch / repo drift vs cached baseline                           | provisional (OQ-1) |
| `link_broken`              | permanent access failure (permission / auth / branch-protected)  | permanent access failure (`link_failure` / auth) |

**GitHub** carries all five categories (requires the `PullRequest.merged` widening — GitHub's REST
`state` collapses merged and closed). **Linear** reliably carries `external_resource_removed` +
`link_broken` today; state-drift categories are **provisional** because Linear ticket status is not
cached — the sweep snapshots a first-seen `sourceStatusId` baseline into `external_metadata` (OQ-1) to
enable a future story, but 4.17 emits no Linear state-drift conflict.

## Failure classification (FR43, reuses `IntegrationFailureCategory`)

- **Transient** (`sync_failure` / `network_api_failure` / `github_rate_limited`): WARN + **skip** the
  link (no conflict row); a rate/network class **short-circuits that integration's remaining links for
  the tick** (AC8 back-off).
- **Permanent removal** (`github_pr_not_found` / `link_failure`): `external_resource_removed`.
- **Permanent access** (`github_permission_denied` / auth / branch-protected): `link_broken`.

The classifying `IntegrationFailureCategory.value()` is stored in the conflict's
`external_state_snapshot` and on the event's `failureCategory` detail key.

## Events, metrics & alerts

- **Event:** `integration.conflictDetected` (`WorkflowEventType.INTEGRATION_CONFLICT_DETECTED`) — a
  non-lifecycle SYSTEM event (`priorState == resultingState == null`, like `integration.linked`),
  emitted once per first-inserted `(link, category)`. Detail keys reuse `failureCategory` /
  `linearTicketReference` / `githubPrReference` / `prState` / `reason` / `correlationId` /
  `workflowRunId` plus the new allow-listed `conflictId` / `conflictCategory`.
- **Metrics:** counter `deliveryline_integration_conflict_detected_total{category, integration}`
  (incremented on first-insert) and gauge `deliveryline_integration_conflict_unresolved_count{category,
  integration}` (a `MeterBinder` reading a cached snapshot in a strongly-referenced field — the scrape
  never throws / never reports `NaN`).
- **Grafana:** the "Integration Conflicts" dashboard
  (`infra/observability/grafana/dashboards/integration-conflicts.json`).
- **Alert:** `IntegrationConflictUnresolvedHigh` — `sum(deliveryline_integration_conflict_unresolved_count)
  > 5` for 10m (`infra/observability/prometheus/alerts.yml`).

## Read surface (no REST yet)

`IntegrationConflictService.listUnresolvedConflicts(ConflictFilter)` returns `List<ConflictSummary>`
where `resolved_at IS NULL AND archived_at IS NULL`, newest-first, filterable by conflict category /
integration type / time-since / workflow run / ticket reference. Bad filter values raise
`INVALID_COMMAND_PAYLOAD`. There is **no REST endpoint** — story 4.18 owns `GET
/api/v1/integration-conflicts` and its OpenAPI schema.

## The deferred resolve path

`integration_conflicts.resolved_at` / `resolved_by_action_id` ship **nullable and unwritten** by this
story (the "column-defined-now, written-by-a-later-story" precedent). Story 4.6's
`RecoveryService.reconcile` reads a `conflictId`, sets `resolved_at` / `resolved_by_action_id`
(→ `recovery_actions.public_id`), and the dedup index then permits the sweep to re-detect a genuinely
recurring conflict.
