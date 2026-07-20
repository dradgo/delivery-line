/**
 * Story 4.2 (AC1–AC10) — `OperatorQueue`: renders the operator fleet queue, gates access via the
 * `useCanViewOperatorQueue` seam, applies URL-owned multi-valued filters (asserted at the request),
 * virtualizes >100 rows, paginates via the keyboard load-more affordance, shows the disabled
 * bulk-actions placeholder, announces filter changes, carries `X-Correlation-Id`, and has zero axe
 * violations.
 *
 * `<Link>` (used by the operator row) is stubbed to a plain anchor and `useReturnToRunContext`
 * (used by `<ErrorState>`) is mocked at its own module (memory `vitest-cross-file-router-mock`) so
 * the queue needs no router context. A no-op `ResizeObserver` + Radix jsdom shims support the
 * virtualizer + Radix Select/Checkbox.
 */
import { useState, type ComponentProps, type ReactNode } from 'react';
import type * as ReactRouter from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import { expectNoA11yViolations } from '@/test/a11y/axe';
import type { OperatorRunSummary } from '@/lib/api/queryOptions';
import type { OperatorQueueFilters } from '@/lib/queryKeys/operatorKeys';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof ReactRouter>();
  const LinkMock = ({
    to,
    params,
    children,
    ...rest
  }: ComponentProps<'a'> & { to?: string; params?: Record<string, string> }) => {
    void params;
    // A real Link renders an anchor WITH an href — keep it so the stretched-link overlay is a
    // proper (named) link and axe does not flag a hrefless anchor. The route path is a test stub.
    return (
      <a href={typeof to === 'string' ? to : '/'} {...rest}>
        {children}
      </a>
    );
  };
  return { ...actual, Link: LinkMock };
});

const canViewSpy = vi.fn(() => true);
vi.mock('../hooks/useCanViewOperatorQueue', () => ({
  useCanViewOperatorQueue: () => canViewSpy(),
}));

import { OperatorQueue } from '../OperatorQueue';

const OPERATOR_URL = 'http://localhost/api/v1/operator/runs';
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const EMPTY_FILTERS: OperatorQueueFilters = {
  states: [],
  failureCategories: [],
  runnerKinds: [],
  timeWindow: 'all',
};

function makeRow(i: number): NonNullable<OperatorRunSummary['runs']>[number] {
  return {
    runId: `run_op${String(i).padStart(4, '0')}`,
    currentState: 'Failed',
    operatorSignifier: 'FAILED',
    failureCategory: 'orphan',
    runnerKind: 'claude',
    escalationMarker: false,
    lastTransitionAt: '2026-05-30T12:00:00Z',
    actorIdentity: 'system',
    linkedTicketRef: null,
    linkedPrRef: null,
    oldestEventAt: null,
  };
}

function makeSummary(count: number, nextCursor: string | null, startAt = 0): OperatorRunSummary {
  return {
    total: count,
    byState: { Failed: count },
    byFailureCategory: { orphan: count },
    oldestEntryAt: null,
    runs: Array.from({ length: count }, (_, i) => makeRow(startAt + i)),
    nextCursor,
  };
}

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function Harness({ initial }: { initial: OperatorQueueFilters }) {
  const [filters, setFilters] = useState(initial);
  return <OperatorQueue filters={filters} onFiltersChange={setFilters} />;
}

function renderQueue(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

function installRadixJsdomShims() {
  Object.defineProperty(Element.prototype, 'hasPointerCapture', {
    configurable: true,
    value: () => false,
  });
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    configurable: true,
    value: () => undefined,
  });
}

beforeEach(() => {
  installRadixJsdomShims();
  canViewSpy.mockReturnValue(true);
  backSpy.mockClear();
  // jsdom reports 0 layout — give the virtualizer's scroll element a measured viewport so it
  // renders a window of rows (otherwise a 0-height viewport yields an empty range).
  Object.defineProperty(Element.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({
      width: 1000,
      height: 800,
      top: 0,
      left: 0,
      bottom: 800,
      right: 1000,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    }),
  });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get: () => 800,
  });
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
    configurable: true,
    get: () => 1000,
  });
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get: () => 800,
  });
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get: () => 1000,
  });
  // The virtualizer observes the scroll element via ResizeObserver — stub it AND fire the callback
  // with a sized rect on observe (jsdom lacks ResizeObserver).
  globalThis.ResizeObserver = class {
    private readonly cb: ResizeObserverCallback;
    constructor(cb: ResizeObserverCallback) {
      this.cb = cb;
    }
    observe(el: Element) {
      this.cb(
        [{ target: el, contentRect: el.getBoundingClientRect() } as ResizeObserverEntry],
        this as unknown as ResizeObserver,
      );
    }
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('OperatorQueue', () => {
  it('AC1 — renders the operator queue with rows when allowed', async () => {
    server.use(http.get(OPERATOR_URL, () => HttpResponse.json(makeSummary(3, null))));
    renderQueue(<OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />);

    expect(await screen.findByTestId('operator-queue')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getAllByTestId('run-review-queue-item').length).toBeGreaterThan(0),
    );
  });

  it('AC1/Reconciliation 6 — not-allowed renders the permission-restricted stub', async () => {
    canViewSpy.mockReturnValue(false);
    server.use(http.get(OPERATOR_URL, () => HttpResponse.json(makeSummary(3, null))));
    renderQueue(<OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />);

    expect(await screen.findByText('You do not have access to the operator queue.')).toBeVisible();
    expect(screen.queryByTestId('operator-queue-list')).toBeNull();
  });

  it('AC3 — active filters are threaded onto the request query params', async () => {
    let capturedUrl: URL | undefined;
    server.use(
      http.get(OPERATOR_URL, ({ request }) => {
        capturedUrl = new URL(request.url);
        return HttpResponse.json(makeSummary(1, null));
      }),
    );
    renderQueue(
      <OperatorQueue
        filters={{
          states: ['failed'],
          failureCategories: ['orphan'],
          runnerKinds: ['claude'],
          timeWindow: '24h',
        }}
        onFiltersChange={vi.fn()}
      />,
    );

    await waitFor(() => expect(capturedUrl).toBeDefined());
    const url = capturedUrl as URL;
    expect(url.searchParams.getAll('state')).toContain('failed');
    expect(url.searchParams.getAll('failureCategory')).toContain('orphan');
    expect(url.searchParams.getAll('runnerKind')).toContain('claude');
    expect(url.searchParams.get('since')).toBe('24h');
  });

  it('AC5 — virtualizes a >100-row page (windowed: only a fraction rendered)', async () => {
    server.use(http.get(OPERATOR_URL, () => HttpResponse.json(makeSummary(150, null))));
    renderQueue(<OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />);

    await waitFor(() =>
      expect(screen.getAllByTestId('run-review-queue-item').length).toBeGreaterThan(0),
    );
    // Windowed: nowhere near all 150 rows are in the DOM.
    expect(screen.getAllByTestId('run-review-queue-item').length).toBeLessThan(60);
  });

  it('AC5/AC7 — load-more fetches the next cursor page (multi-page)', async () => {
    server.use(
      http.get(OPERATOR_URL, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        return HttpResponse.json(
          cursor === 'cursor_page2'
            ? makeSummary(20, null, 100)
            : makeSummary(20, 'cursor_page2', 0),
        );
      }),
    );
    renderQueue(<OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />);

    const loadMore = await screen.findByTestId('operator-queue-load-more');
    await userEvent.click(loadMore);

    // Page 2's runs (run_op0100+) enter the virtualized window once appended.
    await waitFor(() => expect(screen.queryByTestId('operator-queue-load-more')).toBeNull());
  });

  it('AC6 — bulk-actions placeholder is visible but permanently disabled', async () => {
    server.use(http.get(OPERATOR_URL, () => HttpResponse.json(makeSummary(2, null))));
    renderQueue(<OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />);

    const bulk = await screen.findByTestId('operator-bulk-action-dropdown');
    expect(bulk).toBeDisabled();
    expect(bulk).toHaveTextContent('Bulk operator actions arrive in a future release');
    expect(screen.getByLabelText('Select multiple')).toBeInTheDocument();
  });

  it('AC8 — a filter change announces the resolved count + filter summary', async () => {
    server.use(http.get(OPERATOR_URL, () => HttpResponse.json(makeSummary(2, null))));
    renderQueue(<Harness initial={EMPTY_FILTERS} />);

    // Toggle the "failed" state checkbox in the sidebar (URL-owned in prod; the harness state here).
    const failedCheckbox = await screen.findByLabelText('Failed');
    await userEvent.click(failedCheckbox);

    await waitFor(() =>
      expect(screen.getByTestId('operator-queue-announcer')).toHaveTextContent(
        /Operator queue filtered to 2 runs/,
      ),
    );
    expect(screen.getByTestId('operator-queue-announcer')).toHaveTextContent(/state failed/);
  });

  it('AC9 — the operator-runs GET carries X-Correlation-Id', async () => {
    let seen: string | null = null;
    server.use(
      http.get(OPERATOR_URL, ({ request }) => {
        seen = request.headers.get('X-Correlation-Id');
        return HttpResponse.json(makeSummary(1, null));
      }),
    );
    renderQueue(<OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />);

    await waitFor(() => expect(seen).not.toBeNull());
    expect(seen as unknown as string).toMatch(UUID_SHAPE);
  });

  it('AC10 — the populated queue has no axe violations', async () => {
    server.use(http.get(OPERATOR_URL, () => HttpResponse.json(makeSummary(3, null))));
    const { container } = renderQueue(
      <OperatorQueue filters={EMPTY_FILTERS} onFiltersChange={vi.fn()} />,
    );

    await waitFor(() =>
      expect(screen.getAllByTestId('run-review-queue-item').length).toBeGreaterThan(0),
    );
    await expectNoA11yViolations(container);
  });
});
