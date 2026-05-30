# `src/lib/navigation/` — navigation continuity library (story 2.22)

Frontend-only infrastructure that the rest of Epic-2a/2b, Epic-3, and Epic-4
composites consume. It implements the two UX design rules the epics cite:

- **UX-DR16 (navigation continuity)** — navigation always preserves run / artifact
  / workflow identity, and "back" returns to the prior _meaningful_ review context,
  not a generic top-level page.
- **UX-DR17 (content-absent states)** — every empty / loading / error surface
  distinguishes absence vs delay vs failure vs restriction with an explicit next
  safe action. (The state _components_ live in `@/components/feedback`; this library
  ships the navigation glue they depend on.)

This library never reaches for `window.history` (Trap T2). TanStack Router's typed
`useNavigate()` / `useRouterState()` / `useMatches()` / `useLocation()` and its
typed `params` + `search` are the only sanctioned data-passing surfaces.

## Three sub-areas

### 1. Typed navigation helpers

| Export                                               | Purpose                                                                                                                                                                                                                                                                                                                                                                         |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `useReturnToRunContext()`                            | Walks the breadcrumb stack backwards (excluding the current top), returns to the most recent `runDetail \| artifact \| clarification` entry via the typed router; falls back to `/workflows` and `console.debug({ event: 'navigation.fallback', reason: 'empty_stack' })` when none exists. `useCallback`-wrapped so memoized children keep referential equality (Trap T1, T4). |
| `useNavigateToArtifact(runId, artifactId)`           | Validates both ids via `isValidRunId` / `isValidArtifactId` **synchronously at hook-call time**; `console.warn({ event: 'navigation.invalidId', ... })` then throws `InvalidNavigationTargetError` on call when invalid (Trap T6). Returns a stable callback.                                                                                                                   |
| `useNavigateToClarification(runId, clarificationId)` | Navigates to `/workflows/$workflowRunId` with a typed `search: { clarificationId }` param (NOT a hash anchor — OQ-6). Validates `clarificationId` via `isValidClarificationId`. The Clarification Region (story 2.18) reads the search param and scrolls itself into view.                                                                                                      |

### 2. Per-session breadcrumb stack (AC3)

- `NavigationBreadcrumbProvider` (mounted in **`src/App.tsx`**, OUTSIDE the router —
  the router subscribes to the breadcrumb via the tracker, not vice-versa, so the
  stack survives router re-mounts — Trap T3, OQ-1).
- `NavigationBreadcrumbTracker` (mounted in **`src/routes/__root.tsx`**, INSIDE the
  router) drives `useBreadcrumbAutoTrack`, which classifies the current route via
  `useMatches()` and `push`/`replaceLast`es on real route change only.
- `breadcrumbReducer` is a pure, isolated reducer (`useReducer`, OQ-2): explicit
  `push` / `replaceLast` / `clear` actions, **dedup on `kind` + ids** (not path —
  Trap T5), and a FIFO cap of `MAX_BREADCRUMB_STACK_DEPTH = 16` (AC3.d, OQ-7).
- `useNavigationBreadcrumb()` is the typed accessor; it throws outside the provider.
- **Session-scoped, never persisted** (AC3.f) — a refresh resets the stack to the
  entry derived from the current URL. Persisting would re-navigate users into stale
  artifact-version contexts (architecture.md:512-515).

### 3. Run-context snapshot boundary (AC10)

Orthogonal to the breadcrumb stack (Trap T14): the breadcrumb tracks _"what page
was I on?"_, the boundary tracks _"what scroll/selection state was I in?"_.

- `RunContextBoundary` captures `{ runId, artifactId?, clarificationId?, scrollY,
mainPaneScrollTop }` on mount and restores `scrollY` + `<main id="main-content">`
  scrollTop on unmount via `useLayoutEffect` cleanup (NOT `useEffect` — paint must
  happen with the restored scroll, no flash — Trap T12).
- `withRunContext(Component)` is the declarative HOC variant; it forwards full props
  (identity + own props) to the wrapped component.
- `RunContextSnapshotProvider` (mounted in `__root.tsx`) + `useRunContextSnapshot()`
  expose the most recent snapshot to consumers who want to read, not capture, it
  (e.g. the Clarification Region in 2.18).

## `useAssertRunContextLoaded(runId)` — the run-context-loaded guard convention

Composites that depend on run-context data import this discriminated-union guard
rather than re-implementing `{ isLoading, isError, data }` four ways (AC2.b):

```ts
const state = useAssertRunContextLoaded(runId);
// { kind: 'loading' }
//   → <LoadingState variant="fetchingData" />
// { kind: 'runNotFound' }
//   → <ErrorState variant="failedRetrieval" nextAction={{ kind: 'NavigateBack' }} />
// { kind: 'error'; error; refetch }
//   → <ErrorState variant="failedRetrieval" nextAction={{ kind: 'Retry', onRetry: state.refetch }} />
// { kind: 'loaded'; detail; actions }
//   → render the composite
```

It composes `useWorkflowDetail` (2.6) + `useAllowedActions` (2.14) — it does NOT
introduce a new `useQuery`. **Trap T7 / T8:** the guard is read-only and returns the
backend-reported actions to the consumer; it never gates on whether a specific
action is enabled. `<ErrorState nextAction={...}>` is a UX recovery hint, a separate
domain from `useAllowedActions`' real action enabling.

> Note: until story 2.14 rewires `useAllowedActions` from its disabled stub, the
> actions query stays pending in production and the guard reports `loading`. The
> `loaded.actions` type is `unknown` pending the 2.14 `AllowedActions` type. Both are
> forward-correct and tracked as deferred follow-ups on story 2.22.

## Catastrophic-error overlay (AC8) — closes the story-2.7 shell seam

`CatastrophicErrorOverlay` mounts **once in `src/App.tsx`**, OUTSIDE the
`AppShell` / `RouterProvider` subtree, and renders via
`createPortal(..., document.body)` so a shell crash still surfaces it (Trap T11).
The story-2.7 reserved seam inside `AppShell.tsx` is therefore **closed, not used**
(see `src/features/workflows/LAYOUT.md` §4). It uses `role="alertdialog"` with a
focus trap; "Reload" resets the page + stack, "Dismiss" resets the
`react-error-boundary` so the router subtree re-mounts while preserving the
breadcrumb stack (OQ-8). `CatastrophicErrorBoundary` + `CatastrophicErrorProvider`
wrap the router in `App.tsx`; `signalCatastrophic(error)` feeds the overlay and emits
`console.error({ event: 'errorBoundary.catastrophic', message, route })`.

## 2.22 ↔ 2.21 split

This story renders errors **INLINE in the affected region** (UX-DR17, AC8.a) — never
through a toast. The toast / inline / persistent-banner _dispatcher_ is story 2.21
(epic-2b); its future home for `<ErrorState>`'s `nextAction` payloads is `sonner`,
already wired in `AppShell` but unused here.

## Migration recipe for consumers

- Import state components from `@/components/feedback` —
  `import { EmptyState, LoadingState, ErrorState } from '@/components/feedback';`.
- **Never write a generic spinner.** The `local-rules/no-untyped-loading-state`
  ESLint rule flags raw `<*Spinner*>` / `animate-spin` outside the trusted boundary
  and `<LoadingState>` without a `variant`.
- Pass `runId` / `artifactId` / `clarificationId` only through the typed router
  helpers above — never build URLs by hand.

## Observability

Typed, stable-named structured console events at every error boundary
(`navigation.fallback`, `navigation.invalidId`, `breadcrumb.unknownKind`,
`errorBoundary.catastrophic`, `state.activeError`). Runner-output strings are NEVER
logged — when a message derives from runner output, only its length is emitted
(mirrors the backend `answerTextLength` pattern). Frontend console output is
documentation-enforced, not test-pinned (project convention).
