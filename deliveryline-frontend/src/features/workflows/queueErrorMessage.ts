/**
 * Story 2.20 (AC5) — map a queue-load failure to safe, non-technical copy.
 *
 * The error branch NEVER surfaces `error.message` / `.stack` / raw HTTP status
 * (which embed `code`+status and can carry runner-influenced text). It branches on
 * the stable `ProblemDetailsError.code` (story 1.8 `DomainErrorCode`) for the cases
 * worth distinguishing, falls back to one generic line for any other code, and
 * uses a separate transport line when the failure is not a `ProblemDetailsError`
 * (network / proxy). The paired Retry affordance (AC5/AC6) is the real recovery.
 */
import { isProblemDetailsError } from '@/lib/api/problemDetails';

/** Generic fallback for an unrecognized domain `code`. */
const UNKNOWN_CODE_MESSAGE = 'Something went wrong loading the review queue.';

/** Used when the failure never reached the server (network / proxy). */
const TRANSPORT_MESSAGE = 'Couldn’t reach the server to load the review queue.';

/** Fixed copy per known domain code. Unmapped codes use {@link UNKNOWN_CODE_MESSAGE}. */
const MESSAGE_BY_CODE: Record<string, string> = {
  INTERNAL_ERROR: 'The server had a problem loading the review queue.',
  VALIDATION_ERROR: 'The queue request was rejected as invalid.',
};

export function queueErrorMessage(error: unknown): string {
  if (isProblemDetailsError(error)) {
    return MESSAGE_BY_CODE[error.code] ?? UNKNOWN_CODE_MESSAGE;
  }
  return TRANSPORT_MESSAGE;
}
