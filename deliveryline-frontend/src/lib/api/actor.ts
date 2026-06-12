/**
 * Story 3.30 (Task 1, OQ-1 resolved) — the single local-actor seam.
 *
 * `RetryWorkflowRequest` (unlike `ApproveSpecRequest`) requires a non-blank
 * `actorIdentity` + an `actorType` in the BODY, and the frontend has no live
 * actor/session context yet. We send `local-operator` / `HUMAN` — the EXACT value
 * the backend already stamps for every other UI governance action (it is the
 * `deliveryline.security.local-actor-identity` property default that approve / reject
 * / clarify fall back to), so the retry audit trail stays consistent. The actor is
 * audit-only (never used for idempotency or authorization), so a constant is safe
 * until a real auth/session context lands — and THIS module is the single seam to
 * swap when it does.
 *
 * Pure `.ts` constants (no JSX) — importable by hooks without react-refresh concerns.
 */
import type { components } from './schema';

/** The audit actor identity the UI records for governance actions (backend default parity). */
export const LOCAL_ACTOR_IDENTITY = 'local-operator';

/** The audit actor type the UI records for governance actions (backend hardcodes `HUMAN`). */
export const LOCAL_ACTOR_TYPE: components['schemas']['RetryWorkflowRequest']['actorType'] = 'HUMAN';
