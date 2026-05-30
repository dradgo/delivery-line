# `src/components/feedback/` — content-absent state primitives (story 2.22)

> architecture.md:1180-1185 — "Frontend empty, stale, conflict, no-actions, and
> failed-load states live under `components/feedback`."

This directory ships the typed state-component family that enforces **UX-DR17**: no
empty / loading / error state may appear without explaining whether the issue is
**absence**, **delay**, **failure**, or **restriction**, and every error provides the
next safe action. The variant unions enforce that taxonomy at the TypeScript level.

Consumer-facing import path:

```ts
import { EmptyState, LoadingState, ErrorState } from '@/components/feedback';
```

## `states/`

| Component                                                                                                                                      | Meaning                   | Key contract                                                                                                                                                                                                                                                   |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<EmptyState variant="queue \| filtered \| artifactNotGenerated \| noOpenQuestions \| noMeaningfulDiff" message action?>`                      | **Absence**               | 5-way variant union (AC5) guarded by an `assertNeverEmptyVariant` switch tail (adding a 6th variant without a `case` fails `tsc`). `action` is OPTIONAL. No live region — empty states are benign (AC9.c).                                                     |
| `<LoadingState variant="fetchingData \| generatingArtifact \| rebuildingAfterRejection \| retryingRecovery" message?>`                         | **Delay**                 | `variant` REQUIRED (the 4-meaning split, AC7). Renders `<output>` (implicit `role="status"`, `aria-live="polite"`) so screen readers announce material loading (AC9.a).                                                                                        |
| `<ErrorState variant="failedRetrieval \| unavailableDiffBaseline \| permissionRestricted \| blockedByStaleState" message nextAction urgency?>` | **Failure / restriction** | `nextAction` REQUIRED (no action-less path, AC6). `urgency` (`'passive'` default \| `'active'`) drives `aria-live` polite vs assertive (Trap T13) and active-mount focus-to-action (AC9.e). Composes the shadcn `<Alert>` and the `state-error-*` token scale. |

`nextAction` is the discriminated union `Retry | Refresh | NavigateBack |
ContactSupport | DocsLink` from `@/lib/navigation` (`types.ts`):

- `NavigateBack` consumes `useReturnToRunContext()` internally — the consumer passes
  no callback (Trap T9).
- `ContactSupport` renders a DISABLED placeholder when no `href` / `VITE_SUPPORT_URL`
  resolves — never a broken link (Trap T10).
- `DocsLink` / `ContactSupport` hrefs are scheme-validated via story 2.24's
  `validateUrlScheme`.

## Sanitization boundary (Trap T6)

Default copy is operator-authored and renders as plain React nodes. When an
`<ErrorState>` `message` derives from **runner output** it is untrusted-by-default
and MUST route through story 2.24's sanitization pipeline (`SafeMarkdownRenderer` or
the plain-text equivalent) BEFORE display.

## Accessibility (baseline only)

Each variant pairs a text `<h2>` / `<p>` with `aria-hidden` iconography — never
icon-alone (AC9.d). Per-variant RTL + the project lint baseline cover these
primitives. The **full WCAG 2.1 AA audit + axe sweep is story 2.25**; the runtime
`axe-core` assertion (AC11.w) is deferred there (no axe dep in this tree yet).

## Inline, never global

These components are sized to fit their parent — no `fixed` / `absolute`, no
viewport units (AC8.a). They render inside whatever region embeds them (queue list,
artifact pane, clarification sidebar, run-context strip). The global catastrophic
overlay is the only full-frame surface and lives in `@/lib/navigation`, not here.

See `src/lib/navigation/README.md` for the navigation glue (`useReturnToRunContext`,
`useAssertRunContextLoaded`, breadcrumb stack) these primitives pair with.
