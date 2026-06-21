import { createFileRoute } from '@tanstack/react-router';

import { listProjectsOptions } from '@/lib/api/queryOptions';
import { ProjectsScreen } from '@/features/projects/ProjectsScreen';

/**
 * Story 3c-9 (AC1) — the `/projects` route: the project management / configuration
 * area, distinct from the queue (`/workflows`) and run (`/workflows/$id`) views.
 *
 * Renders inside the existing app shell (`__root.tsx` → `AppShell`, via the router
 * `<Outlet />`). The loader warms the SAME `listProjectsOptions()` the list + selector
 * read, so a deep link renders flash-free off one shared cache entry (the `/workflows`
 * loader precedent). Registered into the gitignored `routeTree.gen.ts` by
 * `npm run routes:generate`.
 */
export const Route = createFileRoute('/projects/')({
  loader: ({ context }) => context.queryClient.ensureQueryData(listProjectsOptions()),
  component: ProjectsScreen,
});
