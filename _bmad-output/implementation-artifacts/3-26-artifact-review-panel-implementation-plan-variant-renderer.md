# Story 3.26: Artifact Review Panel — Implementation-Plan Variant Renderer

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Developer reviewing a generated implementation plan,
I want the `ImplementationPlanArtifactRenderer` (stub from story 2.17 AC3) fully implemented as a variant of the generalized Artifact Review Panel,
so that the plan's structured steps + context references + linked spec are rendered with the same primacy + sanitization + accessibility commitments as the spec variant — activating the party-mode-finding-#3 generalization paid for in E2.

## Acceptance Criteria

1. **Real renderer replaces the stub.** `src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx` is replaced (the "renderer coming in Epic 3" placeholder is removed) with a real renderer for the `implementationPlan` artifact variant per the runner-contracts schema-v1 sub-schema (story 1.6 AC4 — `steps` array + `contextReferences`, see `runner-result.v1.schema.json#/$defs/implementationPlanArtifact`).
2. **Anatomy.** The renderer displays: artifact title (e.g. "Implementation Plan — DEL-9002 v1") via `MetadataChrome`, an artifact-type `Badge` with **distinct visual treatment from the spec variant** (different `variant`/token, T5), a revision indicator (`v{version}` + a keyboard-focusable, currently-disabled revision-history placeholder anchor — OQ-4 pattern), a **structured-steps section** (numbered ordered list; each step expandable to reveal its detail + estimated complexity when provided), and a **context-references section** (links to the approved spec artifact + linked GitHub repo + branch reference per story 3.9 AC2 — internal refs navigate within DeliveryLine, external refs open GitHub in a new tab).
3. **Sanitization (story 2.24).** All untrusted step text + detail + context-reference labels render through `SafeMarkdownRenderer` / React text-escaping (never `dangerouslySetInnerHTML`); the XSS fixtures from story 2.24 AC7 are exercised against this renderer in tests (epic line 711 — implementation-plan step text added to the adversarial set). Every external href is run through `validateUrlScheme` before an `<a>` is emitted.
4. **Discriminated-union dispatch (story 2.17 AC1).** When the panel resolves an artifact with `artifactType === 'implementationPlan'`, `ArtifactReviewPanel.renderVariant` dispatches to this renderer (already wired) and passes the action-derived props.
5. **States (story 2.17 AC4).** Loading / empty-not-yet-generated / stale / superseded / incomplete / error all render correctly when the resolved artifact is the implementation-plan variant (panel-owned state chrome + the variant body).
6. **Allowed-actions integration (story 2.17 AC9 / OQ-2).** Variant-specific controls (e.g. the reserved Compare entry) are enabled/disabled from the backend-reported allowed actions — derived by the **container** from `useAllowedActions(workflowRunId)` and passed to the presentational renderer as props (NO frontend permission inference; the hook stays a disabled stub, T3).
7. **Primacy (story 2.17 AC10 + story 2.7 AC10).** The renderer respects the central-pane visual anchor — full width within `<main>`, no min-width below the 36rem floor, no auto-collapse.
8. **Keyboard accessibility (story 2.17 AC8).** Numbered steps are focusable + navigable with Tab + arrow keys; expanding a step is keyboard-activatable (Enter/Space). Achieved by reusing the Radix-backed `Accordion` primitive.
9. **Fixture (story 1.23 AC4 + AC7).** At least one `implementationPlan` `ArtifactView` fixture exists with realistic step content (mirroring the `happy-path-success.json` / `spec-rejection-and-resubmit.json` terminal states), plus an XSS-bearing fixture for the sanitization test.
10. **Component test coverage.** Tests cover: structured steps render correctly (numbered ordered list), expanding/collapsing a step works via mouse + keyboard, context-references render with correct anchors (internal vs external href), sanitization rejects scriptable payloads in step text, each artifact-state variant renders for the impl-plan type, allowed-actions integration enables/disables controls, axe-core a11y zero violations.

## Tasks / Subtasks

- [x] **Task 1 — Extend the frontend-owned `ImplementationPlanArtifactView` contract** (AC: 1, 2)
  - [x] In `src/features/workflows/artifactView.ts`, extend `ImplementationPlanArtifactView` (currently base-only) with the impl-plan read-model fields: `steps` and `contextReferences` (made OPTIONAL — R3 reconciliation below). Mirror the spec variant's optional dormant flags (`stale?`, `superseded?`, `truncated?`) so AC5 banner states are reachable for this variant too (Decision D3).
  - [x] Add the two helper interfaces: `ImplementationPlanStep { summary: string; detail?: string; estimatedComplexity?: string }` and `ImplementationPlanContextRef { kind: 'spec' | 'repository' | 'branch' | 'other'; label: string; href?: string; internal: boolean }`. Documented that `summary`/`detail`/`label` are UNTRUSTED runner-derived text and `href` is validated before render. (Reconciliation R2 — schema v1 carries `steps: string[]` + `contextReferences: string[]`; the frontend read model enriches them. A plain schema-step string maps to `{ summary }`.)
  - [x] Extend `isArtifactView` so the `implementationPlan` branch validates the new fields **when present** (steps array of objects with a string `summary`; contextReferences array of objects with string `label` + boolean `internal` + valid `kind`; optional fields type-checked) instead of returning `true` unconditionally. **R3 reconciliation:** the non-empty-steps requirement in the original wording was relaxed to optional-with-when-present validation because story 3a-9 (landed after this story was authored) made `useArtifact` LIVE and its `ArtifactDetail` wire DTO carries NO `steps`/`contextReferences` — a required/non-empty guard would force `toArtifactView` to either fabricate steps or render every live impl-plan artifact as `error` (memory `artifact-read-dto-must-satisfy-isartifactview`). Optional fields keep the live body-only mapping valid while the fixtures drive the rich rendering, matching the Dev-Notes "additive + optional" intent.
- [x] **Task 2 — Implement the real `ImplementationPlanArtifactRenderer`** (AC: 1, 2, 3, 7, 8)
  - [x] Replaced the stub body. Composes `MetadataChrome` (title/version/classification) wrapping `SafeMarkdownRenderer source={artifact.body}` (T1/T2 — barrel imports only). Kept `data-testid="implementation-plan-artifact-renderer"` + `data-artifact-type="implementationPlan"`.
  - [x] Type badge via `Badge` from `@/components/ui/badge` with `variant="outline"` — DISTINCT from the spec's `secondary` (T5). Label from `artifactTypeLabel(artifact.artifactType)` → `implementation-plan`.
  - [x] Revision indicator: `v{version}` + a disabled, keyboard-focusable revision-history placeholder anchor (copied the `artifact-revision-history-anchor` pattern from `SpecArtifactRenderer`, OQ-4).
  - [x] Structured-steps section: a numbered list rendered with the `Accordion` primitive (`type="multiple"`), `role="list"`/`role="listitem"` for ordered semantics. Each `AccordionItem` trigger shows `Step {n}` + the step `summary` as React-escaped plain text (T-STEPHTML); `AccordionContent` renders the step `detail` through `SafeMarkdownRenderer` + the `estimatedComplexity` as a labeled chip. Section has `aria-label="Implementation steps"` + `data-testid="artifact-plan-steps"`.
  - [x] Context-references section: a labeled list. Internal refs render a focusable placeholder button (OQ-4); external refs render an `<a>` ONLY when `validateUrlScheme(ref.href).ok`, with `target="_blank" rel="noopener noreferrer"`, otherwise plain escaped text. Labels React-escaped.
  - [x] Primacy (AC7): root is `w-full`, no min-width, no auto-collapse.
- [x] **Task 3 — Wire allowed-actions + states through the panel** (AC: 4, 5, 6)
  - [x] In `ArtifactReviewPanel.tsx`, extended `renderVariant` so the `implementationPlan` case receives `compareEnabled` the same way `spec` does; added a reserved disabled Compare control to the renderer for the AC6 enable/disable surface. The container already computes `compareEnabled`; passed through.
  - [x] In `artifactView.ts` `resolveArtifactPanelState`, extended the dormant-flag check (`superseded`/`stale`/`truncated`) to also apply when `artifactType === 'implementationPlan'` (D3). Loading/empty/error are already variant-agnostic (verified).
- [x] **Task 4 — Fixtures** (AC: 9, 3)
  - [x] In `src/test/fixtures/artifact/artifactViewFixtures.ts`, enriched `implementationPlanArtifactView` with realistic `steps` (3; first carries `detail` + `estimatedComplexity`) and `contextReferences` (internal spec ref + external repo ref + branch ref per 3.9), aligned to `art_plan_fix_rej_001`.
  - [x] Added `implementationPlanArtifactViewXss` — step `summary`/`detail` carrying `<script>` + `<img onerror>`, plus a `javascript:` context-ref href (mirror `specArtifactViewXss`) for the AC3 sanitization test.
- [x] **Task 5 — Tests** (AC: 10, 3, 8)
  - [x] Created `ImplementationPlanArtifactRenderer.test.tsx` (fixture-driven, NOT snapshot): anatomy slots (type badge distinct from spec, revision, body via `safe-markdown` marker, steps + context-refs sections); numbered steps in order; expand/collapse via mouse (`fireEvent.click`) AND keyboard (`userEvent` Enter/Space toggle + ArrowDown focus move on accordion triggers); internal-vs-external context-ref anchors (`href`/`target`); XSS fixture asserts no active `<script>`/`<iframe>`, `window.__xss_executed` unset, and `javascript:` ref falls back to plain text; Compare reserved-disabled by default + enabled when `compareEnabled`; body-only (R3) graceful degradation; axe-core zero violations for default + empty-body + XSS variants.
  - [x] T-STUBTEST — removed the `ImplementationPlanArtifactRenderer (stub)` + its a11y describe blocks from `StubArtifactRenderers.test.tsx` (kept the `PrOutputArtifactRenderer` blocks; 3.27 pending).
  - [x] Updated `artifactView` unit tests for the new `isArtifactView` impl-plan branch (R3 when-present validation) + the `resolveArtifactPanelState` impl-plan dormant-flag behavior. `ArtifactReviewPanel` tests already drive impl-plan from the (now-enriched) fixture; updated one stale test description.
- [x] **Task 6 — Gates** (run via PowerShell — memory `rtk-hook-only-matches-bash`)
  - [x] `tsc -b` ✅, `eslint . --max-warnings=0` (incl. `no-unsanitized-html`) ✅, `npm run lint:rules-test` (9/9) ✅, `vitest run` (81 files / 893 tests) ✅, `prettier --write` ✅. No backend change, no new npm dep, no lockfile change → no WSL2/Linux smoke needed.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Frontend-adapted. The renderer is **presentational and emits no logs** (mirrors `SpecArtifactRenderer`). Task 3's state wiring reused the existing pure `resolveArtifactPanelState` + variant-agnostic container — it introduced NO new observable container branch, so no new log line was needed (the existing `artifactPanel.loadError`/`retry`/`stale` console-spy-pinned logs are untouched). No step text / refs / body logged (T8).

## Dev Notes

### The central reconciliation (READ THIS FIRST)

There is **NO backend `ArtifactView` type and NO artifact-read endpoint.** `schema.d.ts` carries only `LatestArtifact { artifactType?, status?, version? }` (a summary — no body, no id, no steps) inside `WorkflowDetail.latestArtifacts[]`, and `useArtifact` / `useAllowedActions` are **disabled stubs**. Therefore:
- `ArtifactView` (incl. `ImplementationPlanArtifactView`) is a **FRONTEND-OWNED** type modeling the epic's intended read model. You are extending that type — there is no backend contract to regenerate.
- The renderer is **PRESENTATIONAL** — it takes a resolved `ImplementationPlanArtifactView` and renders. It NEVER fetches and NEVER calls hooks. The `ArtifactReviewPanelContainer` owns the data seam + logging; the `ArtifactReviewPanel` owns dispatch + state. This split is non-negotiable (story 2.17 OQ-1). Mirror `SpecArtifactRenderer` exactly.
- Every test drives the renderer directly with constructed fixtures (router/query-free), exactly like `SpecArtifactRenderer.test.tsx`.

### What already exists — REUSE, do not rebuild

- **`SpecArtifactRenderer.tsx`** — your template. Copy its structure: type `Badge`, revision indicator + disabled history anchor, `MetadataChrome`+`SafeMarkdownRenderer` body, inline metadata region, region entry-point anchors, reserved disabled Compare control. The impl-plan renderer is the same skeleton with a steps section + context-refs section swapped in for the spec-only change-summary/section-anchor slots.
- **`@/lib/sanitization` barrel** — `MetadataChrome`, `SafeMarkdownRenderer`, `validateUrlScheme`. Import ONLY from the barrel (T1).
- **`@/components/ui/accordion`** — Radix-backed; `Accordion`/`AccordionItem`/`AccordionTrigger`/`AccordionContent`. Gives Tab-to-accordion, arrow-key trigger navigation, Enter/Space toggle for free → AC8 keyboard requirement is met by reuse, not hand-rolled key handlers.
- **`@/components/ui/badge`** — `variant` ∈ `default | secondary | destructive | outline`. Spec uses `secondary`; pick a different one for distinct treatment (T5 / AC2).
- **`artifactTypeLabel`** in `artifactView.ts` — already maps `implementationPlan` → `implementation-plan`.
- **`expectNoA11yViolations`** from `@/test/a11y/axe`; XSS fixture pattern from `specArtifactViewXss`.
- **`ArtifactReviewPanel.renderVariant`** — dispatch is ALREADY wired to `<ImplementationPlanArtifactRenderer artifact={artifact} />` (AC4). You only extend the props it passes (Task 3).

### Schema-v1 sub-schema (the source of the read-model shape)

`deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json#/$defs/implementationPlanArtifact`:
- `steps`: `array`, `minItems: 1`, items `string` (minLength 1).
- `contextReferences`: `array`, items `string` (minLength 1).

The epic (AC2) asks for richer rendering than the raw schema (step detail + estimated complexity; typed context refs with hrefs). Since the read model is frontend-owned and fixture-driven (no live endpoint), model the enriched shape in TS (R2 above) — a future artifact-read story maps the schema strings into it. Keep the enrichment **additive + optional** so a bare schema-string step (`{ summary }`) still renders.

### Traps (do NOT step on these)

- **T-STEPHTML — `SafeMarkdownRenderer` cannot nest in a `<button>`.** It emits a block-level `<div data-component="safe-markdown">`; placing it inside the `AccordionTrigger` (a button) is invalid HTML and will trip a11y/axe. Render the step **summary** in the trigger as React-escaped plain text (auto-safe — no markdown pipeline needed for a plain string), and render the markdown **detail** via `SafeMarkdownRenderer` inside `AccordionContent`. AC3's "all step text through the safe path" is satisfied: the detail goes through the sanitizer and React's text-escaping neutralizes HTML in the summary string.
- **T-STUBTEST — the stub test breaks.** `StubArtifactRenderers.test.tsx` asserts the impl-plan "coming in Epic 3" notice. Replacing the stub will fail it. Remove/migrate the impl-plan blocks; keep the PR-output blocks (3.27 still pending).
- **T-GUARD — `isArtifactView` + fixture co-evolution.** Making `steps`/`contextReferences` part of the type means the runtime guard AND every fixture/test constructing an `ImplementationPlanArtifactView` must include them. Grep `ImplementationPlanArtifactView` — today only the fixtures + stub test reference it.
- **T1 — barrel imports only.** Never reach into individual sanitization files; `no-unsanitized-html` + `--max-warnings=0` fail on direct-file paths / `dangerouslySetInnerHTML`.
- **T2 — never roll your own renderer.** Compose `MetadataChrome` + `SafeMarkdownRenderer` (2.24 AC10).
- **T3 — `useArtifact`/`useAllowedActions` stay disabled.** Do NOT wire live queryFns. The container reads the disabled hooks; the renderer takes props.
- **T4 — dormant states from fixtures only.** `stale`/`superseded`/`incomplete` + full step content are tested via constructed fixtures, never fabricated from `WorkflowDetail.latestArtifacts`.
- **T5 — type badge ≠ state badge, and ≠ spec's badge.** Use `Badge`, a variant distinct from spec's `secondary`.
- **T8 — field-only logs.** No step text / refs / body / business content in any log line.
- **T10 — non-color-alone + prettier.** Every status color carries an icon+label; run `prettier --write` before finishing.

### Decisions (resolved — proceed unless told otherwise)

- **D1 — presentational/container split preserved.** Renderer is pure; container derives action flags. (story 2.17 OQ-1)
- **D2 — allowed-actions via prop, hook stays disabled.** AC6 is satisfied by the container deriving `compareEnabled` (and any future impl-plan action flag) from `useAllowedActions` and passing it down; the renderer never infers permissions. (story 2.17 OQ-2)
- **D3 — extend dormant flags to impl-plan.** Add `stale?`/`superseded?`/`truncated?` to `ImplementationPlanArtifactView` and the `resolveArtifactPanelState` check so AC5's banner states are genuinely reachable for this variant (cheap, makes AC5 literally true; flags remain dormant — no live source).
- **D4 — Accordion for steps.** Reuse the Radix accordion (`type="multiple"`) rather than hand-rolled disclosure — frees the AC8 keyboard semantics and keeps axe clean. Number the steps in the trigger text + an `<ol>`/`role` structure for ordered semantics.
- **D5 — context-ref internal targets are OQ-4 placeholders.** In-app navigation targets (approved-spec artifact route) are not wired live in E3 here; render keyboard-focusable placeholder controls (like the clarification/approval anchors). External GitHub refs DO get real `<a>` hrefs (validated). Live in-app deep-links are a follow-up.

### Logging Requirements (project-wide standard, frontend-adapted)

Frontend story — the backend SLF4J/MDC standard does not apply literally. The artifact panel's structured logs (`artifactPanel.loadError`/`retry`/`stale`, field-only `{ event, code, transport }`) already live in `ArtifactReviewPanelContainer` and are pinned by console-spy tests. The renderer adds none. Only if Task 3's state wiring introduces a new observable container branch do you add a field-only `console.warn`/`info` + a spy test. Never log step text, refs, artifact body, or business content (T8).

### Project Structure Notes

- Component → `src/features/workflows/components/` (existing location of `ImplementationPlanArtifactRenderer.tsx`). Pure helpers/types live in the sibling `artifactView.ts` (`.ts`, not `.tsx`) per `frontend-react-refresh-no-fn-exports` (a `.tsx` exporting a non-component function fails the eslint react-refresh gate).
- No backend changes. No new npm dependency (sanitization stack + `lucide-react` + Radix accordion already present). No `schema.d.ts` change (the read model is frontend-owned).
- Gates via PowerShell / `rtk proxy` (RTK corrupts only the Bash tool — memory `rtk-hook-only-matches-bash`). No lockfile change expected → no WSL2/Linux smoke (memory `wsl-linux-ci-reproduction`).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.26 (lines 521–538)] — AC source; line 711 (XSS fixtures for impl-plan step text), line 709 (Vitest coverage), line 728 (walkthrough screenshots).
- [Source: deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx] — the template renderer (badge, revision/history anchor, MetadataChrome body, region anchors, reserved disabled Compare).
- [Source: deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.test.tsx] — fixture-driven (no-snapshot) test convention, axe + keyboard patterns to mirror.
- [Source: deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx] — `renderVariant` dispatch (AC4), state chrome (AC5), container split (D1).
- [Source: deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx] — the stub to replace.
- [Source: deliveryline-frontend/src/features/workflows/components/StubArtifactRenderers.test.tsx] — T-STUBTEST: impl-plan blocks to remove, PR-output blocks to keep.
- [Source: deliveryline-frontend/src/features/workflows/artifactView.ts] — `ImplementationPlanArtifactView` (extend), `isArtifactView` (extend, T-GUARD), `resolveArtifactPanelState` (D3), `artifactTypeLabel`.
- [Source: deliveryline-frontend/src/test/fixtures/artifact/artifactViewFixtures.ts] — `implementationPlanArtifactView` (enrich), `specArtifactViewXss` (XSS fixture pattern, AC3/AC9).
- [Source: deliveryline-frontend/src/lib/sanitization/index.ts + MetadataChrome.tsx + SafeMarkdownRenderer.tsx + policy.ts] — barrel surface, `{title,version,classification,subtitle?,children}`, `source`, `validateUrlScheme(href) → {ok,href}|{ok:false,reason}`.
- [Source: deliveryline-frontend/src/components/ui/accordion.tsx] — Radix accordion API (D4/AC8).
- [Source: deliveryline-frontend/src/components/ui/badge.tsx] — `variant` set (T5/AC2 distinct treatment).
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json#/$defs/implementationPlanArtifact] — `steps` (string[], minItems 1) + `contextReferences` (string[]) — the schema-v1 sub-schema (R2).
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.json (lines 200–231)] — `art_plan_happy_001` / `implementationPlan` v1 terminal state (AC9 fixture alignment).
- [Source: _bmad-output/implementation-artifacts/2-17-artifact-review-panel-generalized-composite-spec-variant.md] — the generalization this builds on: traps T1–T10, OQ-1/OQ-2/OQ-4, presentational/container split, fixture-source decision.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- `tsc -b` — clean (after R3 reconciliation; `exactOptionalPropertyTypes` forced omitting keys rather than `: undefined` in the body-only test fixture).
- `eslint . --max-warnings=0` — clean (fixed strict-boolean-expressions on a nullable `href` check + removed an unused `declare global` directive in the test).
- `npm run lint:rules-test` — 9/9 custom rules pass.
- `vitest run` — 81 files / 893 tests pass (incl. 19 new in `ImplementationPlanArtifactRenderer.test.tsx`). The `scrollTo`/`InvalidRouteParamError` console lines are pre-existing negative-path test noise, not failures.

### Completion Notes List

- **R3 reconciliation (headline).** Story Dev Notes assumed "NO artifact-read endpoint" and Task 1 specified `steps`/`contextReferences` as REQUIRED with a non-empty guard. Story 3a-9 landed afterward, making `useArtifact` LIVE with a `toArtifactView` mapper whose `ArtifactDetail` wire DTO carries NO structured steps. Required/non-empty would force `toArtifactView` to fabricate steps or render every live impl-plan artifact as `error` (memory `artifact-read-dto-must-satisfy-isartifactview`). **Resolution:** made `steps`/`contextReferences` OPTIONAL with when-present validation in `isArtifactView`; the renderer defaults them to `[]` and shows graceful empty-state notes. Live body-only impl-plan artifacts stay valid + renderable; fixtures drive the full structured rendering. `toArtifactView` needed no change.
- **AC1** — real renderer replaces the stub; the "coming in Epic 3" placeholder is gone.
- **AC2** — anatomy: `MetadataChrome` title, `outline` type badge (distinct from spec's `secondary`, T5), `v{version}` + disabled history anchor, Accordion steps section (numbered, expandable, detail + complexity), context-refs section (internal placeholder + external validated anchors).
- **AC3** — step summary via React text-escaping; step detail + body via `SafeMarkdownRenderer`; every external href through `validateUrlScheme`; no `dangerouslySetInnerHTML`. XSS fixture exercised — no active `<script>`/`<iframe>`, `window.__xss_executed` stays unset, `javascript:` ref degrades to plain text.
- **AC4** — dispatch was already wired; renderer now receives the action-derived prop.
- **AC5** — D3 extends the dormant-flag check to impl-plan, so stale/superseded/incomplete panel banners are reachable for this variant (fixture-driven, T4).
- **AC6** — reserved disabled Compare control, enabled purely from the container-supplied `compareEnabled` prop (no frontend permission inference, D2).
- **AC7** — `w-full` root, no min-width, no auto-collapse.
- **AC8** — keyboard accessibility comes from the Radix Accordion (Tab in, ArrowDown between triggers, Enter/Space toggle) — verified with `userEvent` (D4).
- **AC9/AC10** — enriched `implementationPlanArtifactView` + new XSS fixture; 19 component tests incl. axe-core zero-violations across default / empty-body / XSS variants.

### File List

- `deliveryline-frontend/src/features/workflows/artifactView.ts` (modified — `ImplementationPlanStep`/`ImplementationPlanContextRef` types, extended `ImplementationPlanArtifactView`, `isArtifactView` impl-plan branch + shared dormant-flag helper, `resolveArtifactPanelState` D3)
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx` (modified — stub replaced with the real variant renderer)
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx` (modified — `renderVariant` passes `compareEnabled` to the impl-plan renderer)
- `deliveryline-frontend/src/test/fixtures/artifact/artifactViewFixtures.ts` (modified — enriched `implementationPlanArtifactView` + new `implementationPlanArtifactViewXss`)
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.test.tsx` (new — fixture-driven component tests)
- `deliveryline-frontend/src/features/workflows/components/StubArtifactRenderers.test.tsx` (modified — removed impl-plan stub blocks, kept PR-output)
- `deliveryline-frontend/src/features/workflows/artifactView.test.ts` (modified — impl-plan guard + dormant-state tests)
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx` (modified — one stale test description)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — 3-26 status)

### Review Findings

bmad-code-review (2026-06-14) — 3 parallel layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Acceptance Auditor: AC1–AC10 all PASS, traps T-STEPHTML/T-STUBTEST/T1/T5 respected, R3 optional-fields reconciliation consistent across type/guard/renderer. 0 decision-needed, 0 patch, 3 defer, 8 dismissed (incl. a Blind Hunter false-positive that the XSS test was absent — it is present as the appended new test file and exercises the XSS fixture).

- [x] [Review][Defer] Placeholder controls are focusable no-op affordances; validate the internal href when D5 wires live deep-links [ImplementationPlanArtifactRenderer.tsx:95, :154] — deferred, forward-facing (D5/OQ-4); internal refs emit NO href today so safe now, and the pattern mirrors SpecArtifactRenderer (not introduced here).
- [x] [Review][Defer] External context-ref with a scheme-less/relative href renders as a `target="_blank"` "external" link because `validateUrlScheme` treats relative input as same-origin [ImplementationPlanArtifactRenderer.tsx:80, :103] — deferred, latent (no live contextReferences source yet; fixtures use absolute repo/branch URLs). Add an absolute-URL expectation when the live artifact-read mapping for contextReferences lands.
- [x] [Review][Defer] Empty/whitespace-only step `summary` / ref `label` / `estimatedComplexity` render empty controls (`!== undefined` + `typeof === 'string'` admit `''`) [ImplementationPlanArtifactRenderer.tsx:198, :202; artifactView.ts isImplementationPlanStep/isImplementationPlanContextRef] — deferred, robustness nit; harden if/when a live wire source feeds these strings.

## Change Log

| Date       | Version | Description                                                                 | Author |
| ---------- | ------- | --------------------------------------------------------------------------- | ------ |
| 2026-06-14 | 0.1     | Implemented the implementation-plan variant renderer (AC1–AC10); R3 optional-fields reconciliation with the live 3a-9 artifact-read path; status → review | Amelia (dev-story) |
