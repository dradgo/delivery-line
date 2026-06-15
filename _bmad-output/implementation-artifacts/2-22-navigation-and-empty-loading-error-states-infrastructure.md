# Story 2.22: Navigation + Empty / Loading / Error States Infrastructure

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer building any view that navigates between queue / run-detail / artifact / clarification states,
I want shared navigation helpers (a per-session breadcrumb stack of meaningful contexts, a typed `useReturnToRunContext` / `useNavigateToArtifact` / `useNavigateToClarification`, and a `withRunContext` wrapper for entering and leaving sub-states without losing the run+artifact+scroll position) plus a tightly typed state-component family (`<EmptyState variant="...">`, `<LoadingState variant="...">`, `<ErrorState variant="..." nextAction={...}>`) under `src/lib/navigation/` + `src/components/feedback/states/`,
So that future Epic-2 composites (2-15 / 2-16 / 2-17), Epic-3 composites (3-26 / 3-29 / 3-30), and Epic-4 Compare Mode (4-19 / 4-20) reuse one set of primitives that — per UX-DR16 — preserve run identity and artifact continuity across queue → run-detail → compare → clarification → recovery, and — per UX-DR17 — make every content-absent state distinguish absence vs delay vs failure vs restriction with an explicit next safe action; and so this story closes the open `[Source: 2-7 SEAM]` line in `routes/-states/DeadEndState.tsx` ("when the shared empty-state / feedback-pattern infrastructure lands, these dead-end states adopt that component instead of this local shell").

## ⚠️ Read first — scope, sequencing, and what this story is NOT

**Scope in one line:** ship the **`src/lib/navigation/`** library (typed hooks + per-session breadcrumb stack + `withRunContext` HOC/hook) and the **`src/components/feedback/states/`** primitives (`<EmptyState>`, `<LoadingState>`, `<ErrorState>`) — frontend-only — plus the one ESLint rule that bans generic spinners, plus the migration of `DeadEndState.tsx` to consume the new primitives. **No backend changes.** **No new TanStack Router routes.** **No queue UI, no Run Context Strip, no Artifact Review Panel, no Decision Bar, no Compare Mode, no Clarification Region** — those are 2-15 / 2-16 / 2-17 / 2-18 / 2-19 / 2-20 / 4-19 / 4-20 / 3-26 / 3-27 / 3-29.

This story builds the **infrastructure** the rest of Epic-2a's active slice consumes. Per the sprint-status active-slice order, 2-22 lands BEFORE 2-20 / 2-16 / 2-15 / 2-17 specifically so those composites can import these primitives instead of inventing local empty/loading/error shells.

| Surface                                            | Built by                              | 2.22's job                                                                                                                                                       |
| -------------------------------------------------- | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Queue shell states (`queue empty`, filtered empty) | 2.20                                  | ship the `<EmptyState variant="queue">` + `variant="filtered"` primitives — 2.20 wires them into the queue route                                                 |
| Run Context Strip                                  | 2.16                                  | ship the loading/error variants the strip wraps itself in; ship `useReturnToRunContext` the strip's "back to queue" affordance consults                          |
| Run / Review Queue Item                            | 2.15                                  | ship the `<EmptyState>`+`<LoadingState>` primitives the item embeds while data fetches                                                                            |
| Artifact Review Panel (generalized + spec variant) | 2.17                                  | ship `<EmptyState variant="artifactNotGenerated"|"noMeaningfulDiff">`, `<LoadingState variant="generatingArtifact"|"rebuildingAfterRejection">`, `<ErrorState variant="failedRetrieval"|"unavailableDiffBaseline"|"blockedByStaleState">` |
| Clarification Region                               | 2.18 (epic-2b)                        | ship `useNavigateToClarification(runId, clarificationId)` deep-link anchor + `<EmptyState variant="noOpenQuestions">`                                            |
| Approval / Decision Bar                            | 2.19 (epic-2b)                        | ship `<ErrorState variant="blockedByStaleState">` for the stale-decision case                                                                                    |
| WCAG 2.1 AA audit + axe-core sweep                 | 2.25 (epic-2b)                        | ship correct landmark / role / aria-live baselines per AC9; the audit + automated checks are 2.25                                                                |
| Feedback Patterns Infrastructure (Inline / Persistent / Toast Boundaries) | 2.21 (epic-2b)        | DEFERRED — 2.22's `<ErrorState nextAction={ContactSupport|Retry|...}>` lives in the affected region; 2.21 will later wire the toast/inline/persistent dispatcher this story's `nextAction` payloads can target |
| Compare Mode side-by-side + changed-region nav     | 4.19 / 4.20                           | ship `withRunContext` HOC so entering compare from a run-detail view preserves scroll + selection on return                                                      |
| Recovery / retry re-entry navigation               | 4.5 / 4.7 / 4-22                      | ship `<LoadingState variant="retryingRecovery">` + `<ErrorState>` recovery-specific copy                                                                         |
| Operator failure-diagnostics deep-dive             | 4.4                                   | consumes the breadcrumb stack (per UX-DR16) so "back from diagnostics" returns to the prior run-detail view, not to the global queue                             |
| Recorded-role display ("permission restricted")    | future                                | ship `<ErrorState variant="permissionRestricted">` — the COPY emphasizes audit-only role labeling per architecture.md:519 (Trap T7)                              |

If you find yourself building a composite (queue, artifact viewer, clarification region, decision bar, compare mode), a new route, a new query hook, or a toast/inline-feedback dispatcher — **stop**. This story ends at the typed primitives + the navigation library + the `DeadEndState` migration + the one ESLint rule.

## 🔑 Reference decoder — UX-DR codes are not literal strings in the UX spec

The epic AC text cites `UX-DR16` + `UX-DR17`. Map them before you read:

- **"UX-DR16 / navigation continuity"** → UX spec **"Navigation Patterns"** (lines 1825-1862) + **"Hybrid Coherence Rules"** (989-1002) — "navigation should always preserve current run identity, current artifact identity, current workflow state; back navigation should return users to the prior meaningful review context, not to a generic top-level page; compare and clarification interactions should preserve the current artifact context and not disorient the user."
- **"UX-DR17 / content-absent states"** → UX spec **"Empty, Loading, and Error States"** (1864-1912) — exhaustive variant list for empty (`no runs / filtered / artifact not generated / no open questions / no meaningful diff`), loading (`fetching data / generating artifact / rebuilding after rejection / retrying recovery`), error (`failed retrieval / unavailable diff baseline / permission-restricted / blocked by stale state`) + the "every error state should provide the next safe action" rule + accessibility per "loading state should be announced when it materially affects interaction; errors should use semantic alert treatment; empty states should not depend on iconography alone."
- **"Pattern Enforcement Rules" (UX 2017-2022)** → "no empty, loading, or error state may appear without explaining whether the issue is absence, delay, failure, or restriction" — the variant unions enforce the absence/delay/failure/restriction taxonomy mechanically.
- **Architecture invariants** → `architecture.md:514-520` ("Available UI controls come from backend-reported allowed actions. The frontend must handle empty, disabled, unknown, or stale action sets without inferring workflow permissions locally"); `architecture.md:1182` ("Frontend empty, stale, conflict, no-actions, and failed-load states live under `components/feedback`"); `architecture.md:519` ("UI labels must make clear that MVP roles are recorded audit labels, not enforced authorization. Frontend code must not gate actions based on audit role labels") → Trap T7.

## 🔑 What already exists (stories 2.1–2.14, 2.24, 2.30–2.32, all done) — compose, do not rebuild

- **App root + router (stories 2.5 / 2.6 / 2.7):** `src/main.tsx` → `App.tsx` creates `createRouter({ routeTree, context: { queryClient } })` inside `<QueryClientProvider>`; `src/routes/__root.tsx` renders `<AppShell><Outlet /></AppShell>`. The router exposes `useRouter()`, `useRouterState()`, `useLocation()`, `useNavigate()`, `useMatches()`, `useMatchRoute()` — these are the documented stack for breadcrumb tracking; do NOT reach for `window.history` directly (Trap T2).
- **Flat route tree (story 2.5):** `routes/index.tsx` (`/` → redirect), `routes/workflows/index.tsx` (queue placeholder), `routes/workflows/$workflowRunId/index.tsx` (detail loader + `RECOGNIZED_STATES` guard), `routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` (artifact viewer stub), `routes/-states/DeadEndState.tsx` (the 8 dead-end states the migration in Task 6 retargets). Public-id route params validated via `src/lib/routing/publicId.{js,d.ts}` (`isValidRunId`, `isValidArtifactId`, `parseRunRouteParam`, `parseArtifactRouteParam`).
- **Tri-pane shell (story 2.7):** `src/features/workflows/AppShell.tsx` mounts the `<nav>` / `<main>` / `<aside>` landmarks; `<ContextPanelSlot>` (`src/features/workflows/ContextPanelSlot.tsx`) projects panel content into `<aside>`; `RunIdentityRegion` (`src/features/workflows/RunIdentityRegion.tsx`) reads `useWorkflowDetail`; `useResponsiveLayout()` returns `mobile|tablet|desktop`. **2.22's catastrophic-error overlay (AC8) plugs into the shell SEAM:** the shell reserves a placement; this story ships the overlay component but mounts it in `__root.tsx` alongside the `AppShell` (not inside it) so a shell crash still surfaces it.
- **Layout primitives (story 2.4):** `src/components/layout/{Stack,Inline,Grid,Container,Divider}` + `gap.ts` `GapToken`. `cn()` from `src/lib/utils.ts`.
- **shadcn/ui primitives (story 2.2):** `src/components/ui/{button,sheet,scroll-area,separator,tabs,card,alert,badge,sonner,tooltip,collapsible,dialog,dropdown-menu,popover,...}`. **`alert.tsx` already ships** — it is the `<ErrorState>` semantic foundation (Task 4 wraps it). **`sonner.tsx`** is the toast dispatcher 2.21 will later route into; this story does NOT toast — error states render INLINE per UX-DR17 + AC8 ("in the affected region, not only globally").
- **Design tokens (stories 2.3 / 2.4):** `globals.css` neutral surfaces (`--surface`, `--surface-elevated`, `--text-primary/-secondary/-tertiary`), `--border`, `--ring-focus`, semantic state tokens (`bg-state-error`, `text-state-error-foreground`, `bg-state-warning-subtle`, etc.), typography classes (`.text-page-title`, `.text-section-heading`, `.text-body`, `.text-meta`).
- **Data layer (story 2.6):** `useWorkflowDetail`, `useArtifact`, `useAllowedActions`, `useWorkflowEvents`, `useWorkflowMutation`. Query keys come only from `workflowKeys.*` (ESLint `local-rules/no-inline-query-keys`).
- **2-14 allowed-actions (closed 2026-05-27):** `useAllowedActions(runId)` returns the typed action set; story 2.22's `<ErrorState>` `nextAction={NavigateBack | Retry | Refresh | ...}` is **NOT** an allowed-action gate — it is a UX hint for after-the-fact recovery. The two domains are distinct (Trap T8). Real action enabling/disabling on decision bars / queue items is 2-14's job; 2.22 only ships the visual + a11y primitive.
- **2-24 sanitization (closed 2026-05-26):** state-component message copy renders as plain text or via `SafeMarkdownRenderer` when authored by a human-trusted source (operator copy). Runner-output strings rendered inside `<ErrorState>` MUST be untrusted-by-default — Trap T6 — and routed through the sanitization pipeline before display.
- **Existing ESLint local rules (stories 2.31 / 2.24):** `local-rules/no-inline-query-keys`, `local-rules/no-workflow-domain-in-ui-primitives`, `local-rules/no-unsanitized-html`. Plugin at `deliveryline-frontend/tools/eslint-rules/index.js`. Tests at `tools/eslint-rules/__tests__/*.test.js` via `node --test`. `lint:rules-test` npm script. **This story extends with `local-rules/no-untyped-loading-state` (AC7).**
- **DeadEndState SEAM** is explicitly marked at `src/routes/-states/DeadEndState.tsx:32-33`: "SEAM (story 2.22): when the shared empty-state / feedback-pattern infrastructure lands, these dead-end states adopt that component instead of this local shell." **Task 6 is the redemption.**
- **No `src/lib/navigation/` directory exists yet** — confirmed via `ls src/lib/`. **No `src/components/feedback/` directory exists yet** — confirmed via `ls src/components/`. Both are NEW (architecture.md:1182 sanctioned).

## Acceptance Criteria

1. **Given** `src/lib/navigation/`, **Then** the library exports three typed navigation helpers:
   - `useReturnToRunContext(): () => void` — returns the user to the **prior meaningful run-centered context** drawn from the per-session breadcrumb stack (AC3), NOT to a generic top-level page. When the stack has no prior run-centered entry (e.g., the user landed via deep link), it falls back to `/workflows` (the queue) and emits a debug log. The hook reads the current router state via `useRouter()` and returns a stable `useCallback` (Trap T1 — `useNavigate()` itself is stable but the call MUST be `useCallback`-wrapped to keep `<button onClick>` referential equality stable for memoized children).
   - `useNavigateToArtifact(runId, artifactId): () => void` — typed wrapper around `navigate({ to: '/workflows/$workflowRunId/artifacts/$artifactId', params: { workflowRunId: runId, artifactId } })`. Validates both ids via `isValidRunId` / `isValidArtifactId` from `src/lib/routing/publicId` at hook-call time (NOT at navigation time — invalid ids must be caught synchronously so the consumer can branch on `isValid`). Returns a stable callback; throws `InvalidNavigationTargetError` (NEW typed error) on call when either id is invalid (the consumer wraps the callback in a defensive try/catch when the source is untrusted runner output — Trap T6).
   - `useNavigateToClarification(runId, clarificationId): () => void` — same shape as `useNavigateToArtifact`, with **anchored** deep-link semantics: the navigation lands on the run-detail route (`/workflows/$workflowRunId`) with a TanStack Router `search` param `?clarificationId=cla_xxx` (NOT a hash anchor — TanStack Router controls scroll restoration via the typed search state, and the route's `search` schema enforces validation). The clarification region in story 2.18 reads this param and scrolls itself into view. Validates `clarificationId` via NEW `isValidClarificationId` exported from `src/lib/routing/publicId.{js,d.ts}` (extends the existing module with the `cla_` public-id check mirroring `isValidRunId`).

2. **Given** UX-DR16 rule "navigation should always preserve current run identity, current artifact identity, current workflow state", **Then**:
   - (a) Every navigation helper passes `runId` + `artifactId` (when applicable) + `clarificationId` (when applicable) through TanStack Router's typed `params` + `search` — NEVER through ad-hoc URL building or `window.location.assign`. The router's existing `assertValidRunRouteParams` (`src/lib/routing/routeParamValidation.ts`) is the boundary defense; the navigation library is the SOURCE-side defense.
   - (b) NEW route guard helper `useAssertRunContextLoaded(runId)` (exported from `src/lib/navigation/guards.ts`) — calls `useWorkflowDetail(runId)` + `useAllowedActions(runId)` and returns a discriminated-union `{ kind: 'loading' } | { kind: 'loaded'; detail; actions } | { kind: 'runNotFound' } | { kind: 'error'; error }`. Composites that depend on run-context-loaded data import this guard rather than re-implementing the same `useQuery({ isLoading, isError, data })` shape four different ways.
   - (c) The guard's `runNotFound` case is wired to the existing `RunNotFoundState` (which after Task 6 will be the new `<ErrorState variant="failedRetrieval">` with a `runId`-aware message); the `error` case is wired to `<ErrorState variant="failedRetrieval" nextAction={{ kind: 'Retry', onRetry: refetch }}>`; the `loading` case is wired to `<LoadingState variant="fetchingData">`. This binding is documented in the new `src/lib/navigation/README.md`.
   - (d) The guard is **read-only** — it does NOT enforce permissions; backend-reported `useAllowedActions` is the source of truth (Trap T7 + architecture.md:519). The guard surfaces the loaded actions to the composite; the composite renders the buttons.

3. **Given** "back navigation should return users to the prior meaningful review context, not to a generic top-level page", **Then** the navigation library tracks a **per-session breadcrumb stack** of meaningful contexts:
   - (a) NEW context `NavigationBreadcrumbContext` (`src/lib/navigation/NavigationBreadcrumbContext.tsx`) + provider `<NavigationBreadcrumbProvider>` mounted in `src/App.tsx` (NOT in `__root.tsx` — the provider must live OUTSIDE the router so the router subscribes to it, not the other way around — Trap T3). The provider's stack lives in a `useReducer` (not `useState`) so `push`/`pop`/`replaceLast` actions are explicit and testable.
   - (b) Stack entry shape `BreadcrumbEntry = { kind: 'queue' | 'runDetail' | 'artifact' | 'clarification' | 'compareMode' | 'recoveryDeepDive'; runId?: string; artifactId?: string; clarificationId?: string; scrollY: number; createdAt: number }`. Only the SIX listed kinds are "meaningful" — the queue, a run-detail view, an artifact viewer, a clarification anchor, compare mode (Epic 4 stub for now), and the operator recovery deep-dive (Epic 4 stub for now). Random transitional URLs (e.g., a 404 landing) are NOT pushed.
   - (c) The provider subscribes to `useRouterState({ select: s => s.location })` and pushes a new entry **on route stable** (after the route's loader resolves — using TanStack Router's `onLoad` / `useEffect` after `useLoaderData()` settles) per the kind taxonomy. Identical consecutive entries are deduped (`replaceLast` if the kind + ids match — no double push on TanStack Router re-renders).
   - (d) The stack is capped at 16 entries (oldest dropped). Cap is exported as `MAX_BREADCRUMB_STACK_DEPTH = 16` from the library so future stories can grep for the limit.
   - (e) `useReturnToRunContext()` (AC1) reads the stack via `useNavigationBreadcrumb()` hook, walks BACKWARDS, finds the most recent `kind ∈ { runDetail, artifact, clarification }` entry, and navigates to it via the typed router. If none exists → falls back to `/workflows` (Trap T4 — never fall back to `window.history.back()` because the browser back stack may contain pre-app pages from a fresh tab).
   - (f) The stack is **session-scoped** (NOT persisted to `localStorage` / `sessionStorage`). A browser refresh resets it to the initial entry derived from the current URL. Persisting would conflict with the artifact-version-binding semantics from architecture.md:512-515 (stale decisions reference stale state — a stale stack would re-navigate the user to a context that no longer makes sense).

4. **Given** `src/components/feedback/states/` (NEW directory — architecture.md:1182 sanctioned), **Then** three typed state components exist with discriminated-union variant props:
   - (a) **`<EmptyState variant="queue|filtered|artifactNotGenerated|noOpenQuestions|noMeaningfulDiff" message={ReactNode} action={ReactNode | undefined}>`** — the `variant` field is a TS string-literal union; `message` is the explanation; `action` is OPTIONAL (empty states may stand alone — see AC6 for the contrast with `<ErrorState>` which ALWAYS requires `nextAction`). Variant copy + iconography defaults live in `src/components/feedback/states/EmptyState.tsx` next to the component; consumers override `message` per call site.
   - (b) **`<LoadingState variant="fetchingData|generatingArtifact|rebuildingAfterRejection|retryingRecovery" message?={ReactNode}>`** — `variant` enforces the 4-meaning split per UX-DR17. `message` is optional; the variant has a sensible default ("Loading workflow data…", "Generating artifact…", etc.) per UX "Loading state should indicate whether the system is fetching / generating / rebuilding / retrying." Renders `<output role="status" aria-live="polite">` so screen readers announce when loading materially affects interaction (UX 1896, AC9).
   - (c) **`<ErrorState variant="failedRetrieval|unavailableDiffBaseline|permissionRestricted|blockedByStaleState" message={ReactNode} nextAction={NextAction}>`** — `nextAction` is a discriminated union `NextAction = Retry | Refresh | NavigateBack | ContactSupport | DocsLink`:
     - `Retry = { kind: 'Retry'; onRetry: () => void; label?: string }` (default label "Try again");
     - `Refresh = { kind: 'Refresh'; onRefresh: () => void; label?: string }` (default label "Refresh");
     - `NavigateBack = { kind: 'NavigateBack'; label?: string }` (default label "Back to previous view"; consumes `useReturnToRunContext()` internally — Trap T9 — the consumer does not pass a callback);
     - `ContactSupport = { kind: 'ContactSupport'; href?: string; label?: string }` (default label "Get help"; default `href` resolves from a `VITE_SUPPORT_URL` env var if present, otherwise the action button is rendered as a disabled placeholder — Trap T10);
     - `DocsLink = { kind: 'DocsLink'; href: string; label?: string }` (default label "View docs"; `href` REQUIRED — typically a route-internal anchor or an external docs URL whitelisted via the URL-scheme validator from story 2.24's `validateUrlScheme`).
   - (d) All three components ship from `src/components/feedback/states/index.ts` (barrel) + `src/components/feedback/index.ts` (re-export so `@/components/feedback` is the consumer-facing import path matching the architecture layout convention).
   - (e) Component layout uses `Stack` + `cn()` + the existing semantic tokens (`bg-state-error-subtle`, `text-state-error-foreground`, `border-state-error`, etc.). Iconography uses `lucide-react` (already in dep tree — `DeadEndState.tsx` template). Icons are paired with text and `aria-hidden`; never icon-alone (UX 1898, AC9).

5. **Given** UX-DR17 rule "empty states should distinguish: no runs / no results after filtering / no artifact generated yet / no open questions / no meaningful diff", **Then** the `<EmptyState>` variant union enforces this 5-way distinction at the TypeScript level. **Exhaustiveness check**: a private `assertNeverEmptyVariant(variant: never)` function lives in the component's switch tail so that adding a 6th variant to the union without adding a corresponding `case` in the switch fails `tsc --noEmit` (mirrors the pattern used in `state-signifiers.ts` for the workflow-state signifier registry). A focused unit test asserts the exhaustiveness check holds (introduces a `// @ts-expect-error` line that intentionally breaks the union → asserts the build error type).

6. **Given** UX-DR17 rule "every error state should provide the next safe action where possible", **Then** the `<ErrorState>` component **requires** the `nextAction` prop (TypeScript-required, no default) — there is no path through the component that renders without an action. A component test (RTL) renders each of the 4 error variants × each of the 5 `nextAction` kinds (Retry / Refresh / NavigateBack / ContactSupport / DocsLink) and asserts the action button is present, focusable, and triggers the documented callback or navigation. The `permissionRestricted` variant gets a `ContactSupport` nextAction by convention (Trap T7 reinforces the audit-only role labeling — the copy must read "your recorded role is not associated with this view; access decisions are made by the backend, not here" matching the existing `PermissionRestrictedState` from `DeadEndState.tsx`).

7. **Given** UX-DR17 rule "loading states should indicate whether the system is fetching / generating / rebuilding / retrying", **Then** the `<LoadingState>` `variant` prop is REQUIRED and matches one of the four meanings. **NEW ESLint rule** `local-rules/no-untyped-loading-state` (registered in `tools/eslint-rules/index.js` as the 4th project rule, wired in `eslint.config.js` at `error` for `src/**/*.{ts,tsx}`):
   - (a) Flags JSX usage of `<LoadingState />` without a `variant` prop.
   - (b) Flags JSX usage of any element matching `<*Spinner*>` or className containing `animate-spin` OUTSIDE `src/components/feedback/states/` and `src/components/ui/` (the trusted boundary — the LoadingState component itself uses an inner spinner; shadcn UI primitives may have intrinsic spinners). Suggested fix: import `<LoadingState>`.
   - (c) RuleTester test in `tools/eslint-rules/__tests__/no-untyped-loading-state.test.js` extends the existing `lint:rules-test` script (per the 2-31 pattern — add the file path to the script's argument list).

8. **Given** UX-DR17 rule "empty, loading, and error states should appear inside the affected region, not only globally", **Then**:
   - (a) The three components are sized to fit their parent container — no `fixed` / `absolute` positioning, no viewport-relative units. They render inline inside whatever region embeds them (the queue list, the artifact pane, the clarification sidebar, the run-context strip).
   - (b) A global app-level error overlay is reserved for **catastrophic** auth/network failures only and lives in `src/lib/navigation/CatastrophicErrorOverlay.tsx` — NEW component mounted ONCE in `src/App.tsx` (NOT inside `AppShell` — Trap T11 — a shell crash must still surface the overlay) outside the router subtree. The overlay subscribes to a NEW `useCatastrophicError()` hook backed by a NEW `CatastrophicErrorContext` provider also at `src/App.tsx`. The provider exposes `signalCatastrophic(error: Error)` for the global error boundary (TanStack Router's `errorComponent` at the root, plus an outer `<ErrorBoundary>` from `react-error-boundary` — NEW dep, see OQ-5) to call. The overlay uses `role="alertdialog"` with focus trap + `<button onClick>` to dismiss / reload.
   - (c) The shell's existing `<aside>` reserved-placement seam from story 2.7 (its `data-catastrophic-overlay-mount` attribute, if any — verify) is NOT used; the overlay portal targets `document.body`. Document the override in `LAYOUT.md` (Task 9) so the 2-7 seam is explicitly closed by this story.

9. **Given** accessibility per story 2.25, **Then**:
   - (a) `<LoadingState>` uses `role="status"` + `aria-live="polite"` (announce when loading materially affects interaction, UX 1896).
   - (b) `<ErrorState>` uses `role="alert"` for ACTIVE errors (the user just triggered an action that failed); for PASSIVE error states (the page loaded into an error) the underlying `<Alert>` shadcn primitive's default role (`role="alert"`) is used. Distinguish via a NEW `urgency?: 'active' | 'passive'` prop (default `'passive'`) — `active` adds `aria-live="assertive"`, `passive` uses `aria-live="polite"` (Trap T13 — assertive announcements interrupt screen-reader speech; reserve them for user-action failures).
   - (c) `<EmptyState>` uses no live region (UX 1874 "critical errors should be stronger than benign empty states" → empty states are benign by definition).
   - (d) State messages do NOT depend on color or icon alone — every variant pairs a `<h2>` / `<p>` text label with the iconography (UX 1898, AC9).
   - (e) Focus management: when an `<ErrorState urgency="active">` mounts, focus moves to the action button (the `nextAction` button) so keyboard users can immediately act. When a `<LoadingState>` mounts, focus is NOT moved (would be disruptive); when the loading resolves and a new region renders, focus follows the standard TanStack Router scroll-restoration pattern. The `CatastrophicErrorOverlay` traps focus per `role="alertdialog"` semantics.
   - (f) `<CatastrophicErrorOverlay>` and `<ErrorState urgency="active">` are accompanied by per-component RTL + `axe-core` (already in dev tree via story 2.31's lint baseline) tests asserting zero violations on each variant. The full WCAG 2.1 AA audit (axe sweep + manual screen-reader checklist) is story 2.25.

10. **Given** UX-DR16 rule "compare and clarification interactions should preserve the current artifact context and not disorient the user", **Then** the navigation library exposes a `withRunContext(componentOrChildren)` HOC + `useRunContextPreservation()` hook pattern:
    - (a) NEW component `<RunContextBoundary>` (`src/lib/navigation/RunContextBoundary.tsx`) wraps any sub-state navigation entry point (entering Compare Mode, entering a Clarification deep-link). On mount, it captures `{ runId, artifactId?, clarificationId?, scrollY: window.scrollY, mainPaneScrollTop: <main>.scrollTop }` and stores it in a NEW `RunContextSnapshotContext` provided at the `__root.tsx` level. On unmount (the user exits compare / clarification), the boundary restores `scrollY` + `mainPaneScrollTop` via a `useLayoutEffect` (NOT `useEffect` — paint must happen with the restored scroll, not after a flash — Trap T12).
    - (b) Tests assert: enter Compare Mode (mocked — Compare Mode is Epic 4; the test uses a stub component that pretends to be Compare Mode and renders inside a `<RunContextBoundary>`) → scroll the parent run-detail to 250px → exit → assert `scrollY === 250` + the `<main>` element's scrollTop is restored + the artifact selection (a separate stub) is unchanged.
    - (c) The HOC variant `withRunContext(Component)` wraps a component declaratively for callers that prefer composition over render-prop:
      ```tsx
      const CompareModeWithContext = withRunContext(CompareMode);
      // ...
      <CompareModeWithContext />  // implicit <RunContextBoundary> wrap
      ```
    - (d) `RunContextBoundary` does NOT interact with the breadcrumb stack from AC3 — the two are orthogonal. The breadcrumb stack tracks "what page was I on?"; the boundary tracks "what scroll/selection state was I in?". A future composite may use both (Trap T14).

11. **Given** component test coverage, **Then** tests under `src/lib/navigation/__tests__/` + `src/components/feedback/states/__tests__/` cover:
    - (a) **`useReturnToRunContext` happy path** — push 3 entries (`queue`, `runDetail`, `artifact`), call the hook, assert navigation lands on the `runDetail` entry's runId.
    - (b) **`useReturnToRunContext` fallback** — empty stack → navigation lands on `/workflows`.
    - (c) **`useReturnToRunContext` skips non-meaningful** — push (`runDetail`, `recoveryDeepDive`, `artifact`) — calling from the artifact view returns to the `runDetail` entry (the `recoveryDeepDive` is skipped per the `runDetail|artifact|clarification` filter in AC3.e).
    - (d) **`useNavigateToArtifact` invalid id** — pass an `art_xxx` that fails `isValidArtifactId` → assert `InvalidNavigationTargetError` thrown synchronously on hook call (NOT on the callback invocation — the validity check is at hook-mount time per AC1).
    - (e) **`useNavigateToClarification`** — passes `?clarificationId=cla_xxx` as the `search` param, NOT a hash anchor.
    - (f) **Breadcrumb dedup** — re-render `<NavigationBreadcrumbProvider>` over the same route → assert the stack length stays 1.
    - (g) **Breadcrumb cap** — push 18 entries → assert `stack.length === 16` and the oldest two are dropped (FIFO).
    - (h) **`useAssertRunContextLoaded` happy path** — MSW seeds `useWorkflowDetail` + `useAllowedActions` to resolve → hook returns `kind: 'loaded'` with the typed payload.
    - (i) **`useAssertRunContextLoaded` 404** — MSW seeds `useWorkflowDetail` with a `RUN_NOT_FOUND` Problem Details → hook returns `kind: 'runNotFound'`.
    - (j) **`<EmptyState>` exhaustiveness** — TS-level: a fixture file imports the variant union, the test asserts via `// @ts-expect-error` that an off-union value is a build error.
    - (k) **`<EmptyState>` renders each of 5 variants** — RTL: assert the correct message + icon + (when `action` provided) action button.
    - (l) **`<LoadingState>` renders each of 4 variants** — RTL: assert `role="status"` + `aria-live="polite"` + default message.
    - (m) **`<ErrorState>` requires `nextAction`** — TS-level: omitting `nextAction` fails the build (the test fixture uses `// @ts-expect-error`).
    - (n) **`<ErrorState>` renders each variant × each `nextAction` kind** — 4 × 5 = 20 cases. Assert action button is focusable + triggers the documented callback.
    - (o) **`<ErrorState urgency="active">` focus-moves-to-action** — RTL: render with `urgency="active"`; assert `document.activeElement === actionButton`.
    - (p) **`<ErrorState urgency="active">` uses `aria-live="assertive"`; `urgency="passive"` uses `aria-live="polite"`.**
    - (q) **`<CatastrophicErrorOverlay>` mounts on `signalCatastrophic`** — RTL with a wrapper that calls the hook → assert overlay renders + focus is trapped + dismiss button restores focus to the previously-focused element.
    - (r) **`<RunContextBoundary>` scroll restoration** — JSDOM has limited layout, but `window.scrollY` is patchable + `<main>.scrollTop` is patchable; assert both restore on unmount.
    - (s) **`withRunContext` HOC composition** — passes children through + wraps in the boundary.
    - (t) **ESLint rule `no-untyped-loading-state`** — RuleTester: valid case (variant present); invalid case (variant missing — fix suggestion); invalid case (`animate-spin` outside the trusted boundary).
    - (u) **`DeadEndState.tsx` migration regression** — each of the 8 dead-end states (`InvalidLinkState`, `RunNotFoundState`, `ArtifactNotFoundState`, `PageNotFoundState`, `GenericErrorState`, `UnrecognizedRunStateState`, `UnrenderableArtifactState`, `PermissionRestrictedState`) now renders the new `<EmptyState>` or `<ErrorState>` shell — assert the previously-tested text content is still present (no copy regression — Trap T15) AND the new state-component a11y baseline applies (role / aria-live per AC9).
    - (v) **AC8 (a) inline rendering** — `<EmptyState>` / `<LoadingState>` / `<ErrorState>` mounted inside a fixed-size `<div style="width:400px">` parent → assert no `position: fixed` styles + the component does not overflow the parent's bounding box (via getBoundingClientRect — JSDOM returns 0s but the structural-class assertion holds via `expect(element).not.toHaveClass(/fixed|absolute/)`).
    - (w) **axe-core no-violations** — each variant of `<EmptyState>` / `<LoadingState>` / `<ErrorState>` (urgency `active` + `passive`) passes axe-core (the dep already lands via story 2.31's lint baseline; the runtime axe assertion is a `vitest-axe` or `@axe-core/react` invocation per the documented test pattern).

## Scope Guardrail

This story owns:

- the `src/lib/navigation/` library (3 hooks + 2 contexts + 1 boundary + 1 catastrophic-overlay + 1 README + types module),
- the `src/components/feedback/states/` primitives (`EmptyState`, `LoadingState`, `ErrorState`, barrel),
- the NEW `isValidClarificationId` helper in `src/lib/routing/publicId.{js,d.ts}` (mirrors `isValidRunId` shape),
- the NEW `InvalidNavigationTargetError` typed error,
- the NEW ESLint rule `local-rules/no-untyped-loading-state` + its RuleTester test + the npm-script extension,
- the migration of `src/routes/-states/DeadEndState.tsx` to consume the new state components (closes the existing SEAM at line 32-33),
- the `useAssertRunContextLoaded` discriminated-union guard,
- one new dep `react-error-boundary` (OQ-5 recommended) — pinned in `package.json` + reconciled via `.npmrc legacy-peer-deps=true` per memory `frontend-ts6-legacy-peer-deps.md`.
- the focused test surface listed in AC11.

This story does **NOT** own:

- The Run Context Strip (story 2.16), Queue Item (2.15), Queue States (2.20), Artifact Review Panel (2.17), Clarification Region (2.18), Decision Bar (2.19), Modal patterns (2.23), Feedback Patterns dispatcher (2.21) — those are downstream consumers.
- Compare Mode (Epic 4 — 4.19 / 4.20).
- The full WCAG 2.1 AA audit + axe sweep (story 2.25). This story ships baseline a11y correctness per-variant; the comprehensive sweep is 2.25.
- The full responsive matrix (story 2.26). State components are sized to fit; full responsive collapse rules are 2.26.
- Backend changes (the catastrophic-error overlay surfaces ANY thrown Error — including problemDetails network errors — but the backend's typed-error model is unchanged).
- TanStack Router route additions. The library + components are additions; no `routes/**` file is added.
- The toast / inline-feedback / persistent-banner dispatcher (story 2.21 — epic-2b). `<ErrorState>` renders inline. `sonner` is wired in `AppShell` for 2.21's later use, but this story does NOT route any error through it.

## Tasks / Subtasks

- [x] **Task 1: Scaffold `src/lib/navigation/` library** (AC: 1, 2, 3, 10)
  - [x] Create directory `src/lib/navigation/`. Add `README.md` documenting the library's three sub-areas (typed nav helpers / breadcrumb stack / context-snapshot boundary) and the `useAssertRunContextLoaded` discriminated-union convention. Doc must reference `LAYOUT.md` from story 2.7 (Task 9 update). *(dir created; README authored in Task 9 — references LAYOUT.md §4)*
  - [x] Add `types.ts` exporting `BreadcrumbEntry`, `BreadcrumbKind`, `NextAction` discriminated union, `RunContextSnapshot`, `InvalidNavigationTargetError`, `MAX_BREADCRUMB_STACK_DEPTH = 16`.
  - [x] Add `useReturnToRunContext.ts` — stack walk-back (skips current top) + queue fallback per AC1 + AC3.e; `useCallback`-wrapped (Trap T1).
  - [x] Add `useNavigateToArtifact.ts` — synchronous `isValidRunId` + `isValidArtifactId` at hook body; throws `InvalidNavigationTargetError` (Trap T6). Hooks run before the throw (rules-of-hooks).
  - [x] Add `useNavigateToClarification.ts` — navigates to `/workflows/$workflowRunId` with `search: { clarificationId }`; route `search` schema extended via `validateSearch` in `routes/workflows/$workflowRunId/index.tsx`.
  - [x] Add `isValidClarificationId` + `CLARIFICATION_ID_PATTERN` to `src/lib/routing/publicId.{js,d.ts}`.

- [x] **Task 2: Per-session breadcrumb stack** (AC: 3)
  - [x] Add breadcrumb context (`NavigationBreadcrumbContext.ts`) + provider (`NavigationBreadcrumbProvider.tsx`) + pure reducer (`breadcrumbReducer.ts`, extracted for isolated tests + Fast-Refresh cleanliness) — `useReducer`-backed, dedup-on-kind+ids (Trap T5) + 16-entry FIFO cap. *(Split into 3 files vs the story's single `.tsx`, matching the repo's `AppShellContext` precedent so `react-refresh/only-export-components` passes at `--max-warnings=0`.)*
  - [x] Add `useNavigationBreadcrumb.ts` — typed hook, throws outside provider.
  - [x] Add `useBreadcrumbAutoTrack.ts` — subscribes to router location, classifies kind via `useMatches()`, pushes/replaces. *(Fires only on real route change + reducer short-circuits when kind+ids match — review-hardened.)*
  - [x] Mount `<NavigationBreadcrumbProvider>` in `src/App.tsx` + `<NavigationBreadcrumbTracker />` in `__root.tsx`.

- [x] **Task 3: Run-context preservation boundary** (AC: 10)
  - [x] Add `RunContextSnapshotContext.ts` + `RunContextSnapshotProvider.tsx` (context exposes the most recent snapshot; the BOUNDARY captures it). *(Split context/provider for Fast-Refresh cleanliness, matching the breadcrumb precedent.)*
  - [x] Add `RunContextBoundary.tsx` — captures `{ runId, artifactId?, clarificationId?, scrollY: window.scrollY, mainPaneScrollTop: document.getElementById('main-content')?.scrollTop ?? 0 }` on MOUNT via `useLayoutEffect`; on UNMOUNT restores via `useLayoutEffect` cleanup (returned function in the `useLayoutEffect`). The `<main id="main-content">` selector matches the constant `MAIN_CONTENT_ID` from story 2.7's `AppShell.tsx`.
  - [x] Add `withRunContext.tsx` HOC — returns `props => <RunContextBoundary><Component {...props} /></RunContextBoundary>` (forwards full props — review-hardened).
  - [x] Add `useRunContextSnapshot.ts` hook for consumers who want to read (not capture) the most recent snapshot (e.g., the Clarification Region in 2.18 may want to know the parent artifact id). *(Mount-time snapshot only; re-capture-on-identity-change deferred — no consumer until 2.18.)*

- [x] **Task 4: `<EmptyState>`, `<LoadingState>`, `<ErrorState>` primitives** (AC: 4, 5, 6, 7, 9) — VERIFIED GREEN (19 tests)
  - [x] Create `src/components/feedback/` + `feedback/states/`.
  - [x] `EmptyState.tsx` — variant union + `assertNeverEmptyVariant` switch-tail (AC5); Stack + real state tokens + lucide; no live region (AC9.c); icon aria-hidden + h2 + text (AC9.d).
  - [x] `LoadingState.tsx` — `<output role="status" aria-live="polite">` (AC9.a); `Loader2 + animate-spin` (self-exempt boundary for the Task-7 rule, Trap T16).
  - [x] `ErrorState.tsx` — `variant` + REQUIRED `nextAction`; composes `<Alert>`; `urgency` drives `aria-live` (AC9.b/T13); `NavigateBack` consumes `useReturnToRunContext()` (T9); `ContactSupport` disabled when no URL (T10); `DocsLink`/`ContactSupport` href validated via `validateUrlScheme`; active-mount focus + `state.activeError` log (AC9.e).
  - [x] `feedback/states/index.ts` + `feedback/index.ts` barrels.
  - [x] Active-urgency focus-to-action (AC11.o) — verified.
  - [x] Default copy authored; `permissionRestricted` default preserves the audit-only "access decisions are made by the backend, not here" language (T7). DeadEndState migration (Task 8) passes explicit verbatim copy.

- [x] **Task 5: `useAssertRunContextLoaded` guard hook** (AC: 2)
  - [x] Add `src/lib/navigation/guards.ts` exporting `useAssertRunContextLoaded(runId): { kind: 'loading' } | { kind: 'loaded'; detail: WorkflowDetail; actions: AllowedActions } | { kind: 'runNotFound' } | { kind: 'error'; error: Error; refetch: () => Promise<unknown> }`. *(`loaded.actions` typed `unknown` pending story 2.14's `AllowedActions` type + un-stubbing of `useAllowedActions` — deferred, forward-correct.)*
  - [x] Implementation: calls `useWorkflowDetail(runId)` + `useAllowedActions(runId)` (story 2.6 + 2.14 hooks). Classifies via `isProblemDetailsError` from `src/lib/api/problemDetails.ts` for the `RUN_NOT_FOUND` case (existing pattern from `routes/workflows/$workflowRunId/index.tsx:77`). Combines `isPending` + `isError` from both queries.
  - [x] The `refetch` callback in the `error` case wraps `Promise.all([detail.refetch(), actions.refetch()])` so consumers can wire it into `<ErrorState nextAction={{ kind: 'Retry', onRetry: refetch }}>`.
  - [x] **Trap T7** — the hook returns the loaded actions to the consumer; it does NOT branch on whether a specific action is enabled. Action enabling is the consumer's responsibility (typically the Decision Bar in story 2.19 / 3.28).

- [x] **Task 6: `CatastrophicErrorOverlay` + global error boundary** (AC: 8)
  - [x] Add dep `react-error-boundary` (`^4.1.2`) to `deliveryline-frontend/package.json`. Regenerate `package-lock.json` per memory `frontend-lockfile-cross-platform.md` (full `npm install` on Linux/WSL2; verify the lockfile change on Linux before pushing). The `.npmrc legacy-peer-deps=true` from memory `frontend-ts6-legacy-peer-deps.md` is already present and continues to apply.
  - [x] Add `src/lib/navigation/CatastrophicErrorContext.ts` (context) + `CatastrophicErrorProvider.tsx` + `useCatastrophicError.ts`. Provider exposes `{ signalCatastrophic(error: Error): void; activeError: Error | null; dismiss(): void }`. *(Split context/provider/hook for Fast-Refresh cleanliness.)*
  - [x] Add `src/lib/navigation/CatastrophicErrorOverlay.tsx` — `role="alertdialog"` with focus trap. Renders inside `createPortal(..., document.body)` outside the router subtree (Trap T11).
  - [x] Mount `<CatastrophicErrorBoundary>` + `<CatastrophicErrorProvider>` at `src/App.tsx`, wrapping the `<RouterProvider>`. The boundary uses `react-error-boundary` with `resetKeys`/`onReset` so Dismiss re-mounts the router subtree (review-hardened).
  - [x] Reserve/close the global catastrophic-overlay seam in `src/features/workflows/LAYOUT.md` (Task 9 documentation update — the 2.7 seam is CLOSED, overlay mounts in `App.tsx`, AC8.c).

- [x] **Task 7: ESLint rule `local-rules/no-untyped-loading-state`** (AC: 7)
  - [x] Add `tools/eslint-rules/no-untyped-loading-state.js` modeled on the existing `no-unsanitized-html.js` (handles `JSXSpreadAttribute` variant + namespaced/member-expression JSX spinners — review-hardened):
    - Detects JSX `<LoadingState>` without a `variant` attribute (`JSXOpeningElement` with `name.name === 'LoadingState'` and no `attributes.find(a => a.name?.name === 'variant')`).
    - Detects JSX elements whose name ends in `Spinner` OR whose `className` attribute (string literal or template literal) contains `animate-spin`, OUTSIDE the trusted boundary `src/components/feedback/states/**` and `src/components/ui/**` (the file URL is checked via `context.physicalFilename` — same approach `no-unsanitized-html.js` uses for the `src/lib/sanitization/**` exemption).
    - Suggested fix: import `<LoadingState>` from `@/components/feedback`. (Suggestion text only — no auto-fix; the variant choice is human-judgment per UX-DR17.)
  - [x] Register the rule in `tools/eslint-rules/index.js` (alongside the 3 existing rules).
  - [x] Wire `'local-rules/no-untyped-loading-state': 'error'` in `eslint.config.js` at the same scope as the other 3 rules (the `src/**/*.{ts,tsx}` block — verify by reading the config).
  - [x] Add `tools/eslint-rules/__tests__/no-untyped-loading-state.test.js` modeled on `no-unsanitized-html.test.js` (Node `--test` + `RuleTester`):
    - Valid: `<LoadingState variant="fetchingData" />`.
    - Valid: `<Spinner />` inside `src/components/feedback/states/LoadingState.tsx` (trusted boundary).
    - Invalid: `<LoadingState />` (missing variant).
    - Invalid: `<div className="animate-spin" />` in `src/features/workflows/Foo.tsx` (outside trusted boundary).
  - [x] Extend the `lint:rules-test` npm script in `package.json` with the new test file path (mirrors how `no-unsanitized-html.test.js` was added in story 2.24).

- [x] **Task 8: Migrate `DeadEndState.tsx` to consume the new primitives** (AC: 4, 6, 9)
  - [x] Read each of the 8 existing dead-end states (`InvalidLinkState`, `RunNotFoundState`, `ArtifactNotFoundState`, `PageNotFoundState`, `GenericErrorState`, `UnrecognizedRunStateState`, `UnrenderableArtifactState`, `PermissionRestrictedState`). Map each to the appropriate new primitive:
    - `InvalidLinkState` → `<ErrorState variant="failedRetrieval" nextAction={{ kind: 'NavigateBack' }} message="...">`.
    - `RunNotFoundState` → `<ErrorState variant="failedRetrieval" nextAction={{ kind: 'NavigateBack' }} message="...">` (the existing copy mentions retention; preserve it).
    - `ArtifactNotFoundState` → same as `RunNotFoundState`.
    - `PageNotFoundState` → `<EmptyState variant="filtered" message="..." action={<Link to="/workflows">Go to run queue</Link>}>`.
    - `GenericErrorState` → `<ErrorState variant="failedRetrieval" urgency="active" nextAction={{ kind: 'Refresh', onRefresh: () => window.location.reload() }} message="...">`.
    - `UnrecognizedRunStateState` → `<EmptyState variant="filtered" ... action={<Link to="/workflows">Back to queue</Link>}>` (the copy explicitly says "Nothing about the run has changed" — preserves the artifact-primacy-via-no-disruption posture).
    - `UnrenderableArtifactState` → same — empty state, NOT error (the artifact exists, just not viewable in this build).
    - `PermissionRestrictedState` → `<ErrorState variant="permissionRestricted" urgency="passive" nextAction={{ kind: 'ContactSupport' }} message="...">` — Trap T7 — copy MUST preserve the existing "your recorded role is not associated with this view; this is an informational signal based on recorded role context — it isn't a security control, and access decisions are made by the backend, not here" language.
  - [x] Update the `// SEAM (story 2.22)` comment at line 32-33 to a closing comment: `// CLOSED 2026-05-28 by story 2.22 — these states now compose the shared feedback/states primitives.`
  - [x] Add regression tests per AC11.u — each of the 8 functions still renders the same testable text content (the copy is preserved) AND the new state-component a11y baseline applies.

- [x] **Task 9: Documentation updates** (AC: 1, 2, 3, 8, 10)
  - [x] Create `src/lib/navigation/README.md` documenting:
    - The three sub-areas (typed nav helpers / breadcrumb stack / context-snapshot boundary).
    - The `useAssertRunContextLoaded` discriminated-union convention.
    - The `NavigationBreadcrumb` provider placement (in `src/App.tsx`) and dedup + cap rules.
    - The catastrophic-overlay portal-to-`document.body` decision (closes the story-2.7 reserved seam).
    - The 2.22↔2.21 split note: this story renders errors INLINE; the toast/inline/persistent dispatcher (2.21) is later.
    - Migration recipe for future consumers: "import `{ EmptyState, LoadingState, ErrorState }` from `@/components/feedback`; never write a generic spinner."
  - [x] Update `src/features/workflows/LAYOUT.md` (story 2.7's ADR) with a new section "Catastrophic-error overlay (closed by 2.22)" referencing `CatastrophicErrorOverlay.tsx`. Replaced the existing "SEAM" reference (LAYOUT.md §4 + the `AppShell.tsx` comment now read `SEAM CLOSED`).
  - [x] Update `src/routes/-states/DeadEndState.tsx` doc comment at line 32-33 (handled in Task 8).
  - [x] Add `src/components/feedback/README.md` (NEW) — short doc cross-referencing the architecture line 1182 ("Frontend empty, stale, conflict, no-actions, and failed-load states live under `components/feedback`") and pointing to `states/`.

- [x] **Task 10: Test surface** (AC: 11) — *(nav-hook tests consolidated into `navigationHooks.test.tsx` per memory `vitest-cross-file-router-mock.md`; `useAssertRunContextLoaded` test → `guards.test.tsx`. AC11.w axe deferred — see below.)*
  - [x] Unit + RTL tests under `src/lib/navigation/__tests__/`:
    - `useReturnToRunContext.test.tsx` (AC11.a–c).
    - `useNavigateToArtifact.test.tsx` (AC11.d).
    - `useNavigateToClarification.test.tsx` (AC11.e).
    - `NavigationBreadcrumbContext.test.tsx` (AC11.f, g).
    - `useAssertRunContextLoaded.test.tsx` (AC11.h, i) — uses MSW from the existing `src/test/server.ts`.
    - `RunContextBoundary.test.tsx` (AC11.r).
    - `withRunContext.test.tsx` (AC11.s).
    - `CatastrophicErrorOverlay.test.tsx` (AC11.q).
  - [x] Unit + RTL tests under `src/components/feedback/states/__tests__/`:
    - `EmptyState.test.tsx` (AC11.j, k).
    - `LoadingState.test.tsx` (AC11.l).
    - `ErrorState.test.tsx` (AC11.m, n, o, p).
    - `inline-rendering.test.tsx` (AC11.v).
    - ~~`axe-violations.test.tsx` (AC11.w)~~ — **DEFERRED to story 2.25**: no axe dep (`vitest-axe` / `@axe-core/react`) in the tree; the full WCAG 2.1 AA + axe sweep is story 2.25's scope (see Scope Guardrail). Per-variant role/aria-live a11y baseline IS covered by the RTL tests above.
  - [x] Test under `tools/eslint-rules/__tests__/no-untyped-loading-state.test.js` (AC11.t) — Node `--test` + `RuleTester`.
  - [x] Test under `src/routes/-states/__tests__/DeadEndState.test.tsx` (NEW — story 2.5 did NOT ship one) covering AC11.u — each of the 8 dead-end states preserves its copy and adopts the new a11y baseline (asserts `aria-live` value, not just presence — review-hardened).
  - [x] Update `vitest.config.ts` ONLY if a new test setup file is needed (no change required — the existing `src/test/setup.ts` + `src/test/server.ts` suffice).

- [x] **Logging / observability instrumentation** (cross-cutting; required on every story)
  - [x] **This is a frontend infrastructure story — no SLF4J / no backend logs.** The cross-cutting logging-instrumentation task adapts to frontend per the architectural intent: every typed error path emits a structured observability signal to the console at the appropriate level.
    - `useReturnToRunContext` fallback-to-queue path → `console.debug('navigation: returnToRunContext fallback to /workflows reason=empty_stack')` so QA can diagnose "the back button went to the queue" reports.
    - `useNavigateToArtifact` / `useNavigateToClarification` invalid-id throws → `console.warn('navigation: invalid id', { runId, artifactId, reason })` BEFORE the throw so the validation failure is auditable even if the consumer swallows the error.
    - `<NavigationBreadcrumbProvider>` push of an unknown kind (defensive) → `console.warn(...)`.
    - `<CatastrophicErrorOverlay>` mount → `console.error(...)` with the error.message + the route at time of failure (read from `useLocation()` at the boundary). Pinned by AC11.q.
    - `<ErrorState>` mount with `urgency="active"` → `console.warn(...)` with the variant + the page route, so QA can correlate user-reported errors to the in-app event.
  - [x] All log payloads are typed (`{ event: 'navigation.fallback' | 'navigation.invalidId' | 'breadcrumb.unknownKind' | 'errorBoundary.catastrophic' | 'state.activeError'; ... }`).
  - [x] **Forbidden** in log output: untrusted runner-output strings (they could contain redacted-but-still-not-shareable content). When an `<ErrorState>` message is derived from a runner-output string, the log emits only the variant + the rendered length, NOT the message content (mirrors the backend `answerTextLength` pattern from story 2.13 round 4).
  - [x] No test contract on the console logs (frontend logging is not test-pinned — the existing project pattern from 2-7/2-13 doesn't pin frontend console output). The pattern is documentation-enforced via the README.

## Dev Notes

### Architectural anchors (DO NOT REINVENT)

- **`AppShell` (story 2.7)** is the structural host. This story does NOT touch `AppShell.tsx`. The catastrophic-error overlay mounts OUTSIDE the shell (in `App.tsx`) — Trap T11. The story-2.7 LAYOUT.md is updated with a documentation note (Task 9), not a code change.
- **`useWorkflowDetail` + `useAllowedActions` (stories 2.6 + 2.14)** are the data inputs to `useAssertRunContextLoaded`. The hook composes them; it does NOT introduce a new `useQuery`. Query keys come from `workflowKeys.*` — the existing `local-rules/no-inline-query-keys` ESLint rule enforces this.
- **TanStack Router** is the navigation source of truth. The navigation library wraps `useNavigate()` / `useRouterState()` / `useMatches()` / `useLocation()`; it NEVER reaches for `window.history` (Trap T2). The router's typed `params` + `search` are the only sanctioned data-passing surfaces (AC2.a).
- **`src/lib/routing/publicId.{js,d.ts}`** is the existing public-id validator module. This story EXTENDS it with `isValidClarificationId` (mirroring the existing `isValidRunId` / `isValidArtifactId` shape). Read the existing module before editing.
- **`src/routes/workflows/$workflowRunId/index.tsx`** is the route this story extends with a `search` schema (`clarificationId?: string`). The route's `loader` already validates `params.workflowRunId` via `assertValidRunRouteParams`; the `search` extension follows the same defensive pattern at the route boundary.
- **`src/components/ui/alert.tsx`** is the shadcn primitive `<ErrorState>` composes. Verify its existing props (variant / class) before wrapping.
- **`src/components/ui/dialog.tsx`** is the shadcn primitive `<CatastrophicErrorOverlay>` composes (Radix-backed focus trap).
- **`src/lib/sanitization/`** is the trusted sanitization boundary from story 2.24. When `<ErrorState>` `message` derives from runner output, it MUST route through `SafeMarkdownRenderer` or the plain-text equivalent BEFORE display — Trap T6. The default copy (operator-authored) renders as plain React nodes.
- **`tools/eslint-rules/no-unsanitized-html.js`** (story 2.24) is the template for `no-untyped-loading-state.js` — copy the file-URL exemption pattern + the `context.physicalFilename` check.
- **`tools/eslint-rules/__tests__/no-unsanitized-html.test.js`** (story 2.24) is the test template — `node --test` + `RuleTester`.
- **`DeadEndState.tsx`** (story 2.5) is the migration target — read its 8 states verbatim and preserve every copy string.
- **The frontend's existing `frontend-lockfile-cross-platform.md` + `frontend-ts6-legacy-peer-deps.md` memories** apply to the `react-error-boundary` dep addition.

### Trap registry (sixteen declared traps)

| Trap | Description | How to verify |
| --- | --- | --- |
| **T1** | `useReturnToRunContext` must wrap `useNavigate()` in `useCallback` so memoized children don't re-render. | Test asserts `result.current === previousResult.current` across re-renders with same router state. |
| **T2** | NEVER reach for `window.history`; always go through TanStack Router's typed `useNavigate()` / `useRouterState()`. | Grep: no `window.history` references in `src/lib/navigation/**`. |
| **T3** | `<NavigationBreadcrumbProvider>` lives OUTSIDE the router in `App.tsx`; the auto-tracker lives INSIDE the router in `__root.tsx`. | App.tsx provider wrap → RouterProvider; tracker mounted in `RootLayout`. |
| **T4** | `useReturnToRunContext` empty-stack fallback navigates to `/workflows` via typed router — NEVER calls `window.history.back()`. | Test AC11.b asserts the navigate destination. |
| **T5** | Breadcrumb dedup uses kind + ids equality, NOT path equality (the same path with a different search param is a different entry). | Test AC11.f asserts dedup on identical kind+ids; separate test asserts NO dedup on different search params. |
| **T6** | Untrusted runner-output strings rendered inside `<ErrorState>` MUST route through sanitization. Plain-text copy is the only safe default. | Type system: `message: ReactNode` accepts both; consumer docs in the README make the boundary explicit. |
| **T7** | The `permissionRestricted` variant is an AUDIT-ONLY display label, NOT a security gate. The copy preserves the existing "access decisions are made by the backend, not here" language. | Migration test in AC11.u verifies the literal string. |
| **T8** | `useAllowedActions` (story 2.14) controls real action enabling on Decision Bars; `<ErrorState nextAction={...}>` is a UX hint for after-the-fact recovery. The two are DISTINCT and must not be conflated. | Architecture review of the README; no `useAllowedActions` call inside `<ErrorState>`. |
| **T9** | `NavigateBack` nextAction internally consumes `useReturnToRunContext()` — the consumer does NOT pass a callback. Prevents 5 different "back" implementations across composites. | TypeScript: `NavigateBack` has no `onNavigate` field. |
| **T10** | `ContactSupport` falls back to a disabled placeholder when `VITE_SUPPORT_URL` is unset — never renders a broken link. | Test asserts the disabled state when env is unset. |
| **T11** | `<CatastrophicErrorOverlay>` mounts via `createPortal(..., document.body)`, OUTSIDE the AppShell subtree. A shell crash still surfaces the overlay. | RTL: the rendered overlay does not appear inside the `AppShell` DOM subtree. |
| **T12** | `RunContextBoundary` restores scroll in `useLayoutEffect` cleanup, NOT `useEffect`. A `useEffect` would paint at scroll=0 before restoring, causing a visible flash. | Code review + the AC11.r scroll-restoration test. |
| **T13** | `aria-live="assertive"` is reserved for `urgency="active"` (user-triggered errors). `aria-live="polite"` for passive errors. Assertive interrupts screen-reader speech; the distinction is non-negotiable. | Test AC11.p asserts both aria-live attribute values. |
| **T14** | `RunContextBoundary` and `NavigationBreadcrumbContext` are ORTHOGONAL — boundary tracks scroll/selection; breadcrumb tracks pages. Future composites may use both but never confuse them. | README cross-references the two contexts explicitly. |
| **T15** | The `DeadEndState.tsx` migration MUST preserve every existing copy string verbatim — no copywriting drift via the migration. | AC11.u regression tests assert the literal strings. |
| **T16** | The `no-untyped-loading-state` rule SELF-EXEMPTS inside `src/components/feedback/states/**` (the trusted boundary) AND `src/components/ui/**` (shadcn primitives) — same exemption pattern as `no-unsanitized-html` self-exempts inside `src/lib/sanitization/**`. | RuleTester valid case inside the trusted boundary; invalid case outside. |

### Open Questions (recommendations included; surface for explicit sign-off)

- **OQ-1 — Where does the breadcrumb provider live: `App.tsx` or `__root.tsx`?** **Recommend: `App.tsx`** (outside the router). Rationale: the router subscribes to the breadcrumb (via the auto-tracker rendered inside `RootLayout`), not the other way around — the breadcrumb survives router re-mounts. Alternative: `__root.tsx` (rejected — re-mount loses the stack).
- **OQ-2 — Use a `useReducer` or `useState` for the breadcrumb stack?** **Recommend: `useReducer`** with explicit `push` / `replaceLast` / `clear` actions. The reducer is testable in isolation (a pure function); the actions document the only legal mutations. Alternative: `useState` (rejected — implicit mutation harder to test + easier to introduce dedup bugs).
- **OQ-3 — Add `react-error-boundary` as a new dep or hand-roll a minimal `<ErrorBoundary>`?** **Recommend: add `react-error-boundary` (~4.1.x)**. The lib is ~3KB gzipped, well-maintained, and matches the React 19+ patterns. Hand-rolling is yak-shaving for one feature. The dep addition triggers the `frontend-lockfile-cross-platform.md` + `frontend-ts6-legacy-peer-deps.md` memory checks at install time.
- **OQ-4 — Should `<EmptyState>` enforce `action` REQUIRED on certain variants (e.g., `queue` should always offer a "create a workflow" action)?** **Recommend: NO** — keep `action` optional per UX-DR17. The queue route in story 2-20 will pass an action when appropriate; the artifact-not-generated case may have no meaningful action (waiting on backend). Making it required would force consumers to invent fake actions.
- **OQ-5 — Should `<ErrorState>` `urgency` default to `passive` or `active`?** **Recommend: `passive`** — most error renders are page-load failures (passive). The user-triggered failure case (a button click that errored) is rarer and the consumer can explicitly opt in to `active`. Defaulting to `assertive` aria-live would create accessibility noise.
- **OQ-6 — Should the `clarificationId` deep-link use `search` params or a hash anchor?** **Recommend: `search` param** (`?clarificationId=cla_xxx`) — TanStack Router's typed search-param scroll-restoration is the canonical primitive. Hash anchors bypass the router's state model. Memory: this also matches the existing pattern in `routes/workflows/$workflowRunId/index.tsx`'s `loader` consuming typed params from the router context.
- **OQ-7 — Cap the breadcrumb stack at 16 or a different number?** **Recommend: 16** — generous enough for a typical session (queue → run → artifact → clarification → recovery → diagnostics → back × 2 → compare → back) without unbounded memory growth. Configurable via `MAX_BREADCRUMB_STACK_DEPTH` if a future story needs to tune.
- **OQ-8 — Should the catastrophic-overlay reset the breadcrumb stack on dismiss?** **Recommend: NO** — preserve the stack so the user can resume after a transient error. The overlay's "Reload" action resets the page (and stack); "Dismiss" preserves both. Document in the README.

### Cross-story dependencies + sequencing

- **Strict prerequisites (already done):**
  - Story 2.5 — TanStack Router setup + `DeadEndState.tsx` (the SEAM this story closes).
  - Story 2.6 — TanStack Query + `useWorkflowDetail` + query-key factory.
  - Story 2.7 — `AppShell` + `<main id="main-content">` (the boundary's scroll-restoration target) + the catastrophic-overlay reserved seam.
  - Story 2.14 — `useAllowedActions` (composed by `useAssertRunContextLoaded`).
  - Story 2.24 — `src/lib/sanitization/` + `validateUrlScheme` (`<ErrorState nextAction={DocsLink}>` href validation per Trap T6 + the `DocsLink` href check).
  - Story 2.31 — `tools/eslint-rules/index.js` + the local-rules plugin pattern (template for the new rule).
- **This story unblocks (ordered per sprint-status active-slice):**
  - **Story 2.20 (Queue shell states)** — consumes `<EmptyState variant="queue">`, `<EmptyState variant="filtered">`, `<LoadingState variant="fetchingData">`, `<ErrorState variant="failedRetrieval">`.
  - **Story 2.16 (Run Context Strip)** — consumes `useReturnToRunContext`, `<LoadingState>`, `<ErrorState>`. May also consume `useAssertRunContextLoaded` for its data-loading guard.
  - **Story 2.15 (Run / Review Queue Item)** — consumes `<EmptyState>`, `<LoadingState>`, `<ErrorState>`. Note: 2.15 depends on 2.24 (already done) for sanitization of any runner-output preview.
  - **Story 2.17 (Artifact Review Panel — generalized + spec variant)** — consumes all three primitives + `useAssertRunContextLoaded` + `useNavigateToArtifact`.
- **This story unblocks (epic-2b, deferred):**
  - **Story 2.18 (Clarification Region)** — consumes `useNavigateToClarification` + `<EmptyState variant="noOpenQuestions">`.
  - **Story 2.19 (Decision Bar)** — consumes `<ErrorState variant="blockedByStaleState">`.
  - **Story 2.21 (Feedback Patterns dispatcher)** — the toast/inline/persistent dispatcher this story's `nextAction` payloads CAN later target.
- **This story unblocks (Epic 3a / 3b):**
  - **Story 3.26 (Artifact Review Panel — implementation-plan variant)** + **3.27 (PR/Output variant)** — consume the same primitives 2.17 does.
  - **Story 3.29 (Developer Takeover UI)** — consumes `<ErrorState>` + breadcrumb stack.
  - **Story 3.30 (UI minimum viable recovery)** — consumes `<LoadingState variant="retryingRecovery">` + `<ErrorState>`.
- **This story unblocks (Epic 4):**
  - **Story 4.4 (Failure-diagnostics deep-dive view)** — consumes breadcrumb (the `recoveryDeepDive` kind exists for this).
  - **Story 4.19 / 4.20 (Compare Mode)** — consumes `withRunContext` HOC to preserve scroll/selection across compare-mode entry/exit.
  - **Story 4.22 (Decision-bar recovery operator mode)** — consumes `<ErrorState nextAction={...}>`.

### Logging Requirements (project-wide standard, adapted to frontend)

Every story is expected to leave the touched code observable enough to diagnose a production incident without re-deploying. For the frontend, this means structured `console.warn` / `console.error` / `console.debug` calls at typed-error boundaries with stable event names.

- **Where to emit (this story's surface):**
  - `useReturnToRunContext` fallback → `console.debug({ event: 'navigation.fallback', reason: 'empty_stack' })`.
  - `useNavigateToArtifact` / `useNavigateToClarification` invalid id → `console.warn({ event: 'navigation.invalidId', kind: 'artifact'|'clarification', runId, artifactId?, clarificationId? })` BEFORE the throw.
  - `<NavigationBreadcrumbProvider>` push of an unknown kind (defensive) → `console.warn({ event: 'breadcrumb.unknownKind', kind })`.
  - `<CatastrophicErrorOverlay>` mount → `console.error({ event: 'errorBoundary.catastrophic', message, route })`.
  - `<ErrorState urgency="active">` mount → `console.warn({ event: 'state.activeError', variant, messageLength })`.
- **Required context keys:** `event` (stable identifier), plus the typed payload per emission. Use object form (`console.warn(payload)`) so the browser DevTools render the structured payload (NOT a template-string concatenation).
- **Forbidden:** untrusted runner-output strings as log payloads (privacy + redaction posture from story 2.24). When the message is runner-derived, log `messageLength` only — mirrors the backend `answerTextLength` pattern from story 2.13 round 4.
- **Test contract:** no test pins the frontend console output (project pattern is documentation-enforced per README — frontend logs are not part of the contract surface). The README in `src/lib/navigation/` codifies the convention.

### Project Structure Notes

- Package layout adds:
  - `src/lib/navigation/` (NEW directory) — `README.md`, `types.ts`, `useReturnToRunContext.ts`, `useNavigateToArtifact.ts`, `useNavigateToClarification.ts`, `NavigationBreadcrumbContext.tsx`, `useNavigationBreadcrumb.ts`, `useBreadcrumbAutoTrack.ts`, `RunContextSnapshotContext.tsx`, `RunContextBoundary.tsx`, `withRunContext.tsx`, `useRunContextSnapshot.ts`, `guards.ts` (`useAssertRunContextLoaded`), `CatastrophicErrorContext.tsx`, `CatastrophicErrorOverlay.tsx`, plus `__tests__/`.
  - `src/components/feedback/` (NEW directory — architecture.md:1182 sanctioned) — `README.md`, `index.ts`, `states/index.ts`, `states/EmptyState.tsx`, `states/LoadingState.tsx`, `states/ErrorState.tsx`, plus `states/__tests__/`.
- Package layout MODIFIES:
  - `src/App.tsx` — wraps `<NavigationBreadcrumbProvider>`, `<RunContextSnapshotProvider>`, `<CatastrophicErrorProvider>`, and `<CatastrophicErrorBoundary>` around the existing `<RouterProvider>`.
  - `src/routes/__root.tsx` — mounts `<NavigationBreadcrumbTracker />` at the top of `RootLayout`.
  - `src/routes/workflows/$workflowRunId/index.tsx` — extends the route with a `search` schema (`clarificationId?: string`).
  - `src/routes/-states/DeadEndState.tsx` — Task 8 migration; SEAM comment closed.
  - `src/lib/routing/publicId.{js,d.ts}` — adds `isValidClarificationId`.
  - `tools/eslint-rules/index.js` — registers `no-untyped-loading-state`.
  - `eslint.config.js` — wires the rule at `error`.
  - `package.json` — adds `react-error-boundary` dep + extends `lint:rules-test` script.
  - `package-lock.json` — regenerated per memory `frontend-lockfile-cross-platform.md`.
  - `src/features/workflows/LAYOUT.md` — Task 9 doc note about the catastrophic-overlay seam closure.
- No conflict with existing structure — the two new directories slot into the documented architecture layout (architecture.md:1180-1185 specifies `src/components/feedback/` for empty/stale/conflict/no-actions/failed-load states + `src/lib/` for cross-cutting utilities).
- ArchUnit-equivalent enforcement at the JS level (the new `no-untyped-loading-state` rule) keeps the architecture rule live.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.22:line-1336] — primary spec for AC1–11.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#L1825-1862] — Navigation Patterns ("UX-DR16" anchor).
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#L1864-1912] — Empty, Loading, and Error States ("UX-DR17" anchor).
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#L2017-2022] — Pattern Enforcement Rules.
- [Source: _bmad-output/planning-artifacts/architecture.md#L514-520] — Available UI controls come from backend-reported allowed actions; frontend must handle empty/disabled/unknown/stale without inferring permissions locally.
- [Source: _bmad-output/planning-artifacts/architecture.md#L519] — UI labels must make clear that MVP roles are recorded audit labels, not enforced authorization (Trap T7 anchor).
- [Source: _bmad-output/planning-artifacts/architecture.md#L1180-1185] — Frontend empty / stale / conflict / no-actions / failed-load states live under `components/feedback`.
- [Source: deliveryline-frontend/src/features/workflows/AppShell.tsx] — story 2.7 host; the `MAIN_CONTENT_ID = 'main-content'` constant the boundary's scroll-restoration target uses.
- [Source: deliveryline-frontend/src/features/workflows/LAYOUT.md] — story 2.7 ADR; Task 9 updates it with the catastrophic-overlay closure note.
- [Source: deliveryline-frontend/src/features/workflows/ContextPanelSlot.tsx] — story 2.7 portal pattern; the `CatastrophicErrorOverlay` borrows the `createPortal` idiom (target = `document.body`, not the shell's `<aside>` mount).
- [Source: deliveryline-frontend/src/routes/__root.tsx] — story 2.5 / 2.7 root; the `<NavigationBreadcrumbTracker />` mounts here.
- [Source: deliveryline-frontend/src/routes/-states/DeadEndState.tsx] — story 2.5 SEAM at line 32-33 (the migration target).
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx] — the route this story extends with `?clarificationId` search.
- [Source: deliveryline-frontend/src/lib/routing/publicId.js + publicId.d.ts] — pattern template for `isValidClarificationId`.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowDetail.ts] — story 2.6 hook composed by `useAssertRunContextLoaded`.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts] — story 2.14 hook composed by `useAssertRunContextLoaded`.
- [Source: deliveryline-frontend/src/lib/api/problemDetails.ts] — `isProblemDetailsError` pattern reused for the `RUN_NOT_FOUND` classification.
- [Source: deliveryline-frontend/src/components/ui/alert.tsx] — `<ErrorState>` composition foundation.
- [Source: deliveryline-frontend/src/components/ui/dialog.tsx] — `<CatastrophicErrorOverlay>` focus-trap foundation.
- [Source: deliveryline-frontend/src/lib/sanitization/] — story 2.24 trusted boundary; Trap T6 sanitization route.
- [Source: deliveryline-frontend/tools/eslint-rules/no-unsanitized-html.js] — template for `no-untyped-loading-state.js`.
- [Source: deliveryline-frontend/tools/eslint-rules/__tests__/no-unsanitized-html.test.js] — RuleTester template.
- [Source: deliveryline-frontend/eslint.config.js] — rule wiring point.
- [Source: _bmad-output/implementation-artifacts/2-7-tri-pane-application-shell-with-artifact-primacy-layout-rules.md] — story 2.7 dev-story; the AppShell SEAM this story closes.
- [Source: _bmad-output/implementation-artifacts/2-5-tanstack-router-setup-and-typed-routes-and-deep-link-handling.md] — TanStack Router patterns + `DeadEndState.tsx` origin.
- [Source: _bmad-output/implementation-artifacts/2-24-artifact-content-sanitization-untrusted-runner-output.md] — sanitization boundary + ESLint local-rule template.
- [Source: _bmad-output/implementation-artifacts/2-31-frontend-lint-and-prettier-and-custom-rules.md] — local-rules plugin registration pattern.
- Memory: `frontend-lockfile-cross-platform.md` — the `react-error-boundary` dep add requires a clean lockfile regeneration on Linux/WSL2 before pushing.
- Memory: `frontend-ts6-legacy-peer-deps.md` — `.npmrc legacy-peer-deps=true` is already present and continues to apply to the new dep.
- Memory: `wsl-linux-ci-reproduction.md` — frontend test slice should be smoke-verified on WSL2 Ubuntu before pushing (the `npm test` Vitest tier runs in CI on Linux).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) via bmad-dev-story

### Debug Log References

- **Vitest 4 cross-file mock isolation:** three separate test files each `vi.mock`-ing
  `@tanstack/react-router` raced in one worker — a hook bound to the wrong `navigate`
  spy (`params` arrived as a function). Fix: consolidated the three nav-hook test files
  into one `navigationHooks.test.tsx` (one mock per module per file). Verified green.
- **`useReturnToRunContext` walk-back semantics:** AC11.c only reconciles if the CURRENT
  (top) breadcrumb entry is excluded from the search — the loop starts at `stack.length - 2`
  ("return to PRIOR context"). Confirmed against AC11.a + AC11.c.
- **Token reality vs spec:** the story references `bg-state-error-subtle` — that token does
  NOT exist. Actual scale (globals.css / tailwind.config.ts): `bg-state-error`,
  `text-state-error-foreground`, `border-state-error-border`, `bg-state-error-hc`,
  `text-state-error-hc-foreground`. Primitives (Task 4) will use the real tokens.

### Completion Notes List

**STATUS: COMPLETE — ready for review.** All 10 tasks + the cross-cutting logging
task are implemented and verified. Every code-review finding (12 `[Patch]`) is
resolved; the 2 `[Defer]` findings remain deferred with documented rationale (coupled
to stories 2.14 / 2.18). AC11.w (runtime axe assertion) is deferred to story 2.25 per
the Scope Guardrail — no axe dep in the tree; per-variant role/aria-live a11y baseline
IS covered by the RTL tests.

**VERIFIED GREEN (this session, full gates):**
- `npx vitest run src/lib/navigation/ src/components/feedback/ src/routes/-states/` →
  **54 tests pass / 0 fail** (navigation hooks, breadcrumb, guards, RunContextBoundary,
  withRunContext, CatastrophicErrorOverlay, the 3 state primitives, inline-rendering,
  DeadEndState migration).
- `npm run lint:rules-test` → **4 rule suites pass** (incl. `no-untyped-loading-state`).
- `npx tsc -b` → **clean** (enforces the AC11.j / AC11.m `@ts-expect-error` type-level
  fixtures + the `assertNever*` exhaustiveness checks).
- `npm run lint` (`--max-warnings=0`) → **clean**.

**What shipped (by task):**
- **Task 1–3 (nav library):** typed nav helpers (`useReturnToRunContext`,
  `useNavigateToArtifact`, `useNavigateToClarification`), `isValidClarificationId` +
  `?clarificationId` typed search schema; per-session breadcrumb stack (pure reducer +
  context/provider/tracker, dedup-on-kind+ids, 16-cap) auto-tracking on real route
  change; run-context snapshot boundary + `withRunContext` HOC + snapshot context.
  Traps T1–T5, T9, T12, T14 + OQ-1/2/6/7 honored.
- **Task 4 (state primitives):** `<EmptyState>` (5-variant union + exhaustiveness),
  `<LoadingState>` (4-variant, `<output>` status), `<ErrorState>` (required `nextAction`,
  `urgency`-driven aria-live + active-focus, `state-error-*` tokens) + barrels.
- **Task 5 (guard):** `useAssertRunContextLoaded` discriminated-union composing 2.6 +
  2.14 hooks (read-only, Trap T7/T8).
- **Task 6 (catastrophic overlay):** `react-error-boundary@^4.1.2` dep + provider/boundary/
  overlay portaled to `document.body`, mounted in `App.tsx` outside the shell (Trap T11);
  Dismiss resets the boundary so the router re-mounts (review-hardened).
- **Task 7 (ESLint rule):** `local-rules/no-untyped-loading-state` registered + wired at
  `error` + RuleTester test + `lint:rules-test` extended (spread-attr + namespaced-JSX
  hardened).
- **Task 8 (migration):** all 8 `DeadEndState` states now compose the new primitives,
  copy preserved verbatim (Trap T15), SEAM comment CLOSED; regression test added.
- **Task 9 (docs):** `src/lib/navigation/README.md`, `src/components/feedback/README.md`,
  `LAYOUT.md` §4 catastrophic-overlay closure + `AppShell.tsx` SEAM-CLOSED annotation.
- **Task 10 (tests):** full AC11 surface except AC11.w (deferred, above); nav-hook tests
  consolidated per memory `vitest-cross-file-router-mock.md`.
- **Logging:** typed structured console events at every error boundary; runner-output
  strings never logged (length only).

**Deferred (documented, not blocking review):**
- AC11.w runtime axe assertion → story 2.25 (full WCAG sweep; no axe dep in tree).
- `useAssertRunContextLoaded` `loaded.actions: unknown` + unreachable `loaded` branch →
  coupled to story 2.14 un-stubbing `useAllowedActions`; forward-correct.
- `RunContextBoundary` re-capture on identity change while mounted → no consumer reads
  the snapshot until story 2.18; restore semantics debatable.

**Not run locally:** WSL2 Linux smoke per memory `wsl-linux-ci-reproduction.md` and the
Linux lockfile verification per memory `frontend-lockfile-cross-platform.md` — recommend
confirming the `react-error-boundary` lockfile change + the Vitest tier on Linux before
the PR merges (the lockfile entry is present; cross-platform native-binding risk is the
known failure mode).

### File List

**Added — `src/lib/navigation/`:**
- `deliveryline-frontend/src/lib/navigation/README.md`
- `deliveryline-frontend/src/lib/navigation/types.ts`
- `deliveryline-frontend/src/lib/navigation/breadcrumbReducer.ts`
- `deliveryline-frontend/src/lib/navigation/NavigationBreadcrumbContext.ts`
- `deliveryline-frontend/src/lib/navigation/NavigationBreadcrumbProvider.tsx`
- `deliveryline-frontend/src/lib/navigation/NavigationBreadcrumbTracker.tsx`
- `deliveryline-frontend/src/lib/navigation/useNavigationBreadcrumb.ts`
- `deliveryline-frontend/src/lib/navigation/useBreadcrumbAutoTrack.ts`
- `deliveryline-frontend/src/lib/navigation/useReturnToRunContext.ts`
- `deliveryline-frontend/src/lib/navigation/useNavigateToArtifact.ts`
- `deliveryline-frontend/src/lib/navigation/useNavigateToClarification.ts`
- `deliveryline-frontend/src/lib/navigation/RunContextSnapshotContext.ts`
- `deliveryline-frontend/src/lib/navigation/RunContextSnapshotProvider.tsx`
- `deliveryline-frontend/src/lib/navigation/RunContextBoundary.tsx`
- `deliveryline-frontend/src/lib/navigation/withRunContext.tsx`
- `deliveryline-frontend/src/lib/navigation/useRunContextSnapshot.ts`
- `deliveryline-frontend/src/lib/navigation/guards.ts`
- `deliveryline-frontend/src/lib/navigation/CatastrophicErrorContext.ts`
- `deliveryline-frontend/src/lib/navigation/CatastrophicErrorProvider.tsx`
- `deliveryline-frontend/src/lib/navigation/CatastrophicErrorBoundary.tsx`
- `deliveryline-frontend/src/lib/navigation/CatastrophicErrorOverlay.tsx`
- `deliveryline-frontend/src/lib/navigation/useCatastrophicError.ts`
- `deliveryline-frontend/src/lib/navigation/__tests__/navigationHooks.test.tsx`
- `deliveryline-frontend/src/lib/navigation/__tests__/NavigationBreadcrumbContext.test.tsx`
- `deliveryline-frontend/src/lib/navigation/__tests__/guards.test.tsx`
- `deliveryline-frontend/src/lib/navigation/__tests__/RunContextBoundary.test.tsx`
- `deliveryline-frontend/src/lib/navigation/__tests__/withRunContext.test.tsx`
- `deliveryline-frontend/src/lib/navigation/__tests__/CatastrophicErrorOverlay.test.tsx`

**Added — `src/components/feedback/`:**
- `deliveryline-frontend/src/components/feedback/README.md`
- `deliveryline-frontend/src/components/feedback/index.ts`
- `deliveryline-frontend/src/components/feedback/states/index.ts`
- `deliveryline-frontend/src/components/feedback/states/EmptyState.tsx`
- `deliveryline-frontend/src/components/feedback/states/LoadingState.tsx`
- `deliveryline-frontend/src/components/feedback/states/ErrorState.tsx`
- `deliveryline-frontend/src/components/feedback/states/__tests__/EmptyState.test.tsx`
- `deliveryline-frontend/src/components/feedback/states/__tests__/LoadingState.test.tsx`
- `deliveryline-frontend/src/components/feedback/states/__tests__/ErrorState.test.tsx`
- `deliveryline-frontend/src/components/feedback/states/__tests__/inline-rendering.test.tsx`

**Added — ESLint rule + DeadEndState test:**
- `deliveryline-frontend/tools/eslint-rules/no-untyped-loading-state.js`
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-untyped-loading-state.test.js`
- `deliveryline-frontend/src/routes/-states/__tests__/DeadEndState.test.tsx`

**Modified:**
- `deliveryline-frontend/src/lib/routing/publicId.js` (+`CLARIFICATION_ID_PATTERN`, `isValidClarificationId`)
- `deliveryline-frontend/src/lib/routing/publicId.d.ts` (typed decls)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (`validateSearch` for `?clarificationId`)
- `deliveryline-frontend/src/routes/-states/DeadEndState.tsx` (migrated to new primitives; SEAM CLOSED)
- `deliveryline-frontend/src/routes/__root.tsx` (mounts `<NavigationBreadcrumbTracker />` + `<RunContextSnapshotProvider>`)
- `deliveryline-frontend/src/App.tsx` (wraps catastrophic-error + breadcrumb providers around `<RouterProvider>`)
- `deliveryline-frontend/src/features/workflows/AppShell.tsx` (SEAM-CLOSED comment — overlay mounts outside the shell)
- `deliveryline-frontend/src/features/workflows/LAYOUT.md` (§4 catastrophic-overlay closure note)
- `deliveryline-frontend/tools/eslint-rules/index.js` (registers `no-untyped-loading-state`)
- `deliveryline-frontend/eslint.config.js` (wires the rule at `error`)
- `deliveryline-frontend/package.json` (`react-error-boundary@^4.1.2` dep + `lint:rules-test` script extension)
- `deliveryline-frontend/package-lock.json` (regenerated for the new dep)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (2-22 `ready-for-dev → in-progress → review`)

## Change Log

| Date       | Change                                                                       |
| ---------- | ---------------------------------------------------------------------------- |
| 2026-05-28 | Story created via `bmad-create-story` (`backlog → ready-for-dev`).          |
| 2026-05-30 | dev-story started (`ready-for-dev → in-progress`). Task 1 + breadcrumb core of Task 2 implemented + verified green (17 tests). Remainder scoped in Completion Notes. |
| 2026-05-30 | Task 4 state primitives (EmptyState/LoadingState/ErrorState + barrels) implemented + verified green (19 tests). Running total: 36 tests green for this story. |
| 2026-05-30 | Remaining tasks completed (Tasks 2-tail, 3, 5, 6, 7, 8, 9, 10) + all 12 code-review `[Patch]` findings resolved. Authored both READMEs + closed the 2.7 LAYOUT/AppShell catastrophic-overlay seam. Full gates green: vitest 54/54, lint:rules-test 4/4, `tsc -b` clean, `eslint --max-warnings=0` clean. Story complete (`in-progress → review`). 2 `[Defer]` findings + AC11.w axe remain documented-deferred (coupled to 2.14 / 2.18 / 2.25). |
| 2026-05-30 | `bmad-code-review` Round 2 (fresh pass over post-patch working tree, 52 files, +2,884/-129, 3 adversarial layers). 2 decision-needed (D1 ErrorState ARIA role/live-region → DEFERRED to 2.25; D2 root `errorComponent` AC8.b half → ACCEPTED current inline split), 1 `[Patch]` fixed (active-urgency focus effect re-fire — depend on derived `messageLength` not the `message` ReactNode identity), 14 deferred → `deferred-work.md`, 6 dismissed. No Critical findings. Post-fix gates: vitest 30/30 (feedback/states + -states), `tsc -b` clean, `eslint --max-warnings=0` clean. Story closed (`review → done`). |

## Review Findings

<!-- Appended by bmad-code-review on 2026-05-30. Reviewed the in-progress working-tree diff (39 files, ~2,949 lines) across 3 adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor. Note: this is an IN-PROGRESS story — README/axe-test gaps are tracked as remaining tasks, not listed below as defects. -->

- [x] [Review][Patch] Catastrophic overlay "Dismiss" leaves a blank app — `<ErrorBoundary fallbackRender={() => null}>` has no reset wiring and `dismiss()` only clears `activeError`; after Dismiss the crashed `<RouterProvider>` subtree stays unmounted. **Resolved 2026-05-30: wire react-error-boundary reset (resetKeys/onReset) so Dismiss re-mounts and retries render.** [src/lib/navigation/CatastrophicErrorBoundary.tsx:16, CatastrophicErrorProvider.tsx:13]
- [x] [Review][Patch] `<ErrorState>` uses shadcn `destructive` tokens, not the project `state-error-*` semantic scale named by AC4.e (EmptyState/LoadingState use their `state-*` tokens). **Resolved 2026-05-30: switch ErrorState to the `state-error-*` token scale for AC4.e compliance + consistency.** [src/components/feedback/states/ErrorState.tsx]

- [x] [Review][Patch] Breadcrumb auto-track re-pushes on every router render — effect deps are whole `matches`/`location` objects (fresh refs each render) and the reducer dedup branch always allocates a new array, so every router state change churns the stack + re-renders all consumers. Fire only on real route change + short-circuit the reducer when kind+ids match. [src/lib/navigation/useBreadcrumbAutoTrack.ts:2361, breadcrumbReducer.ts:2093]
- [x] [Review][Patch] `withRunContext` strips `runId`/`artifactId`/`clarificationId` from the props forwarded to the wrapped component — contradicts its own docstring ("receives the boundary's identity props plus its own props"); the real Compare-mode consumer would not get `runId`. Forward full props. [src/lib/navigation/withRunContext.tsx:15]
- [x] [Review][Patch] `<ErrorState urgency="active">` focus effect: deps `[urgency, variant, message]` omit `nextAction` (focus not re-applied when the action changes) AND the focus query `button:not([disabled]), a[href]` no-ops when the only control is a disabled ContactSupport/DocsLink placeholder — AC9.e a11y guarantee silently unmet. Add `nextAction` to deps + fallback focus target. [src/components/feedback/states/ErrorState.tsx:744]
- [x] [Review][Patch] `errorDefaults` default branch is `return errorDefaults(assertNeverErrorVariant(variant))` — needlessly recursive/fragile vs EmptyState's direct `return assertNeverEmptyVariant(variant)`. Match the EmptyState form. [src/components/feedback/states/ErrorState.tsx:655]
- [x] [Review][Patch] `<ErrorState>` title renders as `<h5>` (via `AlertTitle`) but AC9.d mandates `<h2>`; inconsistent heading level vs `<EmptyState>` (`<h2>`). [src/components/feedback/states/ErrorState.tsx, src/components/ui/alert.tsx]
- [x] [Review][Patch] `<LoadingState>` role="status" — **applied, then reverted: `<output>` already carries the implicit ARIA role `status` (ESLint `jsx-a11y/no-redundant-roles` flags the explicit role; `getByRole('status')` passes via the implicit role). Resolved as a non-issue — AC9.a is already satisfied.** [src/components/feedback/states/LoadingState.tsx]
- [x] [Review][Patch] AC11.j / AC11.m prescribe `// @ts-expect-error` type-level fixtures (off-union EmptyState variant; omitted ErrorState `nextAction`) — 0 occurrences in the diff; only runtime substitutes exist. Add the compile-error fixtures. [src/components/feedback/states/__tests__/]
- [x] [Review][Patch] ESLint `no-untyped-loading-state` false-positives on `<LoadingState {...props} />` — a `JSXSpreadAttribute` providing `variant` is not recognized, flagging valid code at `--max-warnings=0`. Suppress missing-variant when a spread is present. [tools/eslint-rules/no-untyped-loading-state.js:2917]
- [x] [Review][Patch] ESLint `no-untyped-loading-state` misses namespaced/member-expression JSX (`<Icons.Spinner />`) — both name checks require `JSXIdentifier`, so a namespaced raw spinner outside the trusted boundary is silently allowed. [tools/eslint-rules/no-untyped-loading-state.js:2929]
- [x] [Review][Patch] Weak test assertions: (a) inline-rendering test only checks bare `fixed`/`absolute` class tokens the primitives never set (tautological vs AC8.a); (b) AC11.n matrix calls `control.focus()` itself then asserts focus (tautological) and never asserts the documented callback fires; (c) DeadEndState migration test asserts `aria-live` presence, not its `polite` value (a flip to `assertive` would pass). Strengthen all three. [src/components/feedback/states/__tests__/inline-rendering.test.tsx, ErrorState.test.tsx, src/routes/-states/__tests__/DeadEndState.test.tsx]

- [x] [Review][Defer] `useAssertRunContextLoaded` `loaded.actions` typed `unknown` (AC2.b wants `AllowedActions`) and the `loaded` branch is unreachable in production because `useAllowedActions` is an `enabled:false` stub — deferred, coupled to story 2.14 completing the stub. [src/lib/navigation/guards.ts:2162]
- [x] [Review][Defer] `RunContextBoundary` publishes a mount-time snapshot and never re-captures when identity props change while mounted (`identityRef` updates but `setSnapshot` isn't re-called) — `useRunContextSnapshot` consumers would read a stale `runId`. Deferred — no consumer reads the snapshot until story 2.18; restore semantics debatable. [src/lib/navigation/RunContextBoundary.tsx:1423]

### Round 2 review (2026-05-30) — fresh review of the post-patch working tree

<!-- Appended by bmad-code-review on 2026-05-30. Second pass over the working-tree diff (52 files, +2,884/-129) AFTER the Round-1 12-patch batch landed. 3 adversarial layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor). Findings already fixed or already deferred-of-record in the Round-1 section above are NOT repeated. 2 decision-needed, 1 patch, 14 deferred, 6 dismissed. No Critical findings. -->

- [x] [Review][Decision→Defer] `<ErrorState>` `role="alert"` + `aria-live="polite"` are semantically contradictory for `passive` urgency, and the audit-only `permissionRestricted` variant (Trap T7) is announced via `role="alert"` — shadcn `<Alert>` hardcodes `role="alert"` (implicit assertive live region), so a "passive page-load failure" and a neutral recorded-role notice both interrupt assistive tech, undercutting Trap T13 (passive = polite) and Trap T7 (audit-only ≠ hard failure). Current behavior matches the literal AC9.b wording and is test-pinned (`DeadEndState.test.tsx`). **Resolved 2026-05-30: DEFERRED to story 2.25 — the dedicated WCAG/axe sweep owns role/live-region refinement (AC11.w axe is already deferred there); not blocking for 2.22.** [src/components/feedback/states/ErrorState.tsx + src/components/ui/alert.tsx]
- [x] [Review][Decision→Accepted] Root router `errorComponent` (`GenericErrorState` in `__root.tsx`) never calls `signalCatastrophic` — AC8.b prescribes BOTH the root `errorComponent` AND the outer `react-error-boundary` feed the catastrophic overlay, but today only the outer boundary does. **Resolved 2026-05-30: ACCEPTED current split — inline `GenericErrorState` for recoverable router loader/render errors is better UX than a full-screen overlay; the overlay is correctly reserved for true React render crashes. AC8.b wording to be reconciled (the "plus the root errorComponent" half is aspirational, not implemented). No code change.** [src/routes/__root.tsx + src/lib/navigation/CatastrophicErrorProvider.tsx]

- [x] [Review][Patch] `<ErrorState>` active-urgency focus effect re-fires on every parent render — its dep array `[urgency, variant, message, nextAction.kind]` includes `message`, which is a fresh `ReactNode` (`<p>…</p>`) identity each render for every `DeadEndState` caller; on `urgency="active"` the effect re-runs, re-logging `state.activeError` and yanking focus back to the action control after the user tabs away. **Resolved 2026-05-30: derive a stable `messageLength` primitive and depend on it instead of the `message` object identity — keeps the log length accurate while firing only on mount + urgency/variant/action change.** [src/components/feedback/states/ErrorState.tsx:194]

- [x] [Review][Defer] Catastrophic deterministic-crash → "Dismiss" re-mounts the crashed subtree and immediately re-throws, so Reload is the only real escape — the Round-1 reset wiring fixed the "blank app" but a component that throws on every render now loops Dismiss→re-crash (acknowledged in code comments). Deferred — accepted MVP posture; revisit with an attempt-count cap or navigate-to-safety path if it bites. [src/lib/navigation/CatastrophicErrorBoundary.tsx:21]
- [x] [Review][Defer] `withRunContext` HOC wraps the DESTINATION component, so `RunContextBoundary` captures the destination's mount-time scroll (≈0) and restores that on exit — defeating the "return to the prior run context's scroll" intent; compounds the already-deferred no-re-capture-on-identity-change item; plus a dev-only StrictMode double-mount transiently nulls the snapshot. Deferred — no real consumer until 2.16/2.18; capture-frame semantics need design. [src/lib/navigation/RunContextBoundary.tsx:41, withRunContext.tsx]
- [x] [Review][Defer] `useAssertRunContextLoaded` `loaded` branch asserts `detail.data` as `WorkflowDetail` and `actions` as `unknown` with no `undefined` narrowing — a success-with-undefined-data query would lie about the type. Branch is unreachable in production today (`useAllowedActions` is `enabled:false`). Deferred — extends the existing 2.14-coupled defer; add a `detail.data !== undefined` guard when the stub is un-stubbed. [src/lib/navigation/guards.ts:50]
- [x] [Review][Defer] ESLint `no-untyped-loading-state` cannot detect `animate-spin` arriving via `cn(...)` or a variable className (only string/template literals resolve) and matches raw spinners only by `*Spinner` element-name suffix — AC7's "no untyped spinner" contract is lint-enforced only for literal classNames; the TS `variant` type is the real backstop. Deferred — inherent static-analysis limitation, low residual risk. [tools/eslint-rules/no-untyped-loading-state.js]
- [x] [Review][Defer] ESLint `no-untyped-loading-state` trusted-boundary regex exempts `src/dev/**` in addition to the two directories AC7.b/Trap T16 name (`feedback/states`, `components/ui`) — undeclared scope widening; harmless while `src/dev/` is non-production but weakens the rule beyond what AC7 authorized. Deferred — tighten or document the third boundary. [tools/eslint-rules/no-untyped-loading-state.js:21]
- [x] [Review][Defer] `useBreadcrumbAutoTrack.classify` matches routes via `String.includes(routeId, '$workflowRunId' / 'artifacts/$artifactId')` — substring matching will mis-classify future nested routes that contain those substrings (e.g. `/workflows/$workflowRunId/compare`). Deferred — fragile as the route tree grows; switch to exact route-id equality. [src/lib/navigation/useBreadcrumbAutoTrack.ts:55]
- [x] [Review][Defer] `useReturnToRunContext` walk-back edges: an `artifact` breadcrumb whose `artifactId` is `undefined` silently downgrades to run-detail navigation; and a synchronous back-click before the post-navigation tracker effect flushes can skip the wrong entry (callback reads the `stack` captured at render). Deferred — narrow timing/edge windows, no current consumer triggers them. [src/lib/navigation/useReturnToRunContext.ts:74]
- [x] [Review][Defer] Breadcrumb `scrollY` is captured at classify time but the dedup `replaceLast` overwrites it with the navigation-time value (≈0) and nothing ever consumes it for restoration — dead data for the stated future purpose. Deferred — wire it when a consumer actually restores breadcrumb scroll. [src/lib/navigation/useBreadcrumbAutoTrack.ts:53, breadcrumbReducer.ts:60]
- [x] [Review][Defer] `CatastrophicErrorOverlay` logs `window.location.pathname` for the `errorBoundary.catastrophic` event (correct, since it lives outside the router) but a crash mid-route-transition can log the prior URL — observability mislabel only. Deferred — accept; router-state read isn't available outside the provider. [src/lib/navigation/CatastrophicErrorOverlay.tsx:23]
- [x] [Review][Defer] README/JSDoc/LAYOUT claim the overlay "renders via `createPortal(..., document.body)`" but it composes shadcn `<Dialog>` (Radix Portal, defaults to `document.body`) — functionally equivalent (AC11.q `document.body.contains` passes) but the documented mechanism is inaccurate; a future Dialog swap could silently break Trap T11 with no test pinning the portal target. Deferred — fix the doc wording or add a portal-target assertion. [src/lib/navigation/CatastrophicErrorOverlay.tsx, README.md]
- [x] [Review][Defer] Trap T5 (no dedup on different search params) is pinned only at the reducer level via synthetic entries — there is no test exercising the auto-tracker turning two different `clarificationId` search params into two distinct entries. Deferred — add an integration test for `useBreadcrumbAutoTrack` search-param classification. [src/lib/navigation/__tests__/NavigationBreadcrumbContext.test.tsx]
- [x] [Review][Defer] Empty-string prop edges: `EmptyState`/`ErrorState` use `title ?? defaultTitle` (nullish), so `title=""`/`message=""` (e.g. an unloaded i18n key) renders a blank heading/body — the icon-alone outcome AC9.d forbids; and `ContactSupport href=""` is kept by `??` (not nullish), suppressing the `VITE_SUPPORT_URL` env fallback into a permanently disabled button. Deferred — no current caller passes empty strings; harden with truthiness/trim checks. [src/components/feedback/states/EmptyState.tsx:836, ErrorState.tsx:990]
- [x] [Review][Defer] `breadcrumbReducer` `clear` returns the shared module-level `INITIAL_BREADCRUMB_STACK` constant (not `Object.freeze`d) and multiple providers share its identity — `readonly` is compile-time only, so the "pure reducer" guarantee rests on the shared reference never being mutated. Deferred — freeze the constant or return a fresh `[]` from `clear`. [src/lib/navigation/breadcrumbReducer.ts:25,67]
- [x] [Review][Defer] `DeadEndState.test.tsx` verifies "verbatim copy preserved" (Trap T15) via a whitespace-normalized substring (`toContain(normalize(body))`) — cannot detect dropped punctuation, edits outside the fragment, or duplicated content; several `body` fragments are short and generic. Deferred — test-strength nit; assert full-string equality on the migrated copy. [src/routes/-states/__tests__/DeadEndState.test.tsx:293]
