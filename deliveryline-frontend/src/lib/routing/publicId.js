// @ts-check
/**
 * Story 2.5 (AC2) — public-id param validators for typed routes.
 *
 * SOURCE OF TRUTH (accepted cross-language duplication): the backend
 * `PublicIdPrefixes` enum + the Flyway V1 CHECK constraints (story 1.4) define the
 * canonical shape `^<prefix>_[A-Za-z0-9_-]{4,64}$`. There is no shared JVM↔TS
 * codegen across the module boundary yet — a generated registry is out of scope —
 * so the `run_` / `art_` shapes are RE-ENCODED here and MUST be kept in lockstep
 * with `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java`
 * (`SUFFIX_PATTERN = [A-Za-z0-9_-]{4,64}`).
 *
 * Why plain framework-free ESM (`.js`, NOT `.ts`): the route-param gate
 * `tools/routing/__tests__/public-id-params.test.js` (`npm run check:routes`)
 * imports THIS module directly with ZERO build step — Node 20.19 cannot strip TS
 * types — so the gate exercises the exact predicates the route tree consumes, not
 * a parallel copy that could drift. The sibling `publicId.d.ts` gives the TS routes
 * a fully-typed import (Vite resolves `.js` for runtime; tsc resolves `.d.ts` for
 * types). This mirrors the `tools/contrast/parse-globals.js` "single source of
 * truth, no JS/JSON copy" discipline.
 */

/** The V1 suffix charset + length: 4–64 chars of `[A-Za-z0-9_-]`. */
const SUFFIX = '[A-Za-z0-9_-]{4,64}';

/** `^run_[A-Za-z0-9_-]{4,64}$` — a well-formed workflow-run public id. */
export const RUN_ID_PATTERN = new RegExp(`^run_${SUFFIX}$`);

/** `^art_[A-Za-z0-9_-]{4,64}$` — a well-formed artifact public id. */
export const ART_ID_PATTERN = new RegExp(`^art_${SUFFIX}$`);

/**
 * Generic predicate: does `value` match `^<prefix>_[A-Za-z0-9_-]{4,64}$`?
 * `prefix` is a registered registry prefix WITHOUT the trailing underscore
 * (e.g. `'run'`, `'art'`). Non-string input is rejected (never throws).
 * @param {string} prefix
 * @param {unknown} value
 * @returns {boolean}
 */
export function matchesPublicId(prefix, value) {
  if (typeof value !== 'string') {
    return false;
  }
  return new RegExp(`^${prefix}_${SUFFIX}$`).test(value);
}

/**
 * @param {unknown} value
 * @returns {boolean} true iff `value` is a well-formed `run_…` public id.
 */
export function isValidRunId(value) {
  return typeof value === 'string' && RUN_ID_PATTERN.test(value);
}

/**
 * @param {unknown} value
 * @returns {boolean} true iff `value` is a well-formed `art_…` public id.
 */
export function isValidArtifactId(value) {
  return typeof value === 'string' && ART_ID_PATTERN.test(value);
}
