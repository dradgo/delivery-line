# Story 3b.6: `implementationPlan` Review Rendering + Plan-Phase Accept/Reject/Takeover

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer reviewer,
I want a live `implementationPlan` artifact's ordered steps rendered as a real review panel (not raw JSON), with accept / reject (developer taxonomy) / takeover firing end-to-end for the plan phase,
so that the plan phase of the two-dispatch flow is reviewable and actionable at `WaitingForReview` — the path that needs **no** `github_pr` link (unlike a `prOutput` accept).

## Context & the ONE non-obvious finding that re-shapes this story (read first)

This is sub-project **#3, Story C** of "Option X" (the full `WaitingForReview` review experience), the **`implementationPlan` twin of 3b-5** (which did the same for `prOutput`). #3-Story-A (3b-4, done) wired the `developer` role so the decision bar's actions appear and fire; #3-Story-B (3b-5, done) made a live `prOutput` render as a real PR + diff. 3b-6 makes a live `implementationPlan` render as **real ordered steps** and verifies the plan-phase decisions fire.

### The epic's framing is HALF-OUTDATED — verify this before you start

The epic stub (`epic-03-agent-execution.md:977-992`) says two things; **one is misleading and one is already done**:

1. *"the done 3.26 impl-plan ARP variant renderer is not surfaced on the live review path; just wire the existing variant to the live artifact path (same `isArtifactView` reachability reconciliation as 3b-5/3.27); do not re-build it."* — **The renderer is already reachable and dispatched** (verified in source 2026-06-18). The real gap is identical to 3b-5's: it renders the **wrong thing** because the structured step data is not typed on the wire and the mapper hard-codes no steps. This is a **read-model** story, not a pure wiring story.

2. *"Plan-phase accept / reject (developer taxonomy) / takeover wired end-to-end to the existing endpoints."* — **This wiring is already DONE.** 3b-4 + the generic decision-bar machinery (`ImplementationReviewDecisionBarContainer`, the three hooks, the developer reject-taxonomy picker, the takeover preserved-PR affordance) are **artifact-agnostic** and already fire for an `implementationPlan` target. 3b-6 does **not** build decision wiring; it **renders the steps** and **verifies/pins** that the plan-phase decisions fire (they need no PR link).

### What the live reviewer sees TODAY (the bug this story fixes)

A live `implementationPlan` artifact-read (`GET /api/v1/workflows/{runId}/artifacts/{artifactId}`, story 3a-9) returns `ArtifactDetail.body` = the **UTF-8 of the stored runner JSON**: `{ artifactId, artifactType:"implementationPlan", steps:[...], contextReferences:[] }` (verified: `RunnerBroker.artifactPayload` at `:2174-2204` serializes the inline `ref` JSON; `getArtifactDetail` at `:1124` sets `responseBody = body` for non-`prOutput`). The frontend `ImplementationPlanArtifactRenderer`:
- renders that **raw JSON string as markdown** in its body section (`SafeMarkdownRenderer source={artifact.body}`), and
- shows **"This implementation plan has no steps."** — because `toArtifactView`'s `implementationPlan` arm (`queryOptions.ts:155-157`) is `return { ...base, artifactType: 'implementationPlan' }` with **no step mapping**, so `view.steps` is `undefined` → the renderer's `steps = artifact.steps ?? []` is empty.

So the reviewer sees raw JSON + an empty-steps shell — the exact 3b-5 prOutput-renders-empty parallel.

### What IS on the wire vs what is NOT (the crux)

| Field | On the live wire today? | Source |
|---|---|---|
| `steps` (the ordered plan, as `string[]`) | ✅ Yes — **inside `body` JSON** | runner emits inline (`runner.mjs commandBuild`, `implementationPlan` arm `:238-244`); stored verbatim by `artifactPayload` |
| `steps` as a **typed `ArtifactDetail` field** | ❌ **No** | `ArtifactDetail` has no `steps` property (`schema.d.ts`); the mapper can't read it typed |
| `contextReferences` | ⚠️ Always **`[]`** | runner hard-codes `contextReferences: []` (`runner.mjs:244,300`) — dormant; **out of scope** (see guardrails) |

**Decision (recommended — mirrors 3b-5 DD2): a read-model story.** Surface `steps` as a **typed nullable `string[]`** field on the `ArtifactDetail` read DTO (parsed from the stored payload JSON at read time), blank the `prOutput`-style markdown `body` for `implementationPlan`, and point the frontend mapper at the typed field. This keeps the frontend off an untyped `JSON.parse(body)` (the [[artifact-read-dto-must-satisfy-isartifactview]] contract) and lets `tsc`/`check:api` police drift — exactly how 3b-5 chose typed DTO fields over frontend body-parsing. **This story is SIMPLER than 3b-5**: the steps are already in the stored payload (no ephemeral scratch to resolve, no enrich, no dual-write trap), and `implementationPlan` ingest is a **single write** (no `enrichPrOutputArtifact` twin). Do **not** re-build the 3.26 renderer or the `isArtifactView` guard — feed them real data.

## ⚠️ Scope decision to confirm in Task 0 (recommended default in place; proceed unless Alex overrides)

**SD-1 — typed `steps[]` wire field vs frontend `JSON.parse(body)`.** Unlike 3b-5 (where the diff lived in ephemeral scratch and `prState` in another table, making backend work unavoidable), the `implementationPlan` steps are **already in the response `body` JSON**, so a frontend-only `JSON.parse(body)` is *technically* possible. **Recommended: typed DTO field** for consistency with 3b-5 DD2 and the [[artifact-read-dto-must-satisfy-isartifactview]] contract (no frontend coupling to an untyped body blob). The ACs below assume the typed-DTO approach; if Alex prefers frontend-only parsing, drop the backend ACs (1–4) and parse `body` in the mapper instead (still set `view.body = ''` in the mapper so the raw JSON never reaches `SafeMarkdownRenderer`).

## Scope guardrails (do NOT do here)

- Do **not** re-build the `ImplementationPlanArtifactRenderer` (3.26), the `ImplementationPlanArtifactView` type, or the `isArtifactView` impl-plan branch — they are correct; this story **feeds them real `steps`**. `steps` stays **OPTIONAL** on the view (no new required field) → **no `isArtifactView`/renderer fan-out** ([[artifactview-variant-field-fanout]] avoided).
- Do **not** build decision wiring (accept/reject/takeover container, hooks, the developer reject-taxonomy picker, the takeover affordance) — **3b-4 + the generic machinery already fire for an `implementationPlan` target** (verified). This story **renders steps + pins** that the plan-phase decisions fire.
- Do **not** wire `contextReferences` — the runner hard-codes `[]`, so it is dormant; leave the renderer's existing "No context references." empty-state. (A future read-model story wires it when the runner emits structured refs.)
- Do **not** build the `prOutput` PR/diff renderer (3b-5, done) or change its mapper arm.
- Do **not** add a `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema change, config property, REST endpoint, ArchUnit rule, transition-table edge, or a `WorkflowInspectionService` ctor dep. The new DTO field is **additive nullable**; the read endpoint's error codes already exist ([[artifact-read-dto-must-satisfy-isartifactview]]).
- Do **not** change the decision-bar role wiring (3b-4) or the kept-alive success/announcement behavior (`WorkflowDecisionBar.tsx:42-61`) — preserve them.

## Acceptance Criteria

### Backend — surface the plan steps on the artifact-read response

1. **Extend the read DTO with an additive nullable `steps` field.** Add `List<String> steps` (nullable) to `ArtifactDetailView` (`WorkflowInspectionService:1681-1698`) and `ArtifactDetailResponse` (`adapters/rest/ArtifactDetailResponse.java`, with an `@Schema(nullable = true)`). Populated **only for `implementationPlan`**; for `spec`/`prOutput` it is `null`. No new `DomainErrorCode`. Wire the new component through `ArtifactDetailResponse.from(view)` (`:70-85`).

2. **Populate `steps` in `getArtifactDetail` for `implementationPlan`.** Add an `else if (artifact.artifactType() == ArtifactType.IMPLEMENTATION_PLAN)` branch alongside the existing 3b-5 `PR_OUTPUT` branch (`:1130-1153`): JSON-parse the stored payload bytes (the same `objectMapper.readTree(payloadBytes.get())` the prOutput arm uses) and read the `steps` array into a `List<String>` (each element `asText()`; skip blank/non-textual). Set `responseBody = ""` (the steps are the source of truth; the raw JSON must not reach a markdown renderer). A **malformed payload JSON must NOT 500** the read — fall back to `steps = null` + the existing `body` (degraded but safe), and `WARN`-log. `spec`/`prOutput` reads are unchanged (all reads byte-identical except the new field is `null`).

3. **Co-existence with the 3b-5 prOutput fields is null-clean.** The five 3b-5 fields (`branch`/`commitSha`/`prReference`/`prState`/`diff`) stay `null` for `implementationPlan`; the new `steps` stays `null` for `spec`/`prOutput`. No field is ever co-populated across types.

4. **Regenerate the contract.** Add the nullable `steps` property (`{"type":["array","null"],"items":{"type":"string"}}`) to the `ArtifactDetail` schema in `openapi.json` and regenerate `schema.d.ts`; `OpenApiSnapshotContractTest` re-captured green. The additive-nullable change keeps existing `ArtifactDetail` consumers (3a-9 spec read, 3b-5 prOutput read) working unchanged. ([[openapi-regen-platform-shim]], [[maven-arglineation-goal-crash]].)

### Frontend — map the real steps and render the ordered plan

5. **`toArtifactView` (`queryOptions.ts`) `implementationPlan` arm consumes the new wire field.** Replace `return { ...base, artifactType: 'implementationPlan' }` (`:155-157`) with an arm that maps the wire `steps: string[] | null | undefined` into the view's `steps: readonly ImplementationPlanStep[]` by wrapping each non-blank string as `{ summary: step }` (the `ImplementationPlanStep.detail`/`estimatedComplexity` stay absent — the runner emits flat strings; this is the 3.26 R2 `string[]→structured` mapping). Build the `steps` field **conditionally** (assign only when the wire array is present and non-empty) to respect `exactOptionalPropertyTypes` ([[artifactview-variant-field-fanout]]); guard `!= null` per [[workflowdetail-wire-sends-null-not-undefined]]. Do **not** map `contextReferences` (out of scope — wire is always `[]`).

6. **A live `implementationPlan` renders its ordered steps, not raw JSON.** On a live `WaitingForReview` run whose plan artifact resolved: the `ImplementationPlanArtifactRenderer` shows the numbered, keyboard-operable step accordion (each step's `summary`), and **no raw JSON** appears (the backend `body=""` + the steps now populate the typed field). The "No context references." empty-state still shows (dormant). **Minor renderer hardening (allowed, not a re-build):** skip the body markdown block when `artifact.body` is blank so a `body=""` plan does not render an empty prose shell (`ImplementationPlanArtifactRenderer.tsx:166-173`).

### Decisions — verify the plan-phase accept/reject/takeover fire (already wired)

7. **Plan-phase accept / reject (developer taxonomy) / takeover fire end-to-end and transition the run, preserving kept-alive behavior.** Using the existing `ImplementationReviewDecisionBarContainer` (3b-4) at `WaitingForReview`: accept transitions the run (plan accept → `Executing`/PR phase), reject with a developer-taxonomy value (`incorrect_approach`/`incomplete_implementation`/`quality_issue`/`breaks_existing_functionality`/`out_of_scope`) transitions the run, and takeover transitions to `TakenOver`. The decision bar stays mounted through the post-decision state flip (the success summary + announcement). **No `github_pr` link is required** for any plan-phase decision — confirmed: the accept PR-link gate is `prOutput`-only (`TechnicalApprovalService`, gated on `artifactType == ArtifactType.PR_OUTPUT`), reject has no link gate, takeover has no link gate. The takeover preserved-PR affordance renders the read-only "Run is taken over" label with **no** "Continue work in PR" link (a plan takeover has no preserved PR → `preservedPrReference` null) — already plan-aware (`ApprovalDecisionBar.tsx:143-172`).

### Tests & verification

8. **Tests (failing-first).**
   - **Backend read** (`WorkflowInspectionServiceArtifactDetailTest` or sibling): `getArtifactDetail` on an `implementationPlan` returns a populated `steps` list parsed from the payload + `body=""`; a malformed payload JSON → `steps = null`, body intact, **no 500**, `WARN`-logged; a `spec` read is unchanged (`steps` null, body intact); a `prOutput` read is unchanged (`steps` null, the five 3b-5 fields intact). Name any Testcontainers IT `*IT` ([[springboot-testcontainers-test-must-be-IT]]).
   - **Backend contract** (`OpenApiSnapshotContractTest`): re-captured snapshot byte-matches the springdoc-generated spec with the new nullable `steps` `ArtifactDetail` field.
   - **Frontend mapper** (`queryOptions`/`ArtifactReviewPanel.test.tsx`): the `implementationPlan` arm maps live wire `steps` → an `ImplementationPlanArtifactView` whose `steps` are `{summary}` objects and which passes `isArtifactView`; null/empty wire steps → `steps` absent (renders the empty-state); the view never carries raw JSON in `body`.
   - **Frontend render** (extend `ImplementationPlanArtifactRenderer.test.tsx` / `ArtifactReviewPanel.test.tsx`): a live-shaped (post-mapper) `implementationPlan` renders the numbered step accordion (not the "no steps" empty-state, not raw JSON); a `body=""` plan renders no empty prose block.
   - **Frontend decisions** (existing `ImplementationReviewDecisionBarContainer` tests stay green): plan-phase accept/reject/takeover fire against the resolved `implementationPlan` `artifactId` with the developer reviewer role; the reject dialog uses the developer taxonomy; the takeover affordance shows label-only when no PR is preserved.
   - **No regression**: spec + prOutput artifact-read and renderer tests unchanged.

9. **Rebuild + re-embed + manual verify.** Regenerate generated files **before** the frontend `tsc`/tests ([[openapi-regen-platform-shim]]). `mvn package` re-embeds the SPA into backend `static/` ([[embedded-frontend-at-package-phase]]). On a live `WaitingForReview` run whose execution produced an `implementationPlan`, the panel shows the ordered steps and a plan-phase decision (accept/reject/takeover) transitions the run. (Live verify is a human-in-the-loop step — flag for the operator if not run headlessly; the automated mapper + render + contract pins stand in for the wiring proof.)

10. **No new production surface beyond the one nullable DTO field + the regenerated generated files + the read-path `steps` parse + the mapper arm + the empty-body renderer guard.** No new `DomainErrorCode`, `WorkflowEventType`, Flyway, runner-contracts schema, config property, REST endpoint, ArchUnit rule, transition-table edge, or `WorkflowInspectionService` ctor dep.

## Tasks / Subtasks

- [x] **Task 0 — Confirm SD-1 (typed `steps[]` field vs frontend body-parse)** (AC: gates 1–5)
  - [x] Default is the typed-DTO read-model (mirrors 3b-5 DD2). Proceeded with the recommended typed-DTO approach (no Alex override).
  - [x] Re-confirmed the live `implementationPlan` body is the inline-steps JSON (`ArtifactType.IMPLEMENTATION_PLAN("implementationPlan", …)`; runner emits inline `steps`; no `contentReference`).
- [x] **Task 1 — Backend: add + populate the nullable `steps` field** (AC: #1, #2, #3)
  - [x] Added `List<String> steps` to `ArtifactDetailView` and threaded it through `getArtifactDetail`'s `ArtifactDetailView` construction.
  - [x] Added the `else if (... == IMPLEMENTATION_PLAN)` branch beside the 3b-5 `PR_OUTPUT` branch: parse `steps` via the new `parseSteps` helper (`asText`, skip blank/non-textual), set `responseBody = ""`; malformed JSON → `steps = null` + keep body + `WARN`. Non-impl-plan → `steps = null`, body unchanged.
  - [x] Added nullable `steps` `@Schema` to `ArtifactDetailResponse` + mapped in `from(view)`.
- [x] **Task 2 — Backend: regenerate the contract** (AC: #4)
  - [x] Added the nullable `steps` array property to `ArtifactDetail` in `openapi.json` (springdoc-write path re-captured it byte-identical); regenerated `schema.d.ts` (`npm run generate-api`); `OpenApiSnapshotContractTest` IT byte-match GREEN; `npm run check:api` in-sync.
- [x] **Task 3 — Frontend: map the wire `steps` in `toArtifactView`** (AC: #5)
  - [x] Replaced the `implementationPlan` arm: map `dto.steps` (`string[]|null`) → `steps: [{summary}]` conditionally (filter blanks; assign only when non-empty, respecting `exactOptionalPropertyTypes`; guard `!= null`); no `contextReferences` mapping. No change to `artifactView.ts`/`isArtifactView`.
- [x] **Task 4 — Frontend: empty-body renderer guard** (AC: #6)
  - [x] In `ImplementationPlanArtifactRenderer.tsx`, render the `MetadataChrome` + `SafeMarkdownRenderer` body block only when `artifact.body.trim() !== ''` (a `body=""` plan shows steps without an empty "Generated content" prose shell). Minor hardening, not a re-build.
- [x] **Task 5 — Tests (failing-first)** (AC: #8)
  - [x] Backend read (`WorkflowInspectionServiceArtifactDetailTest`, +6 cases → 20/20): `implementationPlan` populated `steps` + `body=""`; blank/non-textual elements skipped; no-steps-array → null; malformed → null + body intact + no-500 + WARN (+ step text never logged); spec/prOutput unchanged (`steps` null).
  - [x] Backend contract: snapshot re-capture byte-match (IT 1/1).
  - [x] Frontend mapper + live-render in `ArtifactReviewPanel.test.tsx` (+5 cases) / empty-body guard in `ImplementationPlanArtifactRenderer.test.tsx` (+1 case); full vitest 1047/1047 green.
  - [x] Frontend decisions: existing `ImplementationReviewDecisionBarContainer` tests stay green (7/7) — plan-phase accept/reject/takeover fire artifact-agnostically for the plan target (AC7 verified, no wiring change).
- [x] **Task 6 — Rebuild + re-embed + manual verify** (AC: #9)
  - [x] Regenerated generated files BEFORE frontend `tsc`/tests; `tsc -b` clean, `eslint --max-warnings=0` clean, `prettier` clean; backend Spotless applied + clean. Frontend production `npm run build` succeeds (SPA bundle). **`mvn package` SPA re-embed + the live `WaitingForReview` plan-render + decision verify are flagged for the operator (human-in-the-loop) — not run headlessly.**
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Reused the existing `getArtifactDetail` boundary `INFO` lines (entry/success carry `workflowRunId`/`artifactId` via MDC); added the malformed-payload `WARN` for the impl-plan branch.
  - [x] Parameterized logging throughout (`log.warn("...", workflowRunPublicId, artifactPublicId)`).
  - [x] Levels honored: `INFO` lifecycle, `WARN` for the recoverable malformed-payload fallback.
  - [x] Context keys present: `workflowRunId`, `artifactId` (MDC scope).
  - [x] Never logs the parsed step text — the WARN logs no content; a test asserts the step text never appears in any log line.
  - [x] Pinned the malformed-payload `WARN` with a `ListAppender` (`implementationPlanMalformedPayloadFallsBackToNullStepsAndBodyWithoutError`).

## Dev Notes

### Why this is full-stack-but-small (and exactly which half is which)

The 3.26 renderer, the `ImplementationPlanArtifactView` type + `isArtifactView` impl-plan branch, the artifact-read endpoint (3a-9), the route → container → guard → dispatch path, the decision-bar role wiring (3b-4), and the entire accept/reject/takeover machinery (container + hooks + developer taxonomy picker + takeover affordance) are **all done and correct**. The single thing missing is **real step data reaching the renderer as a typed field**: the live wire carries `steps` only buried inside the body JSON, and the mapper hard-codes no steps → the renderer shows raw JSON + an empty-steps shell. This story parses `steps` at read, surfaces it as one typed nullable field, blanks the body, and points the mapper at it. Decisions need only **verification**, not wiring.

| Concern | Where it already lives | This story |
|---|---|---|
| impl-plan renderer (numbered step accordion + a11y + context-ref empty-state) | `ImplementationPlanArtifactRenderer.tsx` (3.26) | reuse — feed it real `steps`; +1 empty-body guard |
| `ImplementationPlanArtifactView` type (`steps?` OPTIONAL) + `isImplementationPlanStep` + `isArtifactView` branch | `artifactView.ts:110-126,207-229,272-283` (3.26) | **unchanged** (steps stays optional → no fan-out) |
| route → `ArtifactReviewPanelContainer` → `useArtifact` → guard → variant dispatch | `ArtifactReviewPanel.tsx:76-100` | unchanged |
| artifact-read endpoint + `ArtifactDetail` body (UTF-8) + error codes | `WorkflowController.getArtifact`, `WorkflowInspectionService.getArtifactDetail:1046-1184` | **add nullable `steps` + populate for impl-plan** |
| impl-plan payload write (single in-loop write; NO enrich/dual-write) | `RunnerBroker` ingest loop `:1292-1399`, `artifactPayload:2174-2204` | **read it** (reference only — no write change) |
| decision machinery: container, hooks, reject taxonomy picker, takeover affordance | `ImplementationReviewDecisionBarContainer.tsx`, `useAccept/Reject/TakeoverImplementation`, `ApprovalDecisionBar.tsx:81-90,143-172` (3b-4/3.28) | **unchanged** — verify it fires for a plan target |
| accept PR-link gate (`prOutput`-only), reject/takeover (no gate) | `TechnicalApprovalService` | unchanged — confirms plan-phase needs no link |

### Design Decision DD1 — typed nullable `steps` field, not frontend `JSON.parse(body)` (mirrors 3b-5 DD2)

The steps are already in the response `body` JSON, so frontend parsing is *possible* — but surfacing `steps` as a **typed nullable** `ArtifactDetail` field keeps the frontend off an untyped body blob (the [[artifact-read-dto-must-satisfy-isartifactview]] contract warns against coupling to body shape) and lets `tsc`/`check:api` police drift, exactly as 3b-5 chose. Set impl-plan `body=""` so the raw JSON never reaches `SafeMarkdownRenderer` and the steps aren't duplicated. Storage keeps the full JSON (checksum/replay fidelity); only the response **projection** changes.

### Design Decision DD2 — `steps` stays OPTIONAL on the view (no ArtifactView fan-out)

The `ImplementationPlanArtifactView.steps` field is already `readonly steps?: …` (3.26 R3) and `isArtifactView` accepts `undefined`-or-valid-array. Keeping it optional means **no `isArtifactView`/renderer fan-out** ([[artifactview-variant-field-fanout]]): the mapper builds `steps` conditionally (and a plan with no steps degrades to the existing empty-state). This is simpler than 3b-5's prOutput, which had to keep `prReference`+`prState` co-present to satisfy a required-field guard.

### Design Decision DD3 — `string[]` → `{summary}` (the 3.26 R2 mapping); `contextReferences` stays dormant

The runner emits `steps` as **flat strings** and `contextReferences` as **`[]`**. The view's `ImplementationPlanStep` is `{summary, detail?, estimatedComplexity?}`; map each wire string to `{summary: step}` (detail/complexity absent — there is no richer source). `contextReferences` is **out of scope**: the wire is always empty, so the renderer's "No context references." empty-state is correct; a future read-model story wires it when the runner emits structured refs (a `string[]→ContextRef` mapping would be lossy/speculative today).

### Why this is simpler than the prOutput twin (3b-5)

- **No ephemeral scratch resolution** — steps are in the persisted payload (3b-5's diff lived in scratch and had to be resolved at ingest).
- **No dual-write / enrich trap** — `implementationPlan` has a **single** in-loop write; there is no `enrichPrOutputArtifact` twin that creates a v2 head, so the "embed at both sites" trap ([[story-3b-3-availability-reconciliations]], [[story-3b-5-proutput-renderer-reconciliations]]) does **not** apply. Pure read-side parse.
- **No `integration_links` join** — no `prState`/PR link to source (3b-5 added a non-locking projection; not needed here).
- **No decision wiring** — accept/reject/takeover already fire generically for the plan target (3b-4).

### Trust boundary (unchanged, still enforced)

`steps` are **runner-emitted (UNTRUSTED)** plan text; they render through the renderer's existing sanitization (`summary` as escaped plain text, any `detail` markdown via `SafeMarkdownRenderer`). Do not relax sanitization when wiring live data. Never log the step text (reviewer/plan content) — log the **count** only.

### Exact touch points (verified against current source, 2026-06-18)

| File | Line(s) | Relevance |
|---|---|---|
| `application/workflow/WorkflowInspectionService.java` | `getArtifactDetail` `:1046-1184` (impl-plan branch beside `PR_OUTPUT` `:1130-1153`; `ArtifactDetailView` build `:1155-1169`); `ArtifactDetailView` record `:1681-1698` | add+populate `steps`; `body=""` for impl-plan |
| `adapters/rest/ArtifactDetailResponse.java` | record `:20-68`, `from` `:70-85` | add nullable `steps` `@Schema` + map |
| `src/main/resources/openapi/openapi.json` | `ArtifactDetail` schema block | add nullable `steps` array property (regen) |
| `deliveryline-frontend/src/lib/api/schema.d.ts` | `ArtifactDetail` (~`:365-415`) | regenerate |
| `deliveryline-frontend/src/lib/api/queryOptions.ts` | `toArtifactView` impl-plan arm `:155-157` | map live `steps` (remove the bare passthrough) |
| `deliveryline-frontend/src/features/workflows/artifactView.ts` | `ImplementationPlanArtifactView` `:110-126`; `isImplementationPlanStep` `:207-229`; `isArtifactView` impl-plan `:272-283` | unchanged (reference) |
| `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx` | props/steps `:125-132`; body block `:166-173`; steps accordion `:175-227` | empty-body guard only |
| `deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.tsx` | accept/reject/takeover handlers | unchanged (verify fires) |
| `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx` | developer taxonomy `:81-90`; takeover affordance `:143-172` | unchanged (reference) |
| `application/runner/RunnerBroker.java` | impl-plan ingest `:1292-1399`; `artifactPayload` `:2174-2204` | reference (no change — confirms steps are stored inline) |
| `deliveryline-runner-contracts/.../runner-result.v1.schema.json` | `implementationPlanArtifact` `:168-201` | the inline `steps`/`contextReferences` shape |
| `runners/{claude,codex}/lib/runner.mjs` | `implementationPlan` arm `:238-244` | confirms inline `steps`, `contextReferences:[]` |
| test: `WorkflowInspectionServiceArtifactDetailTest.java` | (3b-5 read-projection cases) | add impl-plan steps cases |
| test: `OpenApiSnapshotContractTest.java` | snapshot | re-capture |
| route: `routes/workflows/$workflowRunId/index.tsx` | `:124-197` | the 3b-3 "Open the implementation output →" link that targets this render (reference) |

### Architecture / boundaries

- Backend changes stay in `application` (`WorkflowInspectionService`) + the REST DTO; no `application → adapters` import ([[application-cannot-import-adapters]]); no new `*Controller`. No new `WorkflowInspectionService` ctor dep — the existing `objectMapper` instance field (added in 3b-5) parses the payload.
- Frontend mapper change is in the `.ts` `queryOptions.ts`; the renderer guard is a `.tsx` edit that exports no new function ([[frontend-react-refresh-no-fn-exports]]); respect `exactOptionalPropertyTypes`.
- The OpenAPI regen is the cross-shell, cross-platform ritual ([[openapi-regen-platform-shim]]); commit `openapi.json` + `schema.d.ts` together or the frontend drift gate fails. No runner-contracts touch ([[runner-contracts-schema-stale-in-m2]] not in play).

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying.

- **Framework:** SLF4J + Logback (backend); field-only structured `console.*` (frontend). No `System.out`, no `printStackTrace()`.
- **This story (backend):** reuse the existing `getArtifactDetail` boundary `INFO` lines; add `WARN` "malformed implementationPlan payload (null steps)" at the read fallback. Log the parsed **step count**, never the step text.
- **Required context keys:** `correlationId`, `workflowRunId`, `artifactId`.
- **Forbidden in log output:** step/plan text, raw payload bytes, secrets/tokens, PII, classification-restricted fields — log sizes/counts/refs only.
- **Test contract:** pin the malformed-payload `WARN` with a list-appender/`OutputCaptureExtension`.

### Testing standards

- Backend unit tier = Surefire (no Docker); Testcontainers ITs named `*IT` so Failsafe runs them and the Windows fast tier excludes them ([[springboot-testcontainers-test-must-be-IT]]). OpenAPI regen via the `failsafe:integration-test` write path ([[maven-arglineation-goal-crash]]).
- Regenerate generated files **before** running the frontend `tsc`/tests, or the mapper won't type-check against the new `ArtifactDetail.steps` field.
- Frontend: vitest + MSW; `prettier --write` before pushing ([[prettier-gate-cascades-ci]]); verify lockfile/CI shape on Linux ([[frontend-lockfile-cross-platform]], [[verify-ci-fixes-in-clean-env]]); drive the toolchain via PowerShell ([[rtk-hook-only-matches-bash]]).

### Project Structure Notes

- Net production change: one nullable `steps` field added+populated in `WorkflowInspectionService`/`ArtifactDetailResponse`; the impl-plan read-branch parse + `body=""`; the two regenerated generated files; the `queryOptions.ts` mapper arm; the empty-body renderer guard. No new module, package, Flyway, `DomainErrorCode`, `WorkflowEventType`, runner-contracts schema, config property, REST endpoint, ArchUnit rule, transition-table edge, or `WorkflowInspectionService` ctor dep.

### References

- [Source: docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md:46-75,83-89] — authoritative design (sub-project #3); "Scope" §3 (plan-phase rendering: "render the ordered steps"), §4 (accept/reject/takeover), "Likely story breakdown" Story C, "Takeover may fold into A/B since the container already supports it."
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3b-6] (`:977-992`) — AC-shape, dependencies, sequencing `((3b-1 → 3b-2) and 3b-3 parallel → 3b-4 → 3b-5/3b-6)`; the "reconcile 3.26, do not re-build" + "plan-phase needs no github_pr link" notes.
- [Source: _bmad-output/implementation-artifacts/3b-5-proutput-review-renderer-pr-link-state-badge-and-unified-diff.md] — the prOutput twin; DD2 (typed DTO over body-parse), the read-projection + `body=""` pattern this story mirrors (minus scratch/enrich/dual-write).
- [Source: _bmad-output/implementation-artifacts/3b-4-developer-role-wiring-at-waiting-for-review.md] — the developer-role wiring + the generic accept/reject/takeover machinery this story verifies for a plan target.
- [Source: deliveryline-backend/.../WorkflowInspectionService.java:1046-1184,1681-1698] — `getArtifactDetail`, the 3b-5 `PR_OUTPUT` branch to mirror, `ArtifactDetailView`.
- [Source: deliveryline-backend/.../adapters/rest/ArtifactDetailResponse.java:20-85] — the REST DTO to extend.
- [Source: deliveryline-backend/.../RunnerBroker.java:1292-1399,2174-2204] — impl-plan single-write ingest + `artifactPayload` (inline steps serialized; no enrich).
- [Source: deliveryline-runner-contracts/.../runner-result.v1.schema.json:168-201] — `implementationPlanArtifact` (`steps` `string[]` min 1, `contextReferences` `string[]`); no `contentReference`.
- [Source: runners/claude/lib/runner.mjs:238-244 and runners/codex/lib/runner.mjs] — runner emits inline `steps`, `contextReferences: []`.
- [Source: deliveryline-frontend/src/lib/api/queryOptions.ts:155-157] — the impl-plan mapper arm to replace (the prOutput arm `:158-179` is the worked example).
- [Source: deliveryline-frontend/src/features/workflows/artifactView.ts:110-126,207-229,272-283] — `ImplementationPlanArtifactView` (`steps?` optional), step guard, `isArtifactView` branch (unchanged).
- [Source: deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx:125-227] — the renderer consumed (one empty-body guard).
- [Source: deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.tsx and ApprovalDecisionBar.tsx:81-90,143-172] — the artifact-agnostic decision machinery + developer taxonomy + takeover affordance (unchanged).
- Memory: [[story-3b-5-proutput-renderer-reconciliations]], [[artifactview-variant-field-fanout]], [[artifact-read-dto-must-satisfy-isartifactview]], [[story-3b-3-availability-reconciliations]], [[story-3b-4-developer-role-wiring-reconciliations]], [[workflowdetail-wire-sends-null-not-undefined]], [[frontend-react-refresh-no-fn-exports]], [[openapi-regen-platform-shim]], [[maven-arglineation-goal-crash]], [[springboot-testcontainers-test-must-be-IT]], [[embedded-frontend-at-package-phase]], [[prettier-gate-cascades-ci]], [[verify-ci-fixes-in-clean-env]], [[frontend-lockfile-cross-platform]], [[rtk-hook-only-matches-bash]], [[application-cannot-import-adapters]].

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Backend read-projection unit test: `WorkflowInspectionServiceArtifactDetailTest` 20/20 (incl. 6 net-new impl-plan cases).
- Backend full Surefire: 1116 tests, 0 failures, 0 errors, 13 skipped.
- Backend `OpenApiSnapshotContractTest` (Docker IT, Failsafe): 1/1 byte-match GREEN.
- Backend Spotless: applied + `spotless:check` clean.
- Frontend `tsc -b` clean; `eslint --max-warnings=0` clean; `prettier` clean; `check:api` in-sync.
- Frontend vitest: 1047/1047 (incl. 5 mapper/render cases in `ArtifactReviewPanel.test.tsx`, 1 empty-body case in `ImplementationPlanArtifactRenderer.test.tsx`, decision-bar container 7/7 unchanged).
- Frontend production `npm run build`: SUCCESS.

### Completion Notes List

- **SD-1 → typed DTO (recommended default, no override).** Implemented the read-model approach: one additive nullable `List<String> steps` on `ArtifactDetailView` + `ArtifactDetailResponse`, populated only for `implementationPlan`. Frontend stays off an untyped `JSON.parse(body)` ([[artifact-read-dto-must-satisfy-isartifactview]]); `tsc`/`check:api` police drift.
- **Backend parse** (`getArtifactDetail`): new `else if (IMPLEMENTATION_PLAN)` branch beside the 3b-5 `PR_OUTPUT` branch reuses the existing `objectMapper` field (NO new ctor dep). A new `parseSteps(JsonNode)` helper reads the `steps` array (each non-blank, textual element), returns `null` for an absent/empty/all-blank array so the DTO stays null-clean. `responseBody=""` for an impl-plan; malformed JSON → `steps=null` + body restored + `WARN` (no-500). The five 3b-5 prOutput fields stay null for impl-plan and `steps` stays null for spec/prOutput (never co-populated) — AC3.
- **Contract** (AC4): added the `steps` `{"type":["array","null"],"items":{"type":"string"}}` property to `ArtifactDetail` in `openapi.json` (springdoc-write re-captured it byte-identical to the manual edit, 10-line insertion, nothing else drifted); regenerated `schema.d.ts` → `steps?: string[] | null`. Additive-nullable → existing 3a-9/3b-5 consumers unchanged.
- **Frontend mapper** (AC5): the `implementationPlan` arm maps `dto.steps` → `[{summary}]` (the 3.26 R2 `string[]→structured` mapping; `detail`/`estimatedComplexity` absent), built conditionally (filter blank strings; assign only when non-empty) so the OPTIONAL `steps?` slot is respected under `exactOptionalPropertyTypes` → **no `isArtifactView`/renderer fan-out** ([[artifactview-variant-field-fanout]] avoided). `contextReferences` NOT mapped (runner emits `[]` — dormant/out of scope).
- **Renderer guard** (AC6): the `MetadataChrome`+`SafeMarkdownRenderer` body block now renders only when `artifact.body.trim() !== ''`, so a live `body=""` plan shows its steps without an empty "Generated content" prose shell. No new exported function ([[frontend-react-refresh-no-fn-exports]] respected — it's a `.tsx` conditional render).
- **Decisions** (AC7): NO wiring change — verified the existing `ImplementationReviewDecisionBarContainer` (3b-4) + generic hooks/taxonomy/takeover affordance fire artifact-agnostically for the plan target; the accept PR-link gate is `prOutput`-only so a plan accept needs no `github_pr` link. Container tests stay green (7/7).
- **Simpler than 3b-5** as predicted: a single payload-side parse — no ephemeral-scratch resolution, no enrich/dual-write trap (impl-plan is a single in-loop write), no `integration_links` join.
- **Live verify is human-in-the-loop / operator-flagged** (AC9): the live `WaitingForReview` plan-render + a real plan-phase decision transition + the `mvn package` SPA re-embed were not run headlessly. The automated mapper + render + contract pins stand in for the wiring proof.
- **No new production surface beyond scope** (AC10): no new `DomainErrorCode`, `WorkflowEventType`, Flyway, runner-contracts schema, config property, REST endpoint, ArchUnit rule, transition-table edge, or `WorkflowInspectionService` ctor dep.

### File List

**Backend (modified)**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — `ArtifactDetailView` + `steps`; `getArtifactDetail` impl-plan branch + `parseSteps` helper.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ArtifactDetailResponse.java` — nullable `steps` record component + `@Schema` + `from(view)` map.
- `deliveryline-backend/src/main/resources/openapi/openapi.json` — `ArtifactDetail.steps` nullable array property (regenerated).
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceArtifactDetailTest.java` — +6 impl-plan read cases; spec/prOutput null-steps assertions.

**Frontend (modified)**
- `deliveryline-frontend/src/lib/api/schema.d.ts` — regenerated `ArtifactDetail.steps?: string[] | null`.
- `deliveryline-frontend/src/lib/api/queryOptions.ts` — `toArtifactView` `implementationPlan` arm maps the wire `steps`.
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx` — empty-body guard.
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx` — +5 mapper/render cases.
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.test.tsx` — +1 empty-body guard case.

**Tracking (modified)**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `3b-6` ready-for-dev → in-progress → review.

### Change Log

| Date | Change |
|---|---|
| 2026-06-19 | Story 3b-6 implemented: typed nullable `steps` read-model field on `ArtifactDetail` (parsed at read for `implementationPlan`, `body=""`); frontend mapper maps `steps`→`[{summary}]`; empty-body renderer guard; plan-phase accept/reject/takeover verified (already wired). All automated gates green; live verify operator-flagged. Status → review. |

## Review Findings

_bmad-code-review 2026-06-19 — 3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) over the uncommitted diff vs this spec. 1 decision-needed, 1 patch, 1 deferred, 10 dismissed as noise (Spotless false-positive, sanitized-degradation-by-design, generated-type nits, sibling-consistent IOException catch, truncation cosmetics)._

- [x] [Review][Patch] Preserve the classification badge on blank-body plans — The AC6 empty-body guard (`ImplementationPlanArtifactRenderer.tsx:171`) skips the **entire** `MetadataChrome` (title + classification + body) when `body` is blank, which is the common live path — so a live plan loses its `classification` badge (e.g. `shareable-redacted`) and `title`. Resolved decision (2026-06-19, Alex): surface the classification (and title) outside `MetadataChrome` so they survive a blank body, while still suppressing the empty "Generated content" prose shell. [ImplementationPlanArtifactRenderer.tsx:166-179]
- [x] [Review][Patch] Non-array `dto.steps` throws in `toArtifactView` before the `isArtifactView` guard — `queryOptions.ts` calls `wireSteps.filter(...)` after only a `!= null` check; a non-array wire value (contract drift) throws `TypeError` inside the queryFn → panel renders `error` instead of degrading to the empty-steps state. Backend `parseSteps` can only emit `List<String>|null` so this is defensive-only (unreachable without a backend contract violation), and the sibling prOutput arm has no array guard either — but an `Array.isArray(wireSteps)` guard matches the wire contract and costs nothing. [queryOptions.ts:165-171]
- [x] [Review][Defer] Live `WaitingForReview` plan-render + plan-phase decision transition + `mvn package` SPA re-embed not run headlessly — deferred, operator/human-in-the-loop verification (AC9, already disclosed in the story). [n/a]
