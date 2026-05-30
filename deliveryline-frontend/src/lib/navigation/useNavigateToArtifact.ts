/**
 * Story 2.22 (AC1) — typed navigate-to-artifact helper.
 *
 * Validates both ids at hook-call time via the `publicId` predicates (NOT at
 * navigation time) so a consumer can branch synchronously. On a malformed id it
 * throws {@link InvalidNavigationTargetError} from the hook BODY (Trap T6 — the
 * untrusted-string defense lives at the source, not in the returned callback).
 *
 * All hooks run unconditionally BEFORE the validation throw so the
 * rules-of-hooks contract holds.
 */
import { useCallback } from 'react';
import { useNavigate } from '@tanstack/react-router';

import { isValidArtifactId, isValidRunId } from '@/lib/routing/publicId';
import { InvalidNavigationTargetError } from './types';

export function useNavigateToArtifact(runId: string, artifactId: string): () => void {
  const navigate = useNavigate();
  const go = useCallback(() => {
    void navigate({
      to: '/workflows/$workflowRunId/artifacts/$artifactId',
      params: { workflowRunId: runId, artifactId },
    });
  }, [navigate, runId, artifactId]);

  if (!isValidRunId(runId)) {
    console.warn({
      event: 'navigation.invalidId',
      kind: 'artifact',
      runId,
      artifactId,
      reason: 'invalid runId',
    });
    throw new InvalidNavigationTargetError('run', runId);
  }
  if (!isValidArtifactId(artifactId)) {
    console.warn({
      event: 'navigation.invalidId',
      kind: 'artifact',
      runId,
      artifactId,
      reason: 'invalid artifactId',
    });
    throw new InvalidNavigationTargetError('artifact', artifactId);
  }
  return go;
}
