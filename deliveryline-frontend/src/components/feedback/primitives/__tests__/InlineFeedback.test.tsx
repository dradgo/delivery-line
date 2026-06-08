/**
 * Story 2.21 (AC4, AC5, AC6, AC7) — `<InlineFeedback>`.
 */
import type { ComponentProps } from 'react';
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { InlineFeedback, type InlineFeedbackVariant } from '../InlineFeedback';
import { INLINE_VARIANT_TO_STATE, stateLabel } from '../feedbackStatePresentation';
import { INLINE_FEEDBACK_FIXTURES } from '@/test/fixtures/feedback/feedbackFixtures';

afterEach(cleanup);

const POLITE_VARIANTS: InlineFeedbackVariant[] = ['info', 'success'];
const ASSERTIVE_VARIANTS: InlineFeedbackVariant[] = ['warning', 'blocker', 'error'];
const ALL_VARIANTS: InlineFeedbackVariant[] = [...POLITE_VARIANTS, ...ASSERTIVE_VARIANTS];

describe('InlineFeedback', () => {
  it.each(ALL_VARIANTS)(
    'AC6/AC7 — variant "%s" renders a non-color signifier (icon + text label)',
    (variant) => {
      render(
        <InlineFeedback variant={variant} persistsUntil="infinite">
          Body copy
        </InlineFeedback>,
      );
      const el = screen.getByTestId('inline-feedback');
      expect(el).toHaveAttribute('data-inline-feedback-variant', variant);
      // Icon present (not color alone).
      expect(el.querySelector('svg')).not.toBeNull();
      expect(el).toHaveTextContent(stateLabel(INLINE_VARIANT_TO_STATE[variant]));
    },
  );

  it.each(INLINE_FEEDBACK_FIXTURES)(
    'AC7 — fixture "$id" renders its canonical signifier and authored content',
    (fixture) => {
      const commonProps = {
        variant: fixture.variant,
        title: fixture.title,
        staleStateExplanation: fixture.staleStateExplanation,
      };
      render(
        fixture.persistsUntil === 'dismiss' ? (
          <InlineFeedback {...commonProps} persistsUntil="dismiss" onDismiss={() => undefined}>
            {fixture.body}
          </InlineFeedback>
        ) : (
          <InlineFeedback {...commonProps} persistsUntil={fixture.persistsUntil}>
            {fixture.body}
          </InlineFeedback>
        ),
      );
      const el = screen.getByTestId('inline-feedback');
      expect(el).toHaveTextContent(stateLabel(INLINE_VARIANT_TO_STATE[fixture.variant]));
      expect(el).toHaveTextContent(fixture.body);
      if (fixture.title !== undefined) {
        expect(el).toHaveTextContent(fixture.title);
      }
    },
  );

  it.each(POLITE_VARIANTS)('AC6 — variant "%s" announces politely', (variant) => {
    render(
      <InlineFeedback variant={variant} persistsUntil="infinite">
        x
      </InlineFeedback>,
    );
    const el = screen.getByTestId('inline-feedback');
    expect(el).toHaveAttribute('aria-live', 'polite');
    expect(el).toHaveAttribute('role', 'status');
    expect(el).toHaveAttribute('data-prominence', 'informational');
  });

  it.each(ASSERTIVE_VARIANTS)(
    'AC6 — dominant variant "%s" announces assertively and reads stronger',
    (variant) => {
      render(
        <InlineFeedback variant={variant} persistsUntil="infinite">
          x
        </InlineFeedback>,
      );
      const el = screen.getByTestId('inline-feedback');
      expect(el).toHaveAttribute('aria-live', 'assertive');
      expect(el).toHaveAttribute('role', 'alert');
      expect(el).toHaveAttribute('data-prominence', 'dominant');
    },
  );

  it('AC1 — persistsUntil="dismiss" renders a dismiss control wired to onDismiss', () => {
    const onDismiss = vi.fn();
    render(
      <InlineFeedback variant="info" persistsUntil="dismiss" onDismiss={onDismiss}>
        x
      </InlineFeedback>,
    );
    const button = screen.getByRole('button', { name: 'Dismiss' });
    fireEvent.click(button);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('types require onDismiss for dismissible feedback', () => {
    // @ts-expect-error dismissible feedback must provide a working handler.
    const invalidProps: ComponentProps<typeof InlineFeedback> = {
      variant: 'info',
      persistsUntil: 'dismiss',
    };
    expect(invalidProps).toBeDefined();
  });

  it.each(['workflowChange', 'infinite'] as const)(
    'AC1 — persistsUntil="%s" renders no dismiss control',
    (persistsUntil) => {
      render(
        <InlineFeedback variant="info" persistsUntil={persistsUntil}>
          x
        </InlineFeedback>,
      );
      expect(screen.queryByRole('button', { name: 'Dismiss' })).toBeNull();
    },
  );

  it('AC5 — warning renders staleStateExplanation as two DISTINCT labeled segments', () => {
    render(
      <InlineFeedback
        variant="warning"
        persistsUntil="workflowChange"
        staleStateExplanation={{
          whatChanged: 'A newer version was published.',
          whatToDoNext: 'Refresh and re-submit.',
        }}
      >
        Stale.
      </InlineFeedback>,
    );
    const el = screen.getByTestId('inline-feedback');
    const changed = el.querySelector('[data-stale-segment="what-changed"]');
    const next = el.querySelector('[data-stale-segment="what-to-do-next"]');
    expect(changed).not.toBeNull();
    expect(next).not.toBeNull();
    // The two segments are distinct nodes — never concatenated into one line.
    expect(changed).not.toBe(next);
    expect(within(changed as HTMLElement).getByText('What changed')).toBeInTheDocument();
    expect(
      within(changed as HTMLElement).getByText('A newer version was published.'),
    ).toBeInTheDocument();
    expect(within(next as HTMLElement).getByText('What to do next')).toBeInTheDocument();
    expect(within(next as HTMLElement).getByText('Refresh and re-submit.')).toBeInTheDocument();
  });

  it('AC5 — staleStateExplanation is ignored for non-warning variants', () => {
    render(
      <InlineFeedback
        variant="info"
        persistsUntil="infinite"
        staleStateExplanation={{ whatChanged: 'a', whatToDoNext: 'b' }}
      >
        x
      </InlineFeedback>,
    );
    const el = screen.getByTestId('inline-feedback');
    expect(el.querySelector('[data-stale-explanation]')).toBeNull();
  });

  it('AC4 — renders IN its mounting region, not at document level (no portal escape)', () => {
    render(
      <div data-testid="host">
        <InlineFeedback variant="success" persistsUntil="infinite">
          In region
        </InlineFeedback>
      </div>,
    );
    const host = screen.getByTestId('host');
    expect(host.querySelector('[data-testid="inline-feedback"]')).not.toBeNull();
    // The primitive is a direct descendant of its host, not portaled to <body>.
    expect(document.body.querySelector(':scope > [data-testid="inline-feedback"]')).toBeNull();
  });
});
