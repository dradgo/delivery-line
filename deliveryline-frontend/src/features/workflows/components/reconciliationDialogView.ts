/**
 * Story 4.23 (Task 4/5/6) — pure presentational helpers + copy maps for the reconciliation dialog.
 *
 * Kept in a sibling `.ts` (never the `.tsx`) so React Fast Refresh does not choke on a component
 * module exporting non-component functions ([[frontend-react-refresh-no-fn-exports]]). Everything
 * here is pure + framework-agnostic: snapshot parsing/diffing, the decision label + consequence copy
 * maps, and the safety → state-token mapping. Tests import these directly (fixture discipline).
 *
 * SECURITY (trap #4): the snapshots are raw JSON strings sourced from EXTERNAL, attacker-influenceable
 * integration metadata. Parsing degrades (never throws) on malformed/`null` input; the rendered
 * values are always treated as untrusted text by the component (sanitized, never
 * `dangerouslySetInnerHTML`).
 */
import type { StateName } from '@/lib/state-signifiers';

import type { ReconciliationDecision } from '../hooks/useReconcileWorkflow';

// ---- Snapshot parsing (AC3) ----------------------------------------------------

/** The result of defensively parsing a raw-JSON-string snapshot. */
export interface ParsedSnapshot {
  /** True only when `raw` parsed to a plain JSON object. */
  readonly ok: boolean;
  /** The parsed object's fields (present only when `ok`). */
  readonly fields?: Record<string, unknown> | undefined;
  /** The original raw string (`''` when the snapshot was null/absent) — for the fallback affordance. */
  readonly raw: string;
}

/**
 * Defensively parse a snapshot. A `null`/absent snapshot, a non-object JSON value (array/primitive),
 * or a `JSON.parse` failure all degrade to `{ ok: false }` — the panel then renders the plain-text
 * "unavailable / unparseable" fallback rather than throwing (AC3).
 */
export function parseSnapshot(raw: string | null | undefined): ParsedSnapshot {
  if (raw == null || raw.trim() === '') {
    return { ok: false, raw: '' };
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      return { ok: true, fields: value as Record<string, unknown>, raw };
    }
    return { ok: false, raw };
  } catch {
    return { ok: false, raw };
  }
}

/**
 * The snapshot fields we render as labeled rows (AC3), in display order. Snapshot metadata varies by
 * integration; anything not here falls into the "Raw metadata" expandable section. Keys are matched
 * case-insensitively and across snake/camel case (`commit_sha` ≡ `commitSha`).
 */
const KNOWN_SNAPSHOT_FIELDS: readonly { readonly key: string; readonly label: string }[] = [
  { key: 'state', label: 'State' },
  { key: 'status', label: 'Status' },
  { key: 'prstate', label: 'PR state' },
  { key: 'issuestate', label: 'Issue state' },
  { key: 'ticketstatus', label: 'Ticket status' },
  { key: 'branch', label: 'Branch' },
  { key: 'commitsha', label: 'Commit' },
  { key: 'merged', label: 'Merged' },
  { key: 'externalref', label: 'External reference' },
];

/** Canonical form of a field key for matching (lower-cased, separators stripped). */
function canonicalKey(key: string): string {
  return key.toLowerCase().replace(/[_\-\s]/g, '');
}

const KNOWN_BY_CANONICAL: ReadonlyMap<string, string> = new Map(
  KNOWN_SNAPSHOT_FIELDS.map((f) => [f.key, f.label]),
);

/** Render a parsed field value as untrusted display text (objects/arrays → compact JSON). */
export function stringifySnapshotValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    // Circular / non-serializable external metadata — degrade rather than throw.
    return '[unserializable]';
  }
}

export type FieldDiffStatus = 'added' | 'removed' | 'modified' | 'unchanged';

/** One diffed known field, carrying both sides' display values + the per-field status. */
export interface SnapshotFieldDiff {
  readonly key: string;
  readonly label: string;
  readonly internalValue: string | undefined;
  readonly externalValue: string | undefined;
  readonly status: FieldDiffStatus;
}

/** Look up a known field's value in a parsed snapshot by canonical key (case/separator-insensitive). */
function findKnownValue(
  fields: Record<string, unknown> | undefined,
  canonical: string,
): { present: boolean; value: unknown } {
  if (fields === undefined) {
    return { present: false, value: undefined };
  }
  for (const rawKey of Object.keys(fields)) {
    if (canonicalKey(rawKey) === canonical) {
      return { present: true, value: fields[rawKey] };
    }
  }
  return { present: false, value: undefined };
}

/**
 * Diff the KNOWN fields across the internal (left) and external (right) snapshots (AC2/AC3). A field
 * present only externally is `added`; only internally is `removed`; present on both with differing
 * display values is `modified`; identical is `unchanged`. Fields absent from both are dropped.
 */
export function diffSnapshots(
  internal: ParsedSnapshot,
  external: ParsedSnapshot,
): SnapshotFieldDiff[] {
  const rows: SnapshotFieldDiff[] = [];
  for (const { key, label } of KNOWN_SNAPSHOT_FIELDS) {
    const canonical = canonicalKey(key);
    const left = findKnownValue(internal.fields, canonical);
    const right = findKnownValue(external.fields, canonical);
    if (!left.present && !right.present) {
      continue;
    }
    const internalValue = left.present ? stringifySnapshotValue(left.value) : undefined;
    const externalValue = right.present ? stringifySnapshotValue(right.value) : undefined;
    let status: FieldDiffStatus;
    if (left.present && !right.present) {
      status = 'removed';
    } else if (!left.present && right.present) {
      status = 'added';
    } else {
      status = internalValue === externalValue ? 'unchanged' : 'modified';
    }
    rows.push({ key: label, label, internalValue, externalValue, status });
  }
  return rows;
}

/** The fields NOT rendered as known labeled rows — shown prettified in the "Raw metadata" section. */
export function unknownSnapshotFields(parsed: ParsedSnapshot): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (parsed.fields === undefined) {
    return out;
  }
  for (const rawKey of Object.keys(parsed.fields)) {
    if (!KNOWN_BY_CANONICAL.has(canonicalKey(rawKey))) {
      out[rawKey] = parsed.fields[rawKey];
    }
  }
  return out;
}

/** Prettified JSON of an object for the "Raw metadata" expandable section (untrusted text). */
export function prettyJson(value: Record<string, unknown>): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '{}';
  }
}

// ---- Header labels (AC2, OQ-1a) ------------------------------------------------

const INTEGRATION_TYPE_LABELS: Readonly<Record<string, string>> = {
  github_pr: 'GitHub',
  github: 'GitHub',
  linear: 'Linear',
};

/** Human integration-type label (`github_pr` → "GitHub"); unknown/absent → "Integration". */
export function integrationTypeLabel(integrationType: string | null | undefined): string {
  if (integrationType == null || integrationType === '') {
    return 'Integration';
  }
  return INTEGRATION_TYPE_LABELS[integrationType] ?? titleCaseToken(integrationType);
}

const CONFLICT_CATEGORY_LABELS: Readonly<Record<string, string>> = {
  external_state_advanced: 'External state advanced',
  external_state_reverted: 'External state reverted',
  external_resource_removed: 'External resource removed',
  metadata_drift: 'Metadata drift',
  link_broken: 'Link broken',
};

/** Human conflict-category label; unknown/absent → title-cased token or "Integration conflict". */
export function conflictCategoryLabel(category: string | null | undefined): string {
  if (category == null || category === '') {
    return 'Integration conflict';
  }
  return CONFLICT_CATEGORY_LABELS[category] ?? titleCaseToken(category);
}

/** Title-case a snake/lower token (`external_state_advanced` → "External state advanced"). */
function titleCaseToken(token: string): string {
  const spaced = token.replace(/[_-]+/g, ' ').trim();
  if (spaced === '') {
    return token;
  }
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

// ---- Decision labels + consequences (AC4/AC5/AC6) ------------------------------

/** The four wire reconciliation decisions → human labels. */
const DECISION_LABELS: Readonly<Record<ReconciliationDecision, string>> = {
  accept_external_state: 'Accept external state',
  accept_internal_state: 'Accept internal state',
  mark_completed_externally: 'Mark completed externally',
  mark_failed_externally: 'Mark failed externally',
};

/** Human label for a reconciliation decision; an unknown wire value degrades to a title-cased token. */
export function decisionLabel(decision: string): string {
  return (DECISION_LABELS as Record<string, string>)[decision] ?? titleCaseToken(decision);
}

/**
 * The FE consequence copy per decision (Task 6) — derived from `ConflictReconciliationSuggester`'s
 * per-category semantics. Rendered inline as the `aria-describedby` target for the selected decision
 * (AC10); when the selected option is `risky` it is the inline warning (AC4).
 */
const DECISION_CONSEQUENCES: Readonly<Record<ReconciliationDecision, string>> = {
  accept_external_state:
    'Adopts the external system’s current state as authoritative. The internal record is updated to match — internal-only progress recorded since the divergence is discarded.',
  accept_internal_state:
    'Re-asserts the internal state as authoritative and may re-drive the external system to match — this can overwrite or re-open externally-applied changes (for example, re-opening an externally-merged pull request).',
  mark_completed_externally:
    'Records that this work was completed outside the workflow. The run is closed as externally completed with no further orchestration.',
  mark_failed_externally:
    'Records that this work failed or was abandoned outside the workflow. The run is closed as externally failed with no further orchestration.',
};

/** Consequence copy for a decision; an unknown wire value degrades to a generic caution line. */
export function decisionConsequence(decision: string): string {
  return (
    (DECISION_CONSEQUENCES as Record<string, string>)[decision] ??
    'This decision resolves the divergence between the internal and external state. Review both states before confirming.'
  );
}

// ---- Safety vocabulary (AC4) ---------------------------------------------------

/** The wire safety tier — the read side emits exactly these two (no `caution`, trap #2). */
export type SuggestedSafety = 'safe' | 'risky';

/** Coerce a wire safety string to the two-tier union; anything else → `risky` (conservative). */
export function coerceSuggestedSafety(raw: string | null | undefined): SuggestedSafety {
  return raw === 'safe' ? 'safe' : 'risky';
}

/** The non-color safety label shown on each option's chip (AC4). */
export function safetyChipLabel(safety: SuggestedSafety): string {
  return safety === 'safe' ? 'SAFE' : 'RISKY';
}

/**
 * Map a suggested safety tier → a `StateName` state token driving the option's chip color + icon
 * (AC4: `safe` → success, `risky` → error). Reuses the story-2.3 signifier vocabulary so the chip is
 * never color-alone.
 */
export function safetyStateName(safety: SuggestedSafety): StateName {
  return safety === 'safe' ? 'success' : 'error';
}
