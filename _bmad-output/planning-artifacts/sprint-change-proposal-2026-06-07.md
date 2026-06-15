# Sprint Change Proposal — Non-CLI Ticket Submission

**Date:** 2026-06-07
**Author:** Alex (via bmad-correct-course)
**Change scope:** Moderate (backlog reorganization — two net-new stories added to active slices)
**Status:** Proposed — awaiting approval

---

## Section 1: Issue Summary

**Problem statement.** Today the only way a ticket becomes a governed run is the CLI (`deliveryline submit --ticket LIN-123`, story 1.15). Operators and PMs need a non-CLI submission path — either a **UI submit** form or a **scheduled auto-ingest** job.

**Discovery context.** Surfaced during the active-slice (Epic 2a + 3a) push toward the end-to-end "Linear ticket → real spec → review" scenario. A correct-course search of the PRD, all epic files, the UX spec, and `sprint-status.yaml` confirmed there is **no existing story** covering non-CLI initiation.

**Evidence.**
- **REST submit endpoint already exists** — `WorkflowController.submit()` → `POST /api/v1/workflows/submit-workflow`, body `SubmitWorkflowRequest { linearTicketReference, actorIdentity, actorType, correlationId }`. The backend write path is built and in the generated OpenAPI client; there is simply **no UI consumer**.
- **The scheduled Linear poll is a watcher, not an ingester** — `LinearPollingHost` (lines 32–35): *"The polling loop does not create new integration links — ingestion happens via CLI submit (story 1.15)."* It only touches `integration_links.last_sync_at`.
- **PRD FR1** says *"Product Managers can initiate a governed workflow from a low-risk ticket reference"*, but the FR-coverage table (`epics.md:293`) scopes **FR1 → Epic 1 (CLI)** only. Non-CLI initiation was never written as a story.
- **The product UI even tells users to use the CLI** — queue empty-state copy (`epics.md:1308`): *"New runs from Linear will appear here once submitted via the CLI."*

---

## Section 2: Impact Analysis

**Epic impact.**
- **Epic 2a (active, frontend):** gains one net-new frontend story — the UI submit form. No re-sequencing; slice is otherwise complete.
- **Epic 3a (active, backend):** gains one net-new backend story — scheduled auto-ingest. Sits upstream of the existing **3a-1** spec-stage auto-dispatch (auto-created run lands in `Inbox` → 3a-1 dispatches the spec runner). No re-sequencing.
- **Epics 2b / 3b / 4–6:** unaffected.

**Story impact.**
- **Touch-point (story 2.20):** the queue empty-state copy (`"...submitted via the CLI"`) should be updated by the new UI-submit story to point at the form. Captured as an AC in 2a-1, not a separate edit.
- No existing completed story is invalidated. CLI submit (1.15) and the polling watcher (1.14/3a-4) remain valid; the new work is additive.

**Artifact conflicts.**
- **PRD:** No change required. This *closes* the FR1 gap rather than conflicting with it (FR1 already names PMs as initiators).
- **Architecture:** No change. AR8 (shared command model — CLI/REST translate to the same `submit`) and AR18 (idempotent-by-ticket Linear intake) already cover both paths; the new stories realize existing architecture.
- **UX spec:** Minor — a "submit a run" entry point is a new surface; build to existing shell/feedback infra (2.7/2.21/2.22). No new design-decision rows required for MVP.

**Technical impact.**
- **2a-1 (UI submit):** frontend-only. Backend endpoint + OpenAPI client already exist → **no backend change**.
- **3a-5 (auto-ingest):** backend-only, additive. Extends `LinearPollingHost` to call the existing `WorkflowCommandService.submit`; gated behind a default-off opt-in flag so default behavior stays byte-identical.

---

## Section 3: Recommended Approach

**Selected path: Direct Adjustment (Hybrid) — add two net-new stories, one per active slice.**

- **Effort:** Low–Medium. Both ride existing plumbing (REST endpoint for the UI; submit command + poll loop for auto-ingest).
- **Risk:** Low. 2a-1 adds no backend surface; 3a-5 is opt-in (default off) and reuses idempotent submit + the existing per-ticket failure-isolation pattern.
- **Rationale:** Rollback (Option 2) and MVP-review (Option 3) are not warranted — nothing built is wrong, and MVP scope is *extended*, not reduced. The two paths are complementary: UI submit serves the human PM; auto-ingest serves hands-off operation and feeds 3a-1's auto-dispatch.

---

## Section 4: Detailed Change Proposals

### New Story 2a-1 (Epic 2a) — UI Submit: New Governed Run from the Web App

> Append to `epics.md` Epic 2 section (after Story 2.29). Add `2a-1-...: backlog` to `sprint-status.yaml` epic-2a block.

**As a** Product Manager / Workflow Owner,
**I want** a "Submit a Run" form in the web app that creates a governed run from a Linear ticket reference — calling the existing `POST /api/v1/workflows/submit-workflow`,
**So that** I can initiate work without the CLI (realizing PRD FR1's PM-initiation, which was CLI-only until now).

**Backend status:** DONE — `WorkflowController.submit()` + `SubmitWorkflowRequest` already exist and are in the generated OpenAPI client. **No backend change.**

**Dependencies:** 2.5 (router), 2.6 (TanStack Query + generated client), 2.7 (shell), 2.13 (REST mutations in OpenAPI), 2.19 (mutation-hook + button-precedence + idempotency conventions), 1.8 (ProblemDetails error rendering). Coupling note: persistent feedback infra is story 2.21 (Epic 2b, backlog) — submit-result surface reuses it.

**AC-shape reference** (model on the 2.19 mutation-composite):
- Typed route (e.g. `/submit`) reachable from the queue shell; **update the story 2.20 empty-state CTA** (currently `"...submitted via the CLI"`) to link to this form.
- Fields: `linearTicketReference` (req, ≤128), `actorIdentity` (req, ≤128), `actorType` (select from `ActorType`), optional `correlationId`; client validation mirrors backend `@NotBlank/@Size`.
- `useSubmitWorkflow` hook on the generated client; sends `Idempotency-Key` (UUIDv7, regenerated per distinct submit, reused on retry); button follows locked>error>stale>submitting>blocked>disabled>success>ready precedence.
- Success: persistent (non-toast) confirmation with new `runId` + state `Inbox` + link to run detail.
- Failure: render ProblemDetails `code`/`detail` (`LINEAR_TICKET_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, `MISSING_IDEMPOTENCY_KEY`) via the shared error surface; retry preserves the idempotency key.
- Tests (vitest/MSW): happy submit, validation gating, error-code rendering, idempotency-key reuse on retry, empty-state CTA navigation.
- Logging: field-only client logs (submitAttempt/Success/Error), no PII (2.19 negative-test convention).

**Note:** Closes the FR1 "UI form" gap (FR-coverage table scoped FR1 to Epic 1 CLI). Same command, same idempotency, same domain errors (AR8 equivalence).

---

### New Story 3a-5 (Epic 3a) — Scheduled Linear Auto-Ingest: Poll-Driven Run Creation

> Append to `epic-03-agent-execution.md` (after Story 3a-3 stub). Add `3a-5-...: backlog` to `sprint-status.yaml` epic-3a block.

**As a** Workflow Owner / operator,
**I want** the scheduled Linear poll (`LinearPollingHost`) to auto-create a governed run for each *qualifying* newly-discovered ticket — via the same `WorkflowCommandService.submit` the CLI/REST use,
**So that** low-risk tickets enter the workflow with no CLI (turning the Epic-1 watcher into an opt-in auto-ingest intake).

**Current behavior to change:** `LinearPollingHost.pollLinearInternal()` only touches `last_sync_at` and explicitly does not create links (lines 32–35). This story adds the create path.

**Dependencies:** 1.13/1.14 (poll loop + `pollNewTickets`), 1.7 (`WorkflowCommandService.submit` + `SubmitWorkflowCommand`), 1.12 (IdempotencyService), 3a-4 (team/project poll scoping — bounds eligibility), 3a-1 (spec auto-dispatch — the downstream consumer of the created run), AR18 (idempotent-by-ticket intake).

**AC-shape reference:**
- **Feature gate:** new `deliveryline.linear.auto-ingest.enabled` (default **false**, OPTIONAL+UNVALIDATED appended → no `@SpringBootTest` yaml break per `[[validated-config-needs-test-yaml]]`). Off ⇒ behavior byte-identical to today (watcher only).
- **Eligibility filter:** ingest only tickets matching a configured label/state allow-list (+ the existing 3a-4 team/project scope). Non-qualifying tickets are touched (existing path) but not submitted. Never auto-ingest the whole workspace.
- **Idempotent submit:** for each eligible ticket with no existing active integration link, build a `SubmitWorkflowCommand` (`actorType=SYSTEM`) with a **deterministic idempotency key derived from ticket identity** (re-polls + JVM restarts cannot double-create — replay per 1.12), and call `WorkflowCommandService.submit`. Existing-link tickets keep touch-only (AR18).
- **Failure isolation:** a per-ticket submit failure logs WARN + classified category and does not abort the batch or stall the watermark (mirror existing per-ticket touch best-effort + cursor-preservation).
- **Watermark safety:** cursor advancement unchanged; partial-failure batch preserves the cursor exactly as today.
- **Boundary:** `LinearPollingHost` (infrastructure) drives intake via the application command port only — no repo/orchestration logic (AR11), no `adapters..` import (`[[application-cannot-import-adapters]]`).
- **Observability:** poll-batch log gains `ingested=N skipped=N ineligible=N`; actorType + ticketRef logged, token never logged (3a-4 convention).
- **Tests (unit, MockRestServiceServer/mock adapter):** eligible→submits once, re-poll→no-op (idempotent), ineligible→touch-only, disabled-flag→byte-identical legacy, one failure doesn't abort batch, watermark preserved on partial failure.

**Note:** Upstream of 3a-1 — auto-created run lands in `Inbox`; 3a-1's `dispatchSpecGeneration` (on `Inbox → Investigating`) then auto-dispatches the real spec runner. Together: fully hands-off Linear-ticket → real-spec → review with no CLI.

---

### sprint-status.yaml edits

**Epic 2a block** — add after `2-22-...: done`:
```yaml
  2a-1-ui-submit-new-governed-run-from-the-web-app: backlog  # epic-2a, NEW (correct-course 2026-06-07) — frontend "Submit a Run" form over the existing POST /api/v1/workflows/submit-workflow; closes PRD FR1 UI-form gap (was CLI-only); no backend change; updates story 2.20 empty-state CTA. See sprint-change-proposal-2026-06-07.md.
```

**Epic 3a block** — add after `3a-4-...: done`:
```yaml
  3a-5-scheduled-linear-auto-ingest-poll-driven-run-creation: backlog  # epic-3a, NEW (correct-course 2026-06-07) — LinearPollingHost auto-creates runs for qualifying tickets via WorkflowCommandService.submit; opt-in flag default-off (watcher unchanged when off); idempotent by ticket; upstream of 3a-1 auto-dispatch. See sprint-change-proposal-2026-06-07.md.
```

---

## Section 5: Implementation Handoff

**Scope classification: Moderate** (backlog reorganization — two net-new stories, no PRD/architecture replan).

**Handoff:**
- **PO/DEV:** approve this proposal; entries added to `sprint-status.yaml` as `backlog`.
- **bmad-create-story** drafts full ACs for each when it enters the cycle (stubs above capture title/goal/deps/AC-shape).
- **bmad-dev-story → bmad-code-review** execute each story (run in fresh context windows).

**Suggested sequencing:** 2a-1 and 3a-5 are independent and can run in parallel. If serial, **3a-5 first** completes the hands-off backend scenario that pairs with 3a-1; **2a-1** then gives the human PM a UI alternative.

**Success criteria:** A ticket can become a governed run with no CLI — either by a PM submitting via the web form (2a-1) or by the scheduled poll auto-ingesting a qualifying ticket (3a-5) — with idempotency, ProblemDetails error semantics, and audit parity to the CLI path (AR8/AR18).
