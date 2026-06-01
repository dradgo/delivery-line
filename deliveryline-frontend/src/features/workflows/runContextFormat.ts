/**
 * Story 2.16 (AC2, Task 1) — relative-time + absolute-UTC formatters for the
 * `RunContextStrip`. No new date dependency: relative durations use the built-in
 * `Intl.RelativeTimeFormat` (OQ-4), and the precise tooltip reuses
 * `RunIdentityRegion`'s UTC idiom (`YYYY-MM-DD HH:MM UTC`) so the two surfaces
 * format identically.
 */

/** Largest-unit-first division ladder for `Intl.RelativeTimeFormat`. */
const RELATIVE_TIME_DIVISIONS: readonly {
  readonly amount: number;
  readonly unit: Intl.RelativeTimeFormatUnit;
}[] = [
  { amount: 60, unit: 'second' },
  { amount: 60, unit: 'minute' },
  { amount: 24, unit: 'hour' },
  { amount: 7, unit: 'day' },
  { amount: 4.34524, unit: 'week' },
  { amount: 12, unit: 'month' },
  { amount: Number.POSITIVE_INFINITY, unit: 'year' },
];

const relativeTimeFormatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

/**
 * Format an ISO instant as a human relative time (e.g. `"12 minutes ago"`).
 * `now` is injectable for deterministic tests. Returns `null` for an absent or
 * unparseable value (the caller renders a "Not reported" placeholder).
 */
export function formatRelativeTime(
  iso: string | undefined,
  now: number = Date.now(),
): string | null {
  if (iso === undefined || iso === '') {
    return null;
  }
  const ts = Date.parse(iso);
  if (Number.isNaN(ts)) {
    return null;
  }
  let duration = (ts - now) / 1000; // seconds; negative = in the past
  for (const division of RELATIVE_TIME_DIVISIONS) {
    if (Math.abs(duration) < division.amount) {
      return relativeTimeFormatter.format(Math.round(duration), division.unit);
    }
    duration /= division.amount;
  }
  /* c8 ignore next — the `Infinity` terminal division always returns above. */
  return null;
}

/**
 * Render an ISO instant as a deterministic, timezone-independent UTC string for
 * the precise tooltip / `<time>` title — mirrors `RunIdentityRegion`. Returns
 * `null` for an absent or unparseable value.
 */
export function formatUtcTimestamp(iso: string | undefined): string | null {
  if (iso === undefined || iso === '') {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  // `toISOString()` is always UTC — stable across machines/timezones (audit parity).
  return `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}
