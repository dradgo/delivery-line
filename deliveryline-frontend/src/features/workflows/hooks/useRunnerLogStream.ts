/**
 * Story 3d-5 (FR65, AC1/AC4) — hand-written `EventSource` hook for the Step Execution Log
 * Viewer. The FIRST EventSource in the app: the runner-log stream is Server-Sent Events, NOT a
 * typed `openapi-fetch` REST call, so it is consumed directly rather than via the generated client
 * (the endpoint still round-trips through OpenAPI/`schema.d.ts` to keep the drift gate green).
 *
 * Modeled on the shape of `useWorkflowEvents` (a per-run read) but driven by an `EventSource`
 * subscription instead of React Query. Accumulates `log` events, tracks the live-vs-finished
 * `status` phase, and surfaces the terminal `end`/`error` reason. The subscription is closed on
 * unmount and whenever `enabled` flips false — and explicitly on `end`/`error` so the browser's
 * built-in EventSource auto-reconnect never re-opens a completed stream.
 *
 * The backend applies BEST-EFFORT redaction to live lines and replays the AUTHORITATIVELY-redacted
 * persisted log when finished (ADR 0025); this hook renders whatever it receives verbatim and never
 * re-derives redaction client-side.
 */
import { useEffect, useState } from 'react';

/** One streamed log line. `stream` is `stdout`/`stderr`; `seq` is the per-session ordinal. */
export interface RunnerLogLine {
  stream: string;
  line: string;
  seq: number;
}

/**
 * Lifecycle phase of the stream:
 * - `connecting` — the EventSource is opening, no `status` received yet
 * - `live` — following a running container's logs
 * - `finished` — replaying the persisted redacted log
 * - `reconnecting` — a transient transport drop; the browser is auto-reconnecting (the live follow
 *   resumes when a fresh `status` arrives). Non-terminal: the source is intentionally left open.
 * - `ended` — the stream completed normally (`end` event)
 * - `error` — the stream was denied / dropped via a named `error` event (terminal; source closed)
 */
export type RunnerLogPhase =
  | 'connecting'
  | 'live'
  | 'finished'
  | 'reconnecting'
  | 'ended'
  | 'error';

/**
 * Upper bound on retained lines (review P4). A chatty live follow over the 30-min cap would
 * otherwise grow the array + DOM without bound; once exceeded the oldest lines are dropped (ring
 * buffer). 5000 lines is ample on-screen scrollback for a single step.
 */
const MAX_RETAINED_LINES = 5000;

export interface RunnerLogStreamState {
  lines: RunnerLogLine[];
  phase: RunnerLogPhase;
  /** Terminal `end` reason (e.g. `finished-replay-complete`, `container-exited`). */
  endReason?: string | undefined;
  /** Terminal `error` reason (e.g. `view_runner_logs_not_allowed`, `connection-error`). */
  errorReason?: string | undefined;
}

export interface UseRunnerLogStreamOptions {
  /** Open the stream only when true (the route gates on the `view_runner_logs` action). */
  enabled: boolean;
  /** Actor role appended as `?actorRole=` so the backend can resolve the server-side gate. */
  actorRole?: string | undefined;
}

function streamUrl(workflowRunId: string, actorRole?: string): string {
  const base = `/api/v1/workflows/${encodeURIComponent(workflowRunId)}/runner-logs/stream`;
  return actorRole !== undefined && actorRole !== ''
    ? `${base}?actorRole=${encodeURIComponent(actorRole)}`
    : base;
}

export function useRunnerLogStream(
  workflowRunId: string,
  { enabled, actorRole }: UseRunnerLogStreamOptions,
): RunnerLogStreamState {
  const [lines, setLines] = useState<RunnerLogLine[]>([]);
  const [phase, setPhase] = useState<RunnerLogPhase>('connecting');
  const [endReason, setEndReason] = useState<string | undefined>(undefined);
  const [errorReason, setErrorReason] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    // Fresh subscription → reset accumulated state (a re-enable starts over).
    setLines([]);
    setPhase('connecting');
    setEndReason(undefined);
    setErrorReason(undefined);

    const source = new EventSource(streamUrl(workflowRunId, actorRole));

    // Track the phase synchronously within this subscription so a reconnect can be detected
    // (setPhase is async; this closure variable is authoritative for the handlers below).
    let currentPhase: RunnerLogPhase = 'connecting';
    const goPhase = (next: RunnerLogPhase) => {
      currentPhase = next;
      setPhase(next);
    };

    const onStatus = (event: MessageEvent<unknown>) => {
      const phaseValue = safeParse(event.data)?.phase;
      if (phaseValue === 'live' || phaseValue === 'finished') {
        if (currentPhase === 'reconnecting') {
          // The auto-reconnect re-seeded the stream from scratch (the backend replays a fresh
          // backlog), so drop the pre-drop lines to avoid duplicating them on resume (P8).
          setLines([]);
        }
        goPhase(phaseValue);
      }
    };
    const onLog = (event: MessageEvent<unknown>) => {
      const data = safeParse(event.data);
      if (data === undefined) {
        return;
      }
      // Validate every field rather than coercing a malformed event to the literal string
      // "undefined" / a NaN seq (which would collide as a React key) — skip it instead (P6).
      const { stream, line, seq } = data;
      if (
        typeof stream !== 'string' ||
        typeof line !== 'string' ||
        typeof seq !== 'number' ||
        Number.isNaN(seq)
      ) {
        return;
      }
      setLines((prev) => {
        const next = [...prev, { stream, line, seq }];
        // Ring buffer (P4): keep only the most recent MAX_RETAINED_LINES lines.
        return next.length > MAX_RETAINED_LINES
          ? next.slice(next.length - MAX_RETAINED_LINES)
          : next;
      });
    };
    const onEnd = (event: MessageEvent<unknown>) => {
      const data = safeParse(event.data);
      setEndReason(typeof data?.reason === 'string' ? data.reason : 'ended');
      goPhase('ended');
      source.close();
    };
    const onError = (event: Event) => {
      // A NAMED `error` SSE event carries a JSON `data` payload (server-side denial / failure);
      // a NATIVE transport error has no data. Distinguish so a benign disconnect after the stream
      // already ended is not mislabeled.
      const message: unknown = (event as MessageEvent<unknown>).data;
      if (typeof message === 'string' && message.length > 0) {
        const data = safeParse(message);
        setErrorReason(typeof data?.reason === 'string' ? data.reason : 'stream-error');
        goPhase('error');
        source.close();
        return;
      }
      // Native transport error: do NOT close — let the browser's built-in EventSource auto-reconnect
      // resume the live follow after a transient (localhost) drop. Surface a distinct, non-terminal
      // `reconnecting` phase rather than a terminal `error`, unless the stream had already completed
      // (P8 / resolved review decision).
      if (currentPhase !== 'ended' && currentPhase !== 'error') {
        goPhase('reconnecting');
      }
    };

    source.addEventListener('status', onStatus as EventListener);
    source.addEventListener('log', onLog as EventListener);
    source.addEventListener('end', onEnd as EventListener);
    source.addEventListener('error', onError);

    return () => {
      source.removeEventListener('status', onStatus as EventListener);
      source.removeEventListener('log', onLog as EventListener);
      source.removeEventListener('end', onEnd as EventListener);
      source.removeEventListener('error', onError);
      source.close();
    };
  }, [workflowRunId, enabled, actorRole]);

  return { lines, phase, endReason, errorReason };
}

function safeParse(raw: unknown): Record<string, unknown> | undefined {
  if (typeof raw !== 'string') {
    return undefined;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}
