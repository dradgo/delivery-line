/**
 * Story 2.18 (AC1–AC10) — the `ClarificationRegion` presentational composite.
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): there is NO clarification-read endpoint —
 * this component is PRESENTATIONAL + prop-driven, fed a resolved `ClarificationsView`
 * by its thin sibling {@link ClarificationRegionContainer}. Tests drive it directly
 * with constructed fixtures (router/query-free). Live-reachable today is ONLY the
 * `no open questions` empty state (the disabled `useClarifications` stub → container
 * maps to an empty view); every other state is DORMANT — built + tested via fixtures,
 * never fabricated from live data (the 2.15/2.16/2.17 discipline).
 *
 * Composes the sanctioned primitives (REUSE, never rebuild):
 *   • `StateSignifierChip` (T2/T3) — the non-color status chip (icon + label); NOT
 *     `WorkflowStateBadge` (that vocabulary is workflow-state, not clarification-status).
 *   • `SafeMarkdownRenderer` from the `@/lib/sanitization` BARREL (T1) — the ONLY path
 *     for the UNTRUSTED `questionText`/`answerText`.
 *   • `EmptyState`/`LoadingState`/`ErrorState` from `@/components/feedback` (no raw
 *     spinners — `no-untyped-loading-state`).
 *
 * The lifecycle indicator + inline submit feedback are REGION-LOCAL (T6/T7): story
 * 2.21's `ActionLifecycleIndicator`/`InlineFeedback`/`Toast` are backlog and do not
 * exist; this builds equivalents inline (feedback is inline, NEVER toast — UX-DR15).
 */
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';

import { EmptyState, ErrorState, LoadingState } from '@/components/feedback';
import {
  SafeMarkdownRenderer,
  renderTextWithRedactions,
  scanForRedactions,
} from '@/lib/sanitization';
import { cn } from '@/lib/utils';
import { densityGap, type Density } from '@/lib/density';
import { clarificationAdvanced, clarificationsAdvanced } from '@/lib/a11y/announcements';

import {
  LIFECYCLE_STAGES,
  TERMINAL_STATUSES,
  clarificationItemSignifier,
  countPendingIncorporation,
  groupClarificationsByStatus,
  resolveClarificationItemState,
  resolveLifecyclePosition,
  type ClarificationItemState,
  type ClarificationLifecycleStatus,
  type ClarificationView,
  type ClarificationsView,
} from '../clarificationView';
import { StateSignifierChip } from './WorkflowStateBadge';

/** The mutation state the region renders as inline feedback (AC5a/b). */
export interface ClarificationSubmissionState {
  readonly status: 'idle' | 'pending' | 'success' | 'error';
  /** The clarification the in-flight/last submit targeted. */
  readonly clarificationId?: string | undefined;
  /** Stable ProblemDetails `code` on a failed submit (never a raw message — T8). */
  readonly errorCode?: string | undefined;
}

export type ClarificationRegionVariant = 'inline' | 'sidebar' | 'compact' | 'full';

/** Story 3e-2 — the allowed-action wire strings that gate the accept + regenerate affordances. */
const ACCEPT_CLARIFICATION_ACTION = 'accept_clarification';
const REGENERATE_SPEC_ACTION = 'regenerate_spec_with_clarifications';

/** Story 3e-2 — the mutation state the region renders as inline feedback for regenerate-spec. */
export interface RegenerateSubmissionState {
  readonly status: 'idle' | 'pending' | 'success' | 'error';
  /** Stable ProblemDetails `code` on a failed regenerate (never a raw message — T8). */
  readonly errorCode?: string | undefined;
}

export interface ClarificationRegionProps {
  /** The resolved clarifications (from the container's mapped read model / fixtures). */
  view: ClarificationsView;
  /** Layout variant per AC4. Defaults to `inline`. */
  variant?: ClarificationRegionVariant | undefined;
  density?: Density | undefined;
  /** Controlled selection; falls back to internal state when omitted. */
  selectedClarificationId?: string | undefined;
  onSelectQuestion?: ((clarificationId: string) => void) | undefined;
  /** The LIVE seam (AC9) — wired to `useSubmitClarification` in the container. */
  onSubmitAnswer?: ((clarificationId: string, answerText: string) => void) | undefined;
  /** Current mutation state for inline feedback (AC5). */
  submission?: ClarificationSubmissionState | undefined;
  /** Fired when a question advances to a new lifecycle position (container logs it). */
  onLifecycleAdvance?:
    | ((clarificationId: string, status: ClarificationLifecycleStatus) => void)
    | undefined;
  /** Dormant load-state overlay — reachable when the read endpoint ships. */
  loadState?: 'loading' | 'error' | undefined;
  onRetry?: (() => void) | undefined;
  /** DOM id + focus target for the 2.17 `artifact-clarification-anchor` wiring (Task 5). */
  regionId?: string | undefined;
  /**
   * Story 3e-2 (AC1/AC2) — the run's allowed-action wire strings. Gates the accept-clarification
   * (per answered question) + regenerate-spec affordances; both stay hidden unless the governing
   * action is present (governed-button discipline, no bare role text).
   */
  allowedActions?: readonly string[] | undefined;
  /** Story 3e-2 (AC1) — LIVE seam wired to `useAcceptClarification` in the container. */
  onAcceptClarification?: ((clarificationId: string) => void) | undefined;
  /** Story 3e-2 (AC1) — accept mutation state for inline feedback (reuses the submission shape). */
  acceptSubmission?: ClarificationSubmissionState | undefined;
  /** Story 3e-2 (AC2) — LIVE seam wired to `useRegenerateSpec` in the container. */
  onRegenerateSpec?: (() => void) | undefined;
  /** Story 3e-2 (AC2) — regenerate mutation state for inline feedback. */
  regenerateSubmission?: RegenerateSubmissionState | undefined;
}

/** Map an item state to the chip — the non-color signifier (story 2.3 AC5). */
function ItemStatusChip({ state, testId }: { state: ClarificationItemState; testId?: string }) {
  const { stateName, label } = clarificationItemSignifier(state);
  return <StateSignifierChip stateName={stateName} label={label} testId={testId} />;
}

/**
 * The REGION-LOCAL lifecycle indicator (AC2/AC5) — `submitted → accepted →
 * incorporated` with the current position highlighted. Built here, NOT imported from
 * story 2.21 (backlog — T6). An off-chain status (`superseded`/`rejected_invalid`)
 * renders muted with an explicit off-chain marker rather than a misleading green.
 */
function LifecycleIndicator({ status }: { status: ClarificationLifecycleStatus }) {
  const { currentIndex, offChain } = resolveLifecyclePosition(status);
  return (
    <ol
      className="flex flex-wrap items-center gap-1"
      data-testid="clarification-lifecycle"
      data-lifecycle-current={currentIndex}
      data-lifecycle-offchain={offChain}
      aria-label="Clarification lifecycle"
    >
      {LIFECYCLE_STAGES.map((stage, index) => {
        const reached = index <= currentIndex;
        const isCurrent = index === currentIndex && !offChain;
        return (
          <li
            key={stage}
            data-stage={stage}
            data-stage-reached={reached}
            data-stage-current={isCurrent}
            className={cn(
              'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs',
              reached ? 'text-text-primary' : 'text-text-tertiary',
              isCurrent ? 'font-semibold' : 'font-normal',
            )}
          >
            <span
              aria-hidden
              className={cn(
                'inline-block size-1.5 rounded-full',
                reached ? 'bg-text-primary' : 'bg-border',
              )}
            />
            {stage}
            {index < LIFECYCLE_STAGES.length - 1 ? (
              <span aria-hidden className="text-text-tertiary">
                ›
              </span>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}

/** Whether a question is answerable today (only `open` questions accept an answer). */
function isAnswerable(view: ClarificationView): boolean {
  return view.status === 'open';
}

/**
 * Build a redaction-safe plain string for NON-rendered surfaces (e.g. a `title`
 * attribute) using the sanctioned `scanForRedactions` scanner — so even the row's
 * hover text never leaks a secret (review finding P1). Mirrors the placeholder
 * wrapping `renderTextWithRedactions` applies to the visible text.
 */
function redactedPlainText(text: string): string {
  const { matches } = scanForRedactions(text);
  if (matches.length === 0) {
    return text;
  }
  let out = '';
  let cursor = 0;
  for (const match of matches) {
    out += text.slice(cursor, match.start) + `[REDACTED: ${match.category}]`;
    cursor = match.end;
  }
  return out + text.slice(cursor);
}

/**
 * Compose the "superseded by spec vN" wording from `supersededByArtifactId` when the
 * backend supplies no explicit `noEffectReason` (AC6 / review finding P11). Falls back
 * to a generic sentence when the artifact ref carries no parseable version suffix.
 */
function deriveSupersededReason(view: ClarificationView): string | undefined {
  if (view.supersededByArtifactId === undefined) {
    return undefined;
  }
  const match = /_v(\d+)$/.exec(view.supersededByArtifactId);
  return match !== null
    ? `Spec rebuilt without addressing this question — superseded by spec v${match[1]}.`
    : 'Spec rebuilt without addressing this question — superseded by a newer spec.';
}

/** The no-effect reason callout for `superseded`/`rejected_invalid` (AC6). */
function NoEffectReason({ view }: { view: ClarificationView }) {
  if (view.status !== 'superseded' && view.status !== 'rejected_invalid') {
    return null;
  }
  const reason =
    view.noEffectReason ??
    (view.status === 'superseded'
      ? (deriveSupersededReason(view) ??
        'This clarification was set aside without being addressed.')
      : 'This answer was rejected.');
  return (
    <p
      role="note"
      data-testid="clarification-no-effect-reason"
      className="mt-1 rounded-md border border-state-stale-border bg-state-stale px-2 py-1 text-xs text-state-stale-foreground"
    >
      {reason}
    </p>
  );
}

/** A single question row in the list (collapsed/secondary form). */
function QuestionRow({
  view,
  selected,
  onSelect,
}: {
  view: ClarificationView;
  selected: boolean;
  onSelect: () => void;
}) {
  const state = resolveClarificationItemState(view);
  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      data-clarification-question-button
      data-clarification-id={view.clarificationId}
      data-clarification-item-state={state}
      onClick={onSelect}
      className={cn(
        'flex w-full items-center justify-between gap-2 rounded-md border px-2 py-1.5 text-left text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        selected
          ? 'border-state-selected-border bg-state-selected/40'
          : 'border-border hover:bg-surface-elevated',
      )}
    >
      {/*
        UNTRUSTED question text — even the truncated row label goes through the
        sanitization barrel's redaction filter (T1/AC8 / review finding P1), never a
        raw interpolation. `title` exposes the full (redaction-safe) text for the
        truncated row (a11y) without re-introducing a raw-text surface.
      */}
      <span
        className="min-w-0 flex-1 truncate text-text-secondary"
        title={redactedPlainText(view.questionText)}
      >
        {renderTextWithRedactions(view.questionText)}
      </span>
      <ItemStatusChip state={state} />
    </button>
  );
}

/**
 * The selected-question detail (AC8) — dominates the detail area. Renders the
 * untrusted question/answer ONLY via `SafeMarkdownRenderer`, with the reviewer
 * wording and the system interpretation visually SEPARATED (no commingling).
 */
function QuestionDetail({
  view,
  submission,
  onSubmitAnswer,
  allowedActions,
  onAcceptClarification,
  acceptSubmission,
}: {
  view: ClarificationView;
  submission?: ClarificationSubmissionState | undefined;
  onSubmitAnswer?: ((clarificationId: string, answerText: string) => void) | undefined;
  allowedActions?: readonly string[] | undefined;
  onAcceptClarification?: ((clarificationId: string) => void) | undefined;
  acceptSubmission?: ClarificationSubmissionState | undefined;
}) {
  const headingId = useId();
  const [draft, setDraft] = useState('');
  const [validationError, setValidationError] = useState<string | undefined>(undefined);
  // P9 — hide stale submit feedback once the reviewer edits a fresh draft.
  const [feedbackDismissed, setFeedbackDismissed] = useState(false);

  const submittingThis =
    submission?.status === 'pending' && submission.clarificationId === view.clarificationId;
  // P3 — a submit is in flight for ANY question; block starting a second one.
  const anyPending = submission?.status === 'pending';
  const erroredThis =
    submission?.status === 'error' && submission.clarificationId === view.clarificationId;
  const succeededThis =
    submission?.status === 'success' && submission.clarificationId === view.clarificationId;

  // Story 3e-2 (AC1) — the per-question Accept affordance: only an `answered` clarification can be
  // accepted, and only when the run advertises `accept_clarification` (governed-button discipline).
  const canAccept =
    view.status === 'answered' && (allowedActions?.includes(ACCEPT_CLARIFICATION_ACTION) ?? false);
  const acceptingThis =
    acceptSubmission?.status === 'pending' &&
    acceptSubmission.clarificationId === view.clarificationId;
  const acceptErroredThis =
    acceptSubmission?.status === 'error' &&
    acceptSubmission.clarificationId === view.clarificationId;
  const acceptSucceededThis =
    acceptSubmission?.status === 'success' &&
    acceptSubmission.clarificationId === view.clarificationId;

  // A new submission RESULT (status/target/code change) re-arms the inline feedback.
  useEffect(() => {
    setFeedbackDismissed(false);
  }, [submission?.status, submission?.clarificationId, submission?.errorCode]);

  const itemState = resolveClarificationItemState(view, {
    text: draft,
    validationError,
    // `erroredThis` aliases `submission?.status === 'error'`, so TS narrows
    // `submission` to non-null inside this branch (no optional chain needed).
    errorCode: erroredThis ? submission.errorCode : undefined,
  });

  const handleSubmit = () => {
    // P3 — never start a second submit while one is already in flight.
    if (anyPending) {
      return;
    }
    const trimmed = draft.trim();
    if (trimmed === '') {
      setValidationError('Enter an answer before submitting.');
      return;
    }
    setValidationError(undefined);
    onSubmitAnswer?.(view.clarificationId, trimmed);
  };

  return (
    <div
      data-testid="clarification-detail"
      data-clarification-id={view.clarificationId}
      data-clarification-item-state={itemState}
      className="space-y-2 rounded-md border border-border p-3"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <ItemStatusChip state={itemState} testId="clarification-detail-status" />
        <LifecycleIndicator status={view.status} />
      </div>

      {/* UNTRUSTED question text — sanitized render only (AC8). */}
      <section aria-labelledby={headingId}>
        <h3 id={headingId} className="text-annotation uppercase tracking-wide text-text-tertiary">
          Question
        </h3>
        <div data-testid="clarification-question-body">
          <SafeMarkdownRenderer source={view.questionText} className="prose" />
        </div>
      </section>

      {/* Reviewer wording (raw) and the system interpretation visually SEPARATED (AC8). */}
      {view.answerText !== undefined ? (
        <div className="grid gap-2 sm:grid-cols-2">
          <section
            aria-label="Reviewer answer"
            data-testid="clarification-answer-raw"
            className="rounded-md border border-border p-2"
          >
            <h4 className="text-annotation uppercase tracking-wide text-text-tertiary">
              Reviewer answer
            </h4>
            <SafeMarkdownRenderer source={view.answerText} className="prose" />
          </section>
          <section
            aria-label="System interpretation"
            data-testid="clarification-answer-interpreted"
            className="rounded-md border border-border bg-surface-elevated p-2"
          >
            <h4 className="text-annotation uppercase tracking-wide text-text-tertiary">
              System interpretation
            </h4>
            <p className="text-sm text-text-secondary">
              {clarificationItemSignifier(itemState).label}
            </p>
          </section>
        </div>
      ) : null}

      <NoEffectReason view={view} />

      {/* Story 3e-2 (AC1) — Accept an answered clarification so a spec rebuild can incorporate it.
          Governed: hidden unless the run advertises `accept_clarification`. */}
      {canAccept ? (
        <div className="space-y-1">
          <button
            type="button"
            data-testid="clarification-accept"
            disabled={acceptingThis}
            onClick={() => onAcceptClarification?.(view.clarificationId)}
            className="rounded-md border border-border bg-surface px-2.5 py-1 text-sm font-medium text-text-primary hover:bg-surface-elevated disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          >
            {acceptingThis ? 'Accepting…' : 'Accept answer for incorporation'}
          </button>
          {acceptSucceededThis ? (
            <p
              data-testid="clarification-accept-feedback"
              className="text-xs text-state-success-foreground"
            >
              Answer accepted — ready for spec regeneration.
            </p>
          ) : null}
          {acceptErroredThis ? (
            <p
              role="alert"
              data-testid="clarification-accept-error"
              className="text-xs text-state-error-foreground"
            >
              We couldn’t accept this answer ({acceptSubmission.errorCode ?? 'error'}). Please try
              again.
            </p>
          ) : null}
        </div>
      ) : null}

      {/* Response input — only for answerable (`open`) questions. */}
      {isAnswerable(view) ? (
        <div className="space-y-1">
          <label
            htmlFor={`${headingId}-answer`}
            className="text-annotation uppercase tracking-wide text-text-tertiary"
          >
            Your answer
          </label>
          <textarea
            id={`${headingId}-answer`}
            aria-labelledby={headingId}
            data-testid="clarification-answer-input"
            value={draft}
            disabled={submittingThis}
            onChange={(event) => {
              setDraft(event.target.value);
              if (validationError !== undefined) {
                setValidationError(undefined);
              }
              // P9 — editing a fresh draft dismisses any stale submit feedback.
              if (!feedbackDismissed) {
                setFeedbackDismissed(true);
              }
            }}
            rows={3}
            className="w-full rounded-md border border-border bg-surface px-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          />
          {validationError !== undefined ? (
            <p
              role="alert"
              data-testid="clarification-validation-error"
              className="text-xs text-state-error-foreground"
            >
              {validationError}
            </p>
          ) : null}
          <button
            type="button"
            data-testid="clarification-submit"
            disabled={submittingThis || anyPending}
            onClick={handleSubmit}
            className="rounded-md border border-border bg-surface px-2.5 py-1 text-sm font-medium text-text-primary hover:bg-surface-elevated disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          >
            {submittingThis ? 'Submitting…' : 'Submit answer'}
          </button>
        </div>
      ) : null}

      {/* Inline submit feedback (AC5a) — NEVER a toast (UX-DR15, T7). */}
      {succeededThis && !feedbackDismissed ? (
        <p
          data-testid="clarification-submit-feedback"
          className="text-xs text-state-success-foreground"
        >
          Answer submitted — pending incorporation.
        </p>
      ) : null}
      {erroredThis && !feedbackDismissed ? (
        <p
          role="alert"
          data-testid="clarification-submit-error"
          className="text-xs text-state-error-foreground"
        >
          We couldn’t submit your answer ({submission.errorCode ?? 'error'}). Please try again.
        </p>
      ) : null}
    </div>
  );
}

/** The compact summary variant (AC4) — counts + a CTA only. */
function CompactSummary({
  view,
  onSelectQuestion,
}: {
  view: ClarificationsView;
  onSelectQuestion?: (clarificationId: string) => void;
}) {
  const grouped = groupClarificationsByStatus(view);
  const pending = countPendingIncorporation(view);
  const firstActionable = grouped.open[0] ?? grouped.pending[0];
  return (
    <div
      className="flex items-center justify-between gap-2"
      data-testid="clarification-compact-summary"
    >
      <span className="text-sm text-text-secondary">
        {view.clarifications.length} clarifications · {pending} pending
      </span>
      {firstActionable !== undefined ? (
        <button
          type="button"
          data-testid="clarification-compact-cta"
          onClick={() => onSelectQuestion?.(firstActionable.clarificationId)}
          className="rounded-md border border-border px-2.5 py-1 text-sm font-medium text-text-primary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
        >
          Review
        </button>
      ) : null}
    </div>
  );
}

/**
 * Announce status transitions through an ARIA live region (AC7). Diffs each
 * clarification's status across renders and announces EVERY advance in the update,
 * not only the last-in-array (story 2.25 — closes the 2.18 "single latest slot"
 * multiple-simultaneous-advance gap); also calls `onLifecycleAdvance` for each so
 * the container can log it (field-only). Announcement text is sourced from the
 * shared vocabulary (AC7). Returns the live message for a polite live region.
 *
 * The `setAnnouncement`-in-effect pattern is inherently first-render-safe: the
 * region mounts holding `''` and only changes once a real transition is observed,
 * so the change is announced (AC5) and pre-existing clarifications are not
 * re-announced on mount.
 */
function useLifecycleAnnouncements(
  view: ClarificationsView,
  onLifecycleAdvance?: (clarificationId: string, status: ClarificationLifecycleStatus) => void,
): string {
  const previousStatuses = useRef<Map<string, ClarificationLifecycleStatus>>(new Map());
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    const next = new Map<string, ClarificationLifecycleStatus>();
    const advanced: ClarificationView[] = [];
    for (const clarification of view.clarifications) {
      next.set(clarification.clarificationId, clarification.status);
      const prior = previousStatuses.current.get(clarification.clarificationId);
      if (prior !== undefined && prior !== clarification.status) {
        advanced.push(clarification);
      }
    }
    previousStatuses.current = next;
    if (advanced.length === 1) {
      const only = advanced[0]!;
      const label = clarificationItemSignifier(resolveClarificationItemState(only)).label;
      setAnnouncement(clarificationAdvanced(label));
      onLifecycleAdvance?.(only.clarificationId, only.status);
    } else if (advanced.length > 1) {
      setAnnouncement(clarificationsAdvanced(advanced.length));
      for (const clarification of advanced) {
        onLifecycleAdvance?.(clarification.clarificationId, clarification.status);
      }
    }
  }, [view, onLifecycleAdvance]);

  return announcement;
}

export function ClarificationRegion({
  view,
  variant = 'inline',
  density = 'standard',
  selectedClarificationId,
  onSelectQuestion,
  onSubmitAnswer,
  submission,
  onLifecycleAdvance,
  loadState,
  onRetry,
  regionId,
  allowedActions,
  onAcceptClarification,
  acceptSubmission,
  onRegenerateSpec,
  regenerateSubmission,
}: ClarificationRegionProps) {
  const [internalSelected, setInternalSelected] = useState<string | undefined>(
    selectedClarificationId,
  );
  const announcement = useLifecycleAnnouncements(view, onLifecycleAdvance);
  const [showTerminal, setShowTerminal] = useState(false);

  // P7 — `selectedClarificationId` SEEDS the selection (e.g. a `?clarificationId` deep
  // link) without pinning it: clicks override the seed, and a new deep-link target
  // re-seeds. This keeps the region usable when a controlled id was supplied (the old
  // `?? internalSelected` made a defined deep-link id un-overridable by clicks).
  useEffect(() => {
    if (selectedClarificationId !== undefined) {
      setInternalSelected(selectedClarificationId);
    }
  }, [selectedClarificationId]);

  const selectedId = internalSelected;
  const select = (id: string) => {
    setInternalSelected(id);
    onSelectQuestion?.(id);
  };

  const grouped = groupClarificationsByStatus(view);
  const pendingCount = countPendingIncorporation(view);
  const selected = view.clarifications.find((c) => c.clarificationId === selectedId);
  // D① — the `unknown` sentinel is non-actionable: collapse it WITH the terminal
  // states behind the disclosure, out of the inline actionable list.
  const collapsed = [...grouped.terminal, ...grouped.unknown];

  // P5 — if the selected/deep-linked question is in the collapsed group (terminal or
  // an `unknown` sentinel), auto-expand the disclosure so its row is actually visible.
  const selectedStatus = selected?.status;
  useEffect(() => {
    if (
      selectedStatus !== undefined &&
      (TERMINAL_STATUSES.has(selectedStatus) || selectedStatus === 'unknown')
    ) {
      setShowTerminal(true);
    }
  }, [selectedId, selectedStatus]);

  // Arrow-key navigation across the question buttons (AC7).
  const handleListKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return;
    }
    const buttons = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>(
        '[data-clarification-question-button]',
      ),
    );
    if (buttons.length === 0) {
      return;
    }
    const activeIndex = buttons.findIndex((b) => b === document.activeElement);
    if (activeIndex === -1) {
      // P10 — focus was lost from the list (e.g. a focused row collapsed under us);
      // recover to the first row instead of dead-ending the arrow keys.
      event.preventDefault();
      buttons[0]?.focus();
      return;
    }
    if (buttons.length === 1) {
      // P10 — only one option: let the keystroke through (don't hijack page scroll).
      return;
    }
    event.preventDefault();
    const delta = event.key === 'ArrowDown' ? 1 : -1;
    const nextIndex = (activeIndex + delta + buttons.length) % buttons.length;
    buttons[nextIndex]?.focus();
  };

  const liveRegion = (
    <div
      aria-live="polite"
      role="status"
      className="sr-only"
      data-testid="clarification-live-region"
    >
      {announcement}
    </div>
  );

  function renderBody() {
    if (loadState === 'loading') {
      return <LoadingState variant="fetchingData" message="Loading clarifications…" />;
    }
    if (loadState === 'error') {
      return (
        <ErrorState
          variant="failedRetrieval"
          urgency="passive"
          message="We couldn’t load clarifications for this run."
          // P12 — only offer Retry when there is a real handler; otherwise fall back to
          // a meaningful "back" action rather than a dead no-op Retry button.
          nextAction={onRetry !== undefined ? { kind: 'Retry', onRetry } : { kind: 'NavigateBack' }}
        />
      );
    }
    if (view.clarifications.length === 0) {
      return (
        <EmptyState
          variant="noOpenQuestions"
          message="There are no clarifications waiting on a response for this run."
        />
      );
    }
    if (variant === 'compact') {
      return <CompactSummary view={view} onSelectQuestion={select} />;
    }

    return (
      <div className={cn('flex flex-col', densityGap(density))}>
        {/* AC10 — approval-gating affordance (message ONLY; 2.19 owns the bar). */}
        {pendingCount > 0 ? (
          <p
            role="note"
            data-testid="clarification-approval-gate"
            className="rounded-md border border-state-warning-border bg-state-warning px-2 py-1 text-xs text-state-warning-foreground"
          >
            {pendingCount} clarification{pendingCount === 1 ? '' : 's'} must be incorporated before
            approval.
          </p>
        ) : null}

        {/* Question list — open first, then pending; terminal collapsed by default (AC2).
            The `listbox` role admits ONLY `option` children (story 2.25 AC4 /
            aria-required-children), so the disclosure TOGGLE lives OUTSIDE the listbox —
            the collapsed `option` rows still render inside it when expanded so arrow-key
            roving navigation reaches them. */}
        <div
          role="listbox"
          aria-label="Clarification questions"
          tabIndex={-1}
          className={cn('flex flex-col', densityGap(density))}
          onKeyDown={handleListKeyDown}
        >
          {[...grouped.open, ...grouped.pending].map((clarification) => (
            <QuestionRow
              key={clarification.clarificationId}
              view={clarification}
              selected={clarification.clarificationId === selectedId}
              onSelect={() => select(clarification.clarificationId)}
            />
          ))}

          {collapsed.length > 0 && showTerminal
            ? collapsed.map((clarification) => (
                <QuestionRow
                  key={clarification.clarificationId}
                  view={clarification}
                  selected={clarification.clarificationId === selectedId}
                  onSelect={() => select(clarification.clarificationId)}
                />
              ))
            : null}
        </div>

        {collapsed.length > 0 ? (
          <button
            type="button"
            data-testid="clarification-terminal-toggle"
            aria-expanded={showTerminal}
            onClick={() => setShowTerminal((open) => !open)}
            className="self-start rounded-md px-1 py-0.5 text-xs text-text-tertiary hover:text-text-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          >
            {showTerminal ? 'Hide' : 'Show'} resolved ({collapsed.length})
          </button>
        ) : null}

        {/* Selected-question detail dominates the detail area (AC8). */}
        {selected !== undefined ? (
          // P2 — key by clarificationId so switching questions resets the per-question
          // draft/validation state (the instance is otherwise reused at this slot).
          <QuestionDetail
            key={selected.clarificationId}
            view={selected}
            submission={submission}
            onSubmitAnswer={onSubmitAnswer}
            allowedActions={allowedActions}
            onAcceptClarification={onAcceptClarification}
            acceptSubmission={acceptSubmission}
          />
        ) : selectedId !== undefined ? (
          // P6 — a selected/deep-linked id that resolves to nothing (stale link, wrong
          // run, removed clarification) gets an explicit notice, never a silent blank.
          <p
            role="note"
            data-testid="clarification-not-found"
            className="rounded-md border border-border bg-surface-elevated px-2 py-1 text-xs text-text-secondary"
          >
            This clarification is no longer available. It may have been resolved or removed.
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <section
      aria-label="Clarifications"
      id={regionId}
      tabIndex={regionId !== undefined ? -1 : undefined}
      data-testid="clarification-region"
      data-clarification-region-state={
        loadState ?? (view.clarifications.length === 0 ? 'empty' : 'populated')
      }
      data-variant={variant}
      className={cn(
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        variant === 'sidebar' ? 'w-full' : 'w-full max-w-prose',
      )}
    >
      <header className="mb-2 flex items-center justify-between gap-2">
        <h2 className="text-section-heading">Clarifications</h2>
      </header>
      {liveRegion}
      {renderBody()}
      {/* Story 3e-2 (AC2) — run-level regenerate-spec affordance. Governed: hidden unless the run
          advertises `regenerate_spec_with_clarifications`. Surfaced regardless of clarification
          count (the backend no-ops / informs when there is nothing accepted to rebuild from). */}
      {(allowedActions?.includes(REGENERATE_SPEC_ACTION) ?? false) ? (
        <div
          className="mt-3 flex flex-col gap-1 border-t border-border pt-2"
          data-testid="clarification-regenerate-footer"
        >
          <button
            type="button"
            data-testid="clarification-regenerate"
            disabled={regenerateSubmission?.status === 'pending'}
            onClick={() => onRegenerateSpec?.()}
            className="self-start rounded-md border border-border bg-surface px-2.5 py-1 text-sm font-medium text-text-primary hover:bg-surface-elevated disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          >
            {regenerateSubmission?.status === 'pending'
              ? 'Regenerating…'
              : 'Regenerate specification with accepted clarifications'}
          </button>
          {regenerateSubmission?.status === 'success' ? (
            <p
              data-testid="clarification-regenerate-feedback"
              className="text-xs text-state-success-foreground"
            >
              Spec regeneration dispatched — incorporating accepted clarifications.
            </p>
          ) : null}
          {regenerateSubmission?.status === 'error' ? (
            <p
              role="alert"
              data-testid="clarification-regenerate-error"
              className="text-xs text-state-error-foreground"
            >
              We couldn’t start the regeneration ({regenerateSubmission.errorCode ?? 'error'}).
              Please try again.
            </p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
