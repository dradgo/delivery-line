/**
 * Story 2.22 AC11.u — the 8 migrated dead-end states preserve their original copy
 * verbatim (Trap T15) and adopt the new state-component a11y baseline (role /
 * aria-live per AC9). Rendered inside a real memory router (the EmptyState actions
 * use `<Link>`; `<ErrorState>` consumes `useReturnToRunContext` → `useNavigate`)
 * plus the breadcrumb provider.
 */
import type { ReactNode } from 'react';
import {
  RouterProvider,
  createRootRoute,
  createRouter,
  createMemoryHistory,
} from '@tanstack/react-router';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { NavigationBreadcrumbProvider } from '@/lib/navigation/NavigationBreadcrumbProvider';
import {
  ArtifactNotFoundState,
  GenericErrorState,
  InvalidLinkState,
  PageNotFoundState,
  PermissionRestrictedState,
  RunNotFoundState,
  UnrecognizedRunStateState,
  UnrenderableArtifactState,
} from '../DeadEndState';

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderInRouter(node: ReactNode) {
  const rootRoute = createRootRoute({
    component: () => <NavigationBreadcrumbProvider>{node}</NavigationBreadcrumbProvider>,
  });
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  return render(<RouterProvider router={router} />);
}

interface Case {
  name: string;
  node: ReactNode;
  kind: 'error' | 'empty';
  title: string;
  body: string;
  /** Expected aria-live for error cases: passive page-loads = polite, active = assertive (Trap T13). */
  ariaLive?: 'polite' | 'assertive';
}

const CASES: Case[] = [
  {
    name: 'InvalidLinkState',
    node: <InvalidLinkState />,
    kind: 'error',
    title: 'Invalid link',
    body: 'expected format',
    ariaLive: 'polite',
  },
  {
    name: 'RunNotFoundState',
    node: <RunNotFoundState />,
    kind: 'error',
    title: 'Run not found',
    body: 'removed under a retention policy',
    ariaLive: 'polite',
  },
  {
    name: 'ArtifactNotFoundState',
    node: <ArtifactNotFoundState />,
    kind: 'error',
    title: 'Artifact not found',
    body: 'No artifact matches',
    ariaLive: 'polite',
  },
  {
    name: 'PageNotFoundState',
    node: <PageNotFoundState />,
    kind: 'empty',
    title: 'Page not found',
    body: 'doesn’t match any page in the review workspace',
  },
  {
    name: 'GenericErrorState',
    node: <GenericErrorState />,
    kind: 'error',
    title: 'Something went wrong',
    body: 'An unexpected error interrupted this view',
    ariaLive: 'assertive', // urgency="active" — user hit a failure (Trap T13).
  },
  {
    name: 'UnrecognizedRunStateState',
    node: <UnrecognizedRunStateState />,
    kind: 'empty',
    title: 'This run is in a newer state',
    body: 'Nothing about the run has changed',
  },
  {
    name: 'UnrenderableArtifactState',
    node: <UnrenderableArtifactState />,
    kind: 'empty',
    title: 'This artifact type isn’t viewable here yet',
    body: 'renderable in this build of the review panel',
  },
  {
    name: 'PermissionRestrictedState',
    node: <PermissionRestrictedState />,
    kind: 'error',
    title: 'Not available for your recorded role',
    body: 'access decisions are\n          made by the backend, not here',
    ariaLive: 'polite',
  },
];

describe('DeadEndState migration (AC11.u)', () => {
  const normalize = (s: string) => s.replace(/\s+/g, ' ').trim();

  it.each(CASES)(
    '$name preserves its title + body copy and adopts the a11y baseline',
    async ({ node, kind, title, body, ariaLive }) => {
      const { container } = renderInRouter(node);
      // RouterProvider mounts the matched route asynchronously.
      await waitFor(() => expect(container.textContent).toContain(title));
      // Body copy preserved verbatim (Trap T15; whitespace-normalized comparison).
      expect(normalize(container.textContent ?? '')).toContain(normalize(body));
      // a11y baseline.
      if (kind === 'error') {
        // Assert the EXACT aria-live value (Trap T13) — a flip to assertive on a
        // passive page-load state (or vice-versa) is a regression, not just presence.
        // Story 2.25 (AC5): role tracks urgency — passive=status/polite,
        // active=alert/assertive (the role/aria-live contradiction fix).
        const liveRole = ariaLive === 'assertive' ? 'alert' : 'status';
        expect(screen.getByRole(liveRole)).toHaveAttribute('aria-live', ariaLive);
      } else {
        expect(screen.getByTestId('empty-state')).toBeInTheDocument();
        expect(screen.queryByTestId('empty-state')?.querySelector('[aria-live]')).toBeNull();
      }
    },
  );

  it('Trap T7 — PermissionRestrictedState preserves the audit-only role language verbatim', async () => {
    const { container } = renderInRouter(<PermissionRestrictedState />);
    await waitFor(() => expect(container.textContent).toContain('recorded role'));
    expect(normalize(container.textContent ?? '')).toContain(
      'isn’t a security control, and access decisions are made by the backend, not here',
    );
  });
});
