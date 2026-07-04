# Archive / Unarchive Run Button — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a governed Archive / Unarchive control to the run-detail page so a workflow owner can soft-hide an obsolete run (and reverse it) from the web UI.

**Architecture:** Frontend-only. The backend (story 3d-8) already exposes `POST /archive` + `/unarchive` and advertises `archive_run` / `unarchive_run` through the allowed-actions matrix (scoped to `workflow_owner`). A self-hiding container reads those actions, shows one `GovernedButton`, captures a reason via the shared `RationaleCaptureDialog`, and fires one of two mutation hooks built on the existing `useWorkflowMutation` factory (idempotency key + `detail`/`lists` invalidation → the button auto-flips on success).

**Tech Stack:** React 19, TypeScript, TanStack Query v5, TanStack Router, Vitest + Testing Library + MSW, Tailwind. Generated OpenAPI client (`@/lib/api`).

## Global Constraints

- **No backend / schema / migration / OpenAPI change.** `archive_run` / `unarchive_run` already flow through `useAllowedActions` as raw strings (`actions: string[]`).
- **Reason max length 512** — parity with backend `ArchiveRunRequest.reason @Size(max = 512)`.
- **Never log the reason** (T-LOG-PII) — pass through only; any structured log must exclude it.
- **Forward-compat:** the control keys off `actions.includes('archive_run' | 'unarchive_run')`; unknown actions are ignored.
- **Self-hiding:** render nothing unless one of the two actions is advertised.
- **react-refresh:** a `.tsx` may export only components — non-component helpers/constants live in a sibling `.ts`.
- **Acting role:** always fetch allowed-actions with `actorRole = 'workflow_owner'` (the role the matrix scopes archive to).
- **Gate before "done":** `npm run build`, `npm run lint`, and the prettier `npm run format:check` (the maven reactor enforces `format:check`; the dev FE gate list omits it — run it explicitly).
- **Commits:** omit any `Co-Authored-By: Claude` trailer.

---

### Task 1: `useArchiveRun` mutation hook

**Files:**
- Create: `deliveryline-frontend/src/features/workflows/hooks/useArchiveRun.ts`
- Test: `deliveryline-frontend/src/features/workflows/hooks/useArchiveRun.test.tsx`

**Interfaces:**
- Consumes: `useWorkflowMutation` (`./useWorkflowMutation`), `apiClient`/`unwrap` (`@/lib/api/client`), `IDEMPOTENCY_KEY_HEADER` (`@/lib/api/idempotency`), `components` (`@/lib/api/schema`).
- Produces: `useArchiveRun(workflowRunId: string): ArchiveRunResult`; `interface ArchiveRunVariables { reason: string }`; `type ArchiveRunResult = WorkflowMutationResult<ArchiveRunResponse, ArchiveRunVariables>` where `ArchiveRunResponse = components['schemas']['ArchiveRun']`.

- [ ] **Step 1: Write the failing test**

```tsx
// useArchiveRun.test.tsx
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE, isProblemDetailsError } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useArchiveRun } from './useArchiveRun';

const ARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/archive';
const RUN_ID = 'run_archive_demo_001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}
function mutationClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

describe('useArchiveRun', () => {
  it('POSTs { reason } with an idempotency key; returns the ArchiveRun response', async () => {
    let body: Record<string, unknown> | undefined;
    let idem: string | null = null;
    server.use(
      http.post(ARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        idem = request.headers.get('idempotency-key');
        return HttpResponse.json({
          workflowRunId: RUN_ID,
          currentState: 'Failed',
          archivedAt: '2026-07-03T00:00:00Z',
          replayed: false,
        });
      }),
    );
    const { result } = renderHook(() => useArchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    let response;
    await act(async () => {
      response = await result.current.mutateAsync({ reason: 'obsolete run' });
    });
    expect(response).toMatchObject({ archivedAt: '2026-07-03T00:00:00Z' });
    expect(body).toEqual({ reason: 'obsolete run' });
    expect(idem).toMatch(/[0-9a-f-]{36}/);
  });

  it('surfaces a typed ProblemDetailsError on ARCHIVE_NOT_APPLICABLE (409)', async () => {
    server.use(
      http.post(ARCHIVE_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Archive not applicable',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/archive`,
            code: 'ARCHIVE_NOT_APPLICABLE',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const { result } = renderHook(() => useArchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: 'x' }).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    const error: unknown = result.current.error;
    expect(isProblemDetailsError(error)).toBe(true);
    if (isProblemDetailsError(error)) {
      expect(error.code).toBe('ARCHIVE_NOT_APPLICABLE');
    }
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd deliveryline-frontend && npx vitest run src/features/workflows/hooks/useArchiveRun.test.tsx`
Expected: FAIL — `Failed to resolve import "./useArchiveRun"`.

- [ ] **Step 3: Write minimal implementation**

```ts
// useArchiveRun.ts
/**
 * LIVE `useArchiveRun` mutation (story 3d-8 FE gap). Soft-hides a run via
 * `POST /api/v1/workflows/{id}/archive`. Built on `useWorkflowMutation` so it inherits
 * idempotency-key reuse + the `detail(id)` / `lists()` invalidation cascade (so the
 * allowed-actions refetch flips the control to Unarchive on success).
 *
 * `reason` is user-authored — pass through, NEVER log (T-LOG-PII).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';

type ArchiveRunRequest = components['schemas']['ArchiveRunRequest'];
export type ArchiveRunResponse = components['schemas']['ArchiveRun'];

/** Variables to archive (hide) a run. `reason` is REQUIRED (backend `@NotBlank`). */
export interface ArchiveRunVariables {
  reason: string;
}

export type ArchiveRunResult = WorkflowMutationResult<ArchiveRunResponse, ArchiveRunVariables>;

export function useArchiveRun(workflowRunId: string): ArchiveRunResult {
  return useWorkflowMutation<ArchiveRunVariables, ArchiveRunResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: ArchiveRunRequest = { reason: variables.reason };
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/archive', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
          body,
        }),
      );
    },
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run src/features/workflows/hooks/useArchiveRun.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add deliveryline-frontend/src/features/workflows/hooks/useArchiveRun.ts deliveryline-frontend/src/features/workflows/hooks/useArchiveRun.test.tsx
git commit -m "feat(archive-ui): useArchiveRun mutation hook"
```

---

### Task 2: `useUnarchiveRun` mutation hook

**Files:**
- Create: `deliveryline-frontend/src/features/workflows/hooks/useUnarchiveRun.ts`
- Test: `deliveryline-frontend/src/features/workflows/hooks/useUnarchiveRun.test.tsx`

**Interfaces:**
- Consumes: same imports as Task 1.
- Produces: `useUnarchiveRun(workflowRunId: string): UnarchiveRunResult`; `interface UnarchiveRunVariables { reason?: string | undefined }`; `type UnarchiveRunResult = WorkflowMutationResult<ArchiveRunResponse, UnarchiveRunVariables>` (reuses `ArchiveRunResponse` from Task 1). The unarchive body OMITS `reason` when blank/undefined.

- [ ] **Step 1: Write the failing test**

```tsx
// useUnarchiveRun.test.tsx
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { useUnarchiveRun } from './useUnarchiveRun';

const UNARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/unarchive';
const RUN_ID = 'run_unarchive_demo_001';

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}
function mutationClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

describe('useUnarchiveRun', () => {
  it('sends { reason } when provided', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post(UNARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Failed', archivedAt: null });
      }),
    );
    const { result } = renderHook(() => useUnarchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: 'still needed' });
    });
    expect(body).toEqual({ reason: 'still needed' });
  });

  it('omits reason from the body when blank/undefined', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post(UNARCHIVE_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Failed', archivedAt: null });
      }),
    );
    const { result } = renderHook(() => useUnarchiveRun(RUN_ID), {
      wrapper: createWrapper(mutationClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ reason: '   ' });
    });
    expect(body).toEqual({});
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/features/workflows/hooks/useUnarchiveRun.test.tsx`
Expected: FAIL — `Failed to resolve import "./useUnarchiveRun"`.

- [ ] **Step 3: Write minimal implementation**

```ts
// useUnarchiveRun.ts
/**
 * LIVE `useUnarchiveRun` mutation (story 3d-8 FE gap). Reverses a soft-hide via
 * `POST /api/v1/workflows/{id}/unarchive`. Symmetric twin of `useArchiveRun`; the
 * `reason` is OPTIONAL (backend `UnarchiveRunRequest.reason` is nullable) — omit it from
 * the body when blank. `reason` is user-authored — pass through, NEVER log (T-LOG-PII).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useWorkflowMutation, type WorkflowMutationResult } from './useWorkflowMutation';
import type { ArchiveRunResponse } from './useArchiveRun';

type UnarchiveRunRequest = components['schemas']['UnarchiveRunRequest'];

/** Variables to un-archive (un-hide) a run. `reason` is OPTIONAL. */
export interface UnarchiveRunVariables {
  reason?: string | undefined;
}

export type UnarchiveRunResult = WorkflowMutationResult<ArchiveRunResponse, UnarchiveRunVariables>;

export function useUnarchiveRun(workflowRunId: string): UnarchiveRunResult {
  return useWorkflowMutation<UnarchiveRunVariables, ArchiveRunResponse>({
    workflowRunId,
    mutationFn: async ({ variables, idempotencyKey }) => {
      const trimmed = variables.reason?.trim();
      const body: UnarchiveRunRequest =
        trimmed !== undefined && trimmed !== '' ? { reason: variables.reason } : {};
      return unwrap(
        await apiClient.POST('/api/v1/workflows/{workflowRunId}/unarchive', {
          params: {
            path: { workflowRunId },
            header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          },
          body,
        }),
      );
    },
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run src/features/workflows/hooks/useUnarchiveRun.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add deliveryline-frontend/src/features/workflows/hooks/useUnarchiveRun.ts deliveryline-frontend/src/features/workflows/hooks/useUnarchiveRun.test.tsx
git commit -m "feat(archive-ui): useUnarchiveRun mutation hook"
```

---

### Task 3: `runArchiveView.ts` — pure view helpers (mode, fields, error copy)

**Files:**
- Create: `deliveryline-frontend/src/features/workflows/runArchiveView.ts`
- Test: `deliveryline-frontend/src/features/workflows/runArchiveView.test.ts`

**Interfaces:**
- Consumes: `RationaleField` (`@/components/overlays/RationaleCaptureDialog`).
- Produces:
  - `type RunArchiveMode = 'archive' | 'unarchive'`
  - `resolveArchiveMode(actions: readonly string[] | undefined): RunArchiveMode | null` — `archive_run` → `'archive'`, else `unarchive_run` → `'unarchive'`, else `null`.
  - `ARCHIVE_REASON_MAX_LENGTH = 512`
  - `archiveFields(mode: RunArchiveMode): readonly RationaleField[]` — archive: required `reason`; unarchive: optional `reason`; both max-length validated.
  - `archiveConsequence(mode): string`, `archiveButtonLabel(mode): string`, `archiveConfirmLabel(mode): string`, `archiveDialogTitle(mode): string`, `archiveIntent(mode): 'warning' | 'info'`.
  - `mapArchiveErrorCode(code: string | undefined): string | undefined` — friendly inline copy for `ARCHIVE_NOT_APPLICABLE` / `IDEMPOTENCY_KEY_CONFLICT` / `RUN_NOT_FOUND`; `undefined` for unknown/absent.

- [ ] **Step 1: Write the failing test**

```ts
// runArchiveView.test.ts
import { describe, expect, it } from 'vitest';
import {
  resolveArchiveMode,
  archiveFields,
  archiveButtonLabel,
  archiveConfirmLabel,
  archiveIntent,
  mapArchiveErrorCode,
  ARCHIVE_REASON_MAX_LENGTH,
} from './runArchiveView';

describe('resolveArchiveMode', () => {
  it('archive_run → archive, unarchive_run → unarchive, neither → null', () => {
    expect(resolveArchiveMode(['archive_run', 'retry'])).toBe('archive');
    expect(resolveArchiveMode(['unarchive_run'])).toBe('unarchive');
    expect(resolveArchiveMode(['retry'])).toBeNull();
    expect(resolveArchiveMode(undefined)).toBeNull();
  });
});

describe('archiveFields', () => {
  it('archive reason is required; unarchive reason is optional; both length-capped', () => {
    const [archive] = archiveFields('archive');
    const [unarchive] = archiveFields('unarchive');
    expect(archive.required).toBe(true);
    expect(unarchive.required).toBeFalsy();
    const tooLong = 'a'.repeat(ARCHIVE_REASON_MAX_LENGTH + 1);
    expect(archive.validate?.(tooLong)).toMatch(/512/);
    expect(archive.validate?.('ok')).toBeUndefined();
  });
});

describe('labels + intent + error copy', () => {
  it('mode-specific labels and intent', () => {
    expect(archiveButtonLabel('archive')).toBe('Archive run');
    expect(archiveButtonLabel('unarchive')).toBe('Unarchive run');
    expect(archiveConfirmLabel('archive')).toBe('Archive');
    expect(archiveConfirmLabel('unarchive')).toBe('Unarchive');
    expect(archiveIntent('archive')).toBe('warning');
    expect(archiveIntent('unarchive')).toBe('info');
  });
  it('maps known error codes; undefined otherwise', () => {
    expect(mapArchiveErrorCode('ARCHIVE_NOT_APPLICABLE')).toMatch(/refresh/i);
    expect(mapArchiveErrorCode('RUN_NOT_FOUND')).toMatch(/no longer exists|not found/i);
    expect(mapArchiveErrorCode('SOMETHING_ELSE')).toBeUndefined();
    expect(mapArchiveErrorCode(undefined)).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/features/workflows/runArchiveView.test.ts`
Expected: FAIL — `Failed to resolve import "./runArchiveView"`.

- [ ] **Step 3: Write minimal implementation**

```ts
// runArchiveView.ts
/**
 * Pure view helpers for the run archive/unarchive control (story 3d-8 FE gap). Lives in a
 * sibling `.ts` (not the `.tsx`) so the component file exports only a component
 * (react-refresh-no-fn-exports). No React, no I/O — trivially unit-testable.
 */
import type { RationaleField } from '@/components/overlays/RationaleCaptureDialog';

export type RunArchiveMode = 'archive' | 'unarchive';

/** Reason cap — parity with backend `ArchiveRunRequest.reason @Size(max = 512)`. */
export const ARCHIVE_REASON_MAX_LENGTH = 512;

/**
 * The single advertised lifecycle action (the matrix emits exactly one for a
 * workflow_owner). Forward-compat: ignores unknown actions.
 */
export function resolveArchiveMode(
  actions: readonly string[] | undefined,
): RunArchiveMode | null {
  if (actions === undefined) {
    return null;
  }
  if (actions.includes('archive_run')) {
    return 'archive';
  }
  if (actions.includes('unarchive_run')) {
    return 'unarchive';
  }
  return null;
}

function reasonLengthError(value: string): string | undefined {
  return value.length > ARCHIVE_REASON_MAX_LENGTH
    ? `Reason must be ${ARCHIVE_REASON_MAX_LENGTH} characters or fewer`
    : undefined;
}

export function archiveFields(mode: RunArchiveMode): readonly RationaleField[] {
  if (mode === 'archive') {
    return [
      {
        name: 'reason',
        label: 'Reason',
        type: 'textarea',
        required: true,
        placeholder: 'Why is this run being hidden?',
        validate: reasonLengthError,
      },
    ];
  }
  return [
    {
      name: 'reason',
      label: 'Reason (optional)',
      type: 'textarea',
      required: false,
      placeholder: 'Optional note',
      validate: reasonLengthError,
    },
  ];
}

export function archiveButtonLabel(mode: RunArchiveMode): string {
  return mode === 'archive' ? 'Archive run' : 'Unarchive run';
}

export function archiveConfirmLabel(mode: RunArchiveMode): string {
  return mode === 'archive' ? 'Archive' : 'Unarchive';
}

export function archiveDialogTitle(mode: RunArchiveMode): string {
  return mode === 'archive' ? 'Archive run' : 'Unarchive run';
}

export function archiveIntent(mode: RunArchiveMode): 'warning' | 'info' {
  return mode === 'archive' ? 'warning' : 'info';
}

export function archiveConsequence(mode: RunArchiveMode): string {
  return mode === 'archive'
    ? 'This hides the run from the default review queue. The run stays fully accessible and can be unarchived at any time.'
    : 'This returns the run to the default review queue.';
}

export function mapArchiveErrorCode(code: string | undefined): string | undefined {
  switch (code) {
    case 'ARCHIVE_NOT_APPLICABLE':
      return "This run's hidden state changed — refresh and try again.";
    case 'IDEMPOTENCY_KEY_CONFLICT':
      return 'This action was already submitted — refresh to see the current state.';
    case 'RUN_NOT_FOUND':
      return 'This run no longer exists.';
    default:
      return undefined;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run src/features/workflows/runArchiveView.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add deliveryline-frontend/src/features/workflows/runArchiveView.ts deliveryline-frontend/src/features/workflows/runArchiveView.test.ts
git commit -m "feat(archive-ui): pure view helpers for the run archive control"
```

---

### Task 4: `RunArchiveControl` container component

**Files:**
- Create: `deliveryline-frontend/src/features/workflows/components/RunArchiveControl.tsx`
- Test: `deliveryline-frontend/src/features/workflows/components/RunArchiveControl.test.tsx`

**Interfaces:**
- Consumes: `useAllowedActions` (`../hooks/useAllowedActions`), `useArchiveRun` (`../hooks/useArchiveRun`), `useUnarchiveRun` (`../hooks/useUnarchiveRun`), all `runArchiveView` helpers, `GovernedButton` (`@/components/actions/GovernedButton`), `RationaleCaptureDialog` + `RationaleValues` (`@/components/overlays/RationaleCaptureDialog`), `isProblemDetailsError` (`@/lib/api/problemDetails`).
- Produces: `RunArchiveControl({ workflowRunId }: { workflowRunId: string }): JSX.Element | null`.

Test IDs: root `run-archive-control`; trigger `run-archive-button`; dialog `run-archive-dialog`; error `run-archive-error`.

- [ ] **Step 1: Write the failing test**

```tsx
// RunArchiveControl.test.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { RunArchiveControl } from './RunArchiveControl';

const RUN_ID = 'run_arch_ctrl_001';
const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
const ARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/archive';
const UNARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/unarchive';

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}
function renderControl() {
  return render(
    <QueryClientProvider client={client()}>
      <RunArchiveControl workflowRunId={RUN_ID} />
    </QueryClientProvider>,
  );
}
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('renders nothing when neither archive_run nor unarchive_run is advertised', async () => {
  server.use(http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['retry'] })));
  renderControl();
  await waitFor(() =>
    expect(screen.queryByTestId('run-archive-button')).not.toBeInTheDocument(),
  );
});

it('archive: shows "Archive run", blocks confirm until a reason, then POSTs { reason }', async () => {
  let body: Record<string, unknown> | undefined;
  server.use(
    http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['archive_run'] })),
    http.post(ARCHIVE_URL, async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Failed', archivedAt: '2026-07-03T00:00:00Z' });
    }),
  );
  renderControl();
  fireEvent.click(await screen.findByRole('button', { name: 'Archive run' }));
  const dialog = screen.getByTestId('run-archive-dialog');
  // Confirm disabled with an empty required reason.
  expect(within(dialog).getByRole('button', { name: 'Archive' })).toBeDisabled();
  fireEvent.change(within(dialog).getByLabelText(/reason/i), {
    target: { value: 'obsolete run' },
  });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Archive' }));
  await waitFor(() => expect(body).toBeDefined());
  expect(body).toEqual({ reason: 'obsolete run' });
});

it('unarchive: shows "Unarchive run"; confirm enabled with no reason', async () => {
  let called = false;
  server.use(
    http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['unarchive_run'] })),
    http.post(UNARCHIVE_URL, async () => {
      called = true;
      return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Failed', archivedAt: null });
    }),
  );
  renderControl();
  fireEvent.click(await screen.findByRole('button', { name: 'Unarchive run' }));
  const dialog = screen.getByTestId('run-archive-dialog');
  const confirm = within(dialog).getByRole('button', { name: 'Unarchive' });
  expect(confirm).toBeEnabled();
  fireEvent.click(confirm);
  await waitFor(() => expect(called).toBe(true));
});

it('surfaces an inline message on ARCHIVE_NOT_APPLICABLE (409)', async () => {
  server.use(
    http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['archive_run'] })),
    http.post(ARCHIVE_URL, () =>
      HttpResponse.json(
        {
          type: 'about:blank',
          title: 'x',
          status: 409,
          detail: 'x',
          instance: `/api/v1/workflows/${RUN_ID}/archive`,
          code: 'ARCHIVE_NOT_APPLICABLE',
          retryable: false,
        },
        { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
      ),
    ),
  );
  renderControl();
  fireEvent.click(await screen.findByRole('button', { name: 'Archive run' }));
  const dialog = screen.getByTestId('run-archive-dialog');
  fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'x' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Archive' }));
  await waitFor(() =>
    expect(screen.getByTestId('run-archive-error')).toHaveTextContent(/refresh/i),
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/features/workflows/components/RunArchiveControl.test.tsx`
Expected: FAIL — `Failed to resolve import "./RunArchiveControl"`.

- [ ] **Step 3: Write minimal implementation**

```tsx
// RunArchiveControl.tsx
/**
 * Story 3d-8 FE gap — the run Archive / Unarchive control. Self-hiding container: reads the
 * workflow_owner allowed-actions matrix and renders exactly one governed button (Archive run
 * for a live run, Unarchive run for a hidden run), capturing a reason via the shared
 * `RationaleCaptureDialog`. On success the mutation factory invalidates detail + lists, so the
 * allowed-actions refetch flips the control automatically. The reason is user-authored and is
 * NEVER logged (T-LOG-PII).
 */
import { useState } from 'react';

import { GovernedButton } from '@/components/actions/GovernedButton';
import {
  RationaleCaptureDialog,
  type RationaleValues,
} from '@/components/overlays/RationaleCaptureDialog';
import { isProblemDetailsError } from '@/lib/api/problemDetails';

import { useAllowedActions } from '../hooks/useAllowedActions';
import { useArchiveRun } from '../hooks/useArchiveRun';
import { useUnarchiveRun } from '../hooks/useUnarchiveRun';
import {
  archiveButtonLabel,
  archiveConfirmLabel,
  archiveConsequence,
  archiveDialogTitle,
  archiveFields,
  archiveIntent,
  mapArchiveErrorCode,
  resolveArchiveMode,
} from '../runArchiveView';

export function RunArchiveControl({ workflowRunId }: { workflowRunId: string }) {
  const allowed = useAllowedActions(workflowRunId, 'workflow_owner');
  const archive = useArchiveRun(workflowRunId);
  const unarchive = useUnarchiveRun(workflowRunId);
  const [open, setOpen] = useState(false);

  const mode = resolveArchiveMode(allowed.data?.actions);
  if (mode === null) {
    return null;
  }

  const active = mode === 'archive' ? archive : unarchive;
  const pending = active.isPending;
  const errorMessage = isProblemDetailsError(active.error)
    ? mapArchiveErrorCode(active.error.code)
    : undefined;

  function handleConfirm(values: RationaleValues) {
    const reason = values.reason ?? '';
    if (mode === 'archive') {
      archive.mutate({ reason }, { onSuccess: () => setOpen(false) });
    } else {
      unarchive.mutate({ reason }, { onSuccess: () => setOpen(false) });
    }
  }

  return (
    <div data-testid="run-archive-control" className="flex flex-col gap-1">
      <div>
        <GovernedButton
          priority="secondary"
          workflowState={pending ? 'submitting' : undefined}
          onClick={() => setOpen(true)}
          testId="run-archive-button"
        >
          {archiveButtonLabel(mode)}
        </GovernedButton>
      </div>
      {errorMessage !== undefined ? (
        <p
          role="alert"
          data-testid="run-archive-error"
          className="text-meta text-state-error-foreground"
        >
          {errorMessage}
        </p>
      ) : null}
      <RationaleCaptureDialog
        open={open}
        onOpenChange={setOpen}
        title={archiveDialogTitle(mode)}
        intent={archiveIntent(mode)}
        consequence={archiveConsequence(mode)}
        fields={archiveFields(mode)}
        confirmLabel={archiveConfirmLabel(mode)}
        onConfirm={handleConfirm}
        isConfirming={pending}
        testId="run-archive-dialog"
      />
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run src/features/workflows/components/RunArchiveControl.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add deliveryline-frontend/src/features/workflows/components/RunArchiveControl.tsx deliveryline-frontend/src/features/workflows/components/RunArchiveControl.test.tsx
git commit -m "feat(archive-ui): RunArchiveControl self-hiding archive/unarchive container"
```

---

### Task 5: Wire `RunArchiveControl` into the run-detail route

**Files:**
- Modify: `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (add the import; render the control in the run-actions area beneath the run id/state header, before the Decision Bar)
- Modify: `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.test.tsx` (add one wiring test)

**Interfaces:**
- Consumes: `RunArchiveControl` (`@/features/workflows/components/RunArchiveControl`).
- Produces: nothing new (integration only).

- [ ] **Step 1: Write the failing test** (append to `index.test.tsx`)

```tsx
// index.test.tsx — new describe block appended at end of file
import { RUN_ID as _RUN_ID } from '(no import needed — reuse existing RUN_ID + helpers in this file)';

describe('WorkflowDetail route — archive control wiring (story 3d-8 FE gap)', () => {
  it('renders the Archive run button when the workflow_owner matrix advertises archive_run', async () => {
    server.use(
      http.get(`http://localhost/api/v1/workflows/:workflowRunId/allowed-actions`, () =>
        HttpResponse.json({ actions: ['archive_run'] }),
      ),
      http.get(DETAIL_URL, () => detailWith([SPEC_ARTIFACT])),
    );
    renderRoute();
    expect(await screen.findByRole('button', { name: 'Archive run' })).toBeInTheDocument();
  });
});
```

Note: `RUN_ID`, `DETAIL_URL`, `SPEC_ARTIFACT`, `detailWith`, `renderRoute`, `server`, `http`, `HttpResponse`, `screen`, `describe`, `it`, `expect` are already imported/defined at the top of this file (see lines 1–66). Do NOT re-import them; just append the `describe` block. Remove the placeholder pseudo-import line above.

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/routes/workflows/\$workflowRunId/index.test.tsx -t "archive control wiring"`
Expected: FAIL — no button named "Archive run" (control not yet rendered).

- [ ] **Step 3: Add the wiring** in `index.tsx`

Add the import beside the other `@/features/workflows/components/*` imports:

```tsx
import { RunArchiveControl } from '@/features/workflows/components/RunArchiveControl';
```

Render the control just after the run id/state `<p>` header (the element ending at `index.tsx:273`, the `<p>` that prints `<code>{workflowRunId}</code> · state ...`) and before the descriptive skeleton paragraph:

```tsx
      </p>
      {/* Story 3d-8 FE gap — run Archive / Unarchive control. Self-hiding: renders only when
          the workflow_owner allowed-actions matrix advertises archive_run / unarchive_run. */}
      <RunArchiveControl workflowRunId={workflowRunId} />
      <p className="text-body text-text-secondary max-w-prose">
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run src/routes/workflows/\$workflowRunId/index.test.tsx`
Expected: PASS (existing tests + the new wiring test).

- [ ] **Step 5: Full gate + commit**

```bash
cd deliveryline-frontend
npx vitest run src/features/workflows/hooks/useArchiveRun.test.tsx src/features/workflows/hooks/useUnarchiveRun.test.tsx src/features/workflows/runArchiveView.test.ts src/features/workflows/components/RunArchiveControl.test.tsx "src/routes/workflows/\$workflowRunId/index.test.tsx"
npm run lint
npm run format:check
npm run build
```
Expected: all green. If `format:check` flags files, run `npm run format` (prettier write) and re-stage.

```bash
git add deliveryline-frontend/src/routes/workflows/
git commit -m "feat(archive-ui): wire RunArchiveControl into the run-detail page"
```

---

## Self-Review

**Spec coverage:**
- Zero backend/schema/regen → no backend tasks; allowed-actions gate used (Tasks 3–5). ✓
- Two hooks mirroring `useTakeoverWorkflow` on `useWorkflowMutation` (Tasks 1–2). ✓
- Self-hiding `RunArchiveControl` gated on `archive_run`/`unarchive_run`, `RationaleCaptureDialog`, archive reason required (≤512) / unarchive optional (Tasks 3–4). ✓
- Run-detail placement + `GovernedButton priority="secondary"` + submitting state (Tasks 4–5). ✓
- Typed error handling (`mapArchiveErrorCode`, Task 3; surfaced in Task 4). ✓
- Idempotency + detail/lists invalidation → button auto-flip (inherited from factory; asserted via hook tests). ✓
- Tests: hook + container + route wiring, plus the prettier/lint/build gate. ✓

**Placeholder scan:** The only pseudo-token is the deliberately-flagged pseudo-import in Task 5 Step 1, with an explicit instruction to delete it and reuse the file's existing imports. No other TBD/TODO. ✓

**Type consistency:** `ArchiveRunResponse` defined in Task 1, reused in Task 2. `RunArchiveMode`, `resolveArchiveMode`, `archiveFields`, `archiveButtonLabel`, `archiveConfirmLabel`, `archiveDialogTitle`, `archiveIntent`, `archiveConsequence`, `mapArchiveErrorCode` defined in Task 3, consumed with matching names in Task 4. `useAllowedActions(id, 'workflow_owner').data.actions` shape matches `AllowedActions.actions: string[]`. `GovernedButton` props (`priority`, `workflowState`, `onClick`, `testId`, children) match its contract. `OverlayIntent` values (`warning`/`info`) are valid. ✓
