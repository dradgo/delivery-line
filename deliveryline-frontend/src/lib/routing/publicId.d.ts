// Story 2.5 — typed surface for the framework-free `publicId.js` validators.
// Hand-written so TS routes get a typed import while the runtime `.js` stays
// directly importable by the `node --test` route gate (no build step). Keep in
// sync with `publicId.js`.

/** `^run_[A-Za-z0-9_-]{4,64}$` — a well-formed workflow-run public id. */
export declare const RUN_ID_PATTERN: RegExp;

/** `^art_[A-Za-z0-9_-]{4,64}$` — a well-formed artifact public id. */
export declare const ART_ID_PATTERN: RegExp;

/**
 * Generic predicate: does `value` match `^<prefix>_[A-Za-z0-9_-]{4,64}$`?
 * `prefix` is a registry prefix WITHOUT the trailing underscore (`'run'`, `'art'`).
 */
export declare function matchesPublicId(prefix: string, value: unknown): boolean;

/** True iff `value` is a well-formed `run_…` public id. */
export declare function isValidRunId(value: unknown): value is string;

/** True iff `value` is a well-formed `art_…` public id. */
export declare function isValidArtifactId(value: unknown): value is string;
