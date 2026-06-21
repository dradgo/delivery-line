/**
 * Story 3d-5 (FR65, AC4) — the Step Execution Log Viewer.
 *
 * Renders the run's latest runner-execution logs streamed over SSE (`useRunnerLogStream`): a
 * live-follow with auto-scroll while the container runs, and a finished/static replay of the
 * persisted post-hoc-redacted log once it completes. The live-vs-finished mode is shown with a
 * COLOR-INDEPENDENT signifier (icon + label, never color alone — UX-DR2), and stream start/end is
 * announced through an `aria-live` region (story 2.25 vocabulary).
 *
 * Gating (AC6 / Trap T5) is the route's job: this viewer is rendered only when the backend reports
 * the `view_runner_logs` action (flowing through `useAllowedActions`, never role-inferred). The
 * `actorRole` is threaded into the stream so the backend resolves the SERVER-SIDE gate too.
 */
import { useEffect, useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import {
  logStreamEnded,
  logStreamError,
  logStreamReconnecting,
  logStreamStarted,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import type { StateName } from '@/lib/state-signifiers';
import { cn } from '@/lib/utils';

import { useRunnerLogStream, type RunnerLogPhase } from '../hooks/useRunnerLogStream';
import { StateSignifierChip } from './WorkflowStateBadge';

export interface StepExecutionLogViewerProps {
  workflowRunId: string;
  /** Actor role threaded to the backend gate; also informs the stream URL. */
  actorRole?: string | undefined;
}

/** Color-independent mode signifier (icon + label) for the current stream phase (UX-DR2). */
function modeSignifier(phase: RunnerLogPhase): { stateName: StateName; label: string } {
  switch (phase) {
    case 'live':
      return { stateName: 'loading', label: 'Live' };
    case 'finished':
      return { stateName: 'stale', label: 'Historical' };
    case 'reconnecting':
      return { stateName: 'recovery', label: 'Reconnecting' };
    case 'ended':
      return { stateName: 'success', label: 'Ended' };
    case 'error':
      return { stateName: 'error', label: 'Error' };
    default:
      return { stateName: 'loading', label: 'Connecting' };
  }
}

function announcementFor(phase: RunnerLogPhase): string {
  if (phase === 'live' || phase === 'finished') {
    return logStreamStarted;
  }
  if (phase === 'reconnecting') {
    return logStreamReconnecting;
  }
  if (phase === 'ended') {
    return logStreamEnded;
  }
  if (phase === 'error') {
    return logStreamError;
  }
  return '';
}

export function StepExecutionLogViewer({ workflowRunId, actorRole }: StepExecutionLogViewerProps) {
  const { lines, phase, endReason, errorReason } = useRunnerLogStream(workflowRunId, {
    enabled: true,
    actorRole,
  });

  const scrollRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  // Auto-scroll to the newest line while following (pausable so the operator can read back).
  useEffect(() => {
    if (autoScroll && scrollRef.current !== null) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [lines, autoScroll]);

  // First-render-safe live-region announcement (defers one commit — assert with `waitFor`).
  const announced = useLiveAnnouncement(announcementFor(phase));

  const mode = modeSignifier(phase);
  const isError = phase === 'error';
  const isTerminal = phase === 'ended' || phase === 'error';
  const showEmptyState = lines.length === 0 && (isTerminal || phase === 'finished');

  return (
    <section
      aria-label="Step execution logs"
      data-testid="step-execution-log-viewer"
      data-phase={phase}
      className="w-full"
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-meta uppercase tracking-wide text-text-tertiary">
            Step execution logs
          </h2>
          <StateSignifierChip
            stateName={mode.stateName}
            label={mode.label}
            testId="step-log-mode"
          />
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-pressed={!autoScroll}
          data-testid="step-log-autoscroll-toggle"
          onClick={() => setAutoScroll((value) => !value)}
        >
          {autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}
        </Button>
      </div>

      {isError ? (
        <p
          data-testid="step-log-error"
          className="mb-2 rounded-md border border-state-error-border bg-state-error px-3 py-2 text-sm text-state-error-foreground"
        >
          {errorReason === 'view_runner_logs_not_allowed'
            ? 'You do not have permission to view these logs.'
            : 'The runner log stream could not be loaded.'}
        </p>
      ) : null}

      <div
        ref={scrollRef}
        data-testid="step-log-scroll"
        // A scrollable log region must be keyboard-focusable so a keyboard-only user can
        // scroll it (WCAG 2.1.1); role="log" is the correct append-only live-region semantic.
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- focusable scroll region (WCAG 2.1.1)
        tabIndex={0}
        role="log"
        aria-label="Runner log output"
        className={cn(
          'max-h-80 overflow-auto rounded-md border border-border bg-surface-sunken p-3',
          'font-mono text-xs leading-relaxed text-text-primary',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        )}
      >
        {showEmptyState ? (
          <p data-testid="step-log-empty" className="text-text-tertiary">
            No log output for this step.
          </p>
        ) : (
          <ol className="m-0 list-none p-0">
            {lines.map((entry) => (
              <li
                key={`${entry.stream}-${entry.seq}`}
                data-stream={entry.stream}
                className={cn(
                  'whitespace-pre-wrap break-words',
                  entry.stream === 'stderr' ? 'text-state-warning-foreground' : undefined,
                )}
              >
                {entry.line}
              </li>
            ))}
          </ol>
        )}
      </div>

      {isTerminal && endReason !== undefined ? (
        <p data-testid="step-log-end-reason" className="mt-1 text-meta text-text-tertiary">
          Stream ended.
        </p>
      ) : null}

      {/* AC4 — stream start/end announced via an aria-live region (color-independent: the
          mode chip above carries the icon+label signifier). */}
      <div role="status" aria-live="polite" className="sr-only" data-testid="step-log-announcer">
        {announced}
      </div>
    </section>
  );
}
