/**
 * Story 3d-6 (FR68, AC6) — hand-written `EventSource` hook for the Read-only Diagnostic Console.
 * The console stream is Server-Sent Events, NOT a typed `openapi-fetch` REST call, so it is consumed
 * directly rather than via the generated client (the endpoint still round-trips through
 * OpenAPI/`schema.d.ts` to keep the drift gate green).
 *
 * Near-twin of `useRunnerLogStream` (story 3d-5) with the console deltas: it accumulates `console`
 * chunk events (not `log` lines), it is LIVE-ONLY (there is no finished/historical phase — a
 * finished/absent execution surfaces a terminal `error` with reason `console-not-live`), and it
 * carries NO input channel. EventSource is receive-only by nature; this hook NEVER opens a writable
 * transport back to the container — that is the provable read-only guarantee (DD-1 / Trap T6).
 *
 * The backend applies BEST-EFFORT redaction to chunks before they leave the server (ADR 0025); this
 * hook renders whatever it receives verbatim and never re-derives redaction client-side.
 */
import { useEffect, useState } from 'react';

/** One streamed console chunk. `stream` is `stdout`/`stderr`; `seq` is the per-session ordinal. */
export interface DiagnosticConsoleChunk {
  stream: string;
  chunk: string;
  seq: number;
}

/**
 * Lifecycle phase of the console session:
 * - `connecting` — the EventSource is opening, no `status` received yet
 * - `live` — attached to the running container's read-only console
 * - `reconnecting` — a transient transport drop; the browser is auto-reconnecting (a fresh `status`
 *   resumes the live attach). Non-terminal: the source is intentionally left open.
 * - `ended` — the session completed normally (`end` event; container exited / session closed)
 * - `error` — the session was denied / not-live / dropped via a named `error` event (terminal)
 */
export type DiagnosticConsolePhase = 'connecting' | 'live' | 'reconnecting' | 'ended' | 'error';

/**
 * Upper bound on retained chunks (mirrors 3d-5 review P4). A chatty console over the 30-min cap
 * would otherwise grow the array + DOM without bound; once exceeded the oldest chunks are dropped
 * (ring buffer). 5000 chunks is ample on-screen scrollback for a single step.
 */
const MAX_RETAINED_CHUNKS = 5000;

export interface DiagnosticConsoleState {
  chunks: DiagnosticConsoleChunk[];
  phase: DiagnosticConsolePhase;
  /** Terminal `end` reason (e.g. `container-exited`, `session-closed`, `not-live`). */
  endReason?: string | undefined;
  /** Terminal `error` reason (e.g. `console-not-live`, `open_diagnostic_console_not_allowed`). */
  errorReason?: string | undefined;
}

export interface UseDiagnosticConsoleOptions {
  /** Open the stream only when true (the route gates on the `open_diagnostic_console` action). */
  enabled: boolean;
  /**
   * Actor role appended as `?actorRole=` so the backend resolves the server-side gate. The console
   * is offered ONLY to `workflow_owner`, so callers pass that role.
   */
  actorRole?: string | undefined;
}

function streamUrl(workflowRunId: string, actorRole?: string): string {
  const base = `/api/v1/workflows/${encodeURIComponent(workflowRunId)}/diagnostic-console/stream`;
  return actorRole !== undefined && actorRole !== ''
    ? `${base}?actorRole=${encodeURIComponent(actorRole)}`
    : base;
}

export function useDiagnosticConsole(
  workflowRunId: string,
  { enabled, actorRole }: UseDiagnosticConsoleOptions,
): DiagnosticConsoleState {
  const [chunks, setChunks] = useState<DiagnosticConsoleChunk[]>([]);
  const [phase, setPhase] = useState<DiagnosticConsolePhase>('connecting');
  const [endReason, setEndReason] = useState<string | undefined>(undefined);
  const [errorReason, setErrorReason] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    // Fresh subscription → reset accumulated state (a re-enable starts over).
    setChunks([]);
    setPhase('connecting');
    setEndReason(undefined);
    setErrorReason(undefined);

    const source = new EventSource(streamUrl(workflowRunId, actorRole));

    // Track the phase synchronously within this subscription so a reconnect can be detected
    // (setPhase is async; this closure variable is authoritative for the handlers below).
    let currentPhase: DiagnosticConsolePhase = 'connecting';
    const goPhase = (next: DiagnosticConsolePhase) => {
      currentPhase = next;
      setPhase(next);
    };

    const onStatus = (event: MessageEvent<unknown>) => {
      const phaseValue = safeParse(event.data)?.phase;
      if (phaseValue === 'live') {
        if (currentPhase === 'reconnecting') {
          // The auto-reconnect re-seeded the stream from scratch, so drop the pre-drop chunks to
          // avoid duplicating them on resume.
          setChunks([]);
        }
        goPhase('live');
      }
    };
    const onConsole = (event: MessageEvent<unknown>) => {
      const data = safeParse(event.data);
      if (data === undefined) {
        return;
      }
      // Validate every field rather than coercing a malformed event to the literal string
      // "undefined" / a NaN seq (which would collide as a React key) — skip it instead.
      const { stream, chunk, seq } = data;
      if (
        typeof stream !== 'string' ||
        typeof chunk !== 'string' ||
        typeof seq !== 'number' ||
        Number.isNaN(seq)
      ) {
        return;
      }
      setChunks((prev) => {
        const next = [...prev, { stream, chunk, seq }];
        // Ring buffer: keep only the most recent MAX_RETAINED_CHUNKS chunks.
        return next.length > MAX_RETAINED_CHUNKS
          ? next.slice(next.length - MAX_RETAINED_CHUNKS)
          : next;
      });
    };
    const onEnd = (event: MessageEvent<unknown>) => {
      // The not-live / denial path emits a named `error` AND a terminal `end` (the backend sends
      // both). A terminal `error` already closed the session with its specific reason, so a trailing
      // `end` must NOT downgrade it to a generic `ended` (which would hide the error message).
      if (currentPhase === 'error') {
        return;
      }
      const data = safeParse(event.data);
      setEndReason(typeof data?.reason === 'string' ? data.reason : 'ended');
      goPhase('ended');
      source.close();
    };
    const onError = (event: Event) => {
      // A NAMED `error` SSE event carries a JSON `data` payload (server-side denial / not-live /
      // failure); a NATIVE transport error has no data. Distinguish so a benign disconnect after the
      // session already ended is not mislabeled.
      const message: unknown = (event as MessageEvent<unknown>).data;
      if (typeof message === 'string' && message.length > 0) {
        const data = safeParse(message);
        setErrorReason(typeof data?.reason === 'string' ? data.reason : 'stream-error');
        goPhase('error');
        source.close();
        return;
      }
      // Native transport error: do NOT close — let the browser's built-in EventSource auto-reconnect
      // resume the live attach after a transient (localhost) drop. Surface a distinct, non-terminal
      // `reconnecting` phase rather than a terminal `error`, unless the session had already completed.
      if (currentPhase !== 'ended' && currentPhase !== 'error') {
        goPhase('reconnecting');
      }
    };

    source.addEventListener('status', onStatus as EventListener);
    source.addEventListener('console', onConsole as EventListener);
    source.addEventListener('end', onEnd as EventListener);
    source.addEventListener('error', onError);

    return () => {
      source.removeEventListener('status', onStatus as EventListener);
      source.removeEventListener('console', onConsole as EventListener);
      source.removeEventListener('end', onEnd as EventListener);
      source.removeEventListener('error', onError);
      source.close();
    };
  }, [workflowRunId, enabled, actorRole]);

  return { chunks, phase, endReason, errorReason };
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
