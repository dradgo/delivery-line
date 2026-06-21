/**
 * Story 3c-9 (Task 6, AC5/AC8) — the connectivity-test control.
 *
 * A "Test connection" button runs `useTestProjectConnection` (a live probe, not
 * idempotent) and renders the three per-check results (`repository_reachable`,
 * `ticket_source_auth`, `repository_host_auth`) as `pass`/`fail`/`skipped` with each
 * check's secret-free `detail`. Pass/fail is conveyed by icon + text, NEVER color
 * alone. Progress + results are announced via an `aria-live` region sourced from the
 * shared vocabulary ([[livesnnouncement-defers-one-commit-test-flake]] — assert the
 * announcer in its own `waitFor`).
 *
 * A per-check `fail`/`skipped` is IN-BAND data (HTTP 200); only `PROJECT_NOT_FOUND` /
 * `UNSUPPORTED_CONNECTOR_KIND` (Problem Details) render an `ErrorState`.
 */
import { useEffect, useRef } from 'react';
import { CircleCheck, CircleDashed, MinusCircle, TriangleAlert } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/feedback';
import { connectionTestResult, connectionTestRunning } from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import type { Project } from '@/lib/api/queryOptions';

import { useTestProjectConnection, type TestConnection } from '../hooks/useTestProjectConnection';
import {
  CONNECTION_CHECK_LABELS,
  connectionTestErrorMessage,
  projectErrorCode,
} from '../projectFormView';

export interface ConnectionTestControlProps {
  project: Project;
  /** Whether the project's allowedActions permit `test_connection` (AC7). */
  canTest: boolean;
  /** Lift the settled result for the list's session-scoped last-test cell (R4). */
  onResult?: (result: TestConnection) => void;
}

type CheckStatus = 'pass' | 'fail' | 'skipped';

/** Count outcomes for the secret-free announcement summary. */
function tally(result: TestConnection): { pass: number; fail: number; skip: number } {
  let pass = 0;
  let fail = 0;
  let skip = 0;
  for (const check of result.checks ?? []) {
    if (check.status === 'pass') {
      pass += 1;
    } else if (check.status === 'fail') {
      fail += 1;
    } else if (check.status === 'skipped') {
      skip += 1;
    }
  }
  return { pass, fail, skip };
}

function CheckIcon({ status }: { status: CheckStatus | undefined }) {
  if (status === 'pass') {
    return <CircleCheck className="size-4 shrink-0 text-state-success-foreground" aria-hidden />;
  }
  if (status === 'fail') {
    return <TriangleAlert className="size-4 shrink-0 text-state-error-foreground" aria-hidden />;
  }
  if (status === 'skipped') {
    return <MinusCircle className="size-4 shrink-0 text-text-tertiary" aria-hidden />;
  }
  // An out-of-vocabulary status gets a distinct neutral icon so it does not masquerade
  // as a "Skipped" check (its text label reads "Unknown").
  return <CircleDashed className="size-4 shrink-0 text-text-tertiary" aria-hidden />;
}

function checkStatusLabel(status: CheckStatus | undefined): string {
  switch (status) {
    case 'pass':
      return 'Pass';
    case 'fail':
      return 'Fail';
    case 'skipped':
      return 'Skipped';
    default:
      return 'Unknown';
  }
}

export function ConnectionTestControl({ project, canTest, onResult }: ConnectionTestControlProps) {
  const projectId = project.id ?? '';
  const test = useTestProjectConnection(projectId);
  const { status, data, error } = test;

  // Lift the settled result up for the list's last-test cell (R4). A ref keyed on the
  // data identity fires the callback once per settle, not on every re-render.
  const reportedRef = useRef<TestConnection | undefined>(undefined);
  useEffect(() => {
    if (status === 'success' && data !== reportedRef.current) {
      reportedRef.current = data;
      onResult?.(data);
    }
  }, [status, data, onResult]);

  const counts = status === 'success' ? tally(data) : { pass: 0, fail: 0, skip: 0 };
  const announcement =
    status === 'pending'
      ? connectionTestRunning
      : status === 'success'
        ? connectionTestResult(counts.pass, counts.fail, counts.skip)
        : '';
  const announced = useLiveAnnouncement(announcement);

  const handleTest = () => {
    console.info({ event: 'project.connectionTestAttempt' });
    test.mutate();
  };

  const isProblem = status === 'error';

  return (
    <div className="flex flex-col gap-2" data-testid="connection-test">
      {canTest ? (
        <div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={status === 'pending'}
            onClick={handleTest}
            data-testid="connection-test-button"
          >
            {status === 'pending' ? 'Testing…' : 'Test connection'}
          </Button>
        </div>
      ) : null}

      {/* AC8 — single visually-hidden polite announcer (vocabulary-sourced). */}
      <div
        role="status"
        aria-live="polite"
        className="sr-only"
        data-testid="connection-test-announcer"
      >
        {announced}
      </div>

      {status === 'success' && (data.checks ?? []).length === 0 ? (
        <p className="text-sm text-text-secondary" data-testid="connection-test-empty">
          The connection test returned no checks.
        </p>
      ) : null}

      {status === 'success' && (data.checks ?? []).length > 0 ? (
        <ul className="flex flex-col gap-1" data-testid="connection-test-result">
          {(data.checks ?? []).map((check) => {
            const checkKey = check.check;
            const label = checkKey !== undefined ? CONNECTION_CHECK_LABELS[checkKey] : 'Check';
            return (
              <li
                key={check.check ?? label}
                className="flex items-center gap-2 text-sm"
                data-check={check.check}
                data-check-status={check.status}
              >
                <CheckIcon status={check.status} />
                <span className="font-medium text-text-primary">{label}</span>
                <span className="text-text-secondary">— {checkStatusLabel(check.status)}</span>
                {check.detail !== undefined && check.detail !== '' ? (
                  <span className="text-meta text-text-tertiary">({check.detail})</span>
                ) : null}
              </li>
            );
          })}
        </ul>
      ) : null}

      {isProblem ? (
        <div data-testid="connection-test-error" data-error-code={projectErrorCode(error)}>
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="Couldn't test this connection"
            message={connectionTestErrorMessage(error)}
            nextAction={{ kind: 'Retry', label: 'Try again', onRetry: handleTest }}
          />
        </div>
      ) : null}
    </div>
  );
}
