/**
 * Story 3f-3 (AC8 / AC10) — `RunDependencyPanel`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import type { components } from '@/lib/api/schema';

import { RunDependencyPanel } from './RunDependencyPanel';

type RunDependencies = components['schemas']['RunDependencies'];

afterEach(cleanup);

const BLOCKED: RunDependencies = {
  prerequisites: [
    { runId: 'run_prereq_one', state: 'Executing' },
    { runId: 'run_prereq_two', state: 'Completed' },
  ],
  dependents: [{ runId: 'run_dependent_one', state: 'WaitingForDependencies' }],
  blockedOn: [{ runId: 'run_prereq_one', state: 'Executing' }],
  blockedByDependencies: true,
};

describe('RunDependencyPanel', () => {
  it('renders nothing for a run with no prerequisites or dependents', () => {
    const { container } = render(
      <RunDependencyPanel
        dependencies={{
          prerequisites: [],
          dependents: [],
          blockedOn: [],
          blockedByDependencies: false,
        }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when dependencies are undefined', () => {
    const { container } = render(<RunDependencyPanel dependencies={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('AC8 — surfaces prerequisites, dependents, and an explicit blocked signifier', () => {
    render(<RunDependencyPanel dependencies={BLOCKED} />);
    expect(screen.getByTestId('run-dependency-panel')).toBeInTheDocument();
    expect(screen.getByTestId('run-dependency-blocked')).toHaveTextContent(
      'Blocked — waiting on 1 prerequisite to complete.',
    );
    expect(screen.getByTestId('run-dependency-prerequisites')).toHaveTextContent('run_prereq_one');
    expect(screen.getByTestId('run-dependency-prerequisites')).toHaveTextContent('run_prereq_two');
    expect(screen.getByTestId('run-dependency-dependents')).toHaveTextContent('run_dependent_one');
  });

  it('AC8 — omits the blocked signifier when not blocked', () => {
    render(
      <RunDependencyPanel
        dependencies={{
          prerequisites: [{ runId: 'run_prereq_done', state: 'Completed' }],
          dependents: [],
          blockedOn: [],
          blockedByDependencies: false,
        }}
      />,
    );
    expect(screen.queryByTestId('run-dependency-blocked')).not.toBeInTheDocument();
  });

  it('AC10 — has no axe violations', async () => {
    const { container } = render(<RunDependencyPanel dependencies={BLOCKED} />);
    await expectNoA11yViolations(container);
  });
});
