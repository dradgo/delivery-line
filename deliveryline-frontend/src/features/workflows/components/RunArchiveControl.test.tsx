import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { RunArchiveControl } from './RunArchiveControl';

const RUN_ID = 'run_arch_ctrl_001';
const ALLOWED_URL = 'http://localhost/api/v1/workflows/:runId/allowed-actions';
const ARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/archive';
const UNARCHIVE_URL = 'http://localhost/api/v1/workflows/:runId/unarchive';

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}
function renderControl() {
  return render(
    <QueryClientProvider client={client()}>
      <RunArchiveControl workflowRunId={RUN_ID} />
    </QueryClientProvider>,
  );
}
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('renders nothing when neither archive_run nor unarchive_run is advertised', async () => {
  server.use(http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['retry'] })));
  renderControl();
  await waitFor(() => expect(screen.queryByTestId('run-archive-button')).not.toBeInTheDocument());
});

it('archive: shows "Archive run", blocks confirm until a reason, then POSTs { reason }', async () => {
  let body: Record<string, unknown> | undefined;
  server.use(
    http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['archive_run'] })),
    http.post(ARCHIVE_URL, async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({
        workflowRunId: RUN_ID,
        currentState: 'Failed',
        archivedAt: '2026-07-03T00:00:00Z',
      });
    }),
  );
  renderControl();
  fireEvent.click(await screen.findByRole('button', { name: 'Archive run' }));
  const dialog = screen.getByTestId('run-archive-dialog');
  // Confirm disabled with an empty required reason.
  expect(within(dialog).getByRole('button', { name: 'Archive' })).toBeDisabled();
  fireEvent.change(within(dialog).getByLabelText(/reason/i), {
    target: { value: 'obsolete run' },
  });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Archive' }));
  await waitFor(() => expect(body).toBeDefined());
  expect(body).toEqual({ reason: 'obsolete run' });
});

it('unarchive: shows "Unarchive run"; confirm enabled with no reason', async () => {
  let called = false;
  server.use(
    http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['unarchive_run'] })),
    http.post(UNARCHIVE_URL, () => {
      called = true;
      return HttpResponse.json({ workflowRunId: RUN_ID, currentState: 'Failed', archivedAt: null });
    }),
  );
  renderControl();
  fireEvent.click(await screen.findByRole('button', { name: 'Unarchive run' }));
  const dialog = screen.getByTestId('run-archive-dialog');
  const confirm = within(dialog).getByRole('button', { name: 'Unarchive' });
  expect(confirm).toBeEnabled();
  fireEvent.click(confirm);
  await waitFor(() => expect(called).toBe(true));
});

it('surfaces an inline message on ARCHIVE_NOT_APPLICABLE (409)', async () => {
  server.use(
    http.get(ALLOWED_URL, () => HttpResponse.json({ actions: ['archive_run'] })),
    http.post(ARCHIVE_URL, () =>
      HttpResponse.json(
        {
          type: 'about:blank',
          title: 'x',
          status: 409,
          detail: 'x',
          instance: `/api/v1/workflows/${RUN_ID}/archive`,
          code: 'ARCHIVE_NOT_APPLICABLE',
          retryable: false,
        },
        { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
      ),
    ),
  );
  renderControl();
  fireEvent.click(await screen.findByRole('button', { name: 'Archive run' }));
  const dialog = screen.getByTestId('run-archive-dialog');
  fireEvent.change(within(dialog).getByLabelText(/reason/i), { target: { value: 'x' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Archive' }));
  await waitFor(() =>
    expect(screen.getByTestId('run-archive-error')).toHaveTextContent(/refresh/i),
  );
  expect(screen.getByTestId('run-archive-dialog')).toBeInTheDocument();
});
