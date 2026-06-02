# Story 2.17: Artifact Review Panel — Generalized Composite (Spec Variant)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager reviewing a specification (and later, a developer reviewing implementation-plan + PR/output artifacts in Epic 3),
I want an `ArtifactReviewPanel` composite designed with **artifact-type polymorphism from day one** — currently rendering the spec variant, but with the variant-selection contract, allowed-actions integration, and section-anchor infrastructure already generalized,
so that Epic 3 adds `implementationPlan` + `prOutput` variants without reshaping infrastructure (party-mode finding #3) and the panel preserves artifact primacy as the visual anchor of the review desk (UX-DR10).

## Acceptance Criteria

> The ACs below are the epics.md Story 2.17 criteria, **reconciled against the live frontend contract** (`src/lib/api/schema.d.ts`, the disabled `useArtifact`/`useAllowedActions` stubs, and the story-2.24 sanitization primitives). Where the epic prose assumed a backend shape that does not exist yet, the reconciliation is called out inline and detailed in **Dev Notes → The central reconciliation**. **This is a presentational composite story** (like 2.15/2.16): it is built to the epic anatomy, prop-driven, and fixture-tested — it does NOT ship the artifact-read data layer (that is a later story; `useArtifact`'s query key is reserved but its endpoint does not exist). The live contract is authoritative; epic prose predates it.

1. **`ArtifactReviewPanel` + frontend-owned `ArtifactView` discriminated union + dispatch.** `src/features/workflows/components/ArtifactReviewPanel.tsx` is generalized: it accepts a resolved `ArtifactView` discriminated-union prop whose discriminator is `artifactType` (`'spec' | 'implementationPlan' | 'prOutput'`, the runner-contracts schema-v1 values per story 1.6 AC4) and dispatches to a per-variant renderer. **Reconciliation:** the backend exposes **no `ArtifactView` type and no artifact-read endpoint** — `schema.d.ts` carries only `LatestArtifact` `{ artifactType?, status?, version? }` inside `WorkflowDetail.latestArtifacts[]` (summary, no body, no id), and `src/features/workflows/hooks/useArtifact.ts` is a **disabled stub** (`enabled: false`, throws if forced). Therefore `ArtifactView` is a **frontend-owned type** defined here (new `src/features/workflows/artifactView.ts`) modeling the epic's intended shape; the future artifact-read story populates it. The panel is **presentational** (takes a resolved `ArtifactView`) — see AC4/Dev Notes for how the thin container + route mount behave today.

2. **Spec variant (E2 scope) — `SpecArtifactRenderer`.** `src/features/workflows/components/SpecArtifactRenderer.tsx` is implemented and renders the spec anatomy by **composing the story-2.24 sanitization primitives imported from the barrel `@/lib/sanitization`** (NEVER a hand-rolled renderer — the `local-rules/no-unsanitized-html` rule forbids it): an outer `<MetadataChrome title version classification subtitle?>` wrapper whose `children` slot holds a `<SafeMarkdownRenderer source={body} />`. Within/around that it renders: artifact title (e.g. `"Specification — LIN-123 v3"`, supplied to `MetadataChrome.title`), an **artifact-type badge** (a `<Badge>` from `src/components/ui/badge.tsx` — NOT `WorkflowStateBadge`, which is for workflow *states*), a current-revision indicator (`v3` with an anchor placeholder to revision history — revision-history view is deferred), the markdown body via `SafeMarkdownRenderer` (untrusted runner output — sanitization is the renderer's job, AC7), an inline metadata region (created-at, classification badge, checksum short-form) rendered as **trusted typed React props** (never through markdown — `MetadataChrome` enforces this), an optional **change-summary slot** (rendered via `<SafeDiffRenderer before after />` only when `changeSummary` is non-null, otherwise hidden), **section anchors derived from the markdown headings** (see AC8 reconciliation), an anchor entry-point into the Clarification Region (story 2.18 — rendered as a keyboard-focusable placeholder anchor, no target wired in E2), an anchor entry-point into the Approval/Decision Bar (story 2.19 — same placeholder treatment), and a Compare-Mode entry control (Epic 4 — reserved **disabled** control with an "Available in next release" tooltip, see AC9).

3. **`implementationPlan` + `prOutput` variants (Epic 3 scope) — stub renderers.** `ImplementationPlanArtifactRenderer.tsx` and `PrOutputArtifactRenderer.tsx` are scaffolded as stub components that render a placeholder "Renderer coming in Epic 3" — but they **still compose `<MetadataChrome>` + `<SafeMarkdownRenderer>`** (per story 2.24 AC10: variant renderers MUST consume these primitives, never roll their own), so Epic 3 only fills in variant-specific anatomy. The discriminated-union dispatch in `ArtifactReviewPanel` is **fully wired in E2** — `spec → SpecArtifactRenderer`, `implementationPlan → ImplementationPlanArtifactRenderer`, `prOutput → PrOutputArtifactRenderer` — and an unknown discriminant value falls back to a safe "unsupported artifact type" render (mirrors the route's `UnrenderableArtifactState` defensiveness; never crashes).

4. **States (UX-DR10).** The panel renders exactly one of: `default`, `loading` (Skeleton rows matching layout — `Skeleton` from `src/components/ui/skeleton.tsx`, **never a spinner**), `empty / not-yet-generated`, `stale` (current artifact superseded by a newer version — stale treatment + a clear "View latest" action), `conflicting / superseded` (backend explicitly marked the artifact conflicting — stronger treatment), `incomplete artifact` (partial content — `truncated` flag), `error / failed-retrieval` (composes `<ErrorState variant="failedRetrieval" nextAction={{kind:'Retry', onRetry}}>` from story 2.22). **Reconciliation:** `stale`, `conflicting/superseded`, and `incomplete/truncated` derive from backend artifact flags that **do not exist on any live read model yet** — they are **DORMANT** (fully built + tested via constructed `ArtifactView` fixtures, **never fabricated from live data**, mirroring 2.15/2.16 dormant-field discipline). The only states reachable from the live route today are `loading` → `empty / not-yet-generated` (the disabled `useArtifact` stub never resolves data) and `error` (when a future-enabled hook errors). A pure `resolveArtifactPanelState(...)` is the single state-resolution seam (mirror `resolveRunContextState`); expose a `data-artifact-panel-state` attribute for tests.

5. **Content guidelines (UX-DR10).** The artifact body uses the `prose` typography utility (`.prose` in `src/styles/globals.css` — `max-width: 70ch`, `line-height: var(--leading-relaxed)` ≥1.5, 1rem paragraph rhythm; story 2.4 AC4). Metadata is visually secondary (`MetadataChrome` already treats it as chrome). Revision + staleness are surfaced **near the top** of the panel, not buried.

6. **Artifact primacy (UX-DR5 / story 2.7 + UX-DR10 hard rule).** The panel occupies the **central main pane** of the AppShell (rendered into `<main id="main-content">` via the route — the main pane carries a real `min-width` `DESKTOP_MAIN_MIN_WIDTH = 'min-w-[36rem]'` and **never auto-collapses**; the right `<aside>` is the element that yields width). Section navigation does **not** displace the main reading flow — anchors scroll **within** the panel (not by replacing it). A layout test asserts the panel does not render a collapse/min-width below the documented floor.

7. **Untrusted runner output (story 2.24).** The markdown body is rendered **exclusively** through the story-2.24 `SafeMarkdownRenderer` — which already sanitizes scriptable payloads (`<script>`, `on*` handlers, `javascript:` URLs), renders code blocks as inert text, and runs the second-pass redaction filter. **This story does NOT re-implement sanitization** — it consumes the audited primitive. A panel-level test feeds an XSS-bearing `body` fixture through the panel and asserts no active `<script>`/`<iframe>` element renders (proving the panel routes untrusted content through the safe path, not that the renderer works — that is 2.24's own suite).

8. **Keyboard accessibility (UX-DR10).** Semantic heading hierarchy is preserved (the `SafeMarkdownRenderer` maps markdown `#`/`##`/`###` to `<h1>`/`<h2>`/`<h3>`). Section anchors are keyboard-navigable: each anchor is focusable and activatable with Enter/Space, with a labeled region. Metadata + content live in labeled regions; focus order respects reading order. **Reconciliation / TRAP T-ANCHOR:** `SafeMarkdownRenderer`'s custom `h1`/`h2`/… components pass **only `children`** (they strip `id`/slug attributes — verified at `SafeMarkdownRenderer.tsx:317`), so anchors **cannot target renderer-emitted heading ids**. Derive the anchor list from the raw markdown `source` via a pure `deriveSectionAnchors(source): { id, text, level }[]` util; render a labeled, keyboard-navigable anchor nav; on activate, scroll to the heading **best-effort by matching rendered heading text within the panel's `ref`** (do NOT modify the sanitizer — it is the trusted boundary). The AC8-required keyboard navigability (focusable + Enter/Space) is fully satisfied; precise id-stable section deep-linking is a documented follow-up (OQ-5).

9. **Allowed-actions integration (story 2.14).** The panel reads `useAllowedActions(workflowRunId)` and uses the result to enable/disable variant-specific controls (the Compare-entry control is hidden/disabled when no comparable revision and/or the action is absent from the backend-reported `actions[]`). The panel does **NOT** compute action eligibility locally. **Reconciliation:** `useAllowedActions` is a **disabled stub** today (`src/features/workflows/hooks/useAllowedActions.ts`, `enabled: false`) — its endpoint (`GET /api/v1/workflows/{workflowRunId}/allowed-actions` → typed `AllowedActions { actions: string[], versionStamp }`) exists, but wiring the hook live is the data-layer/2.14-frontend story's job and `src/lib/navigation/guards.ts` depends on the stub's disabled state. In E2 the Compare control is **always disabled** regardless (Compare Mode is Epic 4). So: the panel **consumes the hook as-is** (disabled → no actions → compare disabled, the safe default) and the enable/disable logic is **built + tested via the mocked hook** returning a fixture `AllowedActions`. Do NOT enable the live hook in this story.

10. **Responsibility boundary (UX-DR10).** The panel owns: artifact rendering, inline context, comparison entry points (currently disabled), section anchors, anchors into the clarification + decision regions. It does **NOT** own: the decision workflow itself (Approval/Decision Bar, story 2.19), supporting run history (Run Context Strip owns minimal context, story 2.16; deeper history deferred), or full lineage (deferred Phase 3). An ESLint-equivalent boundary holds — the panel lives under `src/features/workflows/components/`, consumes `src/components/ui/*` primitives, and never imports allowed-actions inference logic (there is none to import — the backend owns it).

11. **Component test coverage.** Vitest + RTL tests cover: spec variant renders all anatomy slots (title, type badge, revision indicator, body, inline metadata, change-summary when present, section anchors, clarification/approval/compare anchors); each state renders with the right treatment + `data-artifact-panel-state`; discriminated-union dispatch routes correctly (`spec → SpecArtifactRenderer`, `implementationPlan → stub`, `prOutput → stub`, unknown → safe fallback); markdown sanitization rejects scriptable payloads via the panel (AC7 XSS fixture — assert absence of `<script>`/`<iframe>` with `queryByRole`/`queryAllByRole`, not string matching); section anchors are keyboard-navigable (Tab + Enter/Space); allowed-actions integration disables/enables the Compare control correctly (mocked hook); the change-summary slot renders when `changeSummary` is non-null and hides when null; **primacy** — a layout test asserts the panel never renders below the documented minimum width / never sets an auto-collapse class. **Reconciliation:** no snapshot harness exists (story 2.27) — use **fixture-driven render assertions**, NOT `toMatchSnapshot`.

12. **Fixture-stream rendering.** Against frontend-owned `ArtifactView` fixtures mirroring the terminal states of the story-1.23 foundation fixture event streams that carry **all three artifact variants** (`happy-path-success.json` and `execution-failure-with-retry.json` both produce `spec` + `implementationPlan` + `prOutput`; `spec-rejection-and-resubmit.json` advances `spec` to v2), the panel renders the **spec** variant against the spec fixture (full anatomy) and the **stub renderers gracefully render** against the `implementationPlan` + `prOutput` fixtures — proving the discriminator dispatch works against real-fixture-derived data. Fixtures live under `src/test/fixtures/artifact/` (frontend-owned copies — the backend fixtures are not yet served to the SPA; see OQ-3).

**Dependency:** Story 2.24 (Artifact Content Sanitization + Redaction-Gap Closure) is **DONE** — its `SafeMarkdownRenderer` / `SafeDiffRenderer` / `MetadataChrome` primitives are the only sanctioned path for rendering untrusted artifact bodies. This story consumes them; it must not render runner-produced content any other way (`dependency-edges` CI check + `no-unsanitized-html` ESLint rule both enforce this).

## Tasks / Subtasks

- [x] **Task 1 — `ArtifactView` type + section-anchor util + fixtures** (AC: 1, 8, 12)
  - [x] Add `src/features/workflows/artifactView.ts` exporting the **frontend-owned** `ArtifactView` discriminated union (discriminator `artifactType: 'spec' | 'implementationPlan' | 'prOutput'`) modeling the epic AC2 spec anatomy. Spec member carries (at minimum): `artifactType: 'spec'`, `artifactId: string`, `title: string`, `version: number`, `classification: string`, `body: string` (untrusted markdown), `createdAt: string` (ISO), `checksum?: string`, `changeSummary?: { before: string; after: string } | null`, and the dormant state flags `stale?: boolean`, `superseded?: boolean`, `truncated?: boolean`. Stub members carry the shared metadata + `body` but no spec-only slots. Document field-by-field which fields the **future artifact-read endpoint** will supply (no live source today).
  - [x] Add a pure `deriveSectionAnchors(source: string): { id: string; text: string; level: number }[]` (parse ATX `#`/`##`/`###` headings from the raw markdown; slugify text → `id`; ignore headings inside fenced code blocks). Unit-test it (incl. duplicate-heading id de-dup + code-fence exclusion).
  - [x] Add `src/test/fixtures/artifact/` fixtures: a populated **spec** `ArtifactView` (`run_fix_rej_001`-aligned — `LIN`/`DEL` ticket, spec **v2** post-resubmit, classification, multi-heading body), an `implementationPlan` fixture, a `prOutput` fixture, a `changeSummary` (before/after) spec fixture, and an **XSS** spec fixture (`body` containing `<script>`, `javascript:` link, `<img onerror>`).

- [x] **Task 2 — `SpecArtifactRenderer`** (AC: 2, 5, 7, 8)
  - [x] Build `SpecArtifactRenderer.tsx` composing `<MetadataChrome title={…} version={…} classification={…} subtitle?={…}>` (imported from `@/lib/sanitization`) wrapping `<SafeMarkdownRenderer source={artifact.body} className="prose" />`. **Barrel import only** — never reach into individual sanitization files (no-unsanitized-html rule).
  - [x] Render the artifact-type badge (`<Badge>` from `components/ui`), revision indicator (`v{version}` + placeholder anchor to revision history), inline metadata (created-at, classification badge, checksum short-form) as typed props.
  - [x] Change-summary slot: render `<SafeDiffRenderer before={changeSummary.before} after={changeSummary.after} />` only when `changeSummary` is non-null; otherwise render nothing.
  - [x] Section-anchor nav from `deriveSectionAnchors(artifact.body)` — labeled region, each anchor a focusable button (Enter/Space activatable, AC8); on activate, scroll best-effort to the matching heading within the renderer's container `ref`. Apply `.prose` to the body container.
  - [x] Clarification (2.18) + Approval (2.19) anchor entry-points — keyboard-focusable placeholders, no target wired in E2. Compare-Mode entry — see Task 4 / AC9 (disabled control + "Available in next release" tooltip).

- [x] **Task 3 — Stub variant renderers** (AC: 3)
  - [x] `ImplementationPlanArtifactRenderer.tsx` + `PrOutputArtifactRenderer.tsx`: render "Renderer coming in Epic 3" **inside** `<MetadataChrome>` + `<SafeMarkdownRenderer source={artifact.body} />` (2.24 AC10 — compose the primitives, do NOT roll your own). Keep them intentionally thin.

- [x] **Task 4 — `ArtifactReviewPanel` dispatch + states + container** (AC: 1, 3, 4, 6, 9, 10)
  - [x] `ArtifactReviewPanel.tsx`: a **presentational** component taking a resolved `artifact: ArtifactView`, the panel state, and the allowed-actions result; dispatch on `artifact.artifactType` (`spec`/`implementationPlan`/`prOutput`/unknown→safe fallback).
  - [x] Pure `resolveArtifactPanelState(...)`: `error → loading → empty/not-yet-generated → conflicting/superseded → stale → incomplete → default` (document the precedence). Expose `data-artifact-panel-state`. `loading` → `Skeleton` (never spinner); `error` → story-2.22 `<ErrorState variant="failedRetrieval" nextAction={{kind:'Retry', onRetry}}>`; `empty` → documented "Specification not yet available" copy.
  - [x] Allowed-actions: read `useAllowedActions(workflowRunId)` (disabled stub today); derive the Compare control's enabled/disabled purely from the backend `actions[]` + "comparable revision exists" — never a local permission guess. Compare stays disabled in E2 regardless.
  - [x] Thin container seam: a `workflowRunId` + `artifactId` entry that reads `useArtifact(artifactId)` (disabled stub → maps to `empty/not-yet-generated` live today; flips to `default` with zero panel changes when the artifact-read story enables the hook + endpoint). Keep the presentational panel and the data container separable for router-free tests (mirror RunContextStrip's OQ-1 resolution).

- [x] **Task 5 — Route mount** (AC: 6)
  - [x] In `src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx`, replace the placeholder paragraph (lines ~84–87) with the `ArtifactReviewPanel` container, passing `workflowRunId` + `artifactId` + the loader's `artifactType`. **Keep** the existing `RENDERABLE_ARTIFACT_TYPES` / `UnrenderableArtifactState` AC8b route-level guard (it guards backend-reported types this build can't render; the panel's internal dispatch is the artifact-level guard). Do NOT fork the route per stage (epic AC10 — the discriminator drives variant selection inside the panel).
  - [x] In `src/routes/workflows/$workflowRunId/index.tsx`, update the "(2.17) renders here once it lands" sentence (lines ~139–142) to point accurately at the artifacts route (the panel renders in the artifact-viewer route, reached via the existing "Open a sample artifact →" link). Do not change the route's loader/guard/RunContextStrip mount.

- [x] **Task 6 — Tests** (AC: 11, 12, 7)
  - [x] `ArtifactReviewPanel.test.tsx`, `SpecArtifactRenderer.test.tsx`, stub-renderer tests, `artifactView.test.ts` (anchor util). Cover all of AC11 + AC12: anatomy slots, each state (incl. dormant stale/conflicting/incomplete via constructed fixtures), dispatch routing, XSS inertness via the panel (`queryByRole`/`queryAllByRole`, not strings), section-anchor keyboard nav, allowed-actions enable/disable (mocked hook), change-summary present/absent, never-auto-collapse layout assertion.
  - [x] `ErrorState` pulls `useReturnToRunContext` internally → `vi.mock` that module in unit tests (established pattern from 2.16/2.22 — see Dev Notes).
  - [x] Fixture-driven (NOT `toMatchSnapshot`); pin `Date.now` if any relative-time renders.

- [x] **Logging instrumentation** (cross-cutting; frontend-adapted — required on every story)
  - [x] Mirror the QueueShell / RunContextStrip structured-`console` seam (field-only objects — NO `error.message`, NO payload bytes, NO body text, NO ticket/run business content): emit `artifactPanel.loadError` (`warn`, `{ event, code, transport }` from ProblemDetails `code` / transport classification, never the raw message), `artifactPanel.retry` (`info`), and `artifactPanel.stale` (`warn`, `{ event }`) when the stale state renders. There is NO logger module — use structured `console.info`/`console.warn` exactly like `QueueShell.tsx`.
  - [x] Pin each new log line with a `console`-spy assertion + a negative test that the raw error message / artifact body is never logged.

### Review Findings

- [x] [Review][Patch] Duplicate section-anchor labels always scroll to the first matching heading [deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx:85]
- [x] [Review][Patch] Longer fenced code blocks can close too early and leak code headings into anchors [deliveryline-frontend/src/features/workflows/artifactView.ts:203]
- [x] [Review][Patch] Artifact route guard blocks `implementationPlan` and `prOutput` stub renderers the panel already supports [deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx:40]
- [x] [Review][Patch] Background refetch with existing data can replace the artifact with the loading skeleton [deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:240]
- [x] [Review][Patch] Future artifact query data is cast to `ArtifactView` without runtime shape validation [deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:237]
- [x] [Review][Patch] Revision-history placeholder is disabled and therefore not keyboard-focusable [deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx:116]
- [x] [Review][Patch] Section-anchor keyboard test does not cover Tab or Enter/Space activation [deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.test.tsx:66]
- [x] [Review][Patch] Metadata/content regions are not accessibility-labeled [deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx:141]
- [x] [Review][Patch] Stale/superseded status color treatments lack icons [deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:100]
- [x] [Review][Patch] `View latest` renders as an enabled no-op button in dormant stale/superseded states [deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:117]

## Dev Notes

### The central reconciliation (READ THIS FIRST)

This story is the **first frontend composite with no live data source for its primary content.** Unlike 2.15 (`WorkflowSummary` from the list endpoint) and 2.16 (`WorkflowDetail` from the detail endpoint), the artifact **body** is not served by any backend endpoint yet:

- **No `ArtifactView` type, no artifact-read endpoint.** `schema.d.ts` exposes only `LatestArtifact { artifactType?, status?, version? }` (summary, no body, no id) inside `WorkflowDetail.latestArtifacts[]`. There is no `GET /artifacts/{id}` path.
- **`useArtifact(artifactId)` is a disabled stub** (`src/features/workflows/hooks/useArtifact.ts`, `enabled: false`, throws if forced) — the query key (`workflowKeys.artifact(artifactId)`) was reserved by story 2.6 AC3; the endpoint "ships with the artifact-read story."
- **`useAllowedActions(workflowRunId)` is also a disabled stub** — but its endpoint (`GET …/allowed-actions` → `AllowedActions`) **does exist** in the OpenAPI. Wiring it live is another story's job; `src/lib/navigation/guards.ts` already composes the disabled stub.

**Consequence (the design rule for this story):** build the panel to the full epic anatomy, **presentational + prop-driven**, and drive every render in tests from **constructed `ArtifactView` fixtures + the story-1.23 fixture-event-stream terminal states**. Live-reachable states today are only `loading → empty/not-yet-generated` (disabled `useArtifact`) and `error`. Everything else (`stale`, `conflicting/superseded`, `incomplete`, the full spec body, allowed-actions-driven compare enabling) is **DORMANT**: fully built + tested, **never fabricated from live data** (exactly the 2.15/2.16 discipline). When the artifact-read story lands, enabling the hook flips the route from `empty` to `default` with **zero panel changes**.

### What already exists — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| `SafeMarkdownRenderer` (`{ source: string; className? }`) | `@/lib/sanitization` (barrel) — `src/lib/sanitization/SafeMarkdownRenderer.tsx` | The ONLY sanctioned markdown body renderer. Pass `source={artifact.body}`. **Barrel import only.** |
| `SafeDiffRenderer` (`{ before, after, beforeLabel?, afterLabel?, className? }`) | `@/lib/sanitization` | The change-summary slot (AC2). |
| `MetadataChrome` (`{ title, version, classification, subtitle?, children, className? }`) | `@/lib/sanitization` | The sanctioned artifact-body wrapper (2.24 AC6/AC10). `children` = the `SafeMarkdownRenderer`. Trusted metadata as typed props. |
| `scanForRedactions` / `renderTextWithRedactions` | `@/lib/sanitization` | Already applied inside `SafeMarkdownRenderer` — do NOT call directly unless rendering raw text outside the renderer. |
| `Badge` (CVA: default/secondary/destructive/outline) | `src/components/ui/badge.tsx` | Artifact-type + classification badges. NOT `WorkflowStateBadge` (that's for workflow states). |
| `Card` + `CardHeader/Title/Content/Footer`, `Alert` | `src/components/ui/card.tsx`, `alert.tsx` | Optional container/layout structure. |
| `Skeleton` (animate-pulse, `bg-surface-elevated`) | `src/components/ui/skeleton.tsx` | Loading state (never a spinner — `no-untyped-loading-state` rule). |
| `ErrorState` (`variant="failedRetrieval" nextAction={Retry}`) | `src/components/feedback/states/ErrorState.tsx` (story 2.22) | The `error` state. **Calls `useReturnToRunContext` internally → `vi.mock` that module in tests.** |
| `.prose` utility (`max-width:70ch`, `line-height:1.625`, 1rem rhythm) | `src/styles/globals.css:424–432` (story 2.4 AC4) | Artifact body typography (AC5). |
| `useArtifact` / `useAllowedActions` (disabled stubs) + `useWorkflowDetail` (live) | `src/features/workflows/hooks/` | Container seams. `useArtifact`/`useAllowedActions` stay disabled (see reconciliation). |
| `workflowKeys` factory (`.artifact(id)`, `.allowedActions(id)`, `.detail(id)`) | `src/lib/queryKeys/workflowKeys.ts` | Query keys — already defined; use via the hooks (the `no-inline-query-keys` rule forbids inline key arrays). |
| `apiClient` (`openapi-fetch`) + `unwrap` | `src/lib/api/client.ts`, `queryOptions.ts` | Only relevant if/when the artifact-read story wires a live queryFn — NOT this story. |
| Route mount (`artifacts/$artifactId.tsx`) — loader stub returns `artifactType: 'spec'`; `RENDERABLE_ARTIFACT_TYPES = {'spec'}`; `UnrenderableArtifactState` (AC8b) | `src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` | THE mount point. Replace the placeholder paragraph; keep the AC8b guard. |
| AppShell central main pane (`min-w-[36rem]`, never collapses) + right `ContextPanelSlot` | `src/features/workflows/AppShell.tsx` (`DESKTOP_MAIN_MIN_WIDTH`), `ContextPanelSlot.tsx` | AC6 primacy floor. The panel renders into `<main>` via the route (not the right slot). |
| `ProblemDetailsError` / `isProblemDetailsError` / `DomainErrorCode` | `src/lib/api/problemDetails.ts` | Map error → stable `code` for the error state + log (never raw message). |
| Structured-log pattern (`console.info({event,…})`, field-only, console-spy tested) | `src/features/workflows/QueueShell.tsx` + `RunContextStrip.tsx` | Copy for the Logging task. No logger module. |
| Frontend fixtures layout (`runContext/`, `runQueue/`) + fixture-driven test convention (no snapshots) | `src/test/fixtures/`, `RunContextStrip.test.tsx` | Add `artifact/` fixtures; follow the same RTL+MSW pattern. |
| Story 1.23 fixture event streams (all three artifact variants) | `deliveryline-backend/src/test/resources/fixture-event-streams/` (`happy-path-success.json`, `execution-failure-with-retry.json` → spec+implementationPlan+prOutput; `spec-rejection-and-resubmit.json` → spec v2) | Source-of-truth terminal states the frontend `ArtifactView` fixtures mirror. |

### Must CREATE in this story

- `src/features/workflows/artifactView.ts` — frontend-owned `ArtifactView` discriminated union + `deriveSectionAnchors`.
- `src/features/workflows/components/ArtifactReviewPanel.tsx` — generalized composite + dispatch + `resolveArtifactPanelState` + container seam.
- `src/features/workflows/components/SpecArtifactRenderer.tsx` — spec variant (composes `MetadataChrome` + `SafeMarkdownRenderer` + `SafeDiffRenderer`).
- `src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx` + `PrOutputArtifactRenderer.tsx` — Epic-3 stub renderers (still compose the primitives).
- `src/test/fixtures/artifact/*.ts` — spec / implementationPlan / prOutput / changeSummary / XSS fixtures.
- Tests for each of the above.

### `ArtifactView` shape (frontend-owned — the intended contract)

```ts
type ArtifactView =
  | { artifactType: 'spec'; artifactId: string; title: string; version: number;
      classification: string; body: string; createdAt: string; checksum?: string;
      changeSummary?: { before: string; after: string } | null;
      stale?: boolean; superseded?: boolean; truncated?: boolean }
  | { artifactType: 'implementationPlan'; artifactId: string; title: string; version: number;
      classification: string; body: string; createdAt: string; /* stub-rendered in E2 */ }
  | { artifactType: 'prOutput'; artifactId: string; title: string; version: number;
      classification: string; body: string; createdAt: string; /* stub-rendered in E2 */ };
```

These fields are the **intended** read-model — the future artifact-read endpoint supplies them. No live source today; fixtures only.

### Traps (do NOT step on these)

- **T-ANCHOR — headings have no ids.** `SafeMarkdownRenderer`'s `h1`/`h2`/… components pass only `children` (no `id`, `SafeMarkdownRenderer.tsx:317`). Derive anchors from raw `source`; scroll best-effort by heading-text match within the panel `ref`. Do NOT patch the sanitizer (trusted boundary). Keyboard-nav (AC8) is fully met; id deep-linking is OQ-5.
- **T1 — barrel imports only.** Import `SafeMarkdownRenderer`/`SafeDiffRenderer`/`MetadataChrome` from `@/lib/sanitization`, never from individual files. The `no-unsanitized-html` rule + `--max-warnings=0` will fail on `dangerouslySetInnerHTML` / direct-file paths.
- **T2 — never roll your own renderer.** Stub variants (AC3) MUST compose `MetadataChrome` + `SafeMarkdownRenderer` (2.24 AC10), not a bespoke body renderer.
- **T3 — `useArtifact`/`useAllowedActions` stay disabled.** Do NOT wire their live queryFns (that's the data-layer story; `guards.ts` depends on the disabled state). Build the container so enabling later is a one-line change.
- **T4 — dormant states from fixtures only.** `stale`/`conflicting`/`incomplete` and the full spec body are tested via constructed `ArtifactView` fixtures — never fabricated from `WorkflowDetail.latestArtifacts` (which has no body and no id to match `artifactId`).
- **T5 — type badge ≠ state badge.** Use `Badge` (`components/ui`) for the artifact-type badge; `WorkflowStateBadge` is workflow-*state* vocabulary and would mislead.
- **T6 — loading is a Skeleton, never a spinner** (AC4; `no-untyped-loading-state`).
- **T7 — `ErrorState` pulls `useReturnToRunContext`.** `vi.mock` that module in unit tests (otherwise the hook throws outside its provider).
- **T8 — field-only logs.** No `error.message`, no artifact body, no ticket/run business content in logs — `{ event, code, transport }` only. Negative test enforces it.
- **T9 — don't fork the route.** The panel mounts in `artifacts/$artifactId.tsx`; variant selection is the panel's internal `artifactType` dispatch (epic AC10). Keep the route-level `RENDERABLE_ARTIFACT_TYPES`/`UnrenderableArtifactState` guard.
- **T10 — `--max-warnings=0` + non-color-alone + prettier.** Every status color carries an icon+label; run `prettier --write` before finishing (memory `prettier-gate-cascades-ci`).

### Open questions (resolved with recommendations — proceed unless told otherwise)

- **OQ-1 — presentational panel vs container split.** *Recommendation:* a pure presentational `ArtifactReviewPanel` taking a resolved `ArtifactView` + a thin `…Container` that reads the disabled hooks and maps to states. Tests drive the presentational panel directly with fixtures (router/query-free); one container test covers the live `empty` mapping. Mirrors RunContextStrip OQ-1.
- **OQ-2 — allowed-actions: enable the live hook?** *Recommendation:* NO. Keep `useAllowedActions` disabled; compare is Epic-4/always-disabled in E2; build + test the enable/disable logic via the mocked hook. Avoids scope-creep into the data-layer story and the `guards.ts` dependency.
- **OQ-3 — fixture source.** *Recommendation:* frontend-owned `ArtifactView` fixtures mirroring the story-1.23 fixture-event-stream terminal states (the backend fixtures aren't served to the SPA). Same approach as RunContextStrip's `specRejectAndResubmit`.
- **OQ-4 — revision-history + clarification/approval anchors.** *Recommendation:* render them as keyboard-focusable placeholder controls with no target wired in E2 (revision history, 2.18, 2.19 land later). AC2 asks for the *entry points*, not the destinations.
- **OQ-5 — precise section deep-linking.** *Recommendation:* E2 ships keyboard-navigable anchors with best-effort text-match scroll (AC8 satisfied). Stable id-based section deep-links (URL hash, browser-back) are a follow-up — would require the sanitizer to emit heading ids; out of scope (trusted boundary).
- **OQ-6 — compare via `SafeDiffRenderer` now?** *Recommendation:* the change-summary slot (AC2) DOES use `SafeDiffRenderer` when `changeSummary` is present (lighter than full Compare Mode). The full Compare-Mode *entry control* stays disabled (Epic 4). Two different things — build both.

### Logging Requirements (project-wide standard, frontend-adapted)

Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. Equivalent contract: structured `console.info`/`console.warn({ event, …fields })`, **field-only** (never `error.message`, artifact body, tokens, ticket/run business content), pinned by `console`-spy tests. Mirror `QueueShell.tsx` / `RunContextStrip.tsx`:

- `artifactPanel.loadError` (`warn`) — `{ event, code, transport }`, `code` from ProblemDetails (never raw message/status).
- `artifactPanel.retry` (`info`) — on Retry.
- `artifactPanel.stale` (`warn`) — `{ event }` when the stale state renders.
- Each new log line asserted at its level by a focused test; a negative test confirms no raw message / body is logged.

### Project Structure Notes

- Components → `src/features/workflows/components/` (architecture.md: workflow-specific components live under `features/workflows`). Pure helpers (`artifactView.ts`, `deriveSectionAnchors`) in a sibling `.ts`, not a `.tsx`, per `frontend-react-refresh-no-fn-exports` (a `.tsx` exporting a non-component function fails the eslint react-refresh gate).
- No backend changes. No new npm dependency (sanitization stack + `lucide-react` already present from 2.24).
- Gates: `tsc -b`, `eslint . --max-warnings=0`, `npm run lint:rules-test` (4 rules incl. `no-unsanitized-html` + `no-workflow-domain-in-ui-primitives`), `vitest`, `prettier --write`. **Run gates via PowerShell or `rtk proxy`** — RTK corrupts only the Bash tool here (memory `rtk-hook-only-matches-bash`). No lockfile change expected → no WSL2/Linux smoke needed (memory `wsl-linux-ci-reproduction`, `prettier-gate-cascades-ci`).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.17 (lines 1231–1252)] — AC source.
- [Source: _bmad-output/planning-artifacts/epics.md lines 829, 918, 941, 1226, 1389, 1394, 1436] — party-mode finding #3 (generalized composites), prose utility, route-not-forked, ARP placement, diff-renderer slot, compare-mode deferral.
- [Source: _bmad-output/implementation-artifacts/2-24-artifact-content-sanitization-untrusted-runner-output.md] — `SafeMarkdownRenderer`/`SafeDiffRenderer`/`MetadataChrome` contract + AC10 (variant renderers MUST consume the primitives) + `no-unsanitized-html` rule.
- [Source: _bmad-output/implementation-artifacts/2-16-run-context-strip-component.md] — reconciliation discipline, state-resolver pattern, `ErrorState` mock note, fixture-driven (no-snapshot) convention.
- [Source: deliveryline-frontend/src/lib/sanitization/index.ts + SafeMarkdownRenderer.tsx + MetadataChrome.tsx + SafeDiffRenderer.tsx] — exact prop signatures (`source`, `{title,version,classification,subtitle?,children}`, `{before,after,beforeLabel?,afterLabel?}`) + heading-no-id constraint (`SafeMarkdownRenderer.tsx:317`).
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx] — mount point, loader stub, `RENDERABLE_ARTIFACT_TYPES`, `UnrenderableArtifactState` (AC8b).
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx] — `RECOGNIZED_STATES`, route-not-forked comment (AC10), 2.17 placeholder sentence to update.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useArtifact.ts + useAllowedActions.ts] — disabled stubs (reconciliation evidence).
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts] — `LatestArtifact`, `WorkflowDetail`, `AllowedActions`, `AllowedActionsVersionStamp` (no `ArtifactView`, no artifact-read path).
- [Source: deliveryline-frontend/src/features/workflows/AppShell.tsx] — `DESKTOP_MAIN_MIN_WIDTH = 'min-w-[36rem]'` (AC6 primacy floor) + `ContextPanelSlot`.
- [Source: deliveryline-frontend/src/styles/globals.css:424–432] — `.prose` utility (AC5).
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/] — `happy-path-success.json` / `execution-failure-with-retry.json` (all three artifact variants) / `spec-rejection-and-resubmit.json` (spec v2) — AC12 terminal states.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Gates run via PowerShell (memory `rtk-hook-only-matches-bash` — RTK corrupts only the Bash tool):
  - `tsc -b` → 0 errors (fixed `noUncheckedIndexedAccess` on the regex-group access in `deriveSectionAnchors`).
  - `eslint . --max-warnings=0` → 0 (fixed: removed an unnecessary `?? ''` on `textContent`, dropped a non-null assertion on `changeSummary` by narrowing to a local, removed a redundant `role="list"` on the anchor `<ul>`).
  - `npm run lint:rules-test` → 4/4 (incl. `no-unsanitized-html`).
  - `vitest run` (full) → 40 files / 313 tests pass (+47 new); the `Not implemented: scrollTo` lines are pre-existing jsdom stderr noise, not failures.
  - `prettier --check "src/**/*.{ts,tsx}"` → clean.
- No lockfile / npm-dependency change → no WSL2/Linux-CI smoke required (memory `wsl-linux-ci-reproduction`, `frontend-lockfile-cross-platform`).

### Completion Notes List

- **Presentational + container split (OQ-1).** `ArtifactReviewPanel` is pure-presentational (takes a resolved `state` + `artifact` + `compareEnabled` + `onRetry`); `ArtifactReviewPanelContainer` is the thin data seam reading the disabled `useArtifact`/`useAllowedActions` stubs (T3 — left disabled). Tests drive the panel directly with fixtures (router/query-free) and the container via mocked hooks.
- **Central reconciliation honored.** `ArtifactView` is frontend-owned (`artifactView.ts`); no live artifact-read endpoint. Live-reachable states today are `loading → empty` (the disabled stub is idle, `isFetching=false` → `empty`) and `error`. The dormant `superseded`/`stale`/`incomplete` states + full spec body + compare-enable are built and tested **only via constructed fixtures** (T4), never fabricated from live data. Enabling the hook later flips `empty → default` with zero panel changes.
- **Sanitization (T1/T2/AC7).** Spec + both stub renderers compose `MetadataChrome` + `SafeMarkdownRenderer` (+ `SafeDiffRenderer` for the change-summary) from the `@/lib/sanitization` barrel only — no hand-rolled renderer, no `dangerouslySetInnerHTML`. A panel-level XSS fixture asserts no active `<script>`/`<iframe>` and no `javascript:` anchor renders.
- **T-ANCHOR.** Section anchors derive from the RAW markdown via `deriveSectionAnchors` (the sanitizer strips heading ids); activation scrolls best-effort by matching rendered heading text within the body container `ref`. The sanitizer was NOT modified. Keyboard-nav (AC8) is fully met via native buttons; id-stable deep-linking remains OQ-5.
- **AC9 reconciliation.** Compare eligibility (`canEnableCompare`) is derived purely from the backend `actions[]` + a comparable-revision check (`version > 1`) — never a local guess. Live the disabled `useAllowedActions` stub yields no actions → Compare disabled (the safe default); the enable/disable logic is built + tested via the mocked hook. The live hook was NOT enabled.
- **AC6 primacy.** The panel root is `w-full` with NO min-width below the `min-w-[36rem]` floor and NO auto-collapse class (a layout test asserts this); the panel mounts into the AppShell `<main>` via the artifacts route. The route was NOT forked per stage (AC10) — the panel's `artifactType` dispatch selects the variant; the route-level `RENDERABLE_ARTIFACT_TYPES`/`UnrenderableArtifactState` AC8b guard is kept.
- **Logging (T8).** `artifactPanel.loadError` (`warn`, `{event,code,transport}`), `artifactPanel.retry` (`info`), `artifactPanel.stale` (`warn`, `{event}`) — field-only; pinned by console-spy assertions + a negative test that the raw error message / artifact body is never logged (exact-key-set assertions).

### File List

**New:**
- `deliveryline-frontend/src/features/workflows/artifactView.ts` — frontend-owned `ArtifactView` union + `artifactTypeLabel` + `deriveSectionAnchors` + `resolveArtifactPanelState` + `canEnableCompare`/`hasComparableRevision`.
- `deliveryline-frontend/src/features/workflows/artifactView.test.ts` — anchor util + resolver + compare-eligibility unit tests.
- `deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx`
- `deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx`
- `deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.tsx`
- `deliveryline-frontend/src/features/workflows/components/StubArtifactRenderers.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx` — presentational panel + dispatch + `ArtifactReviewPanelContainer` + logging.
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx`
- `deliveryline-frontend/src/test/fixtures/artifact/artifactViewFixtures.ts` — spec / changeSummary / XSS / implementationPlan / prOutput fixtures.

**Modified:**
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` — mounted `ArtifactReviewPanelContainer` (kept the AC8b guard).
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` — updated the 2.17 placeholder sentence to point at the artifacts route.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `2-17` status `ready-for-dev → in-progress → review`.

## Change Log

| Date | Change |
|---|---|
| 2026-06-02 | Implemented story 2.17 (Artifact Review Panel — generalized composite, spec variant). Added the frontend-owned `ArtifactView` union + section-anchor util, `SpecArtifactRenderer`, Epic-3 stub renderers, the `ArtifactReviewPanel` dispatch/state/container, the artifact-route mount, and full fixture-driven test coverage. Status `ready-for-dev → review`. Gates green via PowerShell: tsc 0, eslint --max-warnings=0 0, lint:rules-test 4/4, vitest 313/313 (+47), prettier clean. |
