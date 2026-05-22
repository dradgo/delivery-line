/**
 * Story 2.6 (AC5) — typed Problem Details (RFC 9457) parser + error type.
 *
 * The backend maps every domain failure to an `application/problem+json` body
 * with stable DeliveryLine extensions (`code` / `retryable`) — story 1.8's
 * `ProblemDetailsCatalog` + `DomainErrorCode`. TanStack Query `onError` callbacks
 * MUST branch on `error.code` (a stable `DomainErrorCode` string) / `error.status`
 * / `error.retryable`, NEVER on raw HTTP status text or `error.message` matching.
 *
 * DEPENDS-ON 6.9 patch: the committed `openapi.json` snapshot DOES type
 * `ProblemDetailsResponse` (springdoc 3.0.3), but it types `code` as a bare
 * `string` (it carries only an example, no enum of domain codes). So the wire
 * SHAPE is taken from the generated schema, while the `code` UNION is hand-authored
 * from story 1.8's catalog below. When 6.9 strengthens the spec to enumerate the
 * domain codes, swap `DomainErrorCode` for the generated union here.
 */
import type { components } from './schema';

/** The wire shape, straight from the generated OpenAPI schema (story 6.9). */
type ProblemDetailsWire = components['schemas']['ProblemDetailsResponse'];

/** The RFC-9457 problem+json media type the backend emits on every domain error. */
export const PROBLEM_JSON_CONTENT_TYPE = 'application/problem+json';

/**
 * Stable domain error codes (story 1.8 `DomainErrorCode`) the read/spec surface
 * can surface. The `(string & {})` tail keeps the union OPEN so an unknown future
 * code (from a backend ahead of this build) degrades gracefully — it parses to a
 * still-typed `ProblemDetails` instead of crashing — while known codes keep their
 * literal autocomplete. DEPENDS-ON 6.9 patch: replace with the generated enum once
 * the spec enumerates these.
 */
export type DomainErrorCode =
  | 'RUN_NOT_FOUND'
  | 'INVALID_ID_PREFIX'
  | 'APPROVAL_VERSION_MISMATCH'
  | 'ARTIFACT_OPERATION_INTENT_CONFLICT'
  | 'VALIDATION_ERROR'
  | 'HISTORY_TOO_LARGE'
  | 'INTERNAL_ERROR'
  | (string & {});

/** Parsed, typed Problem Details — the wire shape with the narrowed `code`. */
export interface ProblemDetails extends Omit<ProblemDetailsWire, 'code' | 'details'> {
  code: DomainErrorCode;
  /**
   * Domain extension payload — OPEN shape. The generated schema types this as
   * `null`, but real bodies carry an object (most domain errors) or an array of
   * field errors (validation), so it is widened to `unknown` here. DEPENDS-ON 6.9
   * patch: tighten once the spec models the per-code detail shapes.
   */
  details?: unknown;
}

/**
 * Error thrown for any non-2xx response carrying a problem+json body. Consumers
 * read the typed `code` / `status` / `retryable` accessors — never `message`.
 */
export class ProblemDetailsError extends Error {
  readonly problem: ProblemDetails;

  constructor(problem: ProblemDetails) {
    super(`${problem.code} (${problem.status}): ${problem.title}`);
    this.name = 'ProblemDetailsError';
    this.problem = problem;
  }

  /** Stable `DomainErrorCode` — the value `onError` branches on. */
  get code(): DomainErrorCode {
    return this.problem.code;
  }

  /** HTTP status carried in the body (mirrors the response status). */
  get status(): number {
    return this.problem.status;
  }

  /** Whether the backend marks this failure as safe to retry. */
  get retryable(): boolean {
    return this.problem.retryable;
  }
}

/** Type guard so `onError` / loaders can branch without `instanceof` ceremony. */
export function isProblemDetailsError(error: unknown): error is ProblemDetailsError {
  return error instanceof ProblemDetailsError;
}

/**
 * Coerce an arbitrary parsed body into a typed `ProblemDetails`, defaulting the
 * required RFC fields defensively so a malformed body still yields a usable error
 * rather than throwing inside the error path.
 */
export function toProblemDetails(body: unknown, fallbackStatus: number): ProblemDetails {
  const raw: Record<string, unknown> =
    typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  return {
    type: typeof raw.type === 'string' ? raw.type : 'about:blank',
    title: typeof raw.title === 'string' ? raw.title : 'Unexpected error',
    status: typeof raw.status === 'number' ? raw.status : fallbackStatus,
    detail: typeof raw.detail === 'string' ? raw.detail : '',
    instance: typeof raw.instance === 'string' ? raw.instance : '',
    code: typeof raw.code === 'string' ? raw.code : 'INTERNAL_ERROR',
    retryable: typeof raw.retryable === 'boolean' ? raw.retryable : false,
    correlationId: typeof raw.correlationId === 'string' ? raw.correlationId : null,
    details: raw.details ?? null,
  };
}

/**
 * Parse a `Response` whose `content-type` is `application/problem+json` into a
 * typed `ProblemDetails`. Returns `null` for any other content type so the caller
 * can fall through to generic error handling (AC5: only problem+json is typed).
 */
export async function parseProblemDetails(response: Response): Promise<ProblemDetails | null> {
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes(PROBLEM_JSON_CONTENT_TYPE)) {
    return null;
  }
  const body: unknown = await response.json().catch(() => null);
  return toProblemDetails(body, response.status);
}
