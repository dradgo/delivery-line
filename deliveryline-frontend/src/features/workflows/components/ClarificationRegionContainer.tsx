/**
 * Story 2.18 (Task 5, OQ-1) — the thin data CONTAINER for the Clarification Region.
 *
 * Mirrors story 2.17's `ArtifactReviewPanelContainer`: reads the DISABLED
 * `useClarifications` stub (→ the calm `no open questions` empty view today, exactly
 * as 2.17 mapped its disabled `useArtifact` → empty), wires the region's
 * `onSubmitAnswer` to the LIVE `useSubmitClarification` mutation, maps the mutation
 * status into the region's `submission` prop, and owns the field-only structured
 * logging. Keeping it separate from the presentational region lets the region be
 * tested router/query-free with fixtures.
 *
 * T5 — the read hook stays DISABLED (no clarification-read endpoint). When the read
 * endpoint ships, enabling the hook flips the region from `empty` to populated with
 * ZERO region changes (the runtime guard already validates the future shape).
 *
 * T-ANCHOR / OQ-4 — `clarificationId` (the route's `?clarificationId` search param)
 * has no live target pre-read-model, so today it can only focus/scroll the region;
 * once the read model lands it selects + scrolls the matching question.
 */
import { useEffect, useRef, useState } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';

import {
  normalizeClarificationsView,
  type ClarificationLifecycleStatus,
  type ClarificationsView,
} from '../clarificationView';
import { useAcceptClarification } from '../hooks/useAcceptClarification';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useClarifications } from '../hooks/useClarifications';
import { useRegenerateSpec } from '../hooks/useRegenerateSpec';
import { useSubmitClarification } from '../hooks/useSubmitClarification';
import {
  ClarificationRegion,
  type ClarificationRegionVariant,
  type ClarificationSubmissionState,
  type RegenerateSubmissionState,
} from './ClarificationRegion';

/** The stable DOM id the 2.17 `artifact-clarification-anchor` focuses/scrolls (Task 5). */
export const CLARIFICATION_REGION_ID = 'clarification-region';

export interface ClarificationRegionContainerProps {
  workflowRunId: string;
  variant?: ClarificationRegionVariant | undefined;
  /** The route's validated `?clarificationId` deep-link target (story 2.22). */
  clarificationId?: string | undefined;
}

export function ClarificationRegionContainer({
  workflowRunId,
  variant = 'sidebar',
  clarificationId,
}: ClarificationRegionContainerProps) {
  const clarificationsQuery = useClarifications(workflowRunId);
  const submit = useSubmitClarification(workflowRunId);
  // Story 3e-2 — the run's allowed actions gate the accept + regenerate affordances; the two LIVE
  // mutations drive accept_clarification / regenerate_spec_with_clarifications.
  const allowedActionsQuery = useAllowedActions(workflowRunId);
  const accept = useAcceptClarification(workflowRunId);
  const regenerate = useRegenerateSpec(workflowRunId);
  const [activeAcceptId, setActiveAcceptId] = useState<string | undefined>(undefined);

  // Disabled stub → `data` is always `undefined` today. When the hook becomes live,
  // NORMALIZE the future shape rather than trusting a cast (mirrors 2.17): a single
  // malformed row is dropped and an unrecognized status is coerced to `unknown`, so
  // one bad row never blanks the whole region (review finding Decision-②).
  const rawData: unknown = clarificationsQuery.data;
  const view: ClarificationsView = normalizeClarificationsView(rawData);

  // Track which clarification the in-flight/last submit targeted so the region's inline
  // feedback (AC5) is scoped to the right question.
  const [activeClarificationId, setActiveClarificationId] = useState<string | undefined>(undefined);

  const submission: ClarificationSubmissionState = {
    status: submit.isPending
      ? 'pending'
      : submit.isSuccess
        ? 'success'
        : submit.isError
          ? 'error'
          : 'idle',
    clarificationId: activeClarificationId,
    errorCode: isProblemDetailsError(submit.error) ? submit.error.code : undefined,
  };

  const handleSubmitAnswer = (id: string, answerText: string) => {
    // P3 — never start a second submit while one is already in flight (the single
    // shared mutation would clobber the first attempt's reported state).
    if (submit.isPending) {
      return;
    }
    const clarification = view.clarifications.find((c) => c.clarificationId === id);
    if (clarification === undefined) {
      // No live read model today → no artifact binding to submit against (dormant path).
      return;
    }
    setActiveClarificationId(id);
    submit.mutate(
      {
        clarificationId: id,
        answerText,
        artifactId: clarification.artifactId,
        expectedArtifactVersion: clarification.artifactVersion,
      },
      {
        onSuccess: (data) => {
          // Field-only (T8): the response STATUS, never the answer text.
          console.info({
            event: 'clarification.submit',
            clarificationStatus: data.clarificationStatus,
          });
        },
        onError: (error) => {
          console.warn({
            event: 'clarification.submitError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  };

  // Load-error logging (dormant — the disabled stub never errors today).
  useEffect(() => {
    if (!clarificationsQuery.isError) {
      return;
    }
    const error = clarificationsQuery.error;
    console.warn({
      event: 'clarification.loadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [clarificationsQuery.isError, clarificationsQuery.error]);

  const handleLifecycleAdvance = (_id: string, status: ClarificationLifecycleStatus) => {
    // Field-only: the new lifecycle status, never question/answer text (T8).
    console.info({ event: 'clarification.lifecycleAdvance', status });
  };

  // T-ANCHOR / OQ-4 — focus/scroll the region when arrived via a `?clarificationId`
  // deep link. Pre-read-model there is no question to select, so this best-effort
  // focuses the region container; once the read model lands, selecting the matching
  // question is a one-line addition (the param already round-trips).
  // P8 — track WHICH id we last focused for, not a one-shot boolean, so a second
  // in-route deep link (the `?clarificationId` param changes) re-focuses/scrolls the
  // region instead of being ignored after the first focus.
  const focusedForId = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (clarificationId === undefined || focusedForId.current === clarificationId) {
      return;
    }
    const element = document.getElementById(CLARIFICATION_REGION_ID);
    if (element !== null) {
      focusedForId.current = clarificationId;
      if (typeof element.scrollIntoView === 'function') {
        element.scrollIntoView({ block: 'start' });
      }
      element.focus();
    }
  }, [clarificationId, view]);

  // Story 3e-2 (AC1) — accept inline feedback, scoped to the targeted clarification.
  const acceptSubmission: ClarificationSubmissionState = {
    status: accept.isPending
      ? 'pending'
      : accept.isSuccess
        ? 'success'
        : accept.isError
          ? 'error'
          : 'idle',
    clarificationId: activeAcceptId,
    errorCode: isProblemDetailsError(accept.error) ? accept.error.code : undefined,
  };

  // Story 3e-2 (AC2) — regenerate inline feedback (run-level; no per-clarification scoping).
  const regenerateSubmission: RegenerateSubmissionState = {
    status: regenerate.isPending
      ? 'pending'
      : regenerate.isSuccess
        ? 'success'
        : regenerate.isError
          ? 'error'
          : 'idle',
    errorCode: isProblemDetailsError(regenerate.error) ? regenerate.error.code : undefined,
  };

  const handleAcceptClarification = (id: string) => {
    if (accept.isPending) {
      return;
    }
    // (review P3) Clear any settled (success/error) state from a PRIOR accept on a DIFFERENT
    // clarification before retargeting. The single shared mutation's status is scoped to the row
    // via `activeAcceptId`, so without this reset a prior row's banner could momentarily attach to
    // the newly-targeted row. `accept.mutate` transitions to 'pending' immediately after, so a
    // same-row re-accept (id unchanged) is unaffected and keeps its in-place feedback.
    if (activeAcceptId !== id) {
      accept.reset();
    }
    setActiveAcceptId(id);
    accept.mutate(
      { clarificationId: id },
      {
        onSuccess: (data) => {
          // Field-only (T8): the response STATUS, never question/answer text.
          console.info({
            event: 'clarification.accept',
            clarificationStatus: data.clarificationStatus,
          });
        },
        onError: (error) => {
          console.warn({
            event: 'clarification.acceptError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  };

  const handleRegenerateSpec = () => {
    if (regenerate.isPending) {
      return;
    }
    regenerate.mutate(
      {},
      {
        onSuccess: (data) => {
          console.info({ event: 'clarification.regenerate', currentState: data.currentState });
        },
        onError: (error) => {
          console.warn({
            event: 'clarification.regenerateError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  };

  const allowedActions: readonly string[] = allowedActionsQuery.data?.actions ?? [];

  return (
    <ClarificationRegion
      view={view}
      variant={variant}
      regionId={CLARIFICATION_REGION_ID}
      selectedClarificationId={clarificationId}
      submission={submission}
      onSubmitAnswer={handleSubmitAnswer}
      allowedActions={allowedActions}
      onAcceptClarification={handleAcceptClarification}
      acceptSubmission={acceptSubmission}
      onRegenerateSpec={handleRegenerateSpec}
      regenerateSubmission={regenerateSubmission}
      onLifecycleAdvance={handleLifecycleAdvance}
      loadState={
        clarificationsQuery.isError
          ? 'error'
          : clarificationsQuery.isLoading && clarificationsQuery.fetchStatus !== 'idle'
            ? 'loading'
            : undefined
      }
      onRetry={() => {
        console.info({ event: 'clarification.retry' });
        void clarificationsQuery.refetch();
      }}
    />
  );
}
