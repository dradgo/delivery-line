/**
 * Story 3g-4 (FR74, AC1/AC3/AC5/AC6) — `RunStepTokensPanel` Vitest coverage.
 *
 * Drives the one-shot `/steps` read through MSW: per-step tokens render; a step with null counts
 * shows "Not reported" (never `0`/blank); the run-level total renders and its `null` shows "Not
 * reported"; the panel self-hides on an empty step list; and the surface is axe-clean.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { server } from '@/test/server';

import { RunStepTokensPanel } from './RunStepTokensPanel';

const STEPS_URL = 'http://localhost/api/v1/workflows/:runId/steps';

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderPanel(workflowRunId: string, totalTokens: number | null | undefined) {
  return render(
    <QueryClientProvider client={freshClient()}>
      <RunStepTokensPanel workflowRunId={workflowRunId} totalTokens={totalTokens} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
});

describe('RunStepTokensPanel (story 3g-4)', () => {
  it('renders per-step tokens and the run-level total (AC1/AC3)', async () => {
    server.use(
      http.get(STEPS_URL, () =>
        HttpResponse.json([
          {
            runnerExecutionId: 'rex_older',
            stage: 'execution',
            status: 'completed',
            createdAt: '2026-07-02T10:00:00Z',
            inputTokens: 100,
            outputTokens: 200,
            totalTokens: 300,
          },
          {
            runnerExecutionId: 'rex_newer',
            stage: 'review',
            status: 'completed',
            createdAt: '2026-07-02T10:05:00Z',
            inputTokens: 3,
            outputTokens: 8,
            totalTokens: 11,
          },
        ]),
      ),
    );
    renderPanel('run_tokens000001', 311);

    const rows = await screen.findAllByTestId('run-step-tokens-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('execution');
    expect(rows[0]).toHaveTextContent('Input');
    expect(rows[0]).toHaveTextContent('100');
    expect(rows[0]).toHaveTextContent('200');
    expect(rows[0]).toHaveTextContent('300');
    expect(screen.getByTestId('run-step-tokens-total')).toHaveTextContent('Run total: 311');
  });

  it('shows "Not reported" for a step with null counts (never 0/blank) (AC1)', async () => {
    server.use(
      http.get(STEPS_URL, () =>
        HttpResponse.json([
          {
            runnerExecutionId: 'rex_noreport',
            stage: 'execution',
            status: 'running',
            createdAt: '2026-07-02T10:00:00Z',
            inputTokens: null,
            outputTokens: null,
            totalTokens: null,
          },
        ]),
      ),
    );
    renderPanel('run_tokens000002', null);

    const row = await screen.findByTestId('run-step-tokens-row');
    expect(row).toHaveTextContent('Not reported');
    expect(row).not.toHaveTextContent('0');
    // The run total is null → "Not reported" (never 0).
    expect(screen.getByTestId('run-step-tokens-total')).toHaveTextContent(
      'Run total: Not reported',
    );
  });

  it('self-hides when the run has no runner executions (AC6)', async () => {
    server.use(http.get(STEPS_URL, () => HttpResponse.json([])));
    const { container } = renderPanel('run_tokens000003', null);

    // Nothing rendered once the (empty) read settles.
    await waitFor(() => {
      expect(screen.queryByTestId('run-step-tokens')).not.toBeInTheDocument();
    });
    expect(container).toBeEmptyDOMElement();
  });

  it('is axe-clean (AC5)', async () => {
    server.use(
      http.get(STEPS_URL, () =>
        HttpResponse.json([
          {
            runnerExecutionId: 'rex_a11y',
            stage: 'execution',
            status: 'completed',
            createdAt: '2026-07-02T10:00:00Z',
            inputTokens: 100,
            outputTokens: null,
            totalTokens: null,
          },
        ]),
      ),
    );
    const { container } = renderPanel('run_tokens000004', 100);

    await screen.findByTestId('run-step-tokens-row');
    await expectNoA11yViolations(container);
  });
});
