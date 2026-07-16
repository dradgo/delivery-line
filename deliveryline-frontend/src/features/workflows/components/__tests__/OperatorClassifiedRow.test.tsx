/**
 * Story 4.24 review (D2b/D3) — `OperatorClassifiedRow` container tests.
 *
 * Router `Link` is mocked to a plain `<a>` (mirrors `RunReviewQueueItem.test`, memory
 * `vitest-cross-file-router-mock`); MSW backs the per-row `failure-classification` +
 * `failure-taxonomy` reads inside a per-test `QueryClientProvider`. Asserts the two AC surfaces the
 * container derives: Classify offered ONLY on a not-yet-classified Failed row (AC8a), and the applied
 * classification chip (registry-humanized) once classified (AC9); a non-Failed row reads neither.
 */
import type { ComponentProps, MouseEvent } from 'react';
import type * as ReactRouter from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import type { RunQueueRow } from '../../runQueueRow';

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof ReactRouter>();
  const LinkMock = ({
    to,
    params,
    children,
    onClick,
    ...rest
  }: ComponentProps<'a'> & { to?: string; params?: Record<string, string> }) => (
    <a
      href={`/workflows/${params?.workflowRunId ?? ''}`}
      data-to={to}
      data-run-param={params?.workflowRunId}
      onClick={(event: MouseEvent<HTMLAnchorElement>) => {
        event.preventDefault();
        onClick?.(event);
      }}
      {...rest}
    >
      {children}
    </a>
  );
  return { ...actual, Link: LinkMock };
});

import { OperatorClassifiedRow } from '../OperatorClassifiedRow';

const CLASSIFICATION_URL = 'http://localhost/api/v1/workflows/:runId/failure-classification';
const TAXONOMY_URL = 'http://localhost/api/v1/registries/failure-taxonomy';

const FAILED_ROW = {
  runId: 'run_op_classify_1',
  currentState: 'Failed',
  operatorSignifier: 'FAILED',
} as RunQueueRow;

function serveTaxonomy() {
  server.use(
    http.get(TAXONOMY_URL, () =>
      HttpResponse.json({
        values: [
          {
            value: 'agent_execution_failure',
            humanReadableName: 'Agent Execution Failure',
            description: 'The agent failed to produce a valid result.',
            examples: ['malformed output'],
            deprecated: false,
          },
        ],
      }),
    ),
  );
}
function serveClassification(body: Record<string, unknown>) {
  server.use(http.get(CLASSIFICATION_URL, () => HttpResponse.json(body)));
}

function renderRow(run: RunQueueRow = FAILED_ROW, onClassify = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <OperatorClassifiedRow run={run} onClassify={onClassify} />
    </QueryClientProvider>,
  );
  return { onClassify };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('OperatorClassifiedRow (story 4.24 D2b/D3)', () => {
  it('offers Classify on a not-yet-classified Failed row and calls onClassify(runId) (AC8a)', async () => {
    serveTaxonomy();
    serveClassification({
      workflowRunId: 'run_op_classify_1',
      deprecated: false,
      priorClassifications: [],
    });
    const user = userEvent.setup();
    const { onClassify } = renderRow();

    const button = await screen.findByTestId('operator-classify-action');
    await user.click(button);
    expect(onClassify).toHaveBeenCalledWith('run_op_classify_1');
    // Not-yet-classified → no chip.
    expect(screen.queryByTestId('operator-classification-chip')).toBeNull();
  });

  it('surfaces the humanized classification chip and hides Classify once classified (AC9/AC8a)', async () => {
    serveTaxonomy();
    serveClassification({
      workflowRunId: 'run_op_classify_1',
      currentTaxonomyValue: 'agent_execution_failure',
      currentDisplayLabel: 'agent_execution_failure',
      deprecated: false,
      priorClassifications: [],
    });
    renderRow();

    const chip = await screen.findByTestId('operator-classification-chip');
    expect(chip).toHaveTextContent('Classification: Agent Execution Failure');
    // Humanized, never the raw wire value.
    expect(chip).not.toHaveTextContent('agent_execution_failure');
    // A classified row no longer offers Classify (re-classification is a Decision-Bar/diagnostics path).
    expect(screen.queryByTestId('operator-classify-action')).toBeNull();
  });

  it('reads neither surface for a non-Failed row (query disabled)', async () => {
    const { onClassify } = renderRow({
      runId: 'run_op_ok_1',
      currentState: 'WaitingForReview',
      operatorSignifier: 'STALLED',
    } as RunQueueRow);

    await waitFor(() =>
      expect(screen.getByTestId('run-review-queue-item')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('operator-classification-chip')).toBeNull();
    expect(screen.queryByTestId('operator-classify-action')).toBeNull();
    expect(onClassify).not.toHaveBeenCalled();
  });
});
