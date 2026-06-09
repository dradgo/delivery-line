/**
 * Story 2.21 (AC3, AC8) — `<ActionLifecycleIndicator>` + `resolveLifecyclePosition`.
 * Story 2.25 (Task 2, AC2) — axe WCAG-2.1-AA scans across all lifecycle positions.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import { ActionLifecycleIndicator } from '../ActionLifecycleIndicator';
import {
  DEFAULT_LIFECYCLE_STAGES,
  lifecycleStageState,
  resolveLifecyclePosition,
} from '../actionLifecycle';
import { LIFECYCLE_FIXTURES } from '@/test/fixtures/feedback/feedbackFixtures';

afterEach(cleanup);

describe('resolveLifecyclePosition', () => {
  it('AC3 — resolves each on-chain stage to its index, not off-chain', () => {
    DEFAULT_LIFECYCLE_STAGES.forEach((stage, index) => {
      const pos = resolveLifecyclePosition(DEFAULT_LIFECYCLE_STAGES, stage);
      expect(pos.currentIndex).toBe(index);
      expect(pos.isOffChain).toBe(false);
    });
  });

  it('marks an off-chain terminal (failed/superseded) with currentIndex -1 + isOffChain', () => {
    for (const terminal of ['failed', 'superseded']) {
      const pos = resolveLifecyclePosition(DEFAULT_LIFECYCLE_STAGES, terminal);
      expect(pos.currentIndex).toBe(-1);
      expect(pos.isOffChain).toBe(true);
    }
  });

  it('treats an empty currentStage as not-started (not off-chain)', () => {
    const pos = resolveLifecyclePosition(DEFAULT_LIFECYCLE_STAGES, '');
    expect(pos.currentIndex).toBe(-1);
    expect(pos.isOffChain).toBe(false);
  });

  it('lifecycleStageState advances done → current → pending', () => {
    const pos = resolveLifecyclePosition(DEFAULT_LIFECYCLE_STAGES, 'accepted');
    expect(lifecycleStageState(0, pos)).toBe('done'); // submitted
    expect(lifecycleStageState(1, pos)).toBe('current'); // accepted
    expect(lifecycleStageState(2, pos)).toBe('pending'); // incorporated
  });
});

describe('ActionLifecycleIndicator', () => {
  it('AC8 — renders submitted and incorporated as DISTINCT nodes (never collapsed)', () => {
    render(<ActionLifecycleIndicator currentStage="submitted" />);
    const indicator = screen.getByTestId('action-lifecycle-indicator');
    const submitted = indicator.querySelector('[data-stage="submitted"]');
    const incorporated = indicator.querySelector('[data-stage="incorporated"]');
    expect(submitted).not.toBeNull();
    expect(incorporated).not.toBeNull();
    expect(submitted).not.toBe(incorporated);
    // Each stage carries a visible text label (non-color signifier, AC6).
    expect(submitted?.textContent).toContain('submitted');
    expect(incorporated?.textContent).toContain('incorporated');
  });

  it('AC3 — advances the current stage through the chain', () => {
    const { rerender } = render(<ActionLifecycleIndicator currentStage="submitted" />);
    const stageState = (name: string) =>
      screen
        .getByTestId('action-lifecycle-indicator')
        .querySelector(`[data-stage="${name}"]`)
        ?.getAttribute('data-stage-state');

    expect(stageState('submitted')).toBe('current');
    expect(stageState('accepted')).toBe('pending');

    rerender(<ActionLifecycleIndicator currentStage="incorporated" />);
    expect(stageState('submitted')).toBe('done');
    expect(stageState('accepted')).toBe('done');
    expect(stageState('incorporated')).toBe('current');
  });

  it('AC8 — marks an off-chain terminal distinctly', () => {
    render(<ActionLifecycleIndicator currentStage="failed" />);
    const indicator = screen.getByTestId('action-lifecycle-indicator');
    expect(indicator).toHaveAttribute('data-off-chain', 'true');
    const offChain = indicator.querySelector('[data-stage-state="offchain"]');
    expect(offChain).not.toBeNull();
    expect(offChain).toHaveAttribute('data-stage', 'failed');
    // No on-chain stage is "current" once off-chain.
    expect(indicator.querySelector('[data-stage-state="current"]')).toBeNull();
  });

  it.each(LIFECYCLE_FIXTURES)(
    'AC7 — fixture "$id" renders the configured lifecycle state',
    (fixture) => {
      render(
        <ActionLifecycleIndicator stages={fixture.stages} currentStage={fixture.currentStage} />,
      );
      const indicator = screen.getByTestId('action-lifecycle-indicator');
      expect(indicator).toHaveAttribute('data-current-stage', fixture.currentStage);
      if (fixture.currentStage === 'failed') {
        expect(indicator.querySelector('[data-stage-state="offchain"]')).toHaveAttribute(
          'data-stage',
          'failed',
        );
      } else {
        expect(indicator.querySelector(`[data-stage="${fixture.currentStage}"]`)).toHaveAttribute(
          'data-stage-state',
          'current',
        );
      }
    },
  );

  it('supports a custom stage chain', () => {
    render(
      <ActionLifecycleIndicator
        stages={['queued', 'running', 'done'] as const}
        currentStage="running"
      />,
    );
    const indicator = screen.getByTestId('action-lifecycle-indicator');
    expect(indicator.querySelector('[data-stage="running"]')).toHaveAttribute(
      'data-stage-state',
      'current',
    );
    expect(indicator.querySelector('[data-stage="submitted"]')).toBeNull();
  });

  it('rejects duplicate stage names in a generic lifecycle chain', () => {
    expect(() => resolveLifecyclePosition(['queued', 'running', 'running'], 'running')).toThrow(
      'Lifecycle stages must be unique: running',
    );
  });
});

// Story 2.25 (Task 2, AC2) — axe WCAG-2.1-AA scan of every documented lifecycle
// position (not-started, each on-chain stage, and an off-chain terminal).
// ActionLifecycleIndicator is purely presentational (a labelled ordered list with
// no interactive controls), so there is no keyboard test — the indicator exposes
// no activatable affordance and cannot receive Tab focus.
describe('ActionLifecycleIndicator a11y (story 2.25)', () => {
  it('AC2 — not-started (empty currentStage) has no axe violations', async () => {
    const { container } = render(<ActionLifecycleIndicator currentStage="" />);
    await expectNoA11yViolations(container);
  });

  it.each(DEFAULT_LIFECYCLE_STAGES)(
    'AC2 — on-chain stage "%s" has no axe violations',
    async (stage) => {
      const { container } = render(<ActionLifecycleIndicator currentStage={stage} />);
      await expectNoA11yViolations(container);
    },
  );

  it('AC2 — off-chain terminal "failed" has no axe violations', async () => {
    const { container } = render(<ActionLifecycleIndicator currentStage="failed" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — off-chain terminal "superseded" has no axe violations', async () => {
    const { container } = render(<ActionLifecycleIndicator currentStage="superseded" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — custom stage chain with ariaLabel override has no axe violations', async () => {
    const { container } = render(
      <ActionLifecycleIndicator
        stages={['queued', 'running', 'done']}
        currentStage="running"
        ariaLabel="Pipeline progress: running"
      />,
    );
    await expectNoA11yViolations(container);
  });
});
