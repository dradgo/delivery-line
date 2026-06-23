/**
 * Story 3d-4 (AC7/AC9) — Manual Execution Surface Vitest coverage. Mocks the bundle + submit hooks
 * so the test drives the bundle download/copy affordances, paste submission, inline Problem-Details
 * error (asserted on `code`, never human copy), allowed-action gating, the live-region announcement
 * (asserted via `waitFor` — the announcer defers one commit), and zero `wcag2aa` axe violations.
 */
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ProblemDetailsError } from '@/lib/api/problemDetails';
import { expectNoA11yViolations } from '@/test/a11y/axe';

import { ManualExecutionSurface } from './ManualExecutionSurface';
import type { ManualBundleResponse } from '../hooks/useManualBundle';

const useManualBundleMock = vi.fn();
const useSubmitManualArtifactMock = vi.fn();

vi.mock('../hooks/useManualBundle', () => ({
  useManualBundle: (workflowRunId: string, enabled?: boolean): unknown =>
    useManualBundleMock(workflowRunId, enabled) as unknown,
}));
vi.mock('../hooks/useSubmitManualArtifact', () => ({
  useSubmitManualArtifact: (workflowRunId: string): unknown =>
    useSubmitManualArtifactMock(workflowRunId) as unknown,
}));

const availableBundle: ManualBundleResponse = {
  workflowRunId: 'run_manual000001',
  runnerExecutionId: 'rex_manual000001',
  available: true,
  unavailableReason: null,
  contextBundleVersion: 1,
  bundleBase64: btoa('{"hello":"bundle"}'),
};

function bundleQuery(overrides: Partial<{ data: ManualBundleResponse; isPending: boolean }> = {}) {
  return { data: overrides.data, isPending: overrides.isPending ?? false };
}

function submitState(
  overrides: Partial<{
    mutate: ReturnType<typeof vi.fn>;
    isPending: boolean;
    isSuccess: boolean;
    isError: boolean;
    error: unknown;
    data: { currentState?: string };
  }> = {},
) {
  return {
    mutate: overrides.mutate ?? vi.fn(),
    isPending: overrides.isPending ?? false,
    isSuccess: overrides.isSuccess ?? false,
    isError: overrides.isError ?? false,
    error: overrides.error,
    data: overrides.data,
  };
}

beforeEach(() => {
  useManualBundleMock.mockReset();
  useSubmitManualArtifactMock.mockReset();
  // jsdom lacks Blob URL + clipboard plumbing.
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:mock');
  globalThis.URL.revokeObjectURL = vi.fn();
  Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
});

afterEach(() => cleanup());

describe('ManualExecutionSurface (story 3d-4)', () => {
  it('renders bundle download + copy affordances when obtain-bundle is allowed (AC1/AC7)', async () => {
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(submitState());

    render(
      <ManualExecutionSurface
        workflowRunId="run_manual000001"
        canObtainBundle
        canSubmitArtifact={false}
      />,
    );

    expect(screen.getByTestId('manual-bundle-download')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('manual-bundle-copy'));
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledOnce());
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('{"hello":"bundle"}');
  });

  it('submits a pasted runner-result through the mutation (AC2)', () => {
    const mutate = vi.fn();
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(submitState({ mutate }));

    render(
      <ManualExecutionSurface workflowRunId="run_manual000001" canObtainBundle canSubmitArtifact />,
    );

    fireEvent.change(screen.getByTestId('manual-artifact-payload'), {
      target: { value: '{"schemaVersion":1}' },
    });
    fireEvent.click(screen.getByTestId('manual-artifact-submit'));
    expect(mutate).toHaveBeenCalledWith({ result: { schemaVersion: 1 } });
  });

  it('announces an in-flight submission via the live region (AC7)', async () => {
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(submitState({ isPending: true }));

    render(
      <ManualExecutionSurface workflowRunId="run_manual000001" canObtainBundle canSubmitArtifact />,
    );
    // The announcer defers one commit — assert via waitFor (never synchronously).
    await waitFor(() =>
      expect(screen.getByTestId('manual-submit-announcer')).toHaveTextContent(
        'Submitting the manual artifact.',
      ),
    );
  });

  it('rejects non-JSON paste locally without calling the mutation', () => {
    const mutate = vi.fn();
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(submitState({ mutate }));

    render(
      <ManualExecutionSurface workflowRunId="run_manual000001" canObtainBundle canSubmitArtifact />,
    );
    fireEvent.change(screen.getByTestId('manual-artifact-payload'), {
      target: { value: 'not json' },
    });
    fireEvent.click(screen.getByTestId('manual-artifact-submit'));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByTestId('manual-artifact-local-error')).toBeInTheDocument();
  });

  it('surfaces a Problem-Details rejection mapped from its code (AC5/AC9)', () => {
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(
      submitState({
        isError: true,
        error: new ProblemDetailsError({
          type: 'about:blank',
          title: 'Runner output validation failed',
          status: 502,
          detail: 'Manual artifact failed runner-output validation',
          instance: '/api/v1/workflows/run_manual000001/manual-artifact',
          code: 'RUNNER_OUTPUT_VALIDATION_FAILED',
          retryable: false,
        }),
      }),
    );

    render(
      <ManualExecutionSurface workflowRunId="run_manual000001" canObtainBundle canSubmitArtifact />,
    );
    expect(screen.getByTestId('manual-artifact-error')).toHaveTextContent(
      'failed validation against the runner-output contract',
    );
  });

  it('hides the submit region when submit-artifact is not allowed (gating, AC7)', () => {
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(submitState());

    render(
      <ManualExecutionSurface
        workflowRunId="run_manual000001"
        canObtainBundle
        canSubmitArtifact={false}
      />,
    );
    expect(screen.queryByTestId('manual-submit-region')).not.toBeInTheDocument();
  });

  it('renders a typed unavailable bundle state without a crash (AC1)', () => {
    useManualBundleMock.mockReturnValue(
      bundleQuery({
        data: {
          workflowRunId: 'run_manual000001',
          runnerExecutionId: 'rex_manual000001',
          available: false,
          unavailableReason: 'bundleNotPersisted',
          contextBundleVersion: null,
          bundleBase64: null,
        },
      }),
    );
    useSubmitManualArtifactMock.mockReturnValue(submitState());

    render(
      <ManualExecutionSurface workflowRunId="run_manual000001" canObtainBundle canSubmitArtifact />,
    );
    expect(screen.getByTestId('manual-bundle-unavailable')).toHaveTextContent('bundleNotPersisted');
  });

  it('has zero WCAG 2.1 AA axe violations (AC7/AC9)', async () => {
    useManualBundleMock.mockReturnValue(bundleQuery({ data: availableBundle }));
    useSubmitManualArtifactMock.mockReturnValue(submitState());

    const { container } = render(
      <ManualExecutionSurface workflowRunId="run_manual000001" canObtainBundle canSubmitArtifact />,
    );
    await expectNoA11yViolations(container);
  });
});
