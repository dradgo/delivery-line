/**
 * Story 3.31 (Task 1 / AC1, AC2, AC4, AC5, AC6) — the frontend-owned PR-linkage
 * read model shared by BOTH PR-linkage surfaces (the `RunContextStrip` and the
 * `RunReviewQueueItem`). Pure `.ts` (no JSX) so a non-component export does NOT trip
 * the `frontend-react-refresh-no-fn-exports` ESLint gate.
 *
 * ── DORMANT (READ FIRST) ─────────────────────────────────────────────────────
 * There is NO live PR-linkage source on the wire today. `WorkflowDetail` /
 * `WorkflowSummary` (`schema.d.ts`) carry `linkedTicket` (Linear) but NO
 * `integrationLinks` / `prState` / `prReference`, and `toRunContextView` already
 * hardcodes `branchOrCommitReference: undefined`. So this whole feature is built +
 * tested via CONSTRUCTED FIXTURES and never fabricated from live data — the same
 * discipline 3.26/3.27 use for the frontend-owned `ArtifactView` and that 2.15/2.16
 * use for their dormant `summary`/`branchOrCommitReference` fields.
 *
 * INTENDED FUTURE SOURCE: story 3.15 writes `integration_links` rows of type
 * `github_pr` with `externalRef` = canonical PR ref (`owner/repo#number`),
 * `externalMetadata.prState` (`draft`/`open`/`merged`/`closed`), and `lastSyncAt`. A
 * future read-model story (6.9 / a 3.15-surfacing increment) projects those onto the
 * workflow-detail / summary read models. When it lands, only the mapper's optional
 * read flips from `undefined` to live — the components are unchanged.
 *
 * TRUST BOUNDARY (story 3.27 AC3 / 3.31 AC6 — NFR20 wrong-link prevention):
 *   • `prReference` + `prState` + `prUrl` + `lastSyncedAt` come from
 *     `integration_links` (BACKEND TRUTH) — authoritative.
 *   • `branch` + `commitSha` are runner-emitted (UNTRUSTED) — rendered as escaped
 *     text; the owner/repo identity for their links is parsed from the TRUSTED
 *     `prReference`, never inferred from the runner-emitted branch/commit strings.
 */
import type { PrState } from './artifactView';

export type { PrState };

/**
 * The single shared shape both PR-linkage surfaces consume. `prReference` +
 * `prState` are backend-truth (required); the rest are optional and may arrive as
 * JSON `null` on the wire (guard `!= null`, see {@link presentOrUndefined}).
 */
export interface PrLinkageView {
  /** Canonical `org/repo#42` (BACKEND TRUTH — authoritative). */
  readonly prReference: string;
  /** PR lifecycle state (BACKEND TRUTH). */
  readonly prState: PrState;
  /** Canonical PR URL when present (BACKEND TRUTH) — preferred verbatim over a derived URL. */
  readonly prUrl?: string | undefined;
  /** Source branch (runner-emitted, UNTRUSTED). */
  readonly branch?: string | undefined;
  /** Commit SHA, 7–40 hex (runner-emitted, UNTRUSTED) — rendered short-form. */
  readonly commitSha?: string | undefined;
  /** Last-sync instant (ISO-8601 UTC) from `integration_links.last_sync_at` (BACKEND TRUTH). */
  readonly lastSyncedAt?: string | undefined;
  /** `false` ⇒ GitHub was unreachable on the last reconcile → cached-state affordance (AC5). */
  readonly githubReachable?: boolean | undefined;
}

/**
 * AC4 — age (ms) past which a PR's cached state is treated as a "stale" sync.
 * Deliberately a SEPARATE constant from `runContextView`'s 10-minute
 * {@link RUN_STALE_THRESHOLD_MS}: run-activity staleness (is the runner alive?) and
 * PR-sync freshness (how old is the cached GitHub state?) are different concepts.
 */
export const PR_LINKAGE_STALE_THRESHOLD_MS = 5 * 60 * 1000;

/**
 * Whether the PR's cached state is stale: `lastSyncedAt` older than
 * {@link PR_LINKAGE_STALE_THRESHOLD_MS}. Mirrors `runContextView.isStale` — `null`
 * boundary guard included; an absent / unparseable timestamp is treated as NOT stale
 * (no affordance rather than a false alarm). `now` is injectable for tests.
 */
export function isPrLinkageStale(
  lastSyncedAt: string | undefined,
  now: number = Date.now(),
): boolean {
  if (lastSyncedAt === undefined) {
    return false;
  }
  const ts = Date.parse(lastSyncedAt);
  if (Number.isNaN(ts)) {
    return false;
  }
  return now - ts > PR_LINKAGE_STALE_THRESHOLD_MS;
}

/**
 * The intended future wire shape of a `github_pr` `integration_links` row (story
 * 3.15 AC1). Frontend-owned because the live `WorkflowDetail`/`WorkflowSummary` do
 * not carry it yet — the mappers read it off an optional extension slot and produce
 * `undefined` today (DORMANT); fixtures inject it.
 */
export interface GitHubPrLinkWire {
  /**
   * The `integration_links.type` discriminator. The future `integrationLinks` array
   * carries every link type (e.g. `linear`, `github_pr`); the mapper filters for
   * `github_pr`, so this is a plain `string` (a narrowed literal would make the
   * filter a no-op + trip `no-unnecessary-condition`).
   */
  readonly type: string;
  /** Canonical PR ref `owner/repo#number` (`external_ref`). */
  readonly externalRef: string;
  readonly externalMetadata?:
    | {
        readonly prState?: PrState | null;
        readonly prUrl?: string | null;
        /** Runner-emitted (UNTRUSTED). */
        readonly branch?: string | null;
        /** Runner-emitted (UNTRUSTED). */
        readonly commitSha?: string | null;
        /** `false` ⇒ GitHub unreachable on the last reconcile (AC5). */
        readonly githubReachable?: boolean | null;
      }
    | null
    | undefined;
  /** ISO-8601 UTC `last_sync_at`. */
  readonly lastSyncAt?: string | null | undefined;
}

/** Coalesce an optional/blank/`null` wire string to a present value or `undefined`. */
function presentOrUndefined(value: string | null | undefined): string | undefined {
  return value != null && value.trim() !== '' ? value : undefined;
}

const PR_STATES: ReadonlySet<string> = new Set(['draft', 'open', 'merged', 'closed']);

/**
 * Project the FIRST `github_pr` `integration_links` row onto the shared
 * {@link PrLinkageView}, or `undefined` when there is no usable linkage. Returns
 * `undefined` (NOT a partial view) unless BOTH backend-truth required fields are
 * present + valid — `prReference` (`owner/repo#number`-shaped, validated downstream
 * by `parsePrReference`) and a recognized `prState` — so a malformed row never
 * surfaces a broken cluster (AC8 graceful absence).
 */
export function toPrLinkageView(
  links: readonly GitHubPrLinkWire[] | null | undefined,
): PrLinkageView | undefined {
  if (links == null) {
    return undefined;
  }
  const link = links.find((row) => row.type === 'github_pr');
  if (link === undefined) {
    return undefined;
  }
  const prReference = presentOrUndefined(link.externalRef);
  const prStateRaw = link.externalMetadata?.prState;
  if (prReference === undefined || prStateRaw == null || !PR_STATES.has(prStateRaw)) {
    return undefined;
  }
  const meta = link.externalMetadata;
  return {
    prReference,
    prState: prStateRaw,
    prUrl: presentOrUndefined(meta?.prUrl),
    branch: presentOrUndefined(meta?.branch),
    commitSha: presentOrUndefined(meta?.commitSha),
    lastSyncedAt: presentOrUndefined(link.lastSyncAt),
    // Only a literal `false` flips to the cached-state affordance; absent ⇒ reachable.
    githubReachable: meta?.githubReachable === false ? false : undefined,
  };
}
