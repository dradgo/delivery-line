## Epic 3l: Project Memory & Artifact Lineage

The governed workflow already accumulates a rich web of relational data — but **scattered and unorganized**. A run knows its split parent (`parent_run_id`, 3f-2); runs sequence each other (`run_dependencies`, 3f-3); a ticket binds to its run and PR (`integration_links`); each run produces artifacts in the artifacts store; Sentry issues promote into governed bug tickets (3i-4); a run carries a `workflow_profile` discriminating bug from feature (3j). No project-scoped layer **organizes** any of it into a navigable **memory** — there is no way to ask "everything related to ticket X and what it produced," and no mechanism to **feed that history back to the agents** so they leverage prior decisions, specs, and artifacts. This epic builds **both halves**, sub-sequenced: **Part A** projects the existing data (plus a few operator-assertable typed relations) into a queryable, visualized **relationship + artifact graph**; **Part B** retrieves related prior memory into the agent **context bundles** — graph-neighborhood related tickets and their approved specs/artifacts, injected **by reference** and gated behind a per-project toggle that defaults **off** for parity.

**Why this epic exists (the requester-flagged EPIC):** The raw relational data exists across six delivered substrates but lives only as point-to-point foreign keys with no project-scoped organizing layer — an operator cannot see how a project's tickets relate or what they produced, and an agent re-specs work the project already did. This epic adds a **project memory graph** (FR88), a small set of **operator-assertable typed relations** (FR89), and **agent-context retrieval** of related prior memory (FR90) as **new product scope**. It is **not** a general knowledge store — relation data is the project's existing structure plus three operator-assertable edge types. It **complements** (does **not** duplicate) Epic 4's planned audit-by-ticket query (story 4.3): **audit is the event stream**; **project memory is the relationship + artifact graph and the agent-context feed**. The two cross-reference each other and must not grow duplicate query surfaces.

This is a **standalone memory/lineage** epic — it does not fit the connector (3i), ticket-type (3j), or runner-platform (3k) themes. It is sequenced **last** in the `3g`–`3l` family (it depends on the relational data the earlier epics enrich — 3i links, 3j profiles) and is **inserted between Epic 3f and Epic 4** purely for sequencing (avoids renumbering E4–E6). Source: this sprint-change-proposal.

**Prerequisites & reused substrates (all done):**
- **Split lineage** (3f-2 `parent_run_id` nullable FK → `workflow_runs.public_id`) — a graph edge: parent→child decomposition.
- **Run-dependency DAG** (3f-3 `run_dependencies(run_id, depends_on_run_id)`) — a graph edge: run sequencing.
- **Integration links** (`integration_links`, ticket↔run↔PR with `external_metadata`) — graph edges binding a ticket to its run(s) and PR(s).
- **Artifacts store** — per-run produced artifacts (specs, plans, PR output, reviewer verdicts) become `produced-by` graph edges and the artifact nodes the memory query returns.
- **Sentry→bug links** (3i-4) — a promoted bug ticket carries its originating Sentry issue context; a graph edge.
- **Workflow profile** (3j `workflow_profile` run column) — the bug↔feature discriminator surfaced as node metadata / edge typing.
- **`projectId` scoping** (3c-7 `project_id` FK on runs, indexed) — every memory projection and query is scoped by project.
- **Context-bundle builder + `priorFeedbackReferences` by-reference mechanism + the 256KB payload cap** (raised from 2KB) — Part B injects retrieved memory **by reference, never inlined past the cap**.
- **Data-classification + redaction / secret-fixture gate** — injected memory must carry classification and pass the redaction corpus gate (the two-gates trap).
- **Context-bundle inspection** — the provenance surface that records and shows **what** went into a bundle; Part B extends it to show **which memory was used**.
- **`FlywaySchemaContractTest`** — the only new write-path table (`project_relations`) is registered here.

**ADR (proposed):** none required — this epic is additive read-model + one additive write table on existing seams, with no architectural inversion. The **complements-not-duplicates** relationship with Epic 4.3 (audit = event stream; memory = relationship/artifact graph + agent feed) is recorded as a cross-reference in `epics.md` and in story 4.3 (both ways), not a standalone ADR.

### Story List (5 stories — Part A: graph; Part B: agent context)

```
Part A — relationship & artifact graph
3l-1   Project-memory read model — projection over lineage/deps/links/artifacts/Sentry/profiles
3l-2   Operator-assertable relations (duplicate_of / caused_by / relates_to) typed-edge store
3l-3   Memory query API (REST+CLI) + FE relationship graph / timeline view

Part B — agent context
3l-4   Memory retrieval into context bundles (graph-neighborhood, by reference)
3l-5   Injected-memory provenance + relevance ranking + redaction posture (+ FE "memory used")
```

> **Sub-sequence Part A before Part B.** Part A (3l-1, 3l-2, 3l-3) builds the graph and the human-facing query/visualization; Part B (3l-4, 3l-5) feeds the same graph back to the agents. 3l-1 is the foundation projection (read-only over existing persistence). 3l-2 adds the one new write-path (operator-assertable typed edges) and folds them into 3l-1's graph. 3l-3 exposes the query (REST+CLI) and the FE graph/timeline view. 3l-4 retrieves graph-neighborhood memory into context bundles by reference (toggle default off). 3l-5 governs that injection — provenance, relevance bounding, redaction — and surfaces "memory used." Detailed, reconciled implementation stories live at `{implementation_artifacts}/3l-1..3l-5-...md`.

---

### Story 3l-1: Project-Memory Read Model

As an authorized user,
I want a project's tickets, runs, and artifacts organized into a single navigable graph of how they relate,
So that the scattered lineage/dependency/link/artifact data becomes one project-scoped memory I can later query and visualize — without any new write-path for relationships that already exist.

**Acceptance Criteria:**

1. **Given** a `ProjectMemoryService` in `application.*`, **Then** it projects — for a given `projectId` (3c-7) — a graph of **nodes** (tickets, runs, artifacts) and **edges** from **existing** persistence only: split lineage (`parent_run_id`, 3f-2), run dependency (`run_dependencies`, 3f-3), integration link (`integration_links` ticket↔run↔PR), `produced-by` (run→artifact, the artifacts store), Sentry→bug (3i-4), and bug↔feature profile typing (`workflow_profile`, 3j). **No new write-path** for these edges — pure read-side projection.
2. **Given** project scoping, **Then** every node and edge is scoped to the requested project via the existing indexed `project_id` FK on runs; a node/edge belonging to another project is never returned (cross-project leakage is asserted against).
3. **Given** a node model, **Then** each node carries a stable typed id (`ticket:<ref>` / `run:<publicId>` / `artifact:<id>`), a kind, and a minimal label (e.g. ticket title from 3g-1 `external_metadata`, run state, artifact title) — ids only, no artifact bodies inlined into the projection.
4. **Given** an edge model, **Then** each edge carries `from`, `to`, and a typed `edgeKind` (`split_lineage`, `run_dependency`, `integration_link`, `produced_by`, `sentry_bug`, `profile`) so consumers can render and filter by relationship type.
5. **Given** the read-path budgets, **Then** the projection stays within the NFR25/NFR26 read-path budgets — it reuses the existing indexes (`project_id`, `parent_run_id`, the `run_dependencies` PK, `integration_links`) and adds no N+1 fan-out (join-fetched / batched), with a bounded node/edge ceiling per project.
6. **Given** parity, **Then** a project with no splits, dependencies, Sentry links, or extra profiles still projects a valid (sparse) graph — only the integration-link + produced-by edges that already exist — and a pre-3l project is byte-identical in every other read path (the projection is additive, read-only).
7. **Given** redaction, **Then** node labels and metadata pass the same content posture as the already-exposed `ticketRef`/`ticketTitle`; logs carry ids/lengths only.
8. **Given** tests, **Then** coverage asserts: the graph assembles **every** edge kind from seeded fixtures; project scoping (no cross-project leakage); sparse-project parity; ids-only node labels; read-path budget (no N+1); `application.*` ≥80% coverage.

---

### Story 3l-2: Operator-Assertable Relations

As an operator who knows two tickets are related in a way the system can't infer,
I want to assert a typed relation between them — that one **duplicates**, **caused**, or merely **relates to** another — and retract it later,
So that the project memory captures human-known relationships beyond the structural ones, as a small fixed vocabulary rather than a general knowledge store.

**Acceptance Criteria:**

1. **Given** the next-free Flyway head, **Then** an additive `project_relations` table is created — `project_id` (FK), `from_node` (typed node id), `to_node` (typed node id), `relation_type`, `actor`, `reason` (nullable), `created_at`, and a soft-retract marker (`retracted_at` nullable) — replay-safe and registered in `FlywaySchemaContractTest`. No existing table is altered.
2. **Given** a `RelationType` registry, **Then** the three values `duplicate_of`, `caused_by`, `relates_to` are added (registry value + `relation_type` CHECK constraint, the GITLAB-V18 widening precedent) and drift-tested against the DB CHECK; an unknown relation type is rejected.
3. **Given** a self-relation guard, **Then** `from_node == to_node` is rejected (CHECK and/or service guard); a relation references nodes within the **same** project (cross-project assertion refused).
4. **Given** a `ProjectRelationService` + REST + CLI, **Then** an operator can **assert** a relation (`POST` a from/to/type/reason) and **retract** it (soft `retracted_at`); both are **idempotent** (re-asserting the same from/to/type is a no-op, not a duplicate row — a partial unique index on `(project_id, from_node, to_node, relation_type) where retracted_at is null`, the one-active-per-key precedent) and **audited** (actor + reason recorded). OpenAPI + `schema.d.ts` regenerate.
5. **Given** the 3l-1 graph, **Then** active (non-retracted) `project_relations` are folded in as edges with `edgeKind` ∈ {`duplicate_of`, `caused_by`, `relates_to`}; retracted relations never appear; the assertion does **not** turn this into a general knowledge store (fixed three-type vocabulary, asserted between existing nodes only).
6. **Given** redaction, **Then** the operator-supplied `reason` free-text passes the redaction/secret-fixture gate; ids/lengths only in logs.
7. **Given** tests, **Then** coverage asserts: Flyway/registry/CHECK drift for `RelationType`; assert + retract round-trip; idempotent re-assert (no duplicate row); self-relation + cross-project + unknown-type rejected; asserted relations appear in the 3l-1 graph and retracted ones do not; reason redaction; `application.*` ≥80% coverage.

---

### Story 3l-3: Memory Query API + Relationship Graph / Timeline View

As an authorized user,
I want to query "everything related to ticket (or run) X — its relations, its lineage, and the artifacts it produced" and see it as a navigable graph and timeline,
So that the project memory is usable by a human, with bounded transitive depth and redaction, and cross-referenced with — not duplicating — Epic 4's audit-by-ticket.

**Acceptance Criteria:**

1. **Given** a memory-query endpoint (REST + CLI parity) over the 3l-1 graph (incl. 3l-2 asserted edges), **Then** querying a node returns **everything related** to it — transitive relations + lineage + dependencies + integration links + produced artifacts — with a **bounded traversal depth** (a `depth` param, sane default + hard ceiling) so the result is finite and within read-path budgets.
2. **Given** project scoping + redaction, **Then** the query is scoped to the node's project; node labels and any returned artifact references pass the redaction posture; artifact **bodies are returned by reference** (id + title + link), never inlined, mirroring the by-reference discipline Part B depends on.
3. **Given** the **complements-not-duplicates** rule, **Then** the memory query returns the **relationship + artifact graph** and explicitly does **not** re-serve the audit event stream (Epic 4.3's surface); the response and docs cross-reference Epic 4.3 (and 4.3 cross-references this) so the two surfaces stay distinct.
4. **Given** OpenAPI, **Then** the query response DTOs widen `WorkflowDetailResponse`/a new memory response as needed; OpenAPI + `schema.d.ts` regenerate (NOT byte-identical); any summary/detail exact-field contract test (`containsExactlyInAnyOrder`) is updated for new fields (avoids the silent CI-only break).
5. **Given** an FE relationship **graph / timeline** view, **Then** the memory of a ticket/run renders as a **graph** (nodes typed + colored by kind, edges labeled by `edgeKind`) and/or a **timeline** (artifacts + relation events in order); edges asserted via 3l-2 are visually distinguishable from structural edges; depth is bounded in the UI.
6. **Given** the FE traps, **Then** `schema.d.ts` is regenerated **first**; the view honors the react-refresh-no-fn-export trap and the `useLiveAnnouncement` one-commit-lag pattern (assert via `waitFor`).
7. **Given** accessibility, **Then** the graph/timeline view meets WCAG 2.1 AA and is **axe-clean** (the graph offers an accessible/textual equivalent for non-visual traversal); covered by **Vitest** + axe.
8. **Given** tests, **Then** coverage asserts: query returns the full related set; bounded depth honored + ceiling enforced; project scoping + redaction; artifacts by reference (no body inlined); cross-reference to 4.3 (no audit-stream duplication); OpenAPI/`schema.d.ts` drift; FE Vitest (graph + timeline render, asserted vs structural edges) + axe; `application.*` ≥80% coverage.

---

### Story 3l-4: Memory Retrieval into Context Bundles  *(agent context)*

As the system building a context bundle for an agent,
I want to retrieve related prior memory — the graph-neighborhood tickets and their approved specs/artifacts — and include it **by reference** in the bundle,
So that agents leverage the project's prior work instead of re-deriving it, without ever inlining content past the payload cap and without changing behavior for projects that have not opted in.

**Acceptance Criteria:**

1. **Given** the context-bundle builder for the spec and implementation stages, **Then** it consults the 3l-1/3l-2 graph for the current run's node and retrieves its **graph neighborhood** — related tickets (via lineage, dependency, integration link, and asserted relations) and their **approved specs / produced artifacts** — as candidate prior memory.
2. **Given** the by-reference mechanism, **Then** retrieved memory is injected into the bundle via the **`priorFeedbackReferences` reference mechanism** — ids + pointers, **never inlined**; total bundle payload respects the **256KB cap** (the context-bundle-2KB→256KB trap) — anything that would exceed the cap is dropped/truncated **by reference**, never by inlining.
3. **Given** retrieval is **relation-based**, **Then** selection is graph-neighborhood / relation-driven (bounded hop distance over the 3l-1 graph), **not** semantic — embedding / vector retrieval is a documented **forward option**, not built here.
4. **Given** a **per-project toggle** (on `Project` + `ProjectRuntimeConfigResolver`, the `auto-dispatch`/`openspecEnabled` precedent) **defaulting OFF**, **Then** memory retrieval runs only when a project opts in; with the toggle off the context bundle is **byte-identical to pre-3l** (parity hot path) and no graph query is issued.
5. **Given** bounded retrieval, **Then** the number of referenced memory items is capped (a configurable ceiling) so a large project never floods the bundle; the cap composes with the 256KB payload cap.
6. **Given** offline/runner parity, **Then** the by-reference memory entries are transported through the existing bundle contract unchanged (no runner-contract field churn beyond reference carriage) so both `runner.mjs` entrypoints and both offline mocks see a deterministic, bounded reference set.
7. **Given** tests, **Then** coverage asserts: related memory is injected **by reference** (no body inlined); the 256KB cap is respected (over-cap entries dropped by reference); the item ceiling bounds the set; **toggle-off parity** (byte-identical bundle, no graph query); relation-based (not semantic) selection; `application.*` ≥80% coverage.

---

### Story 3l-5: Injected-Memory Provenance + Relevance Ranking + Redaction Posture

As an operator (and the governed system),
I want injected memory to be relevance-ranked, redaction-clean, and recorded so I can see exactly **which** prior memory an agent was given,
So that the agent-context feed is bounded, safe, and fully auditable — never an opaque or leaky injection.

**Acceptance Criteria:**

1. **Given** relevance ranking, **Then** the candidate memory from 3l-4 is **ranked** (relation proximity / recency / artifact kind) and only the top-ranked items within the 3l-4 ceiling are injected; the ranking is deterministic so the same graph yields the same selection (offline-mock-stable).
2. **Given** redaction + data-classification, **Then** every injected memory reference carries its **data-classification** and the referenced content passes the **redaction / secret-fixture gate** (the two-gates trap: manifest + hardcoded corpus) — no secret or over-classified artifact is referenced into a bundle.
3. **Given** context-bundle inspection (provenance), **Then** the inspection surface **records which memory was injected** — the referenced node/artifact ids, their classification, and the ranking that selected them — as bundle provenance, reusing the existing context-bundle-inspection seam (no new event type where the provenance store suffices).
4. **Given** the FE, **Then** the run / context view shows a **"memory used"** panel listing the injected memory references (with link-out to each via the 3l-3 graph/query), so an operator can see what prior work informed the run; `schema.d.ts` regenerated first; Vitest + axe; FE traps honored.
5. **Given** the toggle-off parity (3l-4), **Then** with retrieval disabled the provenance surface shows **no** injected memory and the run/context view is byte-identical to pre-3l (no empty "memory used" affordance leaking the feature).
6. **Given** OpenAPI, **Then** the provenance/"memory used" read model widens `WorkflowDetailResponse`/the context-inspection DTO; OpenAPI + `schema.d.ts` regenerate; any exact-field contract test is updated.
7. **Given** tests, **Then** coverage asserts: ranking bounds + determinism; **redaction over injected memory** (secret-fixture gate); classification carried per reference; provenance recorded + surfaced (which memory was used); toggle-off shows none (parity); OpenAPI/`schema.d.ts` drift; FE Vitest (memory-used panel) + axe; `application.*` ≥80% coverage.

---

### Cross-Cutting Notes

- **Foundation-gate widening:** the only registry/schema drift this epic introduces is the **`project_relations` table + `RelationType` registry** (3l-2 — registry value + `relation_type` CHECK widening + partial unique index for one-active-per-key + `FlywaySchemaContractTest`). There is **no** new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, `RunnerStage`, or error code — lighter foundation-gate footprint than 3f/3h. Each drift point is folded into its story, no separate gate story.
- **Complements (does not duplicate) Epic 4.3:** **audit is the event stream; project memory is the relationship + artifact graph and the agent-context feed.** 3l-3's query returns the graph/artifacts and explicitly does not re-serve the audit event stream. Cross-reference **both ways** (this epic's query response + docs ↔ story 4.3) so the two surfaces never grow duplicate query paths.
- **By-reference + cap discipline:** Part B (3l-4/3l-5) injects memory **by reference via `priorFeedbackReferences`, never inlined past the 256KB payload cap** (the context-bundle-2KB→256KB trap), and 3l-3's human query likewise returns artifacts by reference. Injected memory **carries data-classification and passes the redaction / secret-fixture gate** (two-gates trap).
- **Parity & opt-in:** Part A is read-only over existing persistence (sparse-project parity); Part B is gated by a **per-project toggle defaulting OFF** so every pre-3l project — and every project that does not opt in — is byte-identical (no graph query, no injected memory, no FE "memory used" affordance).
- **Read-model / OpenAPI:** OpenAPI + `schema.d.ts` regenerate at 3l-3 (query) and 3l-5 (provenance / "memory used"); update the summary/detail exact-field contract test (`containsExactlyInAnyOrder`) wherever a DTO field is added. FE graph/timeline + "memory used" views are Vitest + axe, `schema.d.ts` regenerated first, honoring the react-refresh-no-fn-export + `useLiveAnnouncement` traps.
- **Sub-sequence Part A before Part B:** 3l-1 → 3l-2 → 3l-3 (graph + human surface) precede 3l-4 → 3l-5 (agent feed), which depend on the assembled graph.
- **FRs covered:** **3l-1..3l-3** deliver **FR88** (project-scoped memory graph of ticket relations + artifacts, queryable + visualized) and **FR89** (operator-assertable relations); **3l-4..3l-5** deliver **FR90** (related prior memory retrieved into agent context bundles). This epic introduces **new PRD scope** (FR88/FR89/FR90).
- **Forward options (out of scope):** embedding / vector (semantic) retrieval for Part B; cross-project memory; auto-suggested relations (system-inferred `duplicate_of`/`caused_by`); surfacing memory in the recovery / diagnostics views (Epic 4); per-relation richer metadata beyond the fixed three-type vocabulary.
