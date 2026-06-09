/**
 * Story 2a.1 (Task 6, AC3/AC6/AC7/AC9) — `SubmitRunForm` component tests.
 *
 * Rendered inside a REAL in-memory TanStack Router (NOT a mocked `Link`) so the
 * success detail link resolves to a real `/workflows/$workflowRunId` href and the
 * `<ErrorState>` retry's `useReturnToRunContext` works — and so this file never
 * `vi.mock`s `@tanstack/react-router` (memory: vitest-cross-file-router-mock).
 * MSW serves the submit endpoint.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { NavigationBreadcrumbProvider } from '@/lib/navigation/NavigationBreadcrumbProvider';
import { server } from '@/test/server';
import { expectNoA11yViolations } from '@/test/a11y/axe';
import { SubmitRunForm } from './SubmitRunForm';

const SUBMIT_URL = 'http://localhost/api/v1/workflows/submit-workflow';

function buildRouter() {
  const rootRoute = createRootRoute();
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: SubmitRunForm,
  });
  // Stub targets so the form's `<Link>` builds real hrefs.
  const queueRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/workflows',
    component: () => <div>queue</div>,
  });
  const detailRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/workflows/$workflowRunId',
    component: () => <div>detail</div>,
  });
  const routeTree = rootRoute.addChildren([indexRoute, queueRoute, detailRoute]);
  return createRouter({ routeTree, history: createMemoryHistory({ initialEntries: ['/'] }) });
}

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = buildRouter();
  return render(
    <QueryClientProvider client={queryClient}>
      <NavigationBreadcrumbProvider>
        <RouterProvider router={router} />
      </NavigationBreadcrumbProvider>
    </QueryClientProvider>,
  );
}

function fillRequired() {
  fireEvent.change(screen.getByLabelText('Linear ticket reference'), {
    target: { value: 'LIN-2210' },
  });
  fireEvent.change(screen.getByLabelText('Actor identity'), {
    target: { value: 'alex@example.com' },
  });
}

function problem(code: string, status: number) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title: code,
      status,
      detail: 'x',
      instance: '/api/v1/workflows/submit-workflow',
      code,
      retryable: false,
    },
    { status, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
}

beforeEach(() => {
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(console, 'debug').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('SubmitRunForm — happy submit + persistent success (AC4/AC6)', () => {
  it('posts the body and renders a persistent success with runId, Inbox, and a detail link', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post(SUBMIT_URL, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ workflowRunId: 'run_new_001', currentState: 'Inbox' });
      }),
    );

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));

    const success = await screen.findByTestId('submit-success');
    expect(body).toEqual({
      linearTicketReference: 'LIN-2210',
      actorIdentity: 'alex@example.com',
      actorType: 'HUMAN',
    });
    expect(success).toHaveTextContent('run_new_001');
    expect(success).toHaveTextContent('Inbox');
    expect(screen.getByTestId('submit-success-detail-link')).toHaveAttribute(
      'href',
      '/workflows/run_new_001',
    );
  });
});

describe('SubmitRunForm — post-submit lifecycle (review fixes D1/D2)', () => {
  it('disables the submit button after a success so a re-click cannot mint a new key / duplicate run (D2)', async () => {
    server.use(
      http.post(SUBMIT_URL, () =>
        HttpResponse.json({ workflowRunId: 'run_new_010', currentState: 'Inbox' }),
      ),
    );

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));

    await screen.findByTestId('submit-success');
    expect(screen.getByTestId('submit-run-button')).toBeDisabled();
  });

  it('clears the success surface (and re-enables submit) when a field is edited after success (D1)', async () => {
    server.use(
      http.post(SUBMIT_URL, () =>
        HttpResponse.json({ workflowRunId: 'run_new_011', currentState: 'Inbox' }),
      ),
    );

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));
    await screen.findByTestId('submit-success');

    // Editing any field resets the settled mutation: the success card disappears
    // and the button is interactive again for the next (distinct) submission.
    fireEvent.change(screen.getByLabelText('Linear ticket reference'), {
      target: { value: 'LIN-2211' },
    });

    await waitFor(() => expect(screen.queryByTestId('submit-success')).not.toBeInTheDocument());
    expect(screen.getByTestId('submit-run-button')).not.toBeDisabled();
  });

  it('clears the error surface when a field is edited after a failure (D1)', async () => {
    server.use(http.post(SUBMIT_URL, () => problem('LINEAR_TICKET_NOT_FOUND', 404)));

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));
    await screen.findByTestId('submit-error');

    fireEvent.change(screen.getByLabelText('Linear ticket reference'), {
      target: { value: 'LIN-2211' },
    });

    await waitFor(() => expect(screen.queryByTestId('submit-error')).not.toBeInTheDocument());
  });
});

describe('SubmitRunForm — client validation gating (AC3/AC8b)', () => {
  it('blocks submit and shows per-field messages with NO network call when required fields are blank', async () => {
    let called = false;
    server.use(
      http.post(SUBMIT_URL, () => {
        called = true;
        return HttpResponse.json({ workflowRunId: 'run_x', currentState: 'Inbox' });
      }),
    );

    renderForm();
    await screen.findByTestId('submit-run-button');
    fireEvent.click(screen.getByTestId('submit-run-button'));

    expect(await screen.findByText(/Linear ticket reference is required/i)).toBeInTheDocument();
    expect(screen.getByText(/Actor identity is required/i)).toBeInTheDocument();
    expect(screen.getByTestId('submit-run-button')).toHaveAttribute('data-submit-state', 'blocked');
    expect(called).toBe(false);
  });

  it('blocks submit when a field exceeds 128 characters', async () => {
    let called = false;
    server.use(
      http.post(SUBMIT_URL, () => {
        called = true;
        return HttpResponse.json({ workflowRunId: 'run_x', currentState: 'Inbox' });
      }),
    );

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fireEvent.change(screen.getByLabelText('Linear ticket reference'), {
      target: { value: 'a'.repeat(129) },
    });
    fireEvent.change(screen.getByLabelText('Actor identity'), { target: { value: 'alex' } });
    fireEvent.click(screen.getByTestId('submit-run-button'));

    expect(await screen.findByText(/128 characters or fewer/i)).toBeInTheDocument();
    expect(called).toBe(false);
  });
});

describe('SubmitRunForm — failure surface via ProblemDetails (AC7/AC8c)', () => {
  it('renders the LINEAR_TICKET_NOT_FOUND error surface', async () => {
    server.use(http.post(SUBMIT_URL, () => problem('LINEAR_TICKET_NOT_FOUND', 404)));

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));

    const errorSurface = await screen.findByTestId('submit-error');
    expect(errorSurface).toHaveAttribute('data-error-code', 'LINEAR_TICKET_NOT_FOUND');
    expect(errorSurface).toHaveTextContent(/ticket could not be found/i);
  });

  it('renders the IDEMPOTENCY_KEY_CONFLICT error surface', async () => {
    server.use(http.post(SUBMIT_URL, () => problem('IDEMPOTENCY_KEY_CONFLICT', 409)));

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));

    const errorSurface = await screen.findByTestId('submit-error');
    expect(errorSurface).toHaveAttribute('data-error-code', 'IDEMPOTENCY_KEY_CONFLICT');
    expect(errorSurface).toHaveTextContent(/already submitted/i);
  });
});

// ---------------------------------------------------------------------------
// Story 2.25 a11y — axe scans of every documented form state + keyboard.
// ---------------------------------------------------------------------------
describe('SubmitRunForm a11y (story 2.25)', () => {
  it('AC2 — pristine (idle) form has no axe violations', async () => {
    const { container } = renderForm();
    await screen.findByLabelText('Linear ticket reference');
    await expectNoA11yViolations(container);
  });

  it('AC2 — invalid (blocked, per-field errors shown) state has no axe violations', async () => {
    const { container } = renderForm();
    await screen.findByTestId('submit-run-button');
    // Trigger validation display by clicking submit with blank fields.
    fireEvent.click(screen.getByTestId('submit-run-button'));
    await screen.findByText(/Linear ticket reference is required/i);
    await expectNoA11yViolations(container);
  });

  it('AC2 — success state has no axe violations', async () => {
    server.use(
      http.post(SUBMIT_URL, () =>
        HttpResponse.json({ workflowRunId: 'run_a11y_001', currentState: 'Inbox' }),
      ),
    );
    const { container } = renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));
    await screen.findByTestId('submit-success');
    await expectNoA11yViolations(container);
  });

  it('AC2 — error (failed submission) state has no axe violations', async () => {
    server.use(http.post(SUBMIT_URL, () => problem('LINEAR_TICKET_NOT_FOUND', 404)));
    const { container } = renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));
    await screen.findByTestId('submit-error');
    await expectNoA11yViolations(container);
  });

  // AC1 — keyboard: all fields Tab-reachable in DOM order; submit button reachable
  // and activatable. The Radix Select also renders a hidden <select aria-hidden="true">
  // as a native fallback — expectTabReachesAll would pick it up erroneously, so we
  // drive Tab manually through the visible controls instead.
  it('AC1 — visible form controls are Tab-reachable in order', async () => {
    const user = userEvent.setup();
    renderForm();
    await screen.findByLabelText('Linear ticket reference');

    await user.tab();
    expect(document.activeElement).toBe(screen.getByLabelText('Linear ticket reference'));

    await user.tab();
    expect(document.activeElement).toBe(screen.getByLabelText('Actor identity'));

    // The Radix SelectTrigger receives focus (the aria-hidden native <select> is skipped by userEvent).
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('combobox', { name: 'Actor type' }));

    await user.tab();
    expect(document.activeElement).toBe(screen.getByLabelText('Correlation id (optional)'));

    await user.tab();
    expect(document.activeElement).toBe(screen.getByTestId('submit-run-button'));
  });

  it('AC1 — submit button is keyboard-activatable and triggers validation', async () => {
    const user = userEvent.setup();
    renderForm();
    await screen.findByTestId('submit-run-button');
    // Tab to submit button (last tabbable element).
    await user.tab(); // linearTicketReference
    await user.tab(); // actorIdentity
    await user.tab(); // actorType trigger
    await user.tab(); // correlationId
    await user.tab(); // submit button
    expect(document.activeElement).toBe(screen.getByTestId('submit-run-button'));
    await user.keyboard('{Enter}');
    // Blank fields → validation fires (no network call needed).
    expect(await screen.findByText(/Linear ticket reference is required/i)).toBeInTheDocument();
  });
});

describe('SubmitRunForm — field-only logging (AC9, T-LOG-PII)', () => {
  it('logs run.submitSuccess with EXACTLY {currentState, event} and no PII', async () => {
    server.use(
      http.post(SUBMIT_URL, () =>
        HttpResponse.json({ workflowRunId: 'run_new_009', currentState: 'Inbox' }),
      ),
    );
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => undefined);

    renderForm();
    await screen.findByLabelText('Linear ticket reference');
    fillRequired();
    fireEvent.click(screen.getByTestId('submit-run-button'));

    await screen.findByTestId('submit-success');
    await waitFor(() =>
      expect(infoSpy).toHaveBeenCalledWith({ event: 'run.submitSuccess', currentState: 'Inbox' }),
    );

    const successCall = infoSpy.mock.calls.find(
      (c) => (c[0] as { event?: string }).event === 'run.submitSuccess',
    );
    const logged = successCall?.[0] as Record<string, unknown>;
    expect(Object.keys(logged).sort()).toEqual(['currentState', 'event']);
    // No user-entered values anywhere in the success log.
    expect(JSON.stringify(logged)).not.toContain('LIN-2210');
    expect(JSON.stringify(logged)).not.toContain('alex@example.com');
  });
});
