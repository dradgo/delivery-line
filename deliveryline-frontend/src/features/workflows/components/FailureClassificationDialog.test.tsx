/**
 * Story 4.24 (AC12, Task 8) — `FailureClassificationDialog` component tests.
 *
 * MSW-backed taxonomy registry + failure-classification + detail GETs and a classify POST inside a
 * per-test QueryClientProvider. Covers: cards render name/description/examples; a deprecated value
 * renders reduced-prominence + affix + on-select warning; a prior classification pre-selects + shows
 * provenance + the re-classify warning; a deprecated prior pre-selects the replacement; submission
 * sends `{ role, taxonomyValue, reasonText? }` + closes; a `DEPRECATED_TAXONOMY_VALUE` re-selects the
 * replacement inline; the radio group exposes accessible names; axe reports zero AA violations.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { retryUnlessNonRetryable } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { expectNoA11yViolations } from '@/test/a11y/axe';

import { FailureClassificationDialog } from './FailureClassificationDialog';

const RUN_ID = 'run_classify_dialog_001';
const TAXONOMY_URL = 'http://localhost/api/v1/registries/failure-taxonomy';
const CLASSIFICATION_URL = `http://localhost/api/v1/workflows/${RUN_ID}/failure-classification`;
const DETAIL_URL = `http://localhost/api/v1/workflows/${RUN_ID}`;
const DIAGNOSTICS_URL = `http://localhost/api/v1/workflows/${RUN_ID}/failure-diagnostics`;
const CLASSIFY_URL = 'http://localhost/api/v1/workflows/:runId/classify-failure';

const ACTIVE_TAXONOMY = [
  {
    value: 'agent_execution_failure',
    humanReadableName: 'Agent Execution Failure',
    description: 'The agent failed to produce a valid result.',
    examples: ['The runner produced malformed output.', 'The agent looped without converging.'],
    deprecated: false,
  },
  {
    value: 'context_gap',
    humanReadableName: 'Context Gap',
    description: 'The agent lacked repository context.',
    examples: ['A convention was not surfaced.'],
    deprecated: false,
  },
];

const WITH_DEPRECATED = [
  ...ACTIVE_TAXONOMY,
  {
    value: 'legacy_gap',
    humanReadableName: 'Legacy Gap',
    description: 'A retired category.',
    examples: ['old example'],
    deprecated: true,
    replacementValue: 'context_gap',
  },
];

function neverClassified() {
  return { workflowRunId: RUN_ID, deprecated: false, priorClassifications: [] };
}

interface SetupOptions {
  taxonomy?: typeof ACTIVE_TAXONOMY;
  classification?: Record<string, unknown>;
  classify?: () => Response;
  captureBody?: (body: Record<string, unknown>) => void;
  /** Story 4.24 review D4 — the failure-diagnostics `failureReason` surfaced in the header. */
  failureReason?: string | null;
}

function setup(options: SetupOptions = {}) {
  server.use(
    http.get(TAXONOMY_URL, () =>
      HttpResponse.json({ values: options.taxonomy ?? ACTIVE_TAXONOMY }),
    ),
    http.get(CLASSIFICATION_URL, () =>
      HttpResponse.json(options.classification ?? neverClassified()),
    ),
    http.get(DETAIL_URL, () =>
      HttpResponse.json({
        workflowRunId: RUN_ID,
        currentState: 'Failed',
        failureCategory: 'runner_timeout',
        failedStage: 'execution',
      }),
    ),
    // Review D4 — the header's failure-reason segment reads from failure diagnostics.
    http.get(DIAGNOSTICS_URL, () =>
      HttpResponse.json({
        currentState: 'Failed',
        failedStage: 'execution',
        failureCategory: 'runner_timeout',
        failureReason:
          options.failureReason === undefined
            ? 'container exited with SIGSEGV'
            : options.failureReason,
        integrationSyncStatus: { linear: null, github: null },
        recommendedRecoveryActions: [],
      }),
    ),
    http.post(CLASSIFY_URL, async ({ request }) => {
      options.captureBody?.((await request.json()) as Record<string, unknown>);
      return (
        options.classify?.() ??
        HttpResponse.json({
          workflowRunId: RUN_ID,
          taxonomyValue: 'agent_execution_failure',
          recoveryActionId: 'rcv_1',
          replayed: false,
        })
      );
    }),
  );
}

function client() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 },
    },
  });
}

function renderDialog(onClose = vi.fn()) {
  render(
    <QueryClientProvider client={client()}>
      <FailureClassificationDialog workflowRunId={RUN_ID} onClose={onClose} />
    </QueryClientProvider>,
  );
  return { onClose };
}

describe('FailureClassificationDialog (story 4.24)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'info').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders a radio card per value with description + examples', async () => {
    setup();
    renderDialog();

    await waitFor(() =>
      expect(screen.getByRole('radio', { name: /Agent Execution Failure/ })).toBeInTheDocument(),
    );
    const card = screen.getByTestId('failure-classification-option-agent_execution_failure');
    expect(
      within(card).getByText('The agent failed to produce a valid result.'),
    ).toBeInTheDocument();
    expect(within(card).getByText('The runner produced malformed output.')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Context Gap/ })).toBeInTheDocument();
  });

  it('renders a deprecated value with reduced-prominence + affix + on-select warning', async () => {
    setup({ taxonomy: WITH_DEPRECATED });
    const user = userEvent.setup();
    renderDialog();

    const deprecatedCard = await screen.findByTestId('failure-classification-option-legacy_gap');
    expect(deprecatedCard).toHaveAttribute('data-deprecated', 'true');
    expect(deprecatedCard).toHaveTextContent('(deprecated, use context_gap instead)');

    await user.click(within(deprecatedCard).getByRole('radio'));
    expect(screen.getByTestId('failure-classification-deprecated-warning')).toHaveTextContent(
      /deprecated/i,
    );
  });

  it('pre-selects a prior classification + shows provenance and the re-classify warning', async () => {
    setup({
      classification: {
        workflowRunId: RUN_ID,
        currentTaxonomyValue: 'context_gap',
        currentDisplayLabel: 'context_gap',
        deprecated: false,
        classifiedBy: 'alex',
        priorClassifications: [],
      },
    });
    renderDialog();

    await waitFor(() => expect(screen.getByRole('radio', { name: /Context Gap/ })).toBeChecked());
    const prior = screen.getByTestId('failure-classification-prior');
    expect(prior).toHaveTextContent(/Previously classified/i);
    expect(prior).toHaveTextContent(/by alex/i);
    expect(prior).toHaveTextContent(/remains in audit history/i);
  });

  it('pre-selects the replacement when the prior value is deprecated', async () => {
    setup({
      taxonomy: WITH_DEPRECATED,
      classification: {
        workflowRunId: RUN_ID,
        currentTaxonomyValue: 'legacy_gap',
        currentDisplayLabel: 'legacy_gap (deprecated)',
        deprecated: true,
        deprecatedReplacementValue: 'context_gap',
        priorClassifications: [],
      },
    });
    renderDialog();

    await waitFor(() => expect(screen.getByRole('radio', { name: /Context Gap/ })).toBeChecked());
    expect(screen.getByRole('radio', { name: /Legacy Gap/ })).not.toBeChecked();
  });

  it('submits { role, taxonomyValue, reasonText } and closes on success', async () => {
    let body: Record<string, unknown> | undefined;
    setup({ captureBody: (b) => (body = b) });
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(await screen.findByRole('radio', { name: /Agent Execution Failure/ }));
    await user.type(screen.getByTestId('failure-classification-reason'), 'malformed diff');
    await user.click(screen.getByRole('button', { name: /Apply classification/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(body).toMatchObject({
      role: 'workflow_owner',
      taxonomyValue: 'agent_execution_failure',
      reasonText: 'malformed diff',
    });
  });

  it('re-selects the replacement inline on a DEPRECATED_TAXONOMY_VALUE error', async () => {
    setup({
      taxonomy: WITH_DEPRECATED,
      classify: () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Deprecated',
            status: 400,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/classify-failure`,
            code: 'DEPRECATED_TAXONOMY_VALUE',
            retryable: false,
            details: { replacementValue: 'context_gap' },
          },
          { status: 400, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
    });
    const user = userEvent.setup();
    renderDialog();

    await user.click(await screen.findByRole('radio', { name: /Legacy Gap/ }));
    await user.click(screen.getByRole('button', { name: /Apply classification/i }));

    await waitFor(() =>
      expect(screen.getByTestId('failure-classification-error')).toBeInTheDocument(),
    );
    expect(screen.getByRole('radio', { name: /Context Gap/ })).toBeChecked();
  });

  it('shows the failure category AND reason text in the header (AC2, review D4)', async () => {
    setup({ failureReason: 'container exited with SIGSEGV' });
    renderDialog();

    const header = await screen.findByTestId('failure-classification-context');
    await waitFor(() => expect(header).toHaveTextContent('Reason: container exited with SIGSEGV'));
    expect(header).toHaveTextContent(/Failure category:/i);
  });

  it('maps a non-retryable CLASSIFY_NOT_APPLICABLE (409) to a distinct message, not "try again" (review P1)', async () => {
    setup({
      classify: () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Not applicable',
            status: 409,
            detail: 'x',
            instance: `/api/v1/workflows/${RUN_ID}/classify-failure`,
            code: 'CLASSIFY_NOT_APPLICABLE',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
    });
    const user = userEvent.setup();
    renderDialog();

    await user.click(await screen.findByRole('radio', { name: /Agent Execution Failure/ }));
    await user.click(screen.getByRole('button', { name: /Apply classification/i }));

    const error = await screen.findByTestId('failure-classification-error');
    expect(error).toHaveTextContent(/can no longer be classified/i);
    expect(error).not.toHaveTextContent(/try again/i);
  });

  it('announces success through the document-level live region on apply (AC10, review P3)', async () => {
    setup();
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(await screen.findByRole('radio', { name: /Agent Execution Failure/ }));
    await user.click(screen.getByRole('button', { name: /Apply classification/i }));

    // The dialog closes on success, but the global announcer (on document.body) survives its unmount.
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByTestId('global-live-announcer')).toHaveTextContent(
        'Failure classification applied.',
      ),
    );
  });

  it('roving arrow-key focus moves the selection between radio cards (AC10, Task 8)', async () => {
    setup();
    const user = userEvent.setup();
    renderDialog();

    const first = await screen.findByRole('radio', { name: /Agent Execution Failure/ });
    first.focus();
    await user.keyboard('{ArrowDown}');
    // Native radiogroup roving focus: ArrowDown moves selection + focus to the next card.
    expect(screen.getByRole('radio', { name: /Context Gap/ })).toBeChecked();
  });

  it('exposes an accessible radio group and a submission live region (AC10)', async () => {
    setup();
    renderDialog();

    const radios = await screen.findAllByRole('radio');
    expect(radios.length).toBe(ACTIVE_TAXONOMY.length);
    // AC10 — a polite live region is present for submission-state announcements while the dialog is
    // open (its content is deferred one commit; presence is the contract here).
    const live = screen.getByTestId('failure-classification-live');
    expect(live).toHaveAttribute('aria-live', 'polite');
  });

  it('renders a description containing markup as escaped plain text (XSS fixture, feeds 4.26 AC8)', async () => {
    const xssDescription = 'Danger <img src=x onerror="alert(1)"> <script>alert(2)</script>';
    setup({
      taxonomy: [
        {
          value: 'agent_execution_failure',
          humanReadableName: 'Agent Execution Failure',
          description: xssDescription,
          examples: ['<b>not bold</b>'],
          deprecated: false,
        },
      ],
    });
    renderDialog();

    const card = await screen.findByTestId('failure-classification-option-agent_execution_failure');
    // The raw markup is present as TEXT (React auto-escaped), and no <script>/<img> element exists.
    expect(card).toHaveTextContent(xssDescription);
    expect(card.querySelector('script')).toBeNull();
    expect(card.querySelector('img')).toBeNull();
  });

  it('has no axe violations', async () => {
    setup();
    renderDialog();
    await screen.findByRole('radio', { name: /Agent Execution Failure/ });
    await expectNoA11yViolations(document.body);
  });
});
