/**
 * Story 2.26 (Task 5 — AC2, AC5, AC6, AC7, AC12, D2, D4) — the responsive layout
 * matrix for the tri-pane shell.
 *
 * The shell + its breakpoint behaviour were BUILT in story 2.7; this suite is the
 * formal, test-pinned statement of the responsive contract documented in
 * `RESPONSIVE.md`. It asserts the STRUCTURAL contract only (which landmarks/classes/
 * elements render at each simulated breakpoint via `@/test/matchMedia`), NEVER
 * computed pixels — jsdom has no layout engine (D2), so live px checks
 * (touch-target size, no-horizontal-scroll, sticky-footer reachability) are the
 * real-device checklist + story-2.27 Playwright. No `toMatchSnapshot` (story 2.27).
 *
 * The router/query mounting mirrors `AppShell.test.tsx`; this file does NOT `vi.mock`
 * the router (the in-memory history is real), so it cannot race a sibling file's mock
 * of the same module (the Vitest-4 per-worker module-registry caveat).
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
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { installMatchMedia, setViewportWidth, uninstallMatchMedia } from '@/test/matchMedia';
import { server } from '@/test/server';
import { readyView } from '@/test/fixtures/approval/approvalDecisionFixtures';
import { AppShell } from './AppShell';
import { ContextPanelSlot } from './ContextPanelSlot';
import { ApprovalDecisionBar } from './components/ApprovalDecisionBar';

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

/** A `/workflows` route projecting a representative composite into the right panel. */
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

/** Stub the run-detail read so `RunIdentityRegion` resolves identity + the state badge. */
function stubRunDetail(): void {
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
}

afterEach(() => {
  uninstallMatchMedia();
});

describe('AppShell responsive matrix — layout per breakpoint (AC2)', () => {
  it('desktop renders the full inline tri-pane with the artifact-primacy floor', async () => {
    installMatchMedia(1280);
    renderShell({ path: '/workflows' });
    const nav = await screen.findByRole('navigation', { name: 'Workflow navigation' });
    expect(nav).toHaveClass('w-64');
    expect(screen.getByRole('main')).toHaveClass('min-w-[36rem]');
    // Desktop: the supporting-context panel is INLINE (no top-bar banner).
    expect(screen.getByRole('complementary', { name: 'Supporting context' })).toBeInTheDocument();
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
  });

  it('tablet keeps the nav rail + a narrower main floor and a context toggle (AC2 collapse order)', async () => {
    installMatchMedia(800);
    renderShell({ path: '/workflows' });
    const main = await screen.findByRole('main');
    // The right context panel has YIELDED to a toggle/drawer, but the main pane STILL
    // holds its min-width floor — the panel collapses before the artifact narrows.
    expect(main).toHaveClass('min-w-[34rem]');
    expect(screen.getByRole('navigation', { name: 'Workflow navigation' })).toHaveClass('w-56');
    expect(screen.getByRole('button', { name: 'Open supporting context' })).toBeInTheDocument();
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
  });

  it('mobile collapses to a single column with the persistent top bar (AC2)', async () => {
    installMatchMedia(400);
    renderShell({ path: '/workflows' });
    // Persistent top bar replaces the inline nav rail; main drops its min-width floor.
    expect(await screen.findByRole('banner')).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(main).toHaveClass('min-w-0');
    expect(main).not.toHaveClass('min-w-[36rem]');
    expect(main).not.toHaveClass('min-w-[34rem]');
    expect(screen.getByRole('button', { name: 'Open workflow navigation' })).toBeInTheDocument();
  });
});

describe('AppShell responsive matrix — collapse order (AC2 / TRAP 4)', () => {
  it('the right context panel becomes a drawer BEFORE the main pane drops its floor', async () => {
    // Desktop: context panel is the inline complementary column.
    installMatchMedia(1280);
    const desktop = renderShell({ path: '/workflows' });
    expect(await screen.findByRole('main')).toHaveClass('min-w-[36rem]');
    const desktopAside = screen.getByRole('complementary', { name: 'Supporting context' });
    // Empty + inline on desktop — it occupies real layout width (the slim w-12 strip).
    expect(desktopAside).toHaveClass('w-12');
    expect(
      screen.queryByRole('button', { name: 'Open supporting context' }),
    ).not.toBeInTheDocument();
    desktop.unmount();

    // Tablet: the panel has moved to a toggle/drawer, yet the main floor is intact.
    installMatchMedia(800);
    renderShell({ path: '/workflows' });
    expect(await screen.findByRole('main')).toHaveClass('min-w-[34rem]');
    expect(screen.getByRole('button', { name: 'Open supporting context' })).toBeInTheDocument();
  });
});

describe('AppShell responsive matrix — run identity survives a resize sweep (AC5)', () => {
  it('keeps run identity + the current-state badge visible at every breakpoint', async () => {
    stubRunDetail();
    installMatchMedia(1280);
    renderShell({ path: '/workflows/run_test0001' });

    // Desktop — identity lives in the inline nav rail.
    const desktopRegion = await screen.findByTestId('run-identity-region');
    expect(within(desktopRegion).getByText('run_test0001')).toBeInTheDocument();
    expect(await within(desktopRegion).findByText('WaitingForSpecApproval')).toBeInTheDocument();

    // Resize desktop → tablet: still present.
    act(() => setViewportWidth(800));
    await waitFor(() => {
      expect(screen.getByTestId('run-identity-region')).toBeInTheDocument();
    });
    expect(
      within(screen.getByTestId('run-identity-region')).getByText('WaitingForSpecApproval'),
    ).toBeInTheDocument();

    // Resize tablet → mobile: identity moves into the persistent top bar, still visible.
    act(() => setViewportWidth(400));
    expect(await screen.findByRole('banner')).toBeInTheDocument();
    const mobileRegion = screen.getByTestId('run-identity-region');
    expect(within(mobileRegion).getByText('run_test0001')).toBeInTheDocument();
    expect(within(mobileRegion).getByText(/WaitingForSpecApproval/)).toBeInTheDocument();

    // Resize back to desktop: still present (no state lost on the round trip).
    act(() => setViewportWidth(1280));
    await waitFor(() => {
      expect(screen.queryByRole('banner')).not.toBeInTheDocument();
    });
    expect(
      within(screen.getByTestId('run-identity-region')).getByText('run_test0001'),
    ).toBeInTheDocument();
  });
});

describe('ApprovalDecisionBar — primary action on the mobile sticky footer (AC7)', () => {
  it('keeps the primary Approve action present in the sticky_footer at the mobile breakpoint', () => {
    installMatchMedia(400);
    render(
      <ApprovalDecisionBar
        view={readyView}
        mutation={{ status: 'idle' }}
        onApprove={() => undefined}
        onReject={() => undefined}
        onRefresh={() => undefined}
      />,
    );
    const bar = screen.getByTestId('approval-decision-bar');
    // The decision bar keeps its sticky-footer placement regardless of breakpoint.
    expect(bar).toHaveAttribute('data-approval-bar-layout', 'sticky_footer');
    expect(bar).toHaveClass('sticky');
    expect(bar).toHaveClass('bottom-0');
    // The single primary governed action is present and never collapses (AC7).
    const approve = screen.getByRole('button', { name: 'Approve specification' });
    expect(approve).toHaveAttribute('data-primary', 'true');
    expect(approve).toBeEnabled();
  });
});

describe('AppShell responsive matrix — a11y at narrow breakpoints (D4 — closes 2.21)', () => {
  it('has no WCAG-2.1-AA violations on mobile', async () => {
    installMatchMedia(400);
    const { container } = renderShell({
      path: '/workflows',
      workflowsComponent: QueueWithRegisteredPanel,
    });
    await screen.findByRole('banner');
    await expectNoA11yViolations(container);
  });

  it('has no WCAG-2.1-AA violations on tablet', async () => {
    installMatchMedia(800);
    const { container } = renderShell({
      path: '/workflows',
      workflowsComponent: QueueWithRegisteredPanel,
    });
    await screen.findByRole('main');
    await expectNoA11yViolations(container);
  });
});
