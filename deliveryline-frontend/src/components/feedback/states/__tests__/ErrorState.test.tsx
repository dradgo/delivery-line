/**
 * Story 2.22 AC11.n (4 variants × 5 nextAction kinds), AC11.o (active focus),
 * AC11.p (aria-live), Trap T9 (NavigateBack consumes useReturnToRunContext),
 * Trap T10 (ContactSupport disabled when no URL), Trap T13 (assertive/polite).
 *
 * `useReturnToRunContext` is mocked at its own module (NOT the router) so this
 * file never touches `@tanstack/react-router` — keeping it isolated from the
 * nav-hook test's router mock.
 */
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { ErrorState, type ErrorVariant, type ErrorStateProps } from '../ErrorState';

const VARIANTS: ErrorVariant[] = [
  'failedRetrieval',
  'unavailableDiffBaseline',
  'permissionRestricted',
  'blockedByStaleState',
];

beforeEach(() => backSpy.mockClear());
afterEach(cleanup);

describe('ErrorState', () => {
  it('AC11.n — every variant × every nextAction kind: actionable controls fire their callback / links resolve', () => {
    for (const variant of VARIANTS) {
      // Retry — clicking fires onRetry.
      const onRetry = vi.fn();
      let view = render(<ErrorState variant={variant} nextAction={{ kind: 'Retry', onRetry }} />);
      fireEvent.click(screen.getByRole('button'));
      expect(onRetry).toHaveBeenCalledTimes(1);
      view.unmount();

      // Refresh — clicking fires onRefresh.
      const onRefresh = vi.fn();
      view = render(<ErrorState variant={variant} nextAction={{ kind: 'Refresh', onRefresh }} />);
      fireEvent.click(screen.getByRole('button'));
      expect(onRefresh).toHaveBeenCalledTimes(1);
      view.unmount();

      // NavigateBack — clicking consumes useReturnToRunContext (Trap T9), no callback passed.
      backSpy.mockClear();
      view = render(<ErrorState variant={variant} nextAction={{ kind: 'NavigateBack' }} />);
      fireEvent.click(screen.getByRole('button'));
      expect(backSpy).toHaveBeenCalledTimes(1);
      view.unmount();

      // ContactSupport (resolvable href) — renders a focusable external link.
      view = render(
        <ErrorState
          variant={variant}
          nextAction={{ kind: 'ContactSupport', href: 'https://support.example.com' }}
        />,
      );
      expect(screen.getByRole('link')).toHaveAttribute('href', 'https://support.example.com');
      view.unmount();

      // DocsLink — renders a focusable external link to the docs href.
      view = render(
        <ErrorState
          variant={variant}
          nextAction={{ kind: 'DocsLink', href: 'https://docs.example.com' }}
        />,
      );
      expect(screen.getByRole('link')).toHaveAttribute('href', 'https://docs.example.com');
      view.unmount();
    }
  });

  it('AC11.m — omitting the required nextAction is a COMPILE error (type-level contract)', () => {
    // The `@ts-expect-error` fails the build if `nextAction` ever stops being
    // required — there must be no path through <ErrorState> without a next action.
    // @ts-expect-error — nextAction is required (AC6); omitting it must not compile.
    const props: ErrorStateProps = { variant: 'failedRetrieval' };
    expect(props.variant).toBe('failedRetrieval');
  });

  it('AC11.n — Retry/Refresh/NavigateBack controls trigger their callbacks', () => {
    const onRetry = vi.fn();
    const { rerender } = render(
      <ErrorState variant="failedRetrieval" nextAction={{ kind: 'Retry', onRetry }} />,
    );
    fireEvent.click(screen.getByRole('button'));
    expect(onRetry).toHaveBeenCalledTimes(1);

    const onRefresh = vi.fn();
    rerender(<ErrorState variant="failedRetrieval" nextAction={{ kind: 'Refresh', onRefresh }} />);
    fireEvent.click(screen.getByRole('button'));
    expect(onRefresh).toHaveBeenCalledTimes(1);

    rerender(<ErrorState variant="failedRetrieval" nextAction={{ kind: 'NavigateBack' }} />);
    fireEvent.click(screen.getByRole('button'));
    expect(backSpy).toHaveBeenCalledTimes(1);
  });

  it('Trap T10 — ContactSupport with no resolvable URL renders a disabled placeholder', () => {
    render(<ErrorState variant="permissionRestricted" nextAction={{ kind: 'ContactSupport' }} />);
    const btn = screen.getByRole('button', { name: 'Get help' });
    expect(btn).toBeDisabled();
  });

  it('AC11.p / Trap T13 — urgency drives aria-live (active=assertive, passive=polite)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { rerender } = render(
      <ErrorState variant="failedRetrieval" urgency="passive" nextAction={{ kind: 'NavigateBack' }} />,
    );
    expect(screen.getByTestId('error-state')).toHaveAttribute('aria-live', 'polite');
    rerender(
      <ErrorState variant="failedRetrieval" urgency="active" nextAction={{ kind: 'NavigateBack' }} />,
    );
    expect(screen.getByTestId('error-state')).toHaveAttribute('aria-live', 'assertive');
    warn.mockRestore();
  });

  it('AC11.o — active urgency moves focus to the action control on mount', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(
      <ErrorState
        variant="failedRetrieval"
        urgency="active"
        nextAction={{ kind: 'Retry', onRetry: vi.fn() }}
      />,
    );
    expect(document.activeElement).toBe(screen.getByRole('button'));
    warn.mockRestore();
  });
});
