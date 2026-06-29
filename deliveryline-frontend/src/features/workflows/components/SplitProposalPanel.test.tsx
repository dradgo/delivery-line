/**
 * Story 3f-4 (advisory split-proposal channel) — `SplitProposalPanel` + `SplitActionBar`.
 *
 * Fixture-driven RTL on the pure components (no router/query). Pins the channel states
 * (none/pending/available/unavailable), the available proposal's subtasks + dependency edges +
 * self-review signifier + loop count, the open-vs-no-open action sets, and zero axe wcag2aa
 * violations.
 */
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import type { SplitProposalResponse } from '../hooks/useSplitProposal';
import {
  SplitActionBar,
  SplitCommitResultPanel,
  SplitProposalPanel,
  type SplitCommitResponse,
} from './SplitProposalPanel';

/**
 * Render a `<Link>`-using component inside a REAL in-memory TanStack Router (NOT a mocked `Link`,
 * per memory: vitest-cross-file-router-mock) so the child-run links resolve to real hrefs.
 */
async function renderWithRouter(ui: ReactNode) {
  const rootRoute = createRootRoute({ component: () => ui });
  const runRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/workflows/$workflowRunId',
    component: () => null,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([runRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  const result = render(<RouterProvider router={router} />);
  await screen.findByTestId('split-commit-result').catch(() => undefined);
  return result;
}

afterEach(() => cleanup());

function available(overrides: Partial<SplitProposalResponse> = {}): SplitProposalResponse {
  return {
    state: 'available',
    loopCount: 1,
    proposal: {
      splitProposalId: 'splprop_abc123',
      status: 'open',
      subtasks: [
        { ordinal: 1, title: 'Extract the auth module', scope: 'Move the auth handlers out.' },
        { ordinal: 2, title: 'Persist sessions', scope: 'Add the session store.' },
      ],
      dependencies: [{ fromOrdinal: 2, toOrdinal: 1 }],
      reviewedArtifactId: 'art_spec123',
      reviewedArtifactVersion: 1,
      reviewerModelIdentity: 'claude:it-3f4',
      producerModelIdentity: 'codex:it-3f4',
      selfReview: false,
    },
    ...overrides,
  };
}

describe('SplitProposalPanel', () => {
  it('renders nothing for state none', () => {
    const { container } = render(<SplitProposalPanel proposal={{ state: 'none', loopCount: 0 }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing before the first fetch resolves', () => {
    const { container } = render(<SplitProposalPanel proposal={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the pending state while the proposal is in flight', () => {
    render(<SplitProposalPanel proposal={{ state: 'pending', loopCount: 0 }} />);
    expect(screen.getByTestId('split-proposal-pending')).toBeInTheDocument();
  });

  it('renders the unavailable state with a color-independent signifier', () => {
    render(<SplitProposalPanel proposal={{ state: 'unavailable', loopCount: 2 }} />);
    expect(screen.getByTestId('split-proposal-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('split-proposal-loop-count')).toHaveTextContent(
      'Re-propose attempts: 2',
    );
  });

  it('renders an available proposal with subtasks, dependency edges, and the loop count', () => {
    render(<SplitProposalPanel proposal={available()} />);
    expect(screen.getByTestId('split-proposal-available')).toBeInTheDocument();
    const subtasks = screen.getByTestId('split-proposal-subtasks');
    expect(subtasks).toHaveTextContent('1. Extract the auth module');
    expect(subtasks).toHaveTextContent('Move the auth handlers out.');
    expect(subtasks).toHaveTextContent('2. Persist sessions');
    expect(screen.getByTestId('split-proposal-dependencies')).toHaveTextContent('2 → 1');
    expect(screen.getByTestId('split-proposal-loop-count')).toHaveTextContent(
      'Re-propose attempts: 1',
    );
    expect(screen.getByTestId('split-proposal-reviewer-identity')).toHaveTextContent(
      'claude:it-3f4',
    );
  });

  it('flags a same-model self-review without refusing the proposal', () => {
    render(
      <SplitProposalPanel
        proposal={available({
          proposal: { ...available().proposal, selfReview: true },
        })}
      />,
    );
    expect(screen.getByTestId('split-proposal-self-review')).toBeInTheDocument();
    // Still shows the subtasks — surfaced, not blocked.
    expect(screen.getByTestId('split-proposal-subtasks')).toBeInTheDocument();
  });

  it('has zero axe wcag2aa violations across states', async () => {
    for (const proposal of [
      available(),
      available({ proposal: { ...available().proposal, selfReview: true } }),
      { state: 'pending', loopCount: 0 } as SplitProposalResponse,
      { state: 'unavailable', loopCount: 1 } as SplitProposalResponse,
    ]) {
      cleanup();
      const { container } = render(<SplitProposalPanel proposal={proposal} />);
      await expectNoA11yViolations(container);
    }
  });
});

describe('SplitActionBar', () => {
  const noop = () => {};
  const baseProps = {
    mode: 'spec_approval' as const,
    canRequest: false,
    canRepropose: false,
    canDecline: false,
    canApprove: false,
    pending: false,
    onRequest: noop,
    onRepropose: noop,
    onDecline: noop,
    onApprove: noop,
  };

  it('renders nothing when no split action is advertised', () => {
    const { container } = render(<SplitActionBar {...baseProps} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('offers only request when no proposal is open', () => {
    render(<SplitActionBar {...baseProps} canRequest />);
    expect(screen.getByTestId('split-request-button')).toBeInTheDocument();
    expect(screen.queryByTestId('split-repropose-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('split-decline-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('split-approve-button')).not.toBeInTheDocument();
  });

  it('offers approve + repropose + continue-as-single when a proposal is open', () => {
    render(
      <SplitActionBar
        {...baseProps}
        mode="implementation_review"
        canRepropose
        canDecline
        canApprove
      />,
    );
    expect(screen.queryByTestId('split-request-button')).not.toBeInTheDocument();
    expect(screen.getByTestId('split-approve-button')).toBeInTheDocument();
    expect(screen.getByTestId('split-repropose-button')).toBeInTheDocument();
    expect(screen.getByTestId('split-decline-button')).toBeInTheDocument();
  });

  it('fires onApprove when the governed approve primary is clicked', () => {
    const onApprove = vi.fn();
    render(<SplitActionBar {...baseProps} canApprove onApprove={onApprove} />);
    fireEvent.click(screen.getByTestId('split-approve-button'));
    expect(onApprove).toHaveBeenCalledTimes(1);
  });

  it('gates re-propose on non-blank feedback and passes the trimmed text', () => {
    const onRepropose = vi.fn();
    render(<SplitActionBar {...baseProps} canRepropose onRepropose={onRepropose} />);
    const button = screen.getByTestId('split-repropose-button');
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByTestId('split-repropose-feedback'), {
      target: { value: '  split the persistence layer  ' },
    });
    expect(button).not.toBeDisabled();
    fireEvent.click(button);
    expect(onRepropose).toHaveBeenCalledWith('split the persistence layer');
  });

  it('disables every control while a mutation is pending', () => {
    render(<SplitActionBar {...baseProps} canRequest canRepropose canDecline canApprove pending />);
    expect(screen.getByTestId('split-request-button')).toBeDisabled();
    expect(screen.getByTestId('split-repropose-button')).toBeDisabled();
    expect(screen.getByTestId('split-decline-button')).toBeDisabled();
    expect(screen.getByTestId('split-approve-button')).toBeDisabled();
  });

  it('has zero axe wcag2aa violations', async () => {
    const { container } = render(
      <SplitActionBar {...baseProps} canRepropose canDecline canApprove />,
    );
    await expectNoA11yViolations(container);
  });
});

describe('SplitCommitResultPanel', () => {
  function decomposed(): SplitCommitResponse {
    return {
      workflowRunId: 'run_parent',
      splitProposalId: 'splprop_abc123',
      parentDecomposed: true,
      outcome: 'decomposed',
      childRunIds: ['run_child1', 'run_child2'],
      subtasks: [
        { ordinal: 1, status: 'created', childRunId: 'run_child1', childTicketRef: 'LIN-1' },
        { ordinal: 2, status: 'internal_only', childRunId: 'run_child2' },
      ],
    };
  }

  it('renders nothing before any approve has landed', () => {
    const { container } = render(<SplitCommitResultPanel result={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the decomposed outcome with per-subtask statuses and parent → child links', async () => {
    await renderWithRouter(<SplitCommitResultPanel result={decomposed()} />);
    const panel = screen.getByTestId('split-commit-result');
    expect(panel).toHaveAttribute('data-parent-decomposed', 'true');
    expect(screen.getByTestId('split-commit-decomposed')).toBeInTheDocument();
    const subtasks = screen.getAllByTestId('split-commit-subtask');
    expect(subtasks).toHaveLength(2);
    expect(subtasks[0]).toHaveAttribute('data-subtask-status', 'created');
    expect(subtasks[1]).toHaveAttribute('data-subtask-status', 'internal_only');
    const links = screen.getAllByTestId('split-commit-child-link');
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute('href', '/workflows/run_child1');
  });

  it('surfaces a zero-child abort with a color-independent warning and no child links', () => {
    render(
      <SplitCommitResultPanel
        result={{
          workflowRunId: 'run_parent',
          splitProposalId: 'splprop_abc123',
          parentDecomposed: false,
          outcome: 'aborted_no_children',
          childRunIds: [],
          subtasks: [{ ordinal: 1, status: 'failed', reason: 'TicketSourceAdapterException' }],
        }}
      />,
    );
    expect(screen.getByTestId('split-commit-result')).toHaveAttribute(
      'data-parent-decomposed',
      'false',
    );
    expect(screen.getByTestId('split-commit-aborted')).toBeInTheDocument();
    expect(screen.getByTestId('split-commit-subtask')).toHaveAttribute(
      'data-subtask-status',
      'failed',
    );
    expect(screen.queryByTestId('split-commit-child-link')).not.toBeInTheDocument();
  });

  it('has zero axe wcag2aa violations', async () => {
    const { container } = await renderWithRouter(<SplitCommitResultPanel result={decomposed()} />);
    await expectNoA11yViolations(container);
  });
});
