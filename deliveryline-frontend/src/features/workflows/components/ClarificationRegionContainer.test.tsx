/**
 * Story 2.18 (Task 6) — `ClarificationRegionContainer` (the thin data seam).
 *
 * Driven by MOCKED hooks (T5 — the read hook stays disabled): the `empty` mapping of
 * the disabled stub, the live submit wiring (artifactId/version sourced from the
 * view), the `?clarificationId` focus seam (T-ANCHOR/OQ-4), and the field-only
 * structured logging with an exact-key-set negative assertion (never answer text).
 */
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { ProblemDetailsError, toProblemDetails } from '@/lib/api/problemDetails';
import { openView } from '@/test/fixtures/clarification/clarificationViewFixtures';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));
vi.mock('../hooks/useClarifications', () => ({ useClarifications: vi.fn() }));
vi.mock('../hooks/useSubmitClarification', () => ({ useSubmitClarification: vi.fn() }));

import { useClarifications } from '../hooks/useClarifications';
import { useSubmitClarification } from '../hooks/useSubmitClarification';
import {
  ClarificationRegionContainer,
  CLARIFICATION_REGION_ID,
} from './ClarificationRegionContainer';

const mockUseClarifications = vi.mocked(useClarifications);
const mockUseSubmitClarification = vi.mocked(useSubmitClarification);

function fakeReadQuery(overrides: Record<string, unknown> = {}) {
  return {
    data: undefined,
    isError: false,
    isLoading: false,
    fetchStatus: 'idle',
    error: null,
    refetch: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useClarifications>;
}

function fakeSubmit(overrides: Record<string, unknown> = {}) {
  return {
    mutate: vi.fn(),
    isPending: false,
    isSuccess: false,
    isError: false,
    error: null,
    ...overrides,
  } as unknown as ReturnType<typeof useSubmitClarification>;
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  vi.spyOn(console, 'info').mockImplementation(() => {});
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ClarificationRegionContainer', () => {
  it('maps the disabled read stub to the calm empty state (T5)', () => {
    mockUseClarifications.mockReturnValue(fakeReadQuery());
    mockUseSubmitClarification.mockReturnValue(fakeSubmit());

    render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);

    expect(screen.getByTestId('clarification-region')).toHaveAttribute(
      'data-clarification-region-state',
      'empty',
    );
    expect(screen.getByTestId('empty-state')).toHaveAttribute('data-variant', 'noOpenQuestions');
  });

  it('wires onSubmitAnswer to the live mutation with artifactId/version from the view', () => {
    const mutate = vi.fn();
    mockUseClarifications.mockReturnValue(fakeReadQuery({ data: openView }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit({ mutate }));

    render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);
    fireEvent.click(screen.getByRole('option'));
    fireEvent.change(screen.getByTestId('clarification-answer-input'), {
      target: { value: 'Paginate the export.' },
    });
    fireEvent.click(screen.getByTestId('clarification-submit'));

    expect(mutate).toHaveBeenCalledTimes(1);
    const variables = mutate.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(variables).toEqual({
      clarificationId: 'cla_open0001',
      answerText: 'Paginate the export.',
      artifactId: 'art_spec_clr_001',
      expectedArtifactVersion: 3,
    });
  });

  it('logs clarification.submit with ONLY {event, clarificationStatus} on success (never the answer)', () => {
    const infoSpy = vi.spyOn(console, 'info');
    const mutate = vi.fn((_vars, opts?: { onSuccess?: (d: unknown) => void }) => {
      opts?.onSuccess?.({
        clarificationStatus: 'answered',
        currentState: 'WaitingForSpecApproval',
      });
    });
    mockUseClarifications.mockReturnValue(fakeReadQuery({ data: openView }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit({ mutate }));

    render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);
    fireEvent.click(screen.getByRole('option'));
    fireEvent.change(screen.getByTestId('clarification-answer-input'), {
      target: { value: 'secret answer text' },
    });
    fireEvent.click(screen.getByTestId('clarification-submit'));

    const call = infoSpy.mock.calls.find(
      ([arg]) =>
        typeof arg === 'object' &&
        arg !== null &&
        (arg as { event?: string }).event === 'clarification.submit',
    );
    expect(call).toBeDefined();
    const payload = call![0] as Record<string, unknown>;
    // Exact key set — no answerText / message / question leakage (T8).
    expect(Object.keys(payload).sort()).toEqual(['clarificationStatus', 'event']);
    expect(payload.clarificationStatus).toBe('answered');
    expect(JSON.stringify(payload)).not.toContain('secret answer text');
  });

  it('logs clarification.submitError with {event, code, transport} on a typed failure', () => {
    const warnSpy = vi.spyOn(console, 'warn');
    const problem = new ProblemDetailsError(
      toProblemDetails({ code: 'CLARIFICATION_TERMINAL_STATE', status: 409 }, 409),
    );
    const mutate = vi.fn((_vars, opts?: { onError?: (e: unknown) => void }) => {
      opts?.onError?.(problem);
    });
    mockUseClarifications.mockReturnValue(fakeReadQuery({ data: openView }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit({ mutate }));

    render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);
    fireEvent.click(screen.getByRole('option'));
    fireEvent.change(screen.getByTestId('clarification-answer-input'), {
      target: { value: 'answer' },
    });
    fireEvent.click(screen.getByTestId('clarification-submit'));

    const call = warnSpy.mock.calls.find(
      ([arg]) =>
        typeof arg === 'object' &&
        arg !== null &&
        (arg as { event?: string }).event === 'clarification.submitError',
    );
    expect(call).toBeDefined();
    const payload = call![0] as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual(['code', 'event', 'transport']);
    expect(payload.code).toBe('CLARIFICATION_TERMINAL_STATE');
    expect(payload.transport).toBe(false);
  });

  it('logs clarification.loadError when the (future) read query errors', () => {
    const warnSpy = vi.spyOn(console, 'warn');
    const problem = new ProblemDetailsError(
      toProblemDetails({ code: 'INTERNAL_ERROR', status: 500 }, 500),
    );
    mockUseClarifications.mockReturnValue(fakeReadQuery({ isError: true, error: problem }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit());

    render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);

    const call = warnSpy.mock.calls.find(
      ([arg]) =>
        typeof arg === 'object' &&
        arg !== null &&
        (arg as { event?: string }).event === 'clarification.loadError',
    );
    expect(call).toBeDefined();
    expect((call![0] as Record<string, unknown>).code).toBe('INTERNAL_ERROR');
  });

  it('focuses the region when arrived via a ?clarificationId deep link (T-ANCHOR/OQ-4)', async () => {
    mockUseClarifications.mockReturnValue(fakeReadQuery({ data: openView }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit());

    render(
      <ClarificationRegionContainer
        workflowRunId="run_clr_demo_001"
        clarificationId="cla_open0001"
      />,
    );

    await waitFor(() => {
      const region = document.getElementById(CLARIFICATION_REGION_ID);
      expect(region).not.toBeNull();
      expect(document.activeElement).toBe(region);
    });
  });
});

/**
 * Story 2.25 (Task 2 — AC1/AC2) — a11y over the container's mapped states (disabled
 * read stub → empty; mocked populated data; mocked error). Scans the rendered region
 * for WCAG 2.1 AA violations and asserts the live submit flow stays keyboard-operable.
 */
describe('ClarificationRegionContainer a11y (story 2.25)', () => {
  it('AC2 — the mapped empty state (disabled stub) has no axe violations', async () => {
    mockUseClarifications.mockReturnValue(fakeReadQuery());
    mockUseSubmitClarification.mockReturnValue(fakeSubmit());
    const { container } = render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);
    expect(screen.getByTestId('clarification-region')).toHaveAttribute(
      'data-clarification-region-state',
      'empty',
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — the populated state (mocked read) has no axe violations', async () => {
    mockUseClarifications.mockReturnValue(fakeReadQuery({ data: openView }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit());
    const { container } = render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — the error state (mocked read failure) has no axe violations', async () => {
    const problem = new ProblemDetailsError(
      toProblemDetails({ code: 'INTERNAL_ERROR', status: 500 }, 500),
    );
    mockUseClarifications.mockReturnValue(fakeReadQuery({ isError: true, error: problem }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit());
    const { container } = render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);
    expect(screen.getByTestId('clarification-region')).toHaveAttribute(
      'data-clarification-region-state',
      'error',
    );
    await expectNoA11yViolations(container);
  });

  it('AC1 — the wired submit flow is keyboard-operable end to end', async () => {
    const mutate = vi.fn();
    mockUseClarifications.mockReturnValue(fakeReadQuery({ data: openView }));
    mockUseSubmitClarification.mockReturnValue(fakeSubmit({ mutate }));
    const user = userEvent.setup();
    render(<ClarificationRegionContainer workflowRunId="run_clr_demo_001" />);

    await user.click(screen.getByRole('option'));
    const input = screen.getByTestId('clarification-answer-input');
    input.focus();
    await user.keyboard('Paginate the export.');
    await user.tab();
    expect(document.activeElement).toBe(screen.getByTestId('clarification-submit'));
    await user.keyboard('{Enter}');
    expect(mutate).toHaveBeenCalledTimes(1);
  });
});
