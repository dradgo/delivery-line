/**
 * Story 3d-6 (FR68, AC6) — the Read-only Diagnostic Console.
 *
 * Renders a terminal-styled, auto-scrolling view of the run's latest LIVE runner-execution container
 * stdio, streamed over SSE (`useDiagnosticConsole`). It is clearly badged **Read-only** and has NO
 * input control wired to the backend — a pure streaming pty with input disabled, which is the
 * provable non-mutating guarantee (DD-1 / Trap T6). The live/ended/error mode is shown with a
 * COLOR-INDEPENDENT signifier (icon + label, never color alone — UX-DR2), and session start/end is
 * announced through an `aria-live` region (story 2.25 vocabulary).
 *
 * Gating (AC4 / Trap T5) is the route's job: this console is rendered only when the backend reports
 * the `open_diagnostic_console` action (flowing through `useAllowedActions`, never role-inferred).
 * The `actorRole` is threaded into the stream so the backend resolves the SERVER-SIDE gate too.
 *
 * LIVE-ONLY (DD-3): there is no finished/historical mode here — a finished or absent execution
 * surfaces a `console-not-live` error. The finished-state diagnostic surface is the runner-log
 * viewer (`StepExecutionLogViewer`, story 3d-5).
 */
import { useEffect, useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import {
  consoleSessionEnded,
  consoleSessionError,
  consoleSessionStarted,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import type { StateName } from '@/lib/state-signifiers';
import { cn } from '@/lib/utils';

import { useDiagnosticConsole, type DiagnosticConsolePhase } from '../hooks/useDiagnosticConsole';
import { StateSignifierChip } from './WorkflowStateBadge';

export interface ReadOnlyDiagnosticConsoleProps {
  workflowRunId: string;
  /** Actor role threaded to the backend gate; also informs the stream URL (workflow_owner). */
  actorRole?: string | undefined;
}

/** Color-independent mode signifier (icon + label) for the current session phase (UX-DR2). */
function modeSignifier(phase: DiagnosticConsolePhase): { stateName: StateName; label: string } {
  switch (phase) {
    case 'live':
      return { stateName: 'loading', label: 'Live' };
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

function announcementFor(phase: DiagnosticConsolePhase): string {
  if (phase === 'live') {
    return consoleSessionStarted;
  }
  if (phase === 'ended') {
    return consoleSessionEnded;
  }
  if (phase === 'error') {
    return consoleSessionError;
  }
  return '';
}

export function ReadOnlyDiagnosticConsole({
  workflowRunId,
  actorRole,
}: ReadOnlyDiagnosticConsoleProps) {
  const { chunks, phase, endReason, errorReason } = useDiagnosticConsole(workflowRunId, {
    enabled: true,
    actorRole,
  });

  const scrollRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  // Auto-scroll to the newest chunk while attached (pausable so the operator can read back).
  useEffect(() => {
    if (autoScroll && scrollRef.current !== null) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [chunks, autoScroll]);

  // First-render-safe live-region announcement (defers one commit — assert with `waitFor`).
  const announced = useLiveAnnouncement(announcementFor(phase));

  const mode = modeSignifier(phase);
  const isError = phase === 'error';
  const isTerminal = phase === 'ended' || phase === 'error';
  const showEmptyState = chunks.length === 0 && isTerminal;

  return (
    <section
      aria-label="Read-only diagnostic console"
      data-testid="read-only-diagnostic-console"
      data-phase={phase}
      className="w-full"
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-meta uppercase tracking-wide text-text-tertiary">
            Diagnostic console
          </h2>
          {/* AC6 — clearly badged "Read-only" with a color-independent signifier (icon + label). */}
          <StateSignifierChip
            stateName="permission-restricted"
            label="Read-only"
            testId="console-readonly-badge"
          />
          <StateSignifierChip stateName={mode.stateName} label={mode.label} testId="console-mode" />
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-pressed={!autoScroll}
          data-testid="console-autoscroll-toggle"
          onClick={() => setAutoScroll((value) => !value)}
        >
          {autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}
        </Button>
      </div>

      {isError ? (
        <p
          data-testid="console-error"
          className="mb-2 rounded-md border border-state-error-border bg-state-error px-3 py-2 text-sm text-state-error-foreground"
        >
          {errorReason === 'open_diagnostic_console_not_allowed'
            ? 'You do not have permission to open a diagnostic console for this run.'
            : errorReason === 'console-not-live'
              ? 'No live runner execution to attach to. Use the runner logs for a finished step.'
              : 'The diagnostic console could not be opened.'}
        </p>
      ) : null}

      <div
        ref={scrollRef}
        data-testid="console-scroll"
        // A scrollable console region must be keyboard-focusable so a keyboard-only user can scroll
        // it (WCAG 2.1.1); role="log" is the correct append-only live-region semantic. There is NO
        // input control here — the console is read-only (DD-1 / Trap T6).
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- focusable scroll region (WCAG 2.1.1)
        tabIndex={0}
        role="log"
        aria-label="Read-only console output"
        className={cn(
          'max-h-80 overflow-auto rounded-md border border-border bg-surface-sunken p-3',
          'font-mono text-xs leading-relaxed text-text-primary',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        )}
      >
        {showEmptyState ? (
          <p data-testid="console-empty" className="text-text-tertiary">
            No console output for this step.
          </p>
        ) : (
          <ol className="m-0 list-none p-0">
            {chunks.map((entry) => (
              <li
                key={`${entry.stream}-${entry.seq}`}
                data-stream={entry.stream}
                className={cn(
                  'whitespace-pre-wrap break-words',
                  entry.stream === 'stderr' ? 'text-state-warning-foreground' : undefined,
                )}
              >
                {entry.chunk}
              </li>
            ))}
          </ol>
        )}
      </div>

      {isTerminal && endReason !== undefined ? (
        <p data-testid="console-end-reason" className="mt-1 text-meta text-text-tertiary">
          Session ended.
        </p>
      ) : null}

      {/* AC6 — session start/end announced via an aria-live region (color-independent: the mode
          chip above carries the icon+label signifier). */}
      <div role="status" aria-live="polite" className="sr-only" data-testid="console-announcer">
        {announced}
      </div>
    </section>
  );
}
