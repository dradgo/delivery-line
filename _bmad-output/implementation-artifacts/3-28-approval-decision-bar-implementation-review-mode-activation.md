# Story 3.28: Approval / Decision Bar — `implementation_review` Mode Activation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **Developer making a technical accept/reject/takeover decision**,
I want **the Decision Bar's `implementation_review` mode (stub from story 2.19 AC1) fully activated** — wired to the `accept-implementation` (story 3.23), `reject-implementation` (story 3.24), and `takeover` (story 3.25) endpoints with version-stamped mutations and confirmation patterns appropriate to each action's consequence,
so that **the developer surface mirrors the PM surface from E2 with consistent UX patterns, and the takeover confirmation reads its consequence text from OpenAPI per story 3.25 AC9.**

## Acceptance Criteria

> **Reconciliation note (read first):** the epic AC text was written before the three backend endpoints landed. Where the live wire contract diverges from the AC prose it is flagged inline as **[R#]** and resolved in Dev Notes → *Central Reconciliations*. The flagged item WINS over the AC prose.

1. **Given** `ApprovalDecisionBar` from story 2.19 with `mode='implementation_review'`, **Then** the `StubPlaceholder` (`ApprovalDecisionBar.tsx:583-584`) is replaced with a real render: primary action `Accept implementation` (gated on backend allowed-actions per story 2.14), secondary actions `Reject with feedback` + `Take over`, all driven by backend-reported allowed actions — **no frontend permission inference** (story 2.19 AC5 + UX-DR12 hard rule; the `ApprovalDecisionBar.eligibility.test.ts` import-graph guard must still pass).
2. **Given** one visually primary action per decision area (UX-DR19), **Then** when the live allowed-actions include `accept_implementation`: `Accept implementation` is visually primary (`data-primary="true"` / `GovernedButton priority="primary"`); `Reject with feedback` + `Take over` are visually subordinate (enforced by the ESLint single-primary rule from story 2.23 AC7). Exactly one primary-styled control renders.
3. **Given** `Reject with feedback`, **When** invoked, **Then** it opens a rationale dialog with: required `reasonText` textarea, required `taggedFeedback` radio selection from the **developer**-rejection taxonomy **[R5]** (`INCORRECT_APPROACH | INCOMPLETE_IMPLEMENTATION | QUALITY_ISSUE | BREAKS_EXISTING_FUNCTIONALITY | OUT_OF_SCOPE` — NOT the product spec taxonomy), Cancel + "Submit rejection" buttons; submit calls `useRejectImplementation` sending Idempotency-Key + version stamps + (optional) reviewer-role per story 3.24.
4. **Given** `Take over`, **When** invoked, **Then** it opens the shared `<ConfirmationDialog>` (story 2.23) with: **consequence text sourced verbatim from the OpenAPI `takeover.post.description`** per story 3.25 AC9 **[R6]** ("Stops orchestrator dispatch, cancels all in-flight + queued runner executions… This action is non-reversible in E3 — Epic 4 will add takeover-revert; until then, a taken-over run can only be closed by an operator action."), a **required** `reasonText` textarea, Cancel + "Confirm takeover" buttons styled `intent="danger"`; submit calls `useTakeoverWorkflow`.
5. **Given** version-stamped mutations per story 2.19 AC6, **Then** `accept-implementation` + `reject-implementation` send `expectedArtifactVersion` + `expectedContextBundleVersion` **[R2/R3]** (there is NO `expectedAllowedActionsVersionStamp` field on any request body; **takeover sends NO version fields at all**); on `APPROVAL_VERSION_MISMATCH` (accept/reject only — takeover cannot return it), the bar renders the stale-decision state (story 2.19 AC6) with a refresh-and-retry CTA.
6. **Given** post-submit decision summary per story 2.19 AC9, **Then** after a decision lands the bar persists the outcome visibly (decision + resulting state + timestamp + actor + linked correlation/event id) until the parent resets — reusing the `DecisionSummary` mechanism from `ApprovalDecisionBarContainer`.
7. **Given** the takeover result (`TakeoverResponse`, story 3.25 AC8) includes a preserved GitHub PR reference, **Then** the post-takeover bar shows a "Continue work in PR {ref}" navigation control (link to GitHub, new tab, built via the `githubRef.ts` helper) + a "Run is taken over" read-only label. **[R9]** When `preservedPrReference` is `null` (no GH link), render the read-only label WITHOUT the link (no empty placeholder).
8. **Given** ARIA + accessibility per story 2.25, **Then** all actions are keyboard reachable; button labels use explicit verbs ("Accept implementation", "Reject with feedback", "Take over" — never "OK"); a withheld/disabled control's rationale is `aria-describedby`-linked; focus moves into and out of the confirmation/rationale dialogs predictably (story 2.23 AC5 / radix); the single polite live region announces decision-lifecycle transitions (sourced from `lib/a11y/announcements.ts`).
9. **Given** allowed-actions integration with backend-reported `disabledActions: { [action]: reasonCode }` per story 2.19 AC5, **Then** when the backend reports `accept_implementation` disabled (e.g. artifact PR linkage drifted → `ARTIFACT_PR_LINK_MISMATCH`), the bar renders a disabled state with the backend reason mapped to localized text — never silently disabled. **[R11]** `disabledActions` has no live source yet (DORMANT, AC5c) — live posture: accept is hidden when absent from `actions[]`; an `ARTIFACT_PR_LINK_MISMATCH` (409) at submit surfaces the `error` state.
10. **Given** component test coverage, **Then** tests cover: `implementation_review` renders all three actions when allowed; version-stamped mutations send the expected versions (accept/reject) and takeover sends none; stale-decision UI on `APPROVAL_VERSION_MISMATCH`; rejection dialog enforces `reasonText` + developer `taggedFeedback`; takeover dialog enforces `reasonText` + renders the OpenAPI consequence text; post-submit summary persists; post-takeover "Continue in PR" affordance renders (and gracefully omits when `preservedPrReference` is null); keyboard navigation through all actions + dialogs; single-primary-action rule; **axe-core a11y scan: zero violations**.

## Tasks / Subtasks

- [x] **Task 1 — Extend the shared view contract** (`approvalDecisionView.ts`) (AC1, AC2) **[R5, R7, R8]**
  - [x] Add `'accept_implementation' | 'reject_implementation' | 'takeover_workflow'` to the `DecisionAction` union AND to the `KNOWN_ACTIONS` set (else `coerceAction` drops them as `'unknown'` and the bar is permanently `blocked`). Wire values confirmed: `AllowedAction.ACCEPT_IMPLEMENTATION("accept_implementation")`, `REJECT_IMPLEMENTATION("reject_implementation")`, `TAKEOVER_WORKFLOW("takeover_workflow")` (`deliveryline-backend/.../domain/registry/AllowedAction.java:6-19`).
  - [x] Add a `DeveloperTaggedFeedback` type = `components['schemas']['RejectImplementationRequest']['taggedFeedback']` (the 5 UPPERCASE developer values) — distinct from the existing spec `TaggedFeedback`.
  - [x] Add `resolveImplementationArtifactId(detail)` mirroring `resolveSpecArtifactId` but filtering `artifactType ∈ {'implementationPlan','prOutput'}` and picking the highest `version`. (As of story 3a-9 the read model populates `latestArtifacts[].artifactId` — see `routes/workflows/$workflowRunId/index.tsx:119-121`; the stale 2.19 "no live source" javadoc on `resolveSpecArtifactId` is superseded.)
  - [x] Add `buildImplementationContextLabel(detail, versionStamp)` ("Review implementation v{n} by {actor}").
  - [x] Add `CONSEQUENCE_HINTS.implementation_review` entries: `accept_implementation` ("Accepting advances the run past technical review."), `reject_implementation` ("Rejection sends the implementation back for rework."), `takeover_workflow` (short hint; full consequence in the confirm dialog).
  - [x] Extend `resolveApprovalBarState`: add an `implementation_review` branch (mirrors `spec_approval`'s `primary === null || !canFire → blocked → success → ready`, BUT primary = `accept_implementation` and `canFire` uses `resolveImplementationArtifactId`). Resolve it BEFORE the generic `view.mode !== 'spec_approval' → disabled` fallthrough (exactly how `recovery_operator` was carved out at lines 362-367). **`Take over` must remain fireable even when accept/reject are `blocked`** (takeover needs no artifactId/version) — model takeover as an always-available secondary when `takeover_workflow ∈ actions`.

- [x] **Task 2 — Three new mutation hooks** (AC3, AC4, AC5) **[R2, R3, R4]** — all on the `useWorkflowMutation` factory, mirroring `useApproveSpec`/`useRejectSpec`/`useRetryWorkflow`:
  - [x] `useAcceptImplementation(runId)` → `POST /api/v1/workflows/{workflowRunId}/accept-implementation` (op `acceptImplementation`); body `AcceptImplementationRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reason? }`; response `WorkflowStateChangeResponse`.
  - [x] `useRejectImplementation(runId)` → `POST .../reject-implementation` (op `rejectImplementation`); body `RejectImplementationRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reasonText, taggedFeedback }` (`reasonText` + `taggedFeedback` REQUIRED); response `WorkflowStateChangeResponse`.
  - [x] `useTakeoverWorkflow(runId)` → `POST .../takeover` (op `takeover` — **NOT** `/takeover-workflow`, which is the older transition-only endpoint); body `TakeoverRequest { reasonText }` (`reasonText` REQUIRED, NO version fields); response **`TakeoverResponse`** (rich: `currentState, recoveryActionId, replayed, cancelledInFlightCount?, cancelledQueuedCount?, preservedPrReference?, correlationId?`).
  - [x] Actor: OMIT `actorIdentity`/`actorType` from all three bodies — these endpoints derive actor from the optional `X-Actor-Identity` header and the backend defaults `local-operator`/HUMAN (identical to `useApproveSpec`/`useRejectSpec`, which send no actor). Do NOT copy `useRetryWorkflow`'s body-actor pattern (that endpoint requires it; these do not).
  - [x] `reviewerRole`: OMIT (optional; if present must equal `"developer"` → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`; no live role context — accept it forward-compat exactly like the spec hooks).
  - [x] `reasonText` is reviewer-authored — NEVER log it (T-LOG-PII).

- [x] **Task 3 — Takeover consequence text in the confirm catalog** (AC4) **[R6]**
  - [x] Add a NEW `CONFIRMATION_CATALOG` entry `takeoverWorkflow` (`intent: 'danger'`, `owningStory: '3.28'`) whose `consequenceTemplate` is the **verbatim copy** of `openapi.json` `paths./api/v1/workflows/{workflowRunId}/takeover.post.description` (story 3.25 AC9). `openapi-typescript` strips endpoint descriptions from `schema.d.ts`, so there is no runtime field to read — the catalog constant IS the canonical UI copy (same pattern story 3.30 used for `retryOrRecoverConsequential`). Add the new id to `ConfirmationActionId` + `CONFIRMATION_ACTION_IDS`.
  - [x] Pin parity with a comment citing the OpenAPI source + a test asserting the takeover dialog renders this exact text.

- [x] **Task 4 — Real `renderImplementationReview()` in `ApprovalDecisionBar.tsx`** (AC1, AC2, AC6, AC7, AC8, AC9)
  - [x] Replace the `implementation_review` `StubPlaceholder` branch with a real renderer modeled on `renderSpecApproval` + `renderRecoveryOperator`. States: `blocked` (no implementation artifact / versions) — but still render the takeover secondary; `ready` (accept primary + reject + takeover secondaries); `submitting`/`success`/`error`/`stale`/`locked` per the shared state machine.
  - [x] Reject flow reuses a rationale dialog (extend/parameterize the region-local `RejectionDialog` to accept the developer taxonomy options, or add a sibling) — required `reasonText` + required developer `taggedFeedback` radio; the dialog cannot be dismissed except via explicit Cancel (matches existing T-MODAL discipline).
  - [x] Takeover flow uses the shared `<ConfirmationDialog>` with `CONFIRMATION_CATALOG.takeoverWorkflow` + a required `reasonText` field (gate confirm via `confirmDisabled` until non-blank).
  - [x] Post-submit summary (AC6): reuse `DecisionSummaryView`; extend the `DecisionSummary.decision` union if needed (`'accepted' | 'rejected' | 'takenover'`).
  - [x] Post-takeover (AC7): render "Continue work in PR {ref}" via `githubRef.ts` when `preservedPrReference` present + "Run is taken over" read-only label.
  - [x] Announcements (AC8): add strings to `lib/a11y/announcements.ts` (e.g. `implementationAccepted`, `implementationRejected`, `workflowTakenOver`) and route them through the existing single live region (the `announcementText` switch).

- [x] **Task 5 — `ImplementationReviewDecisionBarContainer.tsx` + route wiring** (AC1, AC5, AC6, AC7) **[R9, R10]**
  - [x] New container (sibling of `ApprovalDecisionBarContainer`/`RecoveryDecisionBarContainer`) feeding the SAME `ApprovalDecisionBar` with `mode='implementation_review'`. Reads `useAllowedActions` + `useWorkflowDetail`; resolves `resolveImplementationArtifactId`; derives expected versions (see **[R3]** OQ on which version to send); wires `onApprove→accept`, `onReject→reject`, plus a takeover path.
  - [x] Capture the takeover success `data` (`TakeoverResponse`) in container state to drive the AC7 PR affordance (mirror `lastDecision` capture in `ApprovalDecisionBarContainer:113-166`).
  - [x] Wire into `WorkflowDecisionBar.tsx`: `currentState === 'WaitingForReview'` selects this container (spec approval is `WaitingForSpecApproval`, recovery is `Failed` — no collision; verify against `RECOGNIZED_STATES`). **Lift the accept/reject/takeover mutation instances to `WorkflowDecisionBar` (mirror story 3.30's `retry` prop)** so the success summary + AC7 PR affordance + live-region announcement survive the post-decision state flip (WaitingForReview → Completed/Executing/TakenOver would otherwise unmount the bar and tear down its local summary state). Keep the impl-review bar mounted while `currentState === 'WaitingForReview' || <any of the three mutations isPending || isSuccess>`.

- [x] **Task 6 — Tests** (AC10) — Vitest + Testing Library + axe-core (story 2.27 AC4 / 3.30 coverage pattern):
  - [x] Extend `src/test/fixtures/approval/approvalDecisionFixtures.ts` with `implementation_review` fixtures (all-three-actions, accept-disabled, blocked-no-artifact, stale, post-takeover-with/without-PR).
  - [x] `ApprovalDecisionBar.test.tsx`: all three actions render; single-primary rule; reject dialog enforces reasonText + developer taxonomy; takeover dialog renders OpenAPI consequence text + enforces reasonText; stale UI; post-submit summary; post-takeover PR affordance (present + null); keyboard nav; axe-core zero violations.
  - [x] New `ImplementationReviewDecisionBarContainer.test.tsx`: each mutation fires with the correct body (accept/reject send versions; takeover sends none + no actor/reviewerRole); `APPROVAL_VERSION_MISMATCH`→stale + refetch; takeover success captures `preservedPrReference`.
  - [x] New hook tests (mirror `useRetryWorkflow.test`/`useApproveSpec` style) asserting endpoint path, idempotency-key, body shape, typed error surfacing.
  - [x] Keep `ApprovalDecisionBar.eligibility.test.ts` green (no permission-inference import).
  - [x] Regenerate the openapi client only if the snapshot changed — it should NOT (all three endpoints already shipped; this is a frontend-only consumer story).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] This is a frontend story — the "structured logs" surface is the existing field-only `console.info`/`console.warn` discipline used by `ApprovalDecisionBarContainer`/`RecoveryDecisionBarContainer`. Emit: `impl.acceptSubmit` / `impl.rejectSubmit` / `impl.takeoverSubmit` (on success — the response `currentState` only), and `impl.submitError` / `impl.versionMismatch` (stable ProblemDetails `code` + `transport` flag).
  - [x] **NEVER log** `reasonText`, `taggedFeedback` free text, run/artifact/actor ids beyond what the existing containers already log, `preservedPrReference`, or any PII (T-LOG-PII). `reject`'s `taggedFeedback` enum value is non-PII and MAY be logged (as the spec reject container does).
  - [x] On `APPROVAL_VERSION_MISMATCH`, log at WARN + refetch allowed-actions (mirror `logMutationError`).
  - [x] Add at least one focused test asserting the expected log event/level for the new submit + error branches (the containers' existing tests use spies on `console`).

## Dev Notes

### This is a frontend-only consumer story
All three backend endpoints (accept/reject/takeover) + their OpenAPI + the generated `schema.d.ts` types already shipped (stories 3.23/3.24/3.25, all `done`). **Do not touch the backend or regenerate the OpenAPI snapshot** — the schema is the fixed contract you consume. The work is entirely under `deliveryline-frontend/src/features/workflows/`.

### The reuse map (do NOT reinvent)
| Need | Reuse / extend | Path |
|---|---|---|
| Presentational bar + mode switch | `ApprovalDecisionBar` (extend the `implementation_review` case) | `components/ApprovalDecisionBar.tsx` |
| View contract + state machine + helpers | `approvalDecisionView.ts` (extend) | `features/workflows/approvalDecisionView.ts` |
| Container pattern | `ApprovalDecisionBarContainer` (spec) + `RecoveryDecisionBarContainer` (the closest sibling — new mode + new container + new hook) | `components/*Container.tsx` |
| Mutation factory | `useWorkflowMutation` (idempotency-key + invalidation cascade) | `hooks/useWorkflowMutation.ts` |
| Hook templates | `useApproveSpec` / `useRejectSpec` (accept/reject), `useRetryWorkflow` (takeover w/ rich response) | `hooks/` |
| Confirm dialog | `<ConfirmationDialog>` + `CONFIRMATION_CATALOG` | `components/overlays/`, `lib/overlays/confirmationCatalog.ts` |
| Rejection rationale dialog | region-local `RejectionDialog` (parameterize for dev taxonomy) | inside `ApprovalDecisionBar.tsx` |
| PR-ref → link | `githubRef.ts` (already dot-traversal-hardened — see `[[githubref-branchurl-dot-traversal]]`) | `features/workflows/githubRef.ts` |
| Announcements | `lib/a11y/announcements.ts` (add new strings) | `lib/a11y/announcements.ts` |
| Route selector | `WorkflowDecisionBar.tsx` (add `WaitingForReview` branch) | `components/WorkflowDecisionBar.tsx` |
| Actor seam | `LOCAL_ACTOR_IDENTITY`/`LOCAL_ACTOR_TYPE` (only if ever needed — these endpoints don't need a body actor) | `lib/api/actor.ts` |

### Central Reconciliations (these WIN over the epic AC prose)

- **[R1] accept/reject inherit the `artifactId` seam; takeover does not.** `AcceptImplementationRequest` + `RejectImplementationRequest` require `artifactId` + `expectedArtifactVersion` + `expectedContextBundleVersion`. The 2.19 `artifactId` dormancy is **resolved as of story 3a-9** — the read model now populates `latestArtifacts[].artifactId` (`resolveSpecArtifactId` returns a live id; the route renders a real "Open the specification" link). So accept/reject fire LIVE whenever the run carries an implementation artifact; they render `blocked` only when none exists yet. **`takeover` needs NO artifactId/version** (only `reasonText`), so it is always fireable at `WaitingForReview`.
- **[R2] takeover sends NO version fields (epic AC5 drift).** AC5 says "every mutation sends expectedArtifactVersion + expectedContextBundleVersion" — FALSE for takeover. `TakeoverRequest = { reasonText, reviewerRole? }`. Takeover cannot return `APPROVAL_VERSION_MISMATCH`; the stale-decision path applies to accept + reject only.
- **[R3] `expectedAllowedActionsVersionStamp` does not exist (epic AC5 drift).** No request body carries it. Send only the two version ints for accept/reject. **OQ-1 (verify against backend):** does `versionStamp.currentSpecArtifactVersion` carry the *spec* version or the *artifact-under-review* version at `WaitingForReview`? If it is spec-specific, derive `expectedArtifactVersion` from the resolved implementation `LatestArtifact.version` (unambiguous) and `expectedContextBundleVersion` from the stamp — do NOT blindly reuse `deriveExpectedVersions(versionStamp)` for the artifact version. Confirm in the allowed-actions version-stamp builder before wiring.
- **[R4] three new hooks, factory-built.** Endpoints/ops: `acceptImplementation`, `rejectImplementation`, `takeover`. Omit actor + reviewerRole (see Task 2). Takeover returns the rich `TakeoverResponse`, not `WorkflowStateChangeResponse`.
- **[R5] developer taxonomy ≠ spec taxonomy.** Reject-implementation `taggedFeedback` ∈ `{INCORRECT_APPROACH, INCOMPLETE_IMPLEMENTATION, QUALITY_ISSUE, BREAKS_EXISTING_FUNCTIONALITY, OUT_OF_SCOPE}` (UPPERCASE wire, Jackson binds by enum NAME). The existing `TAGGED_FEEDBACK_OPTIONS` (`ApprovalDecisionBar.tsx:64-68`) is the *product/spec* set — add a NEW developer options list; do not reuse. Errors: `MISSING_REJECTION_TAXONOMY` (400, null), `INVALID_REJECTION_TAXONOMY` (400, valid-enum-but-wrong-role-subset).
- **[R6] takeover consequence text = a verbatim catalog constant, not a runtime read.** AC4's "read from OpenAPI documentation" is satisfied by copying `openapi.json takeover.post.description` into a new `CONFIRMATION_CATALOG.takeoverWorkflow` entry (descriptions are stripped from `schema.d.ts` — there is nothing to read at runtime). This is exactly the precedent story 3.30 set for the retry consequence.
- **[R9] post-takeover affordance reads the rich response.** Capture `TakeoverResponse.preservedPrReference` from the takeover mutation success and render the PR link (null → label-only). The same response carries `cancelledInFlightCount`/`cancelledQueuedCount` (informational; null on idempotent replay) — surface optionally.
- **[R10] route selector + mutation lifting.** Add `WaitingForReview → ImplementationReviewDecisionBarContainer` to `WorkflowDecisionBar`. Lift the three mutations to the selector (3.30 P3 pattern) so the success summary/announcement/PR affordance survive the post-decision state flip out of `WaitingForReview`.
- **[R11] disabledActions is dormant.** Live posture: accept hidden when absent from `actions[]`; `ARTIFACT_PR_LINK_MISMATCH` (409, accept only) surfaces as the `error` state. The reason-code→text map exists (`mapDisabledReason`) for the fixture-driven AC9 path.

### Backend contract (frozen — quoted from the live schema)
- **Endpoints** (`openapi.json` / `schema.d.ts`): `POST .../accept-implementation` (`acceptImplementation`), `.../reject-implementation` (`rejectImplementation`), `.../takeover` (`takeover`).
- **`AcceptImplementationRequest`**: `artifactId` (req), `expectedArtifactVersion` (req), `expectedContextBundleVersion` (req), `reason?`, `reviewerRole?`.
- **`RejectImplementationRequest`**: `artifactId`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `reasonText` (req), `taggedFeedback` (req, developer enum), `reviewerRole?`.
- **`TakeoverRequest`**: `reasonText` (req), `reviewerRole?`.
- **`TakeoverResponse`**: `workflowRunId`, `currentState` (`TakenOver`), `recoveryActionId`, `replayed`, `cancelledInFlightCount?`, `cancelledQueuedCount?`, `preservedPrReference?`, `correlationId?`.
- **Allowed-actions matrix** (`AllowedAction.java`; `WorkflowInspectionServiceAllowedActionsTest.java:126-133`): state `WAITING_FOR_REVIEW` + role `developer` → `ACCEPT_IMPLEMENTATION`, `REJECT_IMPLEMENTATION`, `TAKEOVER_WORKFLOW`, `VIEW_ONLY`. All three in the same state.
- **Error codes**: accept → `APPROVAL_VERSION_MISMATCH`(409), `ARTIFACT_PR_LINK_MISMATCH`(409), `ARTIFACT_PAYLOAD_UNAVAILABLE`(503), `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`(400), `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL`(409), `RUN_NOT_FOUND`/`ARTIFACT_RECORD_NOT_FOUND`(404). reject → as accept minus `ARTIFACT_PR_LINK_MISMATCH`, plus `MISSING_REJECTION_TAXONOMY`/`INVALID_REJECTION_TAXONOMY`(400). takeover → `INVALID_REVIEWER_ROLE_FOR_ENDPOINT`(400), `ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL`/`IDEMPOTENCY_KEY_CONFLICT`(409), `RUN_NOT_FOUND`(404) — NO version-mismatch.

### Architecture compliance
- **No backend / no OpenAPI changes** — consumer-only. If `npm run generate-api` produces any diff, something is wrong (the contract is frozen).
- **`react-refresh/only-export-components`** (`[[frontend-react-refresh-no-fn-exports]]`): all new helpers/maps/types go in `approvalDecisionView.ts` (or a sibling `.ts`), NEVER exported from a `.tsx`.
- **No frontend permission inference** (UX-DR12 / 2.19 AC11): eligibility comes ONLY from `useAllowedActions`. The `ApprovalDecisionBar.eligibility.test.ts` import-graph guard must stay green.
- **`exactOptionalPropertyTypes`** is on (`[[artifactview-variant-field-fanout]]`): optional fields must be `T | undefined`, never bare optional with an `undefined` value assigned in a literal.
- **Untrusted text**: actor identity, `preservedPrReference`, and any echoed strings render through `SafeMarkdownRenderer` / are React-escaped — never raw. The PR ref must pass `githubRef.ts` URL hardening before becoming an `href`.

### Project Structure Notes
- New files: `hooks/useAcceptImplementation.ts`, `hooks/useRejectImplementation.ts`, `hooks/useTakeoverWorkflow.ts`, `components/ImplementationReviewDecisionBarContainer.tsx`, `components/ImplementationReviewDecisionBarContainer.test.tsx`, hook tests.
- Modified: `approvalDecisionView.ts`, `components/ApprovalDecisionBar.tsx`, `components/WorkflowDecisionBar.tsx`, `lib/overlays/confirmationCatalog.ts`, `lib/a11y/announcements.ts`, `src/test/fixtures/approval/approvalDecisionFixtures.ts`, and the relevant `.test.tsx`/`.test.ts`.
- The selector route (`routes/workflows/$workflowRunId/index.tsx`) does NOT need changes if all mode selection stays inside `WorkflowDecisionBar`.

### Logging Requirements (project-wide standard)
This is a frontend story; the observable surface is the existing field-only structured `console` discipline (see `ApprovalDecisionBarContainer` / `RecoveryDecisionBarContainer`). No SLF4J/MDC applies here. The hard rule that carries over: **never log reviewer-authored free text or PII** (`reasonText`, `preservedPrReference`), only stable enum codes / response states / ProblemDetails `code` + a `transport` boolean. Pin each new submit/error branch with a focused `console` spy assertion.

### References
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.28] (lines 559-576)
- [Source: deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx] — mode switch + `renderSpecApproval`/`renderRecoveryOperator` patterns
- [Source: deliveryline-frontend/src/features/workflows/approvalDecisionView.ts] — `DecisionAction`, `resolveApprovalBarState`, `resolveSpecArtifactId`, `CONSEQUENCE_HINTS`
- [Source: deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.tsx + WorkflowDecisionBar.tsx] — the closest "activate a stub mode" precedent (story 3.30)
- [Source: deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.ts, useRejectSpec.ts, useRetryWorkflow.ts] — hook templates
- [Source: deliveryline-frontend/src/lib/overlays/confirmationCatalog.ts + components/overlays/ConfirmationDialog.tsx] — confirm-before primitives
- [Source: deliveryline-backend/.../domain/registry/AllowedAction.java] — `accept_implementation` / `reject_implementation` / `takeover_workflow` wire values
- [Source: deliveryline-backend/.../domain/registry/RejectionTaxonomy.java] — the 5 developer taxonomy values
- [Source: deliveryline-backend/src/main/resources/openapi/openapi.json] — request/response schemas + `takeover.post.description` (the AC4 consequence text)
- Memory: [[story-3-25-takeover-rest-reconciliations]] (TakeoverResponse shape + OpenAPI consequence text), [[story-3-23-accept-implementation-rest-reconciliations]], [[story-3-24-reject-implementation-rest-reconciliations]], [[story-3-30-retry-ui-reconciliations]] (the activate-a-stub-mode precedent), [[githubref-branchurl-dot-traversal]], [[artifactview-variant-field-fanout]], [[frontend-react-refresh-no-fn-exports]], [[workflowdetail-wire-sends-null-not-undefined]]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story 2026-06-16.

### Debug Log References

- `npx tsc -b` — clean (production + tests).
- `npx vitest run` — 1005 passed / 91 files (full suite, no regressions).
- `npm run lint` — 0 errors / 0 warnings (`--max-warnings=0`); `npm run format:check` — clean.
- `npm run check:api` — generated client in sync with the committed OpenAPI snapshot (frontend-only confirmed; ZERO openapi/schema.d.ts change).
- `npm run check:a11y` — 4/4 (announcement vocabulary); `npm run lint:rules-test` — 9/9 (incl. single-primary-action).

### Completion Notes List

Frontend-only activation of the Decision Bar `implementation_review` mode over the done 3.23/3.24/3.25 endpoints — NO backend/OpenAPI/schema change. All 11 reconciliations honored:

- **R7/R8** — added `accept_implementation`/`reject_implementation`/`takeover_workflow` to the `DecisionAction` union AND `KNOWN_ACTIONS`; new `implementation_review` branch in `resolveApprovalBarState`, placed before the (now-removed-as-dead) generic fallthrough, with `success` checked BEFORE `blocked` so the post-decision summary/PR affordance survive the refetch to a terminal allowed-actions set.
- **R1/R3 (OQ-1 resolved)** — accept/reject derive `expectedArtifactVersion` from the resolved IMPLEMENTATION artifact's version (`resolveImplementationArtifact`, filtering `implementationPlan`/`prOutput`, highest version) and `expectedContextBundleVersion` from the stamp — NOT `currentSpecArtifactVersion` (verified backend `ApprovalVersionBinder` compares against the artifact identified by `artifactId`). The bar's `canFire` gate reads the resolved `artifactId` + the context-bundle version; the container only exposes `artifactId` when the full request is buildable.
- **R1/R8** — takeover needs no artifact/version → `Take over` renders as an always-available secondary even when accept/reject are `blocked`.
- **R2** — takeover sends NO version fields and cannot return `APPROVAL_VERSION_MISMATCH`; the stale path is accept/reject only.
- **R4** — three new `useWorkflowMutation`-factory hooks (`useAcceptImplementation`/`useRejectImplementation`/`useTakeoverWorkflow`); actor + reviewerRole OMITTED (header-derived, like the spec hooks). Takeover returns the rich `TakeoverResponse`.
- **R5** — new `DeveloperTaggedFeedback` type + `DEVELOPER_TAGGED_FEEDBACK_OPTIONS` (5 values) distinct from the spec set; the region-local `RejectionDialog` was made generic over the taxonomy value.
- **R6** — new `CONFIRMATION_CATALOG.takeoverWorkflow` (`intent: 'danger'`) whose `consequenceTemplate` is the VERBATIM `openapi.json` `takeover.post.description`; pinned by a test reading the live OpenAPI file.
- **R9** — the container captures `TakeoverResponse.preservedPrReference` into the decision summary; the bar renders "Continue work in PR {ref}" via `githubRef.ts` (null/malformed → read-only "Run is taken over" label only).
- **R10** — `WorkflowDecisionBar` selects `ImplementationReviewDecisionBarContainer` on `currentState === 'WaitingForReview'` and LIFTS the three mutations (3.30 P3 pattern) so the success summary/PR affordance/announcement survive the post-decision flip out of `WaitingForReview`.
- **R11** — `disabledActions` dormant; accept hidden when absent from `actions[]`; `ARTIFACT_PR_LINK_MISMATCH`/other 409s surface as the `error` state.
- **Logging** — field-only `console` discipline (`impl.acceptSubmit`/`impl.rejectSubmit`/`impl.takeoverSubmit` = response `currentState` only, reject adds the non-PII `taggedFeedback` enum; `impl.submitError`/`impl.versionMismatch`); `reasonText` + `preservedPrReference` are NEVER logged (pinned by tests).
- The E3 `StubPlaceholder` for `implementation_review` is removed (the recovery `recovery_operator` stub was already replaced by 3.30); the eligibility import-graph guard stays green.

### File List

**Modified:**
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.ts`
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.test.ts`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/WorkflowDecisionBar.tsx`
- `deliveryline-frontend/src/features/workflows/components/WorkflowDecisionBar.test.tsx`
- `deliveryline-frontend/src/lib/overlays/confirmationCatalog.ts`
- `deliveryline-frontend/src/lib/overlays/__tests__/confirmationCatalog.test.ts`
- `deliveryline-frontend/src/lib/a11y/announcements.ts`
- `deliveryline-frontend/src/test/fixtures/approval/approvalDecisionFixtures.ts`

**New:**
- `deliveryline-frontend/src/features/workflows/hooks/useAcceptImplementation.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useAcceptImplementation.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useRejectImplementation.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useRejectImplementation.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useTakeoverWorkflow.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useTakeoverWorkflow.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.tsx`
- `deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.test.tsx`

### Change Log

- 2026-06-16 — Implemented story 3.28 (Decision Bar `implementation_review` mode activation), frontend-only over the done 3.23/3.24/3.25 endpoints. Status `ready-for-dev → review`.

### Review Findings

_bmad-code-review 2026-06-16 — 3-layer adversarial review (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 1 decision-needed, 3 patch, 2 defer, 12 dismissed as noise. **All decision + patch findings RESOLVED (fixed) 2026-06-16; tsc clean, 124 affected tests pass, prettier + eslint clean.**_

- [x] [Review][Patch] Implementation-artifact selection must prefer `prOutput` over `implementationPlan` [approvalDecisionView.ts:540-564] — `resolveImplementationArtifact` filtered both types and picked the single highest `version` across INDEPENDENT sequences (so `implementationPlan v5` + `prOutput v2` wrongly resolved to the plan; equal versions by array order). **Resolution (D1, user 2026-06-16): prefer the highest-version `prOutput`; fall back to `implementationPlan` only when no `prOutput` exists.** FIXED: rewrote as a per-type `pickHighest` with `prOutput ?? implementationPlan` precedence; added a focused test pinning prefer-prOutput-over-higher-plan + plan-fallback. (source: blind+edge)
- [x] [Review][Patch] Stale-state note shows the SPEC artifact version, not the implementation version [ApprovalDecisionBar.tsx:836+] — impl-review `stale` branch read `currentSpecArtifactVersion` and rendered "It is now at version {n}" (wrong number per [R3]). FIXED: dropped the version note from the impl-review stale copy (the spec-approval stale path, which legitimately uses the spec version, is unchanged). (source: blind+auditor)
- [x] [Review][Patch] Accept button has no in-flight disable → double-click double-submit [ApprovalDecisionBar.tsx:702+] — FIXED: added `disabled={mutation.status === 'pending'}` to the Accept button and folded `|| mutation.status === 'pending'` into the takeover `confirmDisabled`, matching the takeover affordance (the container's `accept.isPending` guard already mitigated server-side via idempotency). (source: blind+edge)
- [x] [Review][Patch] Eligibility import-graph guard does not cover the new container [ApprovalDecisionBar.eligibility.test.ts] — FIXED: the guard now reads `ImplementationReviewDecisionBarContainer.tsx` too, asserting it sources eligibility only from `useAllowedActions` and matches no permission-inference pattern (UX-DR12 now enforced for the new container by the guard, not just manual code). (source: auditor)

- [x] [Review][Defer] Takeover ConfirmationDialog unmounts mid-close on the post-submit state flip → focus drops to body [ApprovalDecisionBar.tsx:752-784] — deferred, shared pattern. On confirm the state flips to `submitting`, unmounting the dialog without radix's close-focus-restore; an a11y concern (AC8 focus predictability) but identical to the existing `spec_approval`/`recovery` confirm flows — belongs to the shared dialog pattern, not this story.
- [x] [Review][Defer] Stale state hides the still-valid `Take over` action until refresh [ApprovalDecisionBar.tsx:836-861] — deferred, minor UX. When accept/reject go stale on `APPROVAL_VERSION_MISMATCH`, the stale branch replaces the whole action area, hiding takeover (which can never be stale). Refresh recovers it; low impact.
