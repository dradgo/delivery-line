import { createFileRoute } from '@tanstack/react-router';

import { SubmitRunForm } from '@/features/workflows/components/SubmitRunForm';

/**
 * Story 2a.1 (AC1) — the `/submit` route: the in-app "Submit a Run" form.
 *
 * Renders inside the existing app shell (`__root.tsx` → `AppShell`, via the router
 * `<Outlet />`), mirroring the `/workflows` file-route registration. The form is a
 * pure client surface over the EXISTING `POST /api/v1/workflows/submit-workflow`, so
 * the route needs no loader/search wiring — it just mounts `<SubmitRunForm>`. This
 * file is registered into `routeTree.gen.ts` by `npm run routes:generate`.
 */
export const Route = createFileRoute('/submit/')({
  component: SubmitRunForm,
});
