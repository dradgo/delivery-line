import { createFileRoute, redirect } from '@tanstack/react-router';

/**
 * Story 2.5 (AC1) — root path `/`. The run queue is the canonical entry surface
 * (ux:1833; architecture.md:467), so `/` redirects to `/workflows` rather than
 * rendering its own landing page. This keeps AC1's distinct `/` and `/workflows`
 * routes while making `/workflows` the real list URL. The redirect runs in
 * `beforeLoad`, so this route never renders a component.
 */
export const Route = createFileRoute('/')({
  beforeLoad: () => {
    // `throw: true` makes redirect() throw the redirect internally, so the route
    // short-circuits without a bare `throw` of a non-Error object (which the
    // @typescript-eslint/only-throw-error rule rejects). This is the documented
    // TanStack idiom for throwing a redirect from a void-returning hook.
    redirect({ to: '/workflows', throw: true });
  },
});
