/**
 * Story 2.7 (Task 9) — component tests for the tri-pane application shell.
 *
 * Covers AC1/AC2/AC3/AC4/AC5/AC7/AC8/AC11. The shell is router- and query-coupled,
 * so each test mounts it inside a minimal in-memory TanStack Router tree (no route
 * codegen) and a fresh `QueryClient`. The artifact-primacy assertion (AC2) checks
 * the STRUCTURAL contract — jsdom has no layout engine, so the true computed-pixel
 * test defers to Playwright in stories 2.26 / 2.27 (TRAP 4).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  Outlet,
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { installMatchMedia, uninstallMatchMedia } from '@/test/matchMedia';
import { expectNoA11yViolations } from '@/test/a11y/axe';
import { server } from '@/test/server';
import { AppShell } from './AppShell';
import { ContextPanelSlot } from './ContextPanelSlot';

/**
 * Mount `<AppShell>` inside a minimal in-memory router. `workflowsComponent` lets
 * a test inject custom content for the `/workflows` route (e.g. a slot registrar).
 */
function renderShell({
  path = '/workflows',
  workflowsComponent,
}: {
  path?: string;
  workflowsComponent?: () => ReactNode;
} = {}) {
  const rootRoute = createRootRoute({
    component: () => (
      <AppShell>
        <Outlet />
      </AppShell>
    ),
  });
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <h1>Index</h1>,
  });
  const workflowsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/workflows',
    component: workflowsComponent ?? (() => <h1>Run review queue</h1>),
  });
  const detailRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/workflows/$workflowRunId',
    component: () => <h1>Run detail</h1>,
  });
  const routeTree = rootRoute.addChildren([indexRoute, workflowsRoute, detailRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** A `/workflows` route that registers content into the right-panel slot. */
function QueueWithRegisteredPanel() {
  return (
    <>
      <h1>Run review queue</h1>
      <ContextPanelSlot>
        <p data-testid="registered-panel">Run context strip placeholder</p>
      </ContextPanelSlot>
    </>
  );
}

function QueueWithStatefulPanel() {
  return (
    <>
      <h1>Run review queue</h1>
      <ContextPanelSlot>
        <label className="flex flex-col gap-2 text-sm text-text-primary">
          Clarification note
          <input
            aria-label="Clarification note"
            defaultValue=""
            className="rounded border border-border px-2 py-1"
          />
        </label>
      </ContextPanelSlot>
    </>
  );
}

afterEach(() => {
  uninstallMatchMedia();
});

describe('AppShell — landmarks & structure', () => {
  it('renders the three tri-pane landmarks with accessible names (AC7)', async () => {
    renderShell({ path: '/workflows' });
    expect(
      await screen.findByRole('navigation', { name: 'Workflow navigation' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();
  });

  it('renders exactly one <main> landmark — routes no longer wrap their own (AC1, TRAP 1)', async () => {
    renderShell({ path: '/workflows' });
    await screen.findByRole('main');
    expect(screen.getAllByRole('main')).toHaveLength(1);
  });

  it('exposes a skip link targeting the main pane (AC7)', async () => {
    renderShell({ path: '/workflows' });
    const skipLink = await screen.findByRole('link', { name: 'Skip to main content' });
    expect(skipLink).toHaveAttribute('href', '#main-content');
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
  });

  // Story 2.27 (Task 2, AC4) — the shell itself must pass a WCAG-2.1-AA scan, not
  // only the composites it hosts (the audit found the main AppShell test had none).
  it('passes a wcag2aa axe scan in its desktop tri-pane layout (AC4)', async () => {
    installMatchMedia(1280);
    const { container } = renderShell({ path: '/workflows' });
    await screen.findByRole('navigation', { name: 'Workflow navigation' });
    await expectNoA11yViolations(container);
  });
});

describe('AppShell — artifact primacy (AC2 / TRAP 4)', () => {
  it('the main pane carries the artifact-primacy min-width on desktop', async () => {
    renderShell({ path: '/workflows' });
    expect(await screen.findByRole('main')).toHaveClass('min-w-[36rem]');
  });

  it('holds the main-pane min-width while the right panel yields to a drawer on tablet', async () => {
    installMatchMedia(800);
    renderShell({ path: '/workflows' });
    const main = await screen.findByRole('main');
    expect(main).toHaveClass('min-w-[34rem]');
    expect(await screen.findByRole('navigation', { name: 'Workflow navigation' })).toHaveClass(
      'w-56',
    );
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();
  });
});

describe('AppShell — responsive collapse (AC5)', () => {
  it('moves the context panel into a drawer on tablet and opens it on demand', async () => {
    installMatchMedia(800);
    renderShell({ path: '/workflows' });
    const toggle = await screen.findByRole('button', { name: 'Open supporting context' });
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();

    fireEvent.click(toggle);

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('collapses to a single column with drawer navigation on mobile', async () => {
    installMatchMedia(400);
    renderShell({ path: '/workflows' });
    expect(await screen.findByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Workflow navigation' })).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Open workflow navigation' }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
});

describe('AppShell — run identity (AC3 / AC11)', () => {
  it('shows backend-sourced run identity on a run route', async () => {
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', () =>
        HttpResponse.json({
          workflowRunId: 'run_test0001',
          currentState: 'WaitingForSpecApproval',
          currentActorIdentity: 'alice',
          currentActorType: 'human',
          lastEventType: 'spec.submitted',
          lastEventAt: '2026-05-20T14:30:00Z',
        }),
      ),
    );
    renderShell({ path: '/workflows/run_test0001' });

    const region = await screen.findByTestId('run-identity-region');
    expect(within(region).getByText('run_test0001')).toBeInTheDocument();
    expect(await within(region).findByText('WaitingForSpecApproval')).toBeInTheDocument();
    expect(within(region).getByText('alice (human)')).toBeInTheDocument();
    expect(within(region).getByText('spec.submitted')).toBeInTheDocument();
    expect(within(region).getByText('2026-05-20 14:30 UTC')).toBeInTheDocument();
  });

  it('keeps actor and last-transition metadata in the compact mobile variant', async () => {
    installMatchMedia(400);
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', () =>
        HttpResponse.json({
          workflowRunId: 'run_test0001',
          currentState: 'WaitingForSpecApproval',
          currentActorIdentity: 'alice',
          currentActorType: 'human',
          lastEventAt: '2026-05-20T14:30:00Z',
        }),
      ),
    );
    renderShell({ path: '/workflows/run_test0001' });

    const region = await screen.findByTestId('run-identity-region');
    expect(within(region).getByText('run_test0001')).toBeInTheDocument();
    expect(
      await within(region).findByText(
        /WaitingForSpecApproval · alice \(human\) · 2026-05-20 14:30 UTC/,
      ),
    ).toBeInTheDocument();
  });

  it('omits the run-identity region on the queue route', async () => {
    renderShell({ path: '/workflows' });
    await screen.findByRole('navigation', { name: 'Workflow navigation' });
    expect(screen.queryByTestId('run-identity-region')).not.toBeInTheDocument();
  });
});

describe('AppShell — right-panel composition slot (AC4)', () => {
  it('renders the empty, collapsed context panel when no composite registers', async () => {
    renderShell({ path: '/workflows' });
    const aside = await screen.findByRole('complementary', { name: 'Supporting context' });
    expect(aside).toHaveClass('w-12');
    expect(within(aside).getByText('No supporting context for this view.')).toBeInTheDocument();
  });

  it('projects a registered composite into the panel and expands it', async () => {
    renderShell({ path: '/workflows', workflowsComponent: QueueWithRegisteredPanel });
    const panel = await screen.findByTestId('registered-panel');
    const aside = screen.getByRole('complementary', { name: 'Supporting context' });
    expect(aside).toContainElement(panel);
    // Slot-registration triggers an effect-driven width transition (w-12 → w-80);
    // wait for the class update so this test doesn't race the re-render in CI.
    await waitFor(() => expect(aside).toHaveClass('w-80'));
  });

  it('preserves registered panel state across tablet drawer close and reopen', async () => {
    installMatchMedia(800);
    renderShell({ path: '/workflows', workflowsComponent: QueueWithStatefulPanel });

    fireEvent.click(await screen.findByRole('button', { name: 'Open supporting context' }));
    const input = await screen.findByLabelText('Clarification note');
    fireEvent.change(input, { target: { value: 'Need product sign-off' } });

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open supporting context' }));

    expect(await screen.findByDisplayValue('Need product sign-off')).toBeInTheDocument();
  });

  it('does not query workflow detail for an invalid route param carried by the shell', async () => {
    installMatchMedia(400);
    const detailRequest = vi.fn();
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', ({ params }) => {
        detailRequest(params.id);
        return HttpResponse.json({ workflowRunId: params.id, currentState: 'Executing' });
      }),
    );

    renderShell({ path: '/workflows/not-a-run-id' });

    await screen.findByRole('main');
    expect(detailRequest).not.toHaveBeenCalled();
    expect(screen.queryByTestId('run-identity-region')).not.toBeInTheDocument();
  });
});

describe('AppShell — structural stability (AC8)', () => {
  it('keeps the same shell frame across the queue and run-detail routes', async () => {
    const queue = renderShell({ path: '/workflows' });
    expect(
      await screen.findByRole('navigation', { name: 'Workflow navigation' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();
    queue.unmount();

    server.use(
      http.get('http://localhost/api/v1/workflows/:id', () =>
        HttpResponse.json({ workflowRunId: 'run_test0002', currentState: 'Executing' }),
      ),
    );
    renderShell({ path: '/workflows/run_test0002' });
    expect(
      await screen.findByRole('navigation', { name: 'Workflow navigation' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();
  });
});
