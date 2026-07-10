/**
 * Story 3i-2 (AC5/AC6/AC8) — `IntakeBrowse`: renders the candidate-ticket list for the selected
 * project, drives the ticket-query params from the URL-owned filter controls, hides the surface on a
 * `TICKET_QUERY_NOT_SUPPORTED` 404 (never on a hardcoded connector kind), starts an independent
 * idempotency-keyed run per row via the EXISTING submit endpoint, announces the settled result count,
 * and has zero axe violations.
 *
 * `useReturnToRunContext` (used by `<ErrorState>`) is mocked at its own module (memory
 * `vitest-cross-file-router-mock`) so the view needs no router context. Radix jsdom shims support the
 * Select/Checkbox primitives.
 */
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import { expectNoA11yViolations } from '@/test/a11y/axe';
import { EMPTY_INTAKE_FILTERS, type IntakeFilters } from '@/lib/queryKeys/intakeKeys';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { IntakeBrowse } from '../IntakeBrowse';

const PROJECT_ID = 'prj_acme0001';
const PROJECTS_URL = 'http://localhost/api/v1/projects';
const TICKET_QUERY_URL = `http://localhost/api/v1/projects/${PROJECT_ID}/ticket-query`;
const SUBMIT_URL = 'http://localhost/api/v1/workflows/submit-workflow';
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function Harness({ initial = EMPTY_INTAKE_FILTERS }: { initial?: IntakeFilters }) {
  const [filters, setFilters] = useState(initial);
  return <IntakeBrowse filters={filters} onFiltersChange={setFilters} />;
}

function renderBrowse(initial?: IntakeFilters) {
  return render(
    <QueryClientProvider client={freshClient()}>
      <Harness {...(initial !== undefined ? { initial } : {})} />
    </QueryClientProvider>,
  );
}

function stubProjects() {
  server.use(
    http.get(PROJECTS_URL, () =>
      HttpResponse.json([{ id: PROJECT_ID, slug: 'acme', name: 'Acme Widgets' }]),
    ),
  );
}

/**
 * Serve one page of the browse envelope. `total` defaults to the row count (a complete page); pass a
 * larger value to simulate a page capped by `limit` at the source.
 */
function stubTickets(
  tickets: { ticketRef: string; title: string; summary: string | null }[],
  onRequest?: (url: URL) => void,
  total: number = tickets.length,
) {
  server.use(
    http.get(TICKET_QUERY_URL, ({ request }) => {
      onRequest?.(new URL(request.url));
      return HttpResponse.json({ tickets, total, truncated: total > tickets.length });
    }),
  );
}

/** The capability-gated 404 the backend returns for a connector that cannot be browsed. */
function stubNotSupported() {
  server.use(
    http.get(TICKET_QUERY_URL, () =>
      HttpResponse.json(
        {
          type: 'https://deliveryline.local/problems/ticket-query-not-supported',
          title: 'Ticket query not supported',
          status: 404,
          code: 'TICKET_QUERY_NOT_SUPPORTED',
          retryable: false,
        },
        { status: 404, headers: { 'content-type': 'application/problem+json' } },
      ),
    ),
  );
}

beforeEach(() => {
  Object.defineProperty(Element.prototype, 'hasPointerCapture', {
    configurable: true,
    value: () => false,
  });
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    configurable: true,
    value: () => undefined,
  });
  // Radix's Checkbox measures its indicator via `useSize` → ResizeObserver, which jsdom lacks.
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  stubProjects();
});

describe('IntakeBrowse', () => {
  it('renders the candidate tickets returned for the adopted project', async () => {
    stubTickets([
      { ticketRef: 'PROJ-1', title: 'Fix rounding', summary: 'Totals round wrong' },
      { ticketRef: 'PROJ-2', title: 'Body-less ticket', summary: null },
    ]);

    renderBrowse();

    expect(await screen.findByTestId('intake-ticket-PROJ-1')).toBeInTheDocument();
    expect(screen.getByTestId('intake-ticket-PROJ-2')).toBeInTheDocument();
    expect(screen.getByText('Fix rounding')).toBeInTheDocument();
    expect(screen.getByText('Totals round wrong')).toBeInTheDocument();
  });

  it('drives the ticket-query params from the filter controls', async () => {
    const user = userEvent.setup();
    let captured: URL | undefined;
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }], (url) => {
      captured = url;
    });

    renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');

    await user.type(screen.getByTestId('intake-filter-assignee'), 'acct-1');
    await user.type(screen.getByTestId('intake-filter-state'), 'To Do');
    await user.type(screen.getByTestId('intake-filter-component-add'), 'billing');
    await user.click(screen.getByTestId('intake-filter-apply'));

    await waitFor(() => {
      expect(captured?.searchParams.get('assignee')).toBe('acct-1');
    });
    expect(captured?.searchParams.get('state')).toBe('To Do');
    expect(captured?.searchParams.getAll('components')).toEqual(['billing']);
    expect(captured?.searchParams.get('limit')).toBe('50');
  });

  it('omits absent filter fields from the query string entirely', async () => {
    let captured: URL | undefined;
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }], (url) => {
      captured = url;
    });

    renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');

    expect(captured?.searchParams.has('assignee')).toBe(false);
    expect(captured?.searchParams.has('state')).toBe(false);
    expect(captured?.searchParams.has('components')).toBe(false);
  });

  it('adds a component as a labelled checkbox that unchecks to remove it', async () => {
    const user = userEvent.setup();
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }]);

    renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');

    await user.type(screen.getByTestId('intake-filter-component-add'), 'billing');
    await user.click(screen.getByTestId('intake-filter-apply'));

    const checkbox = await screen.findByLabelText('billing');
    expect(checkbox).toBeChecked();

    await user.click(checkbox);
    await waitFor(() => {
      expect(screen.queryByLabelText('billing')).not.toBeInTheDocument();
    });
  });

  it('hides the browse surface on a TICKET_QUERY_NOT_SUPPORTED 404, without a kind check', async () => {
    stubNotSupported();

    renderBrowse();

    expect(
      await screen.findByText('Browsing is not available for this project'),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('intake-ticket-list')).not.toBeInTheDocument();
    // A capability-gated 404 is NOT a retryable failure surface.
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument();
  });

  it('starts a run per row through the existing submit endpoint with a fresh idempotency key', async () => {
    const user = userEvent.setup();
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }]);
    const submissions: { body: unknown; idempotencyKey: string | null }[] = [];
    server.use(
      http.post(SUBMIT_URL, async ({ request }) => {
        submissions.push({
          body: await request.json(),
          idempotencyKey: request.headers.get('Idempotency-Key'),
        });
        return HttpResponse.json({ workflowRunId: 'run_new0001' });
      }),
    );

    renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');

    // The action is gated on an actor identity — no identity, no submit.
    expect(screen.getByTestId('intake-start-run')).toBeDisabled();
    await user.type(screen.getByTestId('intake-actor-identity'), 'alex@example.com');
    await user.click(screen.getByTestId('intake-start-run'));

    await waitFor(() => {
      expect(submissions).toHaveLength(1);
    });
    expect(submissions[0]?.body).toEqual({
      linearTicketReference: 'PROJ-1',
      actorIdentity: 'alex@example.com',
      actorType: 'HUMAN',
      projectReference: PROJECT_ID,
    });
    expect(submissions[0]?.idempotencyKey).toMatch(UUID_SHAPE);
    expect(await screen.findByRole('button', { name: 'Run started' })).toBeDisabled();
  });

  it('keeps each row independent — one row failing does not block the other', async () => {
    const user = userEvent.setup();
    stubTickets([
      { ticketRef: 'PROJ-1', title: 'Fails', summary: null },
      { ticketRef: 'PROJ-2', title: 'Succeeds', summary: null },
    ]);
    const keys: string[] = [];
    server.use(
      http.post(SUBMIT_URL, async ({ request }) => {
        const body = (await request.json()) as { linearTicketReference: string };
        keys.push(request.headers.get('Idempotency-Key') ?? '');
        if (body.linearTicketReference === 'PROJ-1') {
          return HttpResponse.json(
            { status: 500, code: 'INTERNAL_ERROR', retryable: true },
            { status: 500, headers: { 'content-type': 'application/problem+json' } },
          );
        }
        return HttpResponse.json({ workflowRunId: 'run_new0002' });
      }),
    );

    renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');
    await user.type(screen.getByTestId('intake-actor-identity'), 'alex@example.com');

    const [firstButton, secondButton] = screen.getAllByTestId('intake-start-run');
    await user.click(firstButton!);
    await screen.findByTestId('intake-row-error');

    // The failing row surfaced its own error; the sibling row is untouched and still submittable.
    expect(secondButton).toBeEnabled();
    await user.click(secondButton!);
    expect(await screen.findByRole('button', { name: 'Run started' })).toBeInTheDocument();

    // Each attempt minted its OWN key — no shared/reused key across rows.
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toEqual(keys[1]);
  });

  it('announces the settled candidate count after a filter change', async () => {
    stubTickets([
      { ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null },
      { ticketRef: 'PROJ-2', title: 'Add audit log', summary: null },
    ]);

    renderBrowse();

    // `useLiveAnnouncement` defers by one commit — assert under waitFor.
    await waitFor(() => {
      expect(screen.getByTestId('intake-announcer')).toHaveTextContent(
        'Ticket intake filtered to 2 tickets',
      );
    });
  });

  it('shows the filtered-empty surface with a clear-filters affordance', async () => {
    stubTickets([]);

    renderBrowse({ ...EMPTY_INTAKE_FILTERS, assignee: 'nobody' });

    expect(await screen.findByTestId('intake-clear-filters')).toBeInTheDocument();
  });

  /**
   * Code-review D3 — a capped page must SAY it is capped. Without this the operator cannot tell a
   * complete backlog from a truncated slice, and has no reason to narrow the filter.
   */
  it('warns that the page is truncated when the source matched more than it returned', async () => {
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }], undefined, 412);

    renderBrowse();

    const notice = await screen.findByTestId('intake-truncation-notice');
    expect(notice).toHaveTextContent('Showing 1 of 412 matching tickets');
  });

  it('announces the truncation so screen-reader users learn it too', async () => {
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }], undefined, 412);

    renderBrowse();

    await waitFor(() => {
      expect(screen.getByTestId('intake-announcer')).toHaveTextContent(
        'Ticket intake filtered to 1 ticket of 412',
      );
    });
  });

  it('shows no truncation notice on a complete page', async () => {
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: null }]);

    renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');

    expect(screen.queryByTestId('intake-truncation-notice')).not.toBeInTheDocument();
  });

  it('is axe-clean', async () => {
    stubTickets([{ ticketRef: 'PROJ-1', title: 'Fix rounding', summary: 'Totals round wrong' }]);

    const { container } = renderBrowse();
    await screen.findByTestId('intake-ticket-PROJ-1');

    await expectNoA11yViolations(container);
  });
});
