/**
 * Story 3f-4 (advisory split-proposal channel) — `SplitProposalPanel` + `SplitActionBar`.
 *
 * Fixture-driven RTL on the pure components (no router/query). Pins the channel states
 * (none/pending/available/unavailable), the available proposal's subtasks + dependency edges +
 * self-review signifier + loop count, the open-vs-no-open action sets, and zero axe wcag2aa
 * violations.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import type { SplitProposalResponse } from '../hooks/useSplitProposal';
import { SplitActionBar, SplitProposalPanel } from './SplitProposalPanel';

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

  it('renders nothing when no split action is advertised', () => {
    const { container } = render(
      <SplitActionBar
        mode="spec_approval"
        canRequest={false}
        canRepropose={false}
        canDecline={false}
        pending={false}
        onRequest={noop}
        onRepropose={noop}
        onDecline={noop}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('offers only request when no proposal is open', () => {
    render(
      <SplitActionBar
        mode="spec_approval"
        canRequest
        canRepropose={false}
        canDecline={false}
        pending={false}
        onRequest={noop}
        onRepropose={noop}
        onDecline={noop}
      />,
    );
    expect(screen.getByTestId('split-request-button')).toBeInTheDocument();
    expect(screen.queryByTestId('split-repropose-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('split-decline-button')).not.toBeInTheDocument();
  });

  it('offers repropose + continue-as-single when a proposal is open', () => {
    render(
      <SplitActionBar
        mode="implementation_review"
        canRequest={false}
        canRepropose
        canDecline
        pending={false}
        onRequest={noop}
        onRepropose={noop}
        onDecline={noop}
      />,
    );
    expect(screen.queryByTestId('split-request-button')).not.toBeInTheDocument();
    expect(screen.getByTestId('split-repropose-button')).toBeInTheDocument();
    expect(screen.getByTestId('split-decline-button')).toBeInTheDocument();
  });

  it('gates re-propose on non-blank feedback and passes the trimmed text', () => {
    const onRepropose = vi.fn();
    render(
      <SplitActionBar
        mode="spec_approval"
        canRequest={false}
        canRepropose
        canDecline={false}
        pending={false}
        onRequest={noop}
        onRepropose={onRepropose}
        onDecline={noop}
      />,
    );
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
    render(
      <SplitActionBar
        mode="spec_approval"
        canRequest
        canRepropose
        canDecline
        pending
        onRequest={noop}
        onRepropose={noop}
        onDecline={noop}
      />,
    );
    expect(screen.getByTestId('split-request-button')).toBeDisabled();
    expect(screen.getByTestId('split-repropose-button')).toBeDisabled();
    expect(screen.getByTestId('split-decline-button')).toBeDisabled();
  });

  it('has zero axe wcag2aa violations', async () => {
    const { container } = render(
      <SplitActionBar
        mode="spec_approval"
        canRequest={false}
        canRepropose
        canDecline
        pending={false}
        onRequest={noop}
        onRepropose={noop}
        onDecline={noop}
      />,
    );
    await expectNoA11yViolations(container);
  });
});
