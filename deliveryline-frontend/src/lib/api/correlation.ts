/**
 * Story 2.5 (AC9) — correlation-ID propagation SEAM.
 *
 * This is the deliberate hook so that, once the typed API client lands in story
 * 2.6, every route-loader-triggered request carries a correlation ID and the
 * server-side structured logs (story 1.19) can be traced back to the UI
 * navigation that triggered them.
 *
 * SEAM ONLY — there is NO HTTP client yet (that is story 2.6). This module
 * intentionally ships just the canonical header name and an ID generator; no
 * runtime request wiring. Loaders mark where the header attaches with a
 * `// SEAM (story 2.6/1.19):` comment.
 *
 * Header name + UUID shape mirror the backend source of truth:
 * `deliveryline-backend/.../infrastructure/observability/CorrelationIdFilter.java`
 * (`HEADER = "X-Correlation-Id"`, value validated as a UUID).
 */

/** The canonical correlation-ID request header (backend `CorrelationIdFilter.HEADER`). */
export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/**
 * Mint a fresh correlation ID for an outbound request. UUID v4 via the Web Crypto
 * API (available in every browser the SPA targets and in the Vite dev server).
 */
export function newCorrelationId(): string {
  return crypto.randomUUID();
}
