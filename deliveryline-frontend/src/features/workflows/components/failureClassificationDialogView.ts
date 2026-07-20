/**
 * Story 4.24 — pure view helpers for `FailureClassificationDialog`.
 *
 * NON-component `.ts` (react-refresh forbids fn exports from a `.tsx`,
 * [[frontend-react-refresh-no-fn-exports]]). Centralizes the deprecated-affix composition, the
 * prior/replacement pre-select resolution, and the wire→human-name lookup so the dialog + the Run
 * Context Strip badge read a value the same way and cannot drift.
 */
import type { components } from '@/lib/api/schema';

export type TaxonomyValue = components['schemas']['TaxonomyValue'];
export type FailureClassification = components['schemas']['FailureClassificationResponse'];

/**
 * Compose the deprecated affix FROM the `deprecated` + `replacementValue` fields (NEVER a
 * pre-affixed backend string, AC4). Empty string for an active value.
 */
export function deprecatedAffix(
  taxonomy: Pick<TaxonomyValue, 'deprecated' | 'replacementValue'>,
): string {
  if (!taxonomy.deprecated) {
    return '';
  }
  return taxonomy.replacementValue != null && taxonomy.replacementValue !== ''
    ? ` (deprecated, use ${taxonomy.replacementValue} instead)`
    : ' (deprecated)';
}

/** Look up one taxonomy entry by its wire value. */
export function findTaxonomy(
  values: readonly TaxonomyValue[] | undefined,
  wireValue: string | null | undefined,
): TaxonomyValue | undefined {
  if (wireValue == null) {
    return undefined;
  }
  return values?.find((entry) => entry.value === wireValue);
}

/**
 * Resolve the value to pre-select when a run was previously classified (AC5): the prior value if it
 * is still active; its replacement (resolved against the live registry) if the prior value is
 * deprecated; `''` (nothing) when the run was never classified or the resolved value is unknown.
 */
export function resolvePreselectValue(
  classification: FailureClassification | undefined,
  values: readonly TaxonomyValue[] | undefined,
): string {
  const prior = classification?.currentTaxonomyValue;
  if (prior == null || prior === '') {
    return '';
  }
  const priorEntry = findTaxonomy(values, prior);
  // Prior value unknown to the current registry → do not guess; leave unselected.
  if (priorEntry === undefined) {
    return '';
  }
  if (!priorEntry.deprecated) {
    return prior;
  }
  // Deprecated prior → pre-select its replacement, but only if the replacement is a known active value.
  const replacement = priorEntry.replacementValue;
  const replacementEntry = findTaxonomy(values, replacement);
  return replacementEntry !== undefined && !replacementEntry.deprecated
    ? (replacement as string)
    : '';
}

/**
 * Human name for a wire value: the registry's `humanReadableName` when known, else a graceful
 * title-cased fallback (forward-compat with a value the current build's registry lacks).
 */
export function humanNameForTaxonomy(
  values: readonly TaxonomyValue[] | undefined,
  wireValue: string | null | undefined,
): string | undefined {
  if (wireValue == null || wireValue === '') {
    return undefined;
  }
  const entry = findTaxonomy(values, wireValue);
  if (entry !== undefined) {
    return entry.humanReadableName;
  }
  return wireValue
    .split(/[_\s-]+/)
    .filter((part) => part.length > 0)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
    .join(' ');
}

/**
 * Read `details.replacementValue` off a typed `DEPRECATED_TAXONOMY_VALUE` problem body (AC7). The
 * `details` payload is an open `unknown`, so narrow defensively.
 */
export function replacementValueFromDetails(details: unknown): string | undefined {
  if (details != null && typeof details === 'object' && 'replacementValue' in details) {
    const value = (details as Record<string, unknown>).replacementValue;
    return typeof value === 'string' ? value : undefined;
  }
  return undefined;
}
