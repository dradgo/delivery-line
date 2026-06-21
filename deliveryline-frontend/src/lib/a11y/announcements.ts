/**
 * Story 2.25 (Task 3 — AC5, AC7) — the documented screen-reader announcement
 * vocabulary.
 *
 * Maps every semantic state + workflow-lifecycle event to a STABLE announcement
 * string. Composites with an `aria-live` region import their text from here
 * rather than inlining it, so the announced wording is centralized, reviewable,
 * and consistent across the queue / clarification / decision surfaces. The
 * `announcement-vocabulary` node-test (tools/a11y/, run via `npm run check:a11y`)
 * enforces that discipline (AC7).
 *
 * Pure TypeScript — NO JSX/React here (helper modules must stay out of `.tsx`,
 * and this is imported by node `--test` parsers too). The first-render /
 * politeness mechanics live in `useLiveAnnouncement.ts`; this module is wording
 * only.
 *
 * Convention (mirrors AC7's examples): parameterless events are string consts
 * (`queueEmpty`, `specApproved`); parameterized events are functions
 * (`queueLoaded(n)`, `decisionStale(what, next)`).
 */

// ---- Review queue (story 2.20) -------------------------------------------------

export const queueLoading = 'Loading the review queue';
export const queueEmpty = 'Review queue is empty';
export const queueFilteredEmpty = 'No runs match the current filters';
/**
 * Used when the queue surface owns its error announcement. In `QueueShell` the
 * error case is delegated to the composed `<ErrorState>` polite region instead,
 * so the shell announcer stays silent on error (live-region duplication
 * reconciliation, story 2.20).
 */
export const queueLoadFailed = 'Failed to load the review queue — retry available';

/** Populated queue, announcing the available run count. */
export function queueLoaded(count: number): string {
  return `Review queue loaded: ${count} ${count === 1 ? 'run' : 'runs'} available`;
}

// ---- Clarifications (story 2.18) -----------------------------------------------

/** A single clarification advanced to a new lifecycle state (`label` is its signifier label). */
export function clarificationAdvanced(label: string): string {
  return `Clarification ${label}`;
}

/**
 * Multiple clarifications advanced in the same update — announce the count rather
 * than only the last-in-array item (closes the "single latest slot" gap, 2.18).
 */
export function clarificationsAdvanced(count: number): string {
  return `${count} clarifications updated`;
}

// ---- Spec-approval decision (story 2.19) ---------------------------------------

export const decisionOptionsLoadFailed =
  'The decision options could not be loaded. Refresh to try again.';
export const decisionSubmitFailed = 'The decision could not be submitted.';
export const specApproved = 'Specification approved. Decision recorded.';
export const specRejected = 'Specification rejected. Decision recorded.';
/**
 * Generic success fallback — the decision settled but the resolved outcome
 * (`lastDecision`) is not yet repopulated in the read view. Mirrors the visual
 * "Decision recorded." so the live region still announces the outcome instead of
 * going silent (review finding, story 2.25).
 */
export const decisionRecorded = 'Decision recorded.';

/**
 * A stale / version-mismatch state needing refresh before deciding — `what`
 * changed and `next` is the recommended next step (AC7 `decisionStale(what,next)`).
 */
export function decisionStale(what: string, next: string): string {
  return `${what} ${next}`;
}

// ---- Recovery / retry (story 3.30) ---------------------------------------------

/**
 * Announced when the run page is observed in the `Failed` state (AC7 — failure-
 * state-entry announcement). Sourced from the Decision Bar's single live region so
 * the failure entry is announced exactly once (no duplicate-region announcements).
 */
export const failureEntered = 'This run has failed. Review the failure and recovery options.';
/** Announced while a retry submission is in flight. */
export const retryInitiated = 'Retrying the failed step.';
/** Announced once a retry is recorded (success), mirroring the inline outcome (never a toast). */
export const retryRecorded = 'Retry recorded. The previous failure is preserved in the timeline.';

// ---- Implementation review (story 3.28) ----------------------------------------

/** Announced once an implementation is accepted (success), mirroring the inline outcome. */
export const implementationAccepted = 'Implementation accepted. Decision recorded.';
/** Announced once an implementation is rejected (success), mirroring the inline outcome. */
export const implementationRejected = 'Implementation rejected. Decision recorded.';
/** Announced once a developer takeover is recorded (success); the run is now taken over. */
export const workflowTakenOver = 'Run taken over for developer continuation. Decision recorded.';

// ---- Step execution log viewer (story 3d-5) ------------------------------------

/** Announced when the runner-log stream attaches (live follow or finished replay begins). */
export const logStreamStarted = 'Runner log stream started.';
/** Announced once the runner-log stream ends (container exited or finished replay complete). */
export const logStreamEnded = 'Runner log stream ended.';
/** Announced when a transient transport drop is auto-reconnecting (the live follow will resume). */
export const logStreamReconnecting = 'Runner log stream reconnecting.';
/** Announced when the runner-log stream could not be served or dropped. */
export const logStreamError = 'The runner log stream could not be loaded.';

// ---- Project connection test (story 3c-9) --------------------------------------

/** Announced while a project connectivity test is in flight. */
export const connectionTestRunning = 'Testing the project connection.';
/**
 * Announced once a connectivity test settles, summarising the per-check tri-state
 * outcome (`pass`/`fail`/`skipped`). Secret-free — counts only.
 */
export function connectionTestResult(
  passCount: number,
  failCount: number,
  skipCount: number,
): string {
  return `Connection test complete: ${passCount} passed, ${failCount} failed, ${skipCount} skipped.`;
}
/** Announced when a connectivity test could not run (project not found / unsupported kind). */
export const connectionTestFailed =
  'The connection test could not run. Review the error and retry.';
