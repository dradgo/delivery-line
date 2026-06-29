## Epic 3i: Connector Expansion — JIRA, Bitbucket, Sentry

The governed workflow's connector abstractions were built vendor-neutral, but only **Linear** (ticket source) and **GitHub** (repository host) are real — GitLab is a stub. Pilot teams run on **JIRA + Bitbucket**, and **Sentry** is the source of the bugs they most want governed. This epic lands those three connectors on the existing seams: a first-class **JIRA `TicketSourceAdapter`** (fetch / comment / sub-ticket / state / URL at Linear parity), an **interactive filtered ticket-intake browse** (the one genuinely new substrate capability — `queryTickets` by assignee + components, JQL-backed, because `supportsPolling` is "updated-since," not "by assignee/component"), a **Bitbucket `RepositoryHostAdapter`** (push / pull requests / **Pipelines CI**, completing the 3h-5 CI-checks port for the second provider), and a new **error-source adapter category** for **Sentry** whose issues are surfaced and **operator-promoted** (never auto-created) into governed **bug tickets** that feed the Epic 3j bug profile, deduplicated on Sentry issue id.

**Why this epic exists (net-new capability):** The 3-32 `TicketSourceAdapter`, 3-33 `RepositoryHostAdapter`, and 3c connector-resolution + credential-store substrates were designed for multiple vendors, but no JIRA, Bitbucket, or error-source connector has ever been implemented — pilot teams cannot run their real tooling, and the bugs surfaced by Sentry have no governed intake path at all. This epic adds those three connectors as **new product scope** (FR80/FR81/FR82/FR83), plus the one capability the substrate genuinely lacks: a **filtered ticket query** for interactive intake. It is **not** an activation of deferred work — these are net-new adapters on stable ports.

This is a **connector-expansion** epic, not a quality-gate or orchestration feature — it does not fit Epic 3h or 3j. It is **inserted after Epic 3h and before Epic 3j** purely for sequencing (3i-3 Bitbucket Pipelines consumes 3h-5's CI port; 3i-4 Sentry feeds 3j's bug profile). Source: this sprint-change-proposal.

**Prerequisites & reused substrates (all done):**
- **Connector resolution** (3c-3 `connectorKind()` + `@Primary` resolution) — each new adapter declares its `connectorKind()` and resolves by project connector kind; the `ConnectorKind` registry gains `jira` + `bitbucket` (the **GITLAB-V18** widening precedent if CHECK-constrained — next-free Flyway head).
- **Encrypted credential store + redaction** (3c-5) — JIRA (API token / email), Bitbucket (app password / token), and Sentry (auth token) credentials are write-only encrypted and pass the redaction posture; nothing secret is logged (ids/lengths only).
- **Connectivity probe** (3c-8 `verifyConnectivity`) — each new connector implements an auth + reachability probe.
- **Doctor probe pattern** (3c-10) — a `jira`, `bitbucket`, and `sentry` doctor probe is added; **heed the checksRun fan-out trap** — every hardcoded `checksRun` assertion is incremented.
- **Repository-host abstraction** (3-33 `RepositoryHostAdapter` `createPullRequest` / `updatePullRequest` / `commentOnPullRequest`; `GitLabRepositoryHostStubAdapter` precedent) — Bitbucket promotes the GitLab-stub shape into a real impl.
- **CI-checks port** (3h-5 `readCheckRuns` + `RepositoryHostCapabilities.supportsCiStatusReads`) — Bitbucket implements it for **Pipelines**, feeding the same 3h-5 CI investigation loop.
- **Ticket creation** (3f-1 `createSubticket` / `supportsTicketCreation`, done) — Sentry→bug promotion mints a governed bug ticket through it.
- **Ticket-URL capability** (3g-1) — each ticket connector builds a source-ticket URL for a `TicketRef`.
- **Intake / submit seam** (`WorkflowCommandService.submit`) — selected query results and promoted Sentry bugs submit through the existing governed-run create path.
- **Project selector** (3c-9 FE pattern) — the intake/browse + Sentry surfaces reuse it.

**ADR (proposed):** touch `docs/adr/0008-repository-host-abstraction.md` for Bitbucket (a second real `RepositoryHostAdapter` + Pipelines CI reader) and the connector-resolution ADR for JIRA (a second real `TicketSourceAdapter` kind + the new `supportsTicketQuery` capability). The new **error-source adapter category** (Sentry) is recorded as a sibling port to `TicketSourceAdapter` — surfacing, not sourcing, with operator-gated promotion as the only write path.

### Story List (4 stories)

```
Ticket source
3i-1   JIRA TicketSourceAdapter (kind=jira) — Linear-parity (fetch/comment/createSubticket/state/URL)   [item 4]
3i-2   Filtered ticket-intake browse — queryTickets by assignee + components (supportsTicketQuery)      [item 4]

Repository host + CI
3i-3   Bitbucket RepositoryHostAdapter (kind=bitbucket) + Bitbucket Pipelines CI reader                 [items 5+6]

Error source
3i-4   Sentry error-source connector — issue ingest → operator-gated promotion to bug tickets           [item 12]
```

> Stories 3i-1 and 3i-3 are largely independent connector foundations on stable ports. 3i-2 (filtered intake) depends on 3i-1 (JIRA is the first connector implementing `queryTickets`). 3i-3 (Bitbucket Pipelines) consumes **3h-5's** CI-checks port. 3i-4 (Sentry) depends on **3f-1** (ticket creation, done) and **feeds Epic 3j** (the bug profile the promoted bug ticket runs on). Detailed, reconciled implementation stories live at `{implementation_artifacts}/3i-1..3i-4-...md`.

---

### Story 3i-1: JIRA Ticket Source

As a pilot operator whose tickets live in JIRA,
I want JIRA to be a first-class ticket source at parity with Linear — fetch, comment, sub-ticket creation, state read, and a link-out URL,
So that I can run governed workflows on real JIRA tickets without changing how the rest of the system behaves.

**Acceptance Criteria:**

1. **Given** the `TicketSourceAdapter` port (story 3-32), **Then** a new impl declares `connectorKind()=jira` and implements `fetchTicketByReference`, `postGovernedRunComment`, `createSubticket` (sub-task under the parent issue), a ticket-state read, and the 3g-1 ticket-URL builder (the JIRA issue browse URL for a `TicketRef`); resolved via the 3c-3 `@Primary` resolution for `jira`-kind projects.
2. **Given** `getCapabilities`, **Then** it reports the real JIRA `TicketSourceCapabilities` set: `supportsCommentOnTicket=true`, `supportsTicketCreation=true`, `supportsTicketStateUpdates` as the JIRA workflow allows, and `supportsPolling` as available — verified by an adapter capability contract test.
3. **Given** the `ConnectorKind` registry, **Then** `jira` is added (registry value + Flyway connector-kind CHECK widening at the next-free Flyway head **iff** CHECK-constrained — the GITLAB-V18 precedent); replay-safe; in `FlywaySchemaContractTest`.
4. **Given** the 3c-5 encrypted credential store, **Then** JIRA credentials (base URL + account email + API token) are stored write-only encrypted, never exposed on read, and pass the redaction posture (the two-gates trap: manifest **and** hardcoded corpus); nothing secret is logged (ids/lengths only).
5. **Given** `verifyConnectivity` (3c-8), **Then** the JIRA adapter probes auth + project reachability (e.g. `myself` + project lookup) and reports a structured connectivity result; an unreachable/unauthorized instance yields a non-5xx connectivity failure.
6. **Given** the doctor probe pattern (3c-10), **Then** a `jira` doctor probe is added and **every hardcoded `checksRun` assertion is incremented** (the fan-out trap) — `DoctorServiceTest` mock + hardcoded count both updated.
7. **Given** idempotency + parent-link, **Then** `createSubticket` is keyed (the 3f-1 contract: parent run + ordinal) so a replayed split does not double-create, and posts a parent-link back-reference via `postGovernedRunComment` (the 3f-1 shape).
8. **Given** tests, **Then** coverage asserts: capability contract drift; `fetchTicketByReference` / `postGovernedRunComment` / `createSubticket` happy-paths; ticket-URL build; `ConnectorKind`/Flyway drift; connectivity probe (reachable + failure); credential redaction over the JIRA corpus; doctor `checksRun` fan-out; `application.*` ≥80% coverage.

---

### Story 3i-2: Filtered Ticket-Intake Browse

As an operator standing up governed runs,
I want to browse candidate JIRA tickets filtered by assignee and components and pick which ones to start,
So that I can pull a focused slice of my backlog into governance interactively, instead of polling everything updated-since.

**Acceptance Criteria:**

1. **Given** the `TicketSourceAdapter` port, **Then** a new method `List<TicketSummary> queryTickets(TicketQuery{assignee?, components[], state?, limit})` is added and `TicketSourceCapabilities` gains `supportsTicketQuery` (default `false`); JIRA (3i-1) implements it JQL-backed, while Linear/GitHub/GitLab keep the default `false` for now (additive later) — verified by capability contract test.
2. **Given** the JIRA impl, **Then** `queryTickets` maps the filter to JQL (`assignee = … AND component in (…) AND status = … ORDER BY … `) bounded by `limit`; an empty/absent filter field is omitted from the JQL rather than matching-all unbounded; results map to `TicketSummary{ticketRef, title, summary}` (the 3g-1 read shape).
3. **Given** a REST + CLI intake surface, **Then** an endpoint (e.g. `GET /projects/{id}/ticket-query?assignee=&components=`) + CLI parity lists candidate tickets for a `jira`-kind project; a connector whose `supportsTicketQuery=false` omits the surface (capability-gated, no 5xx); OpenAPI + `schema.d.ts` regenerate (NOT byte-identical).
4. **Given** selection, **Then** the operator selects one or several listed tickets and submits each as a governed run through the **existing** `WorkflowCommandService.submit` path (no bespoke create seam) — each submit is independent + idempotency-keyed (the batch-submission posture).
5. **Given** the FE, **Then** an intake/browse view renders the candidate list with **assignee + component** filter controls (reusing the 3c-9 project selector pattern) and a per-row "start run" action; `schema.d.ts` is regenerated **first**.
6. **Given** accessibility + FE traps, **Then** the view is **axe-clean** (WCAG 2.1 AA), covered by **Vitest** (filter controls drive the query; results render; capability-false hides the surface; selection submits), and honors the react-refresh-no-fn-export + `useLiveAnnouncement` one-commit-lag (`waitFor`) traps.
7. **Given** redaction, **Then** queried ticket titles/summaries carry the same content posture as any exposed `ticketRef` (ids/lengths only in logs).
8. **Given** tests, **Then** coverage asserts: `queryTickets` maps filters → JQL (with omitted-field handling); `supportsTicketQuery=false` connectors omit the surface (parity); selection submits a run via the existing path; OpenAPI/`schema.d.ts` drift; FE Vitest + axe; `application.*` ≥80% coverage.

---

### Story 3i-3: Bitbucket Repository Host + Pipelines CI

As a pilot operator whose code lives in Bitbucket,
I want Bitbucket to be a real repository host — push target, pull requests, and a Pipelines CI reader — at parity with the GitHub adapter,
So that the full governed delivery tail (including the 3h-5 CI investigation loop) works for Bitbucket projects.

**Acceptance Criteria:**

1. **Given** the `RepositoryHostAdapter` port (story 3-33) and the `GitLabRepositoryHostStubAdapter` precedent, **Then** a new impl declares `connectorKind()=bitbucket` and implements `createPullRequest`, `updatePullRequest`, `commentOnPullRequest` (Bitbucket Cloud pull requests), `verifyConnectivity`, and `getCapabilities` — the GitLab-stub shape promoted to a real impl; resolved via 3c-3 for `bitbucket`-kind projects (backend keeps git ownership — only the host adapter is new).
2. **Given** the 3h-5 CI-checks port, **Then** the Bitbucket adapter implements `readCheckRuns(repo, ref) → CiStatus` for **Bitbucket Pipelines** and reports `RepositoryHostCapabilities.supportsCiStatusReads=true`; the same 3h-5 CI investigation/fix loop drives Bitbucket projects (completing item 6 for the second CI provider — no new loop).
3. **Given** the `ConnectorKind` registry, **Then** `bitbucket` is added (registry value + Flyway connector-kind CHECK widening at the next-free Flyway head iff CHECK-constrained — the GITLAB-V18 precedent); replay-safe; in `FlywaySchemaContractTest`.
4. **Given** the 3c-5 credential store, **Then** Bitbucket credentials (workspace + app password / access token) are stored write-only encrypted, never exposed, and pass the redaction posture (manifest + corpus gates); nothing secret is logged.
5. **Given** `verifyConnectivity` (3c-8), **Then** the adapter probes Bitbucket auth + repository reachability and reports a structured connectivity result (non-5xx on failure).
6. **Given** the doctor probe pattern (3c-10), **Then** a `bitbucket` doctor probe is added and **every hardcoded `checksRun` assertion is incremented** (the fan-out trap).
7. **Given** capability parity, **Then** the adapter's capability contract test asserts its real `RepositoryHostCapabilities` (PR support, `supportsCiStatusReads=true`, any host-specific flags), and a Bitbucket project flows through the existing delivery tail (3h-4 push/PR gates) unchanged.
8. **Given** tests, **Then** coverage asserts: PR create/update/comment happy-paths; Pipelines status read (green proceeds / red feeds the 3h-5 loop); `ConnectorKind`/Flyway drift; connectivity probe; credential redaction over the Bitbucket corpus; doctor `checksRun` fan-out; capability contract drift; `application.*` ≥80% coverage.

---

### Story 3i-4: Sentry Error-Source Connector

As an operator triaging production errors,
I want to browse Sentry issues for a project and promote selected ones into governed bug tickets,
So that the bugs I choose enter the governed workflow (on the Epic 3j bug profile) with their Sentry context — without Sentry auto-flooding the queue.

**Acceptance Criteria:**

1. **Given** a **new error-source adapter category** (a `SentryErrorSourceAdapter` / `ErrorSourceAdapter` port — a sibling to `TicketSourceAdapter`, surfacing-not-sourcing), **Then** it lists Sentry issues for a project filtered by environment / level / unresolved status, with credentials (auth token + org/project) via the 3c-5 encrypted store; resolved by its own connector kind, never `@Primary`-competing with the project's ticket source.
2. **Given** the 3c-5 credential store + redaction, **Then** Sentry credentials are write-only encrypted and never exposed; Sentry issue payloads (title, culprit, stack context) pass the redaction / secret-fixture gate (the two-gates trap) before surfacing or promotion.
3. **Given** operator-gated promotion, **Then** a REST + CLI + FE surface lists Sentry issues and a **promote** action on a selected issue creates a governed **bug ticket** in the project's ticket source via the 3f-1 `createSubticket` / ticket-creation capability, carrying the Sentry issue context (title, culprit, permalink), then submits a governed run on the **bug workflow profile** (Epic 3j) via `WorkflowCommandService.submit`.
4. **Given** dedup, **Then** promotion is **idempotent on the Sentry issue id** — promoting the same issue twice neither creates a duplicate bug ticket nor a duplicate run (the dedup key is the Sentry issue id; replay returns the existing linkage).
5. **Given** the no-auto-create rule, **Then** **nothing** enters the queue without an explicit operator promote action — listing Sentry issues has zero write effect; there is no poller/auto-promotion path in this epic (a per-project auto-create flag is a documented forward option).
6. **Given** the FE, **Then** a Sentry issue-list/promote view (reusing the 3c-9 project selector pattern) renders the filtered issue list with a per-issue promote action and a "already promoted" indicator (dedup made visible); `schema.d.ts` regenerated **first**; **axe-clean**; **Vitest** covers list render, promote action, and already-promoted state; FE traps honored.
7. **Given** the doctor probe pattern (3c-10), **Then** a `sentry` doctor probe (auth + project reachability) is added and **every hardcoded `checksRun` assertion is incremented** (the fan-out trap); OpenAPI + `schema.d.ts` regenerate.
8. **Given** tests, **Then** coverage asserts: issue list (filtered); promote → bug ticket + run with Sentry context (on the 3j bug profile); dedup replay (no duplicate bug/run); no-auto-create (list is read-only); redaction over Sentry payloads; doctor `checksRun` fan-out; OpenAPI/`schema.d.ts` drift; FE Vitest + axe; `application.*` ≥80% coverage.

---

### Cross-Cutting Notes

- **Foundation-gate widening:** new `ConnectorKind` registry values (`jira`, `bitbucket`) + Flyway connector-kind CHECK widening at the next-free Flyway head (iff CHECK-constrained — the GITLAB-V18 precedent); the new `TicketSourceCapabilities.supportsTicketQuery` flag (3i-2); the new **error-source adapter category** (3i-4 Sentry — a sibling port, not a `TicketSourceAdapter`); **three new doctor probes** (`jira`, `bitbucket`, `sentry` — the checksRun fan-out trap: update **every** hardcoded `checksRun` assertion **and** the `DoctorServiceTest` mock); per-connector credential-store entries + the redaction-corpus **two-gates** trap (manifest **and** hardcoded corpus) — folded into each story, no separate gate story.
- **Cross-epic dependencies:** 3i-3 Bitbucket Pipelines **consumes 3h-5's** CI-checks port (`readCheckRuns` + `supportsCiStatusReads`) — sequence after 3h. 3i-4 Sentry **depends on 3f-1** (ticket creation, done) and **feeds Epic 3j** (the promoted bug ticket runs on the bug profile) — sequence 3i-4's promotion target against 3j's profile concept (the run lands on `feature` parity until 3j ships, then the `bug` profile).
- **Read-model / OpenAPI:** the 3i-2 intake/query surface and the 3i-4 Sentry surface each widen the API — OpenAPI + `schema.d.ts` regenerate (NOT byte-identical) at both points; regen `schema.d.ts` **first** before FE work (the OpenAPI-regen→FE-client-drift cascade).
- **Documentation:** confirm new vocabulary (`ticket query` / `intake browse`, `error source`, `Sentry issue`, `bug promotion`) in `docs/glossary.md` against NFR43 (minimize new concepts — justify each); a connector-onboarding note documents adding JIRA, Bitbucket, and Sentry credentials.
- **FRs covered:** **3i-1** delivers **FR80** (JIRA as a first-class ticket source); **3i-2** delivers **FR81** (filtered ticket intake by assignee + components); **3i-3** delivers **FR82** (Bitbucket as a repository host incl. Pipelines CI); **3i-4** delivers **FR83** (Sentry error ingestion → operator-promoted governed bug tickets). This epic introduces **new PRD scope** (FR80–FR83) — it is net-new connectors on stable ports, not an activation of deferred work.
- **Forward options (out of scope):** `queryTickets` for Linear/GitHub/GitLab; Bitbucket Issues as a ticket source (this epic is repo-host only for Bitbucket); GitLab promoted from stub to a real repo host; auto-create Sentry bugs behind a per-project flag (this epic is operator-gated only); a unified multi-connector intake view.
