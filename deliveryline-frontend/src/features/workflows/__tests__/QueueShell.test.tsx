/**
 * Story 2.20 (AC1–AC10) — `QueueShell` renders exactly one of the four non-row
 * states + the populated list, announces transitions, retries, clears filters, and
 * logs the error surface.
 *
 * `useReturnToRunContext` (consumed by `<ErrorState>`) is mocked at its own module
 * — NOT the router — so this file stays isolated from `@tanstack/react-router`
 * internals (mirrors `ErrorState.test.tsx`; memory `vitest-cross-file-router-mock`).
 * `<Link>` (used by the placeholder row) is stubbed to a plain anchor so the
 * populated state needs no router context.
 */
import type { ComponentProps, ReactNode } from 'react';
import type * as ReactRouter from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import { expectNoA11yViolations } from '@/test/a11y/axe';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof ReactRouter>();
  // Strip router-only props (`to`/`params`) so they don't reach the DOM <a>; the
  // rest (className/style/data-testid/children) forwards to a plain anchor.
  const LinkMock = ({
    to,
    params,
    children,
    ...rest
  }: ComponentProps<'a'> & { to?: string; params?: Record<string, string> }) => {
    void to;
    void params;
    return <a {...rest}>{children}</a>;
  };
  return { ...actual, Link: LinkMock };
});

import { QueueShell } from '../QueueShell';

const LIST_URL = 'http://localhost/api/v1/workflows';

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderShell(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

function installRadixSelectJsdomShims() {
  Object.defineProperty(Element.prototype, 'hasPointerCapture', {
    configurable: true,
    value: () => false,
  });
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    configurable: true,
    value: () => undefined,
  });
}
function problemResponse() {
  return new HttpResponse(
    JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', retryable: true, title: 'x' }),
    { status: 500, headers: { 'content-type': 'application/problem+json' } },
  );
}

beforeEach(() => {
  installRadixSelectJsdomShims();
  backSpy.mockClear();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

describe('QueueShell', () => {
  it('AC2 — loading renders skeleton rows, never a spinner', async () => {
    // A short RESOLVING delay so the first paint is `isPending` (asserted
    // synchronously) without leaving a never-resolving request to be aborted at
    // teardown (which surfaces as an unhandled rejection and corrupts the worker).
    server.use(
      http.get(LIST_URL, async () => {
        await delay(20);
        return HttpResponse.json([]);
      }),
    );

    const { container } = renderShell(<QueueShell />);

    // Synchronous first paint is the loading state (no cached data yet).
    expect(container.querySelector('[data-queue-state="loading"]')).not.toBeNull();
    expect(screen.getByTestId('queue-loading')).toBeInTheDocument();
    // AC2 / Trap T2 — skeleton pulses, never spins.
    expect(container.querySelector('.animate-spin')).toBeNull();
    expect(container.querySelector('.animate-pulse')).not.toBeNull();
    expect(screen.getByTestId('queue-announcer')).toHaveTextContent('Loading the review queue');

    // Let the query settle so nothing is left pending after the test.
    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="empty"]')).not.toBeNull(),
    );
  });

  it('AC3 — empty state (no filters) renders the queue EmptyState + announcer', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="empty"]')).not.toBeNull(),
    );
    const empty = screen.getByTestId('empty-state');
    expect(empty).toHaveAttribute('data-variant', 'queue');
    // The announcer (useLiveAnnouncement) lags `data-queue-state` by one commit by
    // design, so poll for it rather than reading it on the same tick as the state flip.
    await waitFor(() =>
      expect(screen.getByTestId('queue-announcer')).toHaveTextContent('Review queue is empty'),
    );
  });

  it('AC4 — filtered-empty is distinct from empty and Clear filters fires + logs', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const onClearFilters = vi.fn();

    const { container } = renderShell(
      <QueueShell filters={{ state: 'Executing' }} onClearFilters={onClearFilters} />,
    );

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="filtered-empty"]')).not.toBeNull(),
    );
    expect(screen.getByTestId('empty-state')).toHaveAttribute('data-variant', 'filtered');
    // Announcer lags the state flip by one commit (useLiveAnnouncement) — poll for it.
    await waitFor(() =>
      expect(screen.getByTestId('queue-announcer')).toHaveTextContent(
        'No runs match the current filters',
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(onClearFilters).toHaveBeenCalledTimes(1);
    expect(console.info).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'queue.clearFilters' }),
    );
  });

  it('AC5 — error renders code-derived copy (never the raw status) + logs + Retry refetches', async () => {
    let calls = 0;
    server.use(
      http.get(LIST_URL, () => {
        calls += 1;
        return problemResponse();
      }),
    );

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="error"]')).not.toBeNull(),
    );
    const errorEl = screen.getByTestId('error-state');
    expect(errorEl).toHaveTextContent('The server had a problem loading the review queue.');
    // Trap T3 — no raw status / message leakage.
    expect(errorEl).not.toHaveTextContent('500');
    expect(console.warn).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'queue.loadError', code: 'INTERNAL_ERROR' }),
    );

    expect(calls).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(calls).toBe(2));
    expect(console.info).toHaveBeenCalledWith(expect.objectContaining({ event: 'queue.retry' }));
  });

  it('AC9 — populated delegates rows through the renderItem seam (no RunReviewQueueItem import)', async () => {
    server.use(
      http.get(LIST_URL, () =>
        HttpResponse.json([{ workflowRunId: 'run_abcd0001', currentState: 'Executing' }]),
      ),
    );

    const { container } = renderShell(
      <QueueShell
        renderItem={(item) => <div data-testid="custom-row">{item.workflowRunId}</div>}
      />,
    );

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="populated"]')).not.toBeNull(),
    );
    expect(screen.getByTestId('custom-row')).toHaveTextContent('run_abcd0001');
    // Announcer lags the state flip by one commit (useLiveAnnouncement) — poll for it.
    await waitFor(() =>
      expect(screen.getByTestId('queue-announcer')).toHaveTextContent(
        'Review queue loaded: 1 run available',
      ),
    );
  });

  it('AC9 — populated falls back to the placeholder row when no renderItem is given', async () => {
    server.use(
      http.get(LIST_URL, () =>
        HttpResponse.json([{ workflowRunId: 'run_abcd0001', currentState: 'Executing' }]),
      ),
    );

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="populated"]')).not.toBeNull(),
    );
    const rows = container.querySelectorAll('[data-testid="queue-placeholder-row"]');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('run_abcd0001');
  });

  it('AC8/AC10 — the announcer updates across the loading → populated transition', async () => {
    server.use(
      http.get(LIST_URL, async () => {
        await delay(20);
        return HttpResponse.json([{ workflowRunId: 'run_abcd0001', currentState: 'Executing' }]);
      }),
    );

    const { container } = renderShell(<QueueShell />);

    // First paint: loading + its announcement.
    expect(container.querySelector('[data-queue-state="loading"]')).not.toBeNull();
    expect(screen.getByTestId('queue-announcer')).toHaveTextContent('Loading the review queue');

    // After the query resolves: the SAME live region flips to the populated text.
    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="populated"]')).not.toBeNull(),
    );
    // Announcer lags the state flip by one commit (useLiveAnnouncement) — poll for it.
    await waitFor(() =>
      expect(screen.getByTestId('queue-announcer')).toHaveTextContent(
        'Review queue loaded: 1 run available',
      ),
    );
  });

  it('AC3/OQ-2 — empty docs CTA is a real link when VITE_DOCS_URL is a safe URL', async () => {
    vi.stubEnv('VITE_DOCS_URL', 'https://docs.example.com/quickstart');
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="empty"]')).not.toBeNull(),
    );
    const link = screen.getByRole('link', { name: 'Read the getting-started guide' });
    expect(link).toHaveAttribute('href', 'https://docs.example.com/quickstart');
  });

  it('AC3/OQ-2 — empty docs CTA falls back to a disabled control for an unsafe scheme', async () => {
    vi.stubEnv('VITE_DOCS_URL', 'javascript:alert(1)');
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="empty"]')).not.toBeNull(),
    );
    // No anchor — never a dead/unsafe link; the fallback is a disabled button.
    expect(screen.queryByRole('link', { name: 'Read the getting-started guide' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Read the getting-started guide' })).toBeDisabled();
  });

  it('Task 8 — queue.loadError logs only the field-only contract, never the raw message', async () => {
    server.use(http.get(LIST_URL, () => problemResponse()));

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="error"]')).not.toBeNull(),
    );
    const warnMock = vi.mocked(console.warn);
    const loadErrorCall = warnMock.mock.calls.find(
      ([arg]) => (arg as { event?: string } | undefined)?.event === 'queue.loadError',
    );
    expect(loadErrorCall).toBeDefined();
    const payload = loadErrorCall![0] as Record<string, unknown>;
    // Forbidden-field contract — no `message`/payload/PII, only the documented fields.
    expect(payload).not.toHaveProperty('message');
    expect(Object.keys(payload).sort()).toEqual(['code', 'event', 'filtersActive', 'state']);
  });

  // Story 2.27 (Task 2, AC4) — every documented non-row state must pass a
  // WCAG-2.1-AA axe scan (the audit found QueueShell had no a11y scan at all).
  it('AC4 — empty / populated / error states each pass a wcag2aa axe scan', async () => {
    // Empty.
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const empty = renderShell(<QueueShell />);
    await waitFor(() =>
      expect(empty.container.querySelector('[data-queue-state="empty"]')).not.toBeNull(),
    );
    await expectNoA11yViolations(empty.container);
    cleanup();

    // Populated (through the renderItem seam).
    server.use(
      http.get(LIST_URL, () =>
        HttpResponse.json([{ workflowRunId: 'run_abcd0001', currentState: 'Executing' }]),
      ),
    );
    const populated = renderShell(
      <QueueShell
        renderItem={(item) => <div data-testid="custom-row">{item.workflowRunId}</div>}
      />,
    );
    await waitFor(() =>
      expect(populated.container.querySelector('[data-queue-state="populated"]')).not.toBeNull(),
    );
    await expectNoA11yViolations(populated.container);
    cleanup();

    // Error.
    server.use(http.get(LIST_URL, () => problemResponse()));
    const errored = renderShell(<QueueShell />);
    await waitFor(() =>
      expect(errored.container.querySelector('[data-queue-state="error"]')).not.toBeNull(),
    );
    await expectNoA11yViolations(errored.container);
  });

  it('M2/verified — a Retry click shows the loading skeleton (feedback) before settling', async () => {
    // Verifies the review-M2 "dead Retry button" concern is a non-issue: in this
    // project's TanStack v5 setup, refetch() on an errored query flips it back to
    // `isPending`, so the shell shows the loading skeleton during the retry rather
    // than a frozen error card. A manually-released gate makes the window observable.
    let releaseRetry: () => void = () => {};
    const retryGate = new Promise<void>((resolve) => {
      releaseRetry = resolve;
    });
    let calls = 0;
    server.use(
      http.get(LIST_URL, async () => {
        calls += 1;
        if (calls >= 2) {
          await retryGate;
        }
        return problemResponse();
      }),
    );

    const { container } = renderShell(<QueueShell />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="error"]')).not.toBeNull(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    // The retry produces visible feedback — the queue shows the loading skeleton.
    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="loading"]')).not.toBeNull(),
    );
    // Announcer lags the state flip by one commit (useLiveAnnouncement) — poll for it.
    await waitFor(() =>
      expect(screen.getByTestId('queue-announcer')).toHaveTextContent('Loading the review queue'),
    );

    // Release the gate; it settles back to error (still no data).
    releaseRetry();
    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="error"]')).not.toBeNull(),
    );
  });
});

// ---------------------------------------------------------------------------
// Story 3.29 (AC4/R5) — the "Show taken-over runs" filter toggle.
// ---------------------------------------------------------------------------
describe('QueueShell — taken-over filter toggle (story 3.29)', () => {
  it('toggles ON: navigates to ?state=TakenOver and logs the new state', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const onToggle = vi.fn();

    renderShell(<QueueShell filters={{}} onToggleTakenOverFilter={onToggle} />);

    const toggle = await screen.findByTestId('queue-takenover-filter-toggle');
    // Unfiltered → toggle is off.
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(toggle).toHaveTextContent('Show taken-over runs');

    fireEvent.click(toggle);
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(onToggle).toHaveBeenCalledWith('TakenOver');
    expect(console.info).toHaveBeenCalledWith({
      event: 'queue.toggleTakenOverFilter',
      state: 'TakenOver',
    });
  });

  it('reflects the active URL state and toggles OFF (clears ?state)', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const onToggle = vi.fn();

    renderShell(<QueueShell filters={{ state: 'TakenOver' }} onToggleTakenOverFilter={onToggle} />);

    const toggle = await screen.findByTestId('queue-takenover-filter-toggle');
    // The URL is the single source of truth — active state is reflected, not local.
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(toggle).toHaveTextContent('Showing taken-over runs');

    fireEvent.click(toggle);
    expect(onToggle).toHaveBeenCalledWith(undefined);
    expect(console.info).toHaveBeenCalledWith({
      event: 'queue.toggleTakenOverFilter',
      state: undefined,
    });
  });

  it('keeps the filtered-empty + Clear filters path working for ?state=TakenOver', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const onClearFilters = vi.fn();

    const { container } = renderShell(
      <QueueShell filters={{ state: 'TakenOver' }} onClearFilters={onClearFilters} />,
    );

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="filtered-empty"]')).not.toBeNull(),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(onClearFilters).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------------------
// Story 3d-8 (AC5) — the "Show archived runs" include-archived filter toggle.
// ---------------------------------------------------------------------------
describe('QueueShell — include-archived filter toggle (story 3d-8)', () => {
  it('toggles ON: requests includeArchived=true and logs the new flag', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const onToggle = vi.fn();

    renderShell(<QueueShell filters={{}} onToggleIncludeArchived={onToggle} />);

    const toggle = await screen.findByTestId('queue-include-archived-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(toggle).toHaveTextContent('Show archived runs');

    fireEvent.click(toggle);
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(onToggle).toHaveBeenCalledWith(true);
    expect(console.info).toHaveBeenCalledWith({
      event: 'queue.toggleIncludeArchivedFilter',
      includeArchived: true,
    });
  });

  it('reflects the active flag and toggles OFF (clears includeArchived)', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));
    const onToggle = vi.fn();

    renderShell(
      <QueueShell filters={{ includeArchived: true }} onToggleIncludeArchived={onToggle} />,
    );

    const toggle = await screen.findByTestId('queue-include-archived-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(toggle).toHaveTextContent('Showing archived runs');

    fireEvent.click(toggle);
    expect(onToggle).toHaveBeenCalledWith(undefined);
    expect(console.info).toHaveBeenCalledWith({
      event: 'queue.toggleIncludeArchivedFilter',
      includeArchived: undefined,
    });
  });
});

// ---------------------------------------------------------------------------
// Story 3f-6 - project filter selector.
// ---------------------------------------------------------------------------
describe('QueueShell - project filter selector (story 3f-6)', () => {
  it('changes the URL-owned project filter and logs only field-level state', async () => {
    server.use(
      http.get('http://localhost/api/v1/projects', () =>
        HttpResponse.json([
          { id: 'prj_alpha', slug: 'alpha', name: 'Alpha project', status: 'ACTIVE' },
          { id: 'prj_beta', slug: 'beta', name: 'Beta project', status: 'ACTIVE' },
        ]),
      ),
      http.get(LIST_URL, () => HttpResponse.json([])),
    );
    const onProjectFilterChange = vi.fn();
    const user = userEvent.setup();

    const { rerender } = renderShell(
      <QueueShell filters={{}} onProjectFilterChange={onProjectFilterChange} />,
    );

    await user.click(await screen.findByRole('combobox', { name: 'Project:' }));
    await user.click(await screen.findByRole('option', { name: 'Beta project' }));

    expect(onProjectFilterChange).toHaveBeenCalledWith('prj_beta');
    expect(console.info).toHaveBeenCalledWith({
      event: 'queue.projectFilterChange',
      projectId: 'prj_beta',
    });

    rerender(
      <QueryClientProvider client={freshClient()}>
        <QueueShell
          filters={{ projectId: 'prj_beta' }}
          onProjectFilterChange={onProjectFilterChange}
        />
      </QueryClientProvider>,
    );
    await user.click(screen.getByRole('combobox', { name: 'Project:' }));
    await user.click(await screen.findByRole('option', { name: 'All projects' }));

    expect(onProjectFilterChange).toHaveBeenCalledWith(undefined);
    expect(console.info).toHaveBeenCalledWith({
      event: 'queue.projectFilterChange',
      projectId: null,
    });
  });

  it('AC5 — the expanded project filter control passes a wcag2aa axe scan', async () => {
    server.use(
      http.get('http://localhost/api/v1/projects', () =>
        HttpResponse.json([
          { id: 'prj_alpha', slug: 'alpha', name: 'Alpha project', status: 'ACTIVE' },
          { id: 'prj_beta', slug: 'beta', name: 'Beta project', status: 'ACTIVE' },
        ]),
      ),
      http.get(LIST_URL, () => HttpResponse.json([])),
    );
    const user = userEvent.setup();
    renderShell(<QueueShell filters={{}} onProjectFilterChange={vi.fn()} />);

    // Expand the listbox so the axe scan covers the open combobox + options (Radix portals the
    // listbox onto document.body, so scan the whole document rather than the render container).
    await user.click(await screen.findByRole('combobox', { name: 'Project:' }));
    await screen.findByRole('option', { name: 'Beta project' });
    await expectNoA11yViolations(document.body);
  });

  it('announces the filtered-empty state after a project filter resolves', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    const { container } = renderShell(<QueueShell filters={{ projectId: 'prj_alpha' }} />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="filtered-empty"]')).not.toBeNull(),
    );
    await waitFor(() =>
      expect(screen.getByTestId('queue-announcer')).toHaveTextContent(
        'No runs match the current filters',
      ),
    );
  });
  it('treats an empty projectId as inactive so Clear filters is not shown for it alone', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])));

    const { container } = renderShell(<QueueShell filters={{ projectId: ' ' }} />);

    await waitFor(() =>
      expect(container.querySelector('[data-queue-state="empty"]')).not.toBeNull(),
    );
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull();
  });
});
