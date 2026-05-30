/**
 * Story 2.22 (AC1) — typed navigate-to-clarification helper.
 *
 * Anchored deep-link semantics (OQ-6): lands on the run-detail route with a
 * typed `?clarificationId=cla_xxx` SEARCH param (NOT a hash anchor — TanStack
 * Router owns scroll restoration via typed search state). The clarification
 * region (story 2.18) reads the param and scrolls itself into view.
 *
 * Validates `runId` + `clarificationId` at hook-call time; throws
 * {@link InvalidNavigationTargetError} from the hook BODY on a malformed id
 * (Trap T6). All hooks run before the throw (rules-of-hooks).
 */
import { useCallback } from 'react';
import { useNavigate } from '@tanstack/react-router';

import { isValidClarificationId, isValidRunId } from '@/lib/routing/publicId';
import { InvalidNavigationTargetError } from './types';

export function useNavigateToClarification(runId: string, clarificationId: string): () => void {
  const navigate = useNavigate();
  const go = useCallback(() => {
    void navigate({
      to: '/workflows/$workflowRunId',
      params: { workflowRunId: runId },
      search: { clarificationId },
    });
  }, [navigate, runId, clarificationId]);

  if (!isValidRunId(runId)) {
    console.warn({ event: 'navigation.invalidId', kind: 'clarification', runId, clarificationId, reason: 'invalid runId' });
    throw new InvalidNavigationTargetError('run', runId);
  }
  if (!isValidClarificationId(clarificationId)) {
    console.warn({ event: 'navigation.invalidId', kind: 'clarification', runId, clarificationId, reason: 'invalid clarificationId' });
    throw new InvalidNavigationTargetError('clarification', clarificationId);
  }
  return go;
}
