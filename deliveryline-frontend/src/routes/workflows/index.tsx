import { Link, createFileRoute } from '@tanstack/react-router';

import { Stack } from '@/components/layout';
import { listQueryOptions } from '@/lib/api/queryOptions';

/**
 * WorkflowsRoute: the `/workflows` run queue/list.
 *
 * The BODY is still a MINIMAL PLACEHOLDER — the real Run Review Queue (Queue Item
 * 2.15, queue shell states 2.20) replaces it. Story 2.7 now hosts this content in
 * the tri-pane AppShell's central main pane, so this route renders plain content
 * (no `<Container>` wrapper) — the shell owns the single `<main>` + base padding.
 * Story 2.6 added the DATA seam: the loader warms the run-list query so the queue
 * UI (when 2.15/2.20 land) renders flash-free off the shared cache. The
 * X-Correlation-Id header rides the request via the client middleware (2.6/1.19).
 */
export const Route = createFileRoute('/workflows/')({
  loader: ({ context }) => context.queryClient.ensureQueryData(listQueryOptions()),
  component: WorkflowsRoute,
});

const SAMPLE_RUN_IDS = ['run_sample0001', 'run_sample0002', 'run_sample0003'] as const;

function WorkflowsRoute() {
  return (
    <Stack gap="4" className="items-start">
      <h1 className="text-page-title">Run review queue</h1>
      <p className="text-body text-text-secondary max-w-prose">
        Navigation skeleton (story 2.5), now hosted in the tri-pane shell (story 2.7). The real
        review queue arrives in stories 2.15 / 2.20; data wiring landed in 2.6.
      </p>
      <Stack gap="2" className="items-start">
        {SAMPLE_RUN_IDS.map((runId) => (
          <Link
            key={runId}
            to="/workflows/$workflowRunId"
            params={{ workflowRunId: runId }}
            className="text-body text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
          >
            {runId}
          </Link>
        ))}
      </Stack>
    </Stack>
  );
}
