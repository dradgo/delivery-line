## Epic 3g: Run Provenance & Token Accounting

A governed run is identified today only by its machine `ticketRef` ("DEL-1234") and `runId` — opaque in the queue and on the detail page. This epic gives every run a **human-readable origin** (the originating ticket's title plus a link back to the source ticket) and **per-step token accounting** (each agent execution records its input/output/total token consumption, with a run-level rollup). The originating title is **already fetched** for the runner context bundle (`TicketSummary{ticketRef,title,summary}` via `TicketSummaryProvider`) but discarded after bundling — never persisted, never shown; this epic **snapshots it at run creation** onto the linked-ticket `integration_link` so the origin is immutable and offline-safe. Token consumption is **net-new data**: `RunnerExecutionSnapshot` carries no token fields, and 3d-7's `ProviderUsageSnapshot` is rolling *quota* status, **not** per-step counts. Both surfaces are **pure additive read-model work** — no new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, or `DomainErrorCode` — making this the lightest foundation-gate footprint of the 3g–3l family.

**Why this epic exists (net-new capability):** Epics 1–3f leave a run human-anonymous in the read model and leave agent token consumption entirely uncaptured. There is **no governed way to see *what* a run is** beyond decoding a bare `ticketRef`, **no link back** to the source ticket, and **no per-execution token accounting** to show *where* tokens were spent or what a run cost in tokens overall. This epic adds those two capabilities as **new product scope** (FR73 run origin/title visibility, FR74 per-step token accounting + run rollup) on existing seams. It is **not** an activation of deferred work — the title is fetched-then-discarded today and token fields do not exist anywhere. As the warm-up epic of the 3g–3l family it deliberately **pins the additive DTO + persistence conventions** (nullable read-model widening, exact-field contract-test update, additive runner-contract field, snapshot ctor-shim fan-out) that the heavier epics (3h–3l) reuse.

**Prerequisites & reused substrates (all done):**
- **Ticket-summary fetch** (`TicketSummary{ticketRef,title,summary}` via `TicketSummaryProvider`, Linear adapter + offline stub) — already fetches the title for the context bundle; 3g-1 snapshots it instead of discarding it.
- **External-ticket metadata home** (`integration_links.external_metadata`; `LinkedTicketView` → `WorkflowDetailResponse.LinkedTicket` is built from it) — the snapshot title + ticket URL live here at link time; no new `workflow_runs` column.
- **Ticket-source abstraction** (`TicketSourceAdapter`) — gains the source-ticket URL builder behind a capability gate; the offline stub returns a deterministic stub URL.
- **Run-summary read model** (`WorkflowRunSummaryView` → `WorkflowSummaryResponse`) + the **summary exact-field contract test** (the `containsExactlyInAnyOrder` guard) — the queue title widening lands here and must be reflected in the guard or it silently breaks CI-only.
- **Runner result contract + entrypoints** (`runner-contracts` schema, both `runner.mjs` entrypoints, both offline mocks) — gain an additive optional `usage` block, mirroring the 3d-7 additive-runner-field precedent.
- **Per-execution persistence** (`runner_executions` table; `RunnerExecutionSnapshot` + ctor-shim fan-out pattern) — gains three nullable token columns/fields, appended at the END.
- **Raw-output capture (3.6)** + the **3d-5 per-step log/step view** — token display attaches to the existing per-step surface.
- **Run inspection** (`WorkflowInspectionService`) — hosts the run-level token rollup over non-null step tokens.
- **Schema-drift gate** (`FlywaySchemaContractTest`) — the additive token columns are pinned here.

**ADR (proposed):** **none.** This epic is pure additive read-model work with no new state/action/event/error-code and no architectural decision to record — it does not warrant an ADR. It does, however, **establish the additive-read-model conventions** (nullable DTO widening + exact-field contract-test discipline, additive runner-contract `usage` field, snapshot END-append + ctor-shim) that ADR-0030 (3h) and the later 3g–3l epics build on; those conventions are documented inline per story rather than in a standalone ADR.

### Story List (4 stories)

```
Provenance (origin snapshot + display)
3g-1   Ticket-origin snapshot + read model (backend)
3g-2   FE — ticket title in queue + minimal Origin block on detail page

Token accounting (capture + display)
3g-3   Runner token-usage capture (contract + persistence)
3g-4   FE — per-step token display + run-level token rollup
```

> Stories 3g-1 (provenance backend) and 3g-3 (token backend) are independent additive backends. 3g-2 (FE) consumes 3g-1's `ticketTitle` + `LinkedTicket.title/url`; 3g-4 (FE) consumes 3g-3's per-step token fields plus the 3g-4 backend rollup on `WorkflowDetailResponse`. The two tracks (provenance, tokens) share no schema and may be built in parallel.

---

### Story 3g-1: Ticket-Origin Snapshot + Read Model

As an authorized user scanning the queue or opening a run,
I want each run to carry its originating ticket's title and a link back to the source ticket,
So that I can see *what* the run is and *where it came from* without decoding a bare `ticketRef`.

**Acceptance Criteria:**

1. **Given** run creation (where the linked `integration_link` is written), **Then** the already-fetched `TicketSummary.title` and a connector-built ticket URL are **snapshotted** onto the linked-ticket `integration_links.external_metadata` (keys `title`, `url`) at link time — **immutable** (snapshot-at-creation, never live-resolved) and adding **no** new `workflow_runs` column; pre-3g rows keep `null` for both keys (parity — no origin).
2. **Given** the `TicketSourceAdapter`, **Then** it can produce a source-ticket URL for a `TicketRef` (the Linear adapter builds the real issue URL; the offline stub returns a deterministic stub URL); the builder is **capability-gated** — a connector that cannot produce a URL yields `null`, and the FE (3g-2) hides the link-out for a `null` url.
3. **Given** the read model, **Then** `WorkflowRunSummaryView` → `WorkflowSummaryResponse` gains a **nullable** `ticketTitle`, and `LinkedTicketView` → `WorkflowDetailResponse.LinkedTicket` gains **nullable** `title` + `url`; OpenAPI + `schema.d.ts` regenerate (NOT byte-identical). No field is required; an unlinked or pre-3g run serializes all three as `null`.
4. **Given** the summary exact-field contract test (the `containsExactlyInAnyOrder` guard over `WorkflowSummaryResponse`), **Then** it is updated for the new `ticketTitle` field — the field is added to the expected set so the new column does not trip the silent CI-only break (the documented summary-exact-field trap).
5. **Given** redaction (story 1.10), **Then** the snapshotted title passes the **same content posture** as the already-exposed `ticketRef`; the title is never logged in full — ids/lengths only — and the URL builder logs no secret query/token material.
6. **Given** tests, **Then** coverage asserts: the title + URL snapshot **persists at run creation** (immutable thereafter); summary carries `ticketTitle` and detail carries `LinkedTicket.title`/`url`; **unlinked / pre-3g parity** (all `null` → no break, contract test green); **URL capability fallback** (a connector with no URL builder → `url == null`); the exact-field contract test passes with the new field; `application.*` ≥80% coverage.

### Story 3g-2: FE — Ticket Title in Queue + Origin Block

As an authorized user,
I want the ticket title in the queue and a small "Origin" block on the detail page,
So that runs are human-identifiable at a glance and I can click through to the source ticket.

**Acceptance Criteria:**

1. **Given** the regenerated `schema.d.ts` (regenerated **first**, before FE work — the OpenAPI-regen-then-`generate-api` discipline), **Then** the queue run row renders `ticketTitle` as the run's human label and **falls back to `ticketRef`** when `ticketTitle` is `null` (pre-3g / unlinked parity — never a blank cell).
2. **Given** the detail page, **Then** a minimal **Origin** block renders the originating ticket's `title`, `ticketRef`, and `integrationType`, plus a **link-out** to the source ticket — the link is rendered **only when `LinkedTicket.url` is present** and is omitted entirely (no dead/`#` anchor) when `url` is `null`.
3. **Given** origin depth is deliberately minimal, **Then** the Origin block shows **title + ref + link only** — it does **not** render the full original ticket body or the initiating prompt (honoring the locked origin-depth decision).
4. **Given** accessibility, **Then** the queue title cell, the Origin block, and the link-out meet WCAG 2.1 AA and are **axe-clean**; the link-out has an accessible name distinguishing it as the external source ticket.
5. **Given** the FE rendering traps, **Then** any new helper is placed in a sibling `.ts` (the react-refresh no-fn-export rule), and any announcer reflecting the loaded title is asserted via `waitFor` (the `useLiveAnnouncement` one-commit-lag trap); wire reads guard `!= null` rather than `undefined`.
6. **Given** tests, **Then** Vitest covers: title render in the queue row; **ref fallback** when `ticketTitle == null`; Origin block fields; link-out **present when `url` set / absent when `url null`**; the component is **axe-clean**; `schema.d.ts` was regenerated.

### Story 3g-3: Runner Token-Usage Capture

As the system,
I want each runner execution to record the agent's input/output/total token counts when the agent reports them,
So that per-step token consumption is governed data — best-effort and nullable where the agent does not report it.

**Acceptance Criteria:**

1. **Given** the runner result contract (`runner-contracts` schema), **Then** an **additive optional** `usage{inputTokens,outputTokens,totalTokens}` object is added; both `runner.mjs` entrypoints emit it **when the agent reports usage** and omit it / emit `null` when not reported (best-effort, never fatal); both offline mocks emit **deterministic** token counts. (Heed the `runner-contracts` stale-in-`.m2` trap — install the contracts jar or build with `-am` before backend-only tests.)
2. **Given** the next-free Flyway head, **Then** additive **nullable** `input_tokens` / `output_tokens` / `total_tokens` columns are added to `runner_executions`; replay-safe; pinned in `FlywaySchemaContractTest`. Existing rows stay `NULL` (parity — no token data pre-3g).
3. **Given** `RunnerExecutionSnapshot`, **Then** the three nullable token fields are **appended at the END** (with a ctor shim per the snapshot fan-out pattern, so the ~existing `new RunnerExecutionSnapshot(...)` call sites stay green); they are populated by the persistence mapper from the contract `usage` block on result ingest.
4. **Given** a result with **no** `usage` (agent did not report, or a command-only/no-LLM execution), **Then** the three columns and snapshot fields persist as `null` — a missing or malformed `usage` block is **non-fatal** (logged, ingest proceeds), never a 5xx or a strand. (Forward note: command-only BUILD/LINT executions in 3h emit no token usage and rely on this null posture.)
5. **Given** redaction, **Then** token counts are numeric governed data carrying nothing secret; raw-output capture (3.6) retains its existing redaction posture and the `usage` block adds no new sensitive surface.
6. **Given** tests, **Then** coverage asserts: contract **round-trip** present / absent / malformed-non-fatal across **both** runner entrypoints; columns persist and the snapshot **carries** the three fields end-to-end on ingest; null parity for no-usage results; **mock determinism** (both offline mocks emit fixed counts); `FlywaySchemaContractTest` drift; `application.*` ≥80% coverage.

### Story 3g-4: FE — Per-Step Tokens + Run-Level Rollup

As an authorized user,
I want each step to show its token usage and the run to show a total,
So that I can see where tokens were spent and the run's overall consumption.

**Acceptance Criteria:**

1. **Given** the 3d-5 per-step log/step view, **Then** each step renders its **input / output / total** tokens; a step whose counts are `null` shows an explicit **"not reported"** indicator (mirroring 3d-7's not-exposed posture) rather than `0` or a blank — distinguishing "agent reported zero" from "agent reported nothing."
2. **Given** a backend run-level rollup, **Then** `WorkflowInspectionService` **sums the non-null** step token counts into a **nullable** `totalTokens` on `WorkflowDetailResponse` — `null` when **no** step reported any tokens (not `0`); OpenAPI + `schema.d.ts` regenerate (NOT byte-identical).
3. **Given** the detail page, **Then** the run-level `totalTokens` renders as the run's overall consumption, with the same **"not reported"** treatment when `totalTokens` is `null`; tokens-only is displayed (no estimated $ cost — the locked tokens-only decision; cost is a documented forward option).
4. **Given** the regenerated `schema.d.ts` (regenerated **first**) + the FE traps, **Then** any new helper lives in a sibling `.ts` (react-refresh no-fn-export), wire reads guard `!= null`, and any announcer is asserted via `waitFor` (the `useLiveAnnouncement` one-commit-lag trap).
5. **Given** accessibility, **Then** the per-step token cells, the "not reported" indicators, and the run-total surface meet WCAG 2.1 AA and are **axe-clean**.
6. **Given** tests, **Then** Vitest covers: per-step token render; the **not-reported** state when a step's counts are `null`; the run-level **rollup** total; the rollup's `null` (no step reported) state; the component is **axe-clean**; backend coverage of the `WorkflowInspectionService` rollup asserts `application.*` ≥80% (sum of non-null steps; `null` when none reported).

---

### Cross-Cutting Notes

- **Foundation-gate footprint (deliberately light):** **no** new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, or `DomainErrorCode` — the lightest footprint of the 3g–3l family. The only drift-tested additions are the additive `runner_executions` token columns (`FlywaySchemaContractTest`, 3g-3) and the additive `usage` runner-contract field (3g-3) — folded into their stories, no separate gate story.
- **Read-model / OpenAPI regen — two points:** 3g-1 (`WorkflowSummaryResponse.ticketTitle` + `LinkedTicket.title`/`url`) and 3g-4 (`WorkflowDetailResponse.totalTokens`). Each non-byte-identical regen requires `schema.d.ts` regeneration **before** the consuming FE story (the OpenAPI-regen → FE-client-drift cascade).
- **Summary exact-field contract test:** the new `ticketTitle` field on `WorkflowSummaryResponse` **must** be added to the `containsExactlyInAnyOrder` guard in 3g-1, or it silently reds CI-only (the documented summary-exact-field trap).
- **`runner-contracts` install trap:** 3g-3 adds a contract field; backend-only `mvnw test` would use the OLD jar from `.m2` — install the contracts module or build with `-am` first.
- **Snapshot ctor-shim fan-out:** 3g-3's three new `RunnerExecutionSnapshot` fields are appended at the END behind a ctor shim so existing construction sites stay green (the snapshot fan-out pattern).
- **Origin posture (locked):** detail shows **title + ticketRef + link-out only** (not the original body or initiating prompt); the title is **snapshotted at run creation** (offline-safe, immutable), never live-resolved.
- **Token posture (locked):** **per-step + run-level rollup**, **tokens only** — best-effort and nullable where unreported; a `null` step/run total renders "not reported," never `0`.
- **FRs covered:** **3g-1 + 3g-2** deliver **FR73** (run origin/title visibility — originating ticket title + link back to source); **3g-3 + 3g-4** deliver **FR74** (per-step agent token accounting + run-level rollup). This epic introduces **new PRD scope** (FR73/FR74) — append both FR blocks to PRD §FR.
- **Forward options (out of scope):** estimated $-cost display (a per-model pricing table + cost rollup — deliberately deferred to avoid maintaining pricing config); token-budget alerts/limits; per-model token attribution; surfacing tokens in the recovery/diagnostics views (Epic 4).
