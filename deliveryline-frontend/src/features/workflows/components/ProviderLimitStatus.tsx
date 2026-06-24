/**
 * Story 3d-7 (FR69, AC5) — the Provider Limit Status indicator.
 *
 * Renders the run's latest provider usage/limit status (a one-shot React-Query read via
 * `useProviderUsageStatus`): the 5-hour + weekly window status, or the documented "not exposed by
 * provider" state. Values are clearly labelled PROVIDER-REPORTED and AS-OF a timestamp, and the
 * signal state is shown with a COLOR-INDEPENDENT signifier (icon + label, never color alone —
 * UX-DR2). NON-SECRET: only window numbers, timestamps, and the non-secret account label.
 *
 * Gating (AC5 / Trap T5) is the route's job: this indicator is rendered only when the backend
 * reports the `view_provider_usage_status` action (flowing through `useAllowedActions`, never
 * role-inferred). The read endpoint enforces the same gate server-side.
 */
import {
  providerUsageStatusError,
  providerUsageStatusLoaded,
  providerUsageStatusNotExposed,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import type { StateName } from '@/lib/state-signifiers';
import { cn } from '@/lib/utils';

import { useProviderUsageStatus, type ProviderUsageStatus } from '../hooks/useProviderUsageStatus';
import { StateSignifierChip } from './WorkflowStateBadge';

export interface ProviderLimitStatusProps {
  workflowRunId: string;
}

type UsageWindow = NonNullable<ProviderUsageStatus['fiveHour']>;

/** Color-independent signifier (icon + label) for the captured signal state (UX-DR2). */
function signalSignifier(
  status: ProviderUsageStatus | undefined,
  isError: boolean,
): { stateName: StateName; label: string } {
  if (isError) {
    return { stateName: 'error', label: 'Error' };
  }
  if (status === undefined) {
    return { stateName: 'loading', label: 'Loading' };
  }
  if (status.present !== true) {
    return { stateName: 'empty', label: 'No snapshot yet' };
  }
  if (status.signalState === 'available') {
    return { stateName: 'success', label: 'Provider-reported' };
  }
  return { stateName: 'stale', label: 'Not exposed by provider' };
}

function announcementFor(status: ProviderUsageStatus | undefined, isError: boolean): string {
  if (isError) {
    return providerUsageStatusError;
  }
  if (status === undefined || status.present !== true) {
    return '';
  }
  return status.signalState === 'available'
    ? providerUsageStatusLoaded
    : providerUsageStatusNotExposed;
}

/**
 * Color-independent display of a window's values, preserving partials instead of collapsing a
 * present `used`/`limit` to a literal "partial" (review 2026-06-24).
 */
function windowDisplay(window: UsageWindow): string {
  const parts: string[] = [];
  if (window.used != null && window.limit != null) {
    const pct = window.usedFraction != null ? ` (${Math.round(window.usedFraction * 100)}%)` : '';
    parts.push(`${window.used}/${window.limit}${pct}`);
  } else if (window.usedFraction != null) {
    parts.push(`${Math.round(window.usedFraction * 100)}%`);
  } else if (window.used != null) {
    parts.push(`used ${window.used}`);
  } else if (window.limit != null) {
    parts.push(`limit ${window.limit}`);
  }
  if (window.resetsAt != null) {
    parts.push(`resets ${window.resetsAt}`);
  }
  return parts.join(' · ');
}

/** Color-independent render of one window (5h / weekly), or the not-exposed / no-data state. */
function WindowRow({
  label,
  window,
  testId,
  signalAvailable,
}: {
  label: string;
  window: UsageWindow | null | undefined;
  testId: string;
  signalAvailable: boolean;
}) {
  const hasData =
    window != null &&
    (window.usedFraction != null ||
      window.used != null ||
      window.limit != null ||
      window.resetsAt != null);
  return (
    <div data-testid={testId} className="flex items-center justify-between gap-2 text-sm">
      <span className="text-text-tertiary">{label}</span>
      {hasData ? (
        <span className="font-mono text-text-primary">{windowDisplay(window)}</span>
      ) : (
        <span data-testid={`${testId}-not-exposed`} className="text-text-tertiary">
          {/* When the provider reported a signal but this window carries no numbers, that is "no
              window data" — distinct from the provider not exposing the signal at all. */}
          {signalAvailable ? 'No window data' : 'Not exposed'}
        </span>
      )}
    </div>
  );
}

export function ProviderLimitStatus({ workflowRunId }: ProviderLimitStatusProps) {
  const { data: status, isError, isPending } = useProviderUsageStatus(workflowRunId);

  const signal = signalSignifier(status, isError);
  const announced = useLiveAnnouncement(announcementFor(status, isError));
  const present = status?.present === true;

  return (
    <section
      aria-label="Provider limit status"
      data-testid="provider-limit-status"
      data-signal-state={status?.signalState ?? (present ? 'present' : 'absent')}
      className="w-full"
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-meta uppercase tracking-wide text-text-tertiary">
            Provider limit status
          </h2>
          <StateSignifierChip
            stateName={signal.stateName}
            label={signal.label}
            testId="provider-limit-signal"
          />
        </div>
        {present && status.accountReference != null ? (
          <span data-testid="provider-limit-account" className="text-meta text-text-tertiary">
            {status.accountReference}
          </span>
        ) : null}
      </div>

      {isError ? (
        <p
          data-testid="provider-limit-error"
          className="rounded-md border border-state-error-border bg-state-error px-3 py-2 text-sm text-state-error-foreground"
        >
          The provider usage status could not be loaded.
        </p>
      ) : isPending ? (
        <p data-testid="provider-limit-loading" className="text-sm text-text-tertiary">
          Loading provider usage status…
        </p>
      ) : !present ? (
        <p data-testid="provider-limit-empty" className="text-sm text-text-tertiary">
          No provider usage snapshot has been captured for this run yet.
        </p>
      ) : (
        <div
          className={cn(
            'flex flex-col gap-1 rounded-md border border-border bg-surface-sunken p-3',
          )}
        >
          <WindowRow
            label="5-hour window"
            window={status.fiveHour}
            testId="provider-limit-5h"
            signalAvailable={status.signalState === 'available'}
          />
          <WindowRow
            label="Weekly window"
            window={status.weekly}
            testId="provider-limit-weekly"
            signalAvailable={status.signalState === 'available'}
          />
          <p data-testid="provider-limit-asof" className="mt-1 text-meta text-text-tertiary">
            {status.signalState === 'available'
              ? `Provider-reported${status.asOf != null ? `, as of ${status.asOf}` : ''}`
              : 'The provider does not expose 5-hour / weekly window status.'}
          </p>
        </div>
      )}

      {/* AC5 — status load / not-exposed announced via an aria-live region (color-independent:
          the signal chip above carries the icon+label signifier). */}
      <div
        role="status"
        aria-live="polite"
        className="sr-only"
        data-testid="provider-limit-announcer"
      >
        {announced}
      </div>
    </section>
  );
}
