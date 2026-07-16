/**
 * Story 2.17 (AC1, AC8, AC12) — the frontend-owned `ArtifactView` contract.
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): the backend exposes NO `ArtifactView`
 * type and NO artifact-read endpoint. `schema.d.ts` carries only
 * `LatestArtifact { artifactType?, status?, version? }` (summary, no body, no id)
 * inside `WorkflowDetail.latestArtifacts[]`, and `useArtifact` is a disabled stub.
 * Therefore `ArtifactView` is a FRONTEND-OWNED type modeling the epic's intended
 * read model — the future artifact-read story populates it. No live source today;
 * the panel is presentational and every render is driven by constructed fixtures.
 *
 * The discriminator is `artifactType` — the runner-contracts schema-v1 values from
 * story 1.6 AC4 (`'spec' | 'implementationPlan' | 'prOutput'`). The panel dispatches
 * on it to a per-variant renderer.
 *
 * Field source map (what the future artifact-read endpoint will supply):
 *   • artifactType  — the artifact's discriminator (today only `LatestArtifact.artifactType`
 *                     exists as a summary; the read endpoint carries the body too).
 *   • artifactId    — the artifact's own public id (NO live source — `LatestArtifact`
 *                     has no id; the read endpoint supplies it).
 *   • title         — composed display title (e.g. "Specification — LIN-123 v3").
 *   • version       — `LatestArtifact.version` analog on the read model.
 *   • classification — the artifact's classification label (trusted metadata).
 *   • body          — the UNTRUSTED markdown body (runner output; NO live source —
 *                     `LatestArtifact` carries no body).
 *   • createdAt     — ISO timestamp the artifact was produced.
 *   • checksum      — optional content checksum (short-form rendered).
 *   • changeSummary — optional before/after pair (drives the SafeDiffRenderer slot).
 *   • stale/superseded/truncated — DORMANT backend flags that do NOT exist on any
 *                     live read model yet (tested via fixtures only, never fabricated).
 */

/** The shared metadata every artifact variant carries. */
interface ArtifactViewBase {
  readonly artifactId: string;
  readonly title: string;
  readonly version: number;
  readonly classification: string;
  /** UNTRUSTED markdown body — rendered EXCLUSIVELY through `SafeMarkdownRenderer`. */
  readonly body: string;
  /** ISO-8601 creation timestamp. */
  readonly createdAt: string;
  /**
   * Story 4.20 (OQ-2) — the immediately-prior version's public id (the lineage parent), the
   * Compare-Mode baseline. `null`/absent for a v1 artifact or a lineage root (the wire sends JSON
   * null, [[workflowdetail-wire-sends-null-not-undefined]]) — the Compare control then opens
   * against an unresolved baseline and the surface renders "no baseline available".
   */
  readonly parentArtifactId?: string | null;
}

/**
 * The spec variant (Epic 2 scope) — the only fully-rendered variant today. Carries
 * the spec-only slots (checksum, change-summary) plus the DORMANT state flags.
 */
export interface SpecArtifactView extends ArtifactViewBase {
  readonly artifactType: 'spec';
  readonly checksum?: string;
  /** Before/after pair driving the change-summary `SafeDiffRenderer` (AC2); `null`/absent hides it. */
  readonly changeSummary?: { before: string; after: string } | null;
  /** DORMANT (AC4) — backend flags with no live source yet; fixtures only. */
  readonly stale?: boolean;
  readonly superseded?: boolean;
  readonly truncated?: boolean;
}

/**
 * A single structured step of an implementation plan (story 3.26 AC2).
 *
 * Reconciliation R2: the runner-contracts schema-v1 sub-schema carries `steps` as a
 * bare `string[]`. This frontend-owned read model ENRICHES each step additively — a
 * plain schema string maps to `{ summary }`, with `detail`/`estimatedComplexity`
 * populated by a future artifact-read story when the backend supplies them.
 *
 * `summary` and `detail` are UNTRUSTED runner-derived text: `summary` renders as
 * React-escaped plain text (it cannot nest the block-level `SafeMarkdownRenderer`
 * inside an `AccordionTrigger` button — T-STEPHTML), `detail` renders through
 * `SafeMarkdownRenderer`.
 */
export interface ImplementationPlanStep {
  /** UNTRUSTED — the step's one-line summary (React-escaped plain text). */
  readonly summary: string;
  /** UNTRUSTED — optional longer detail (rendered via `SafeMarkdownRenderer`). */
  readonly detail?: string;
  /** Optional trusted-style complexity label (e.g. "M", "high"). */
  readonly estimatedComplexity?: string;
}

/**
 * A context reference linked from an implementation plan (story 3.26 AC2 / story 3.9
 * AC2). Internal refs (the approved spec artifact) navigate WITHIN DeliveryLine —
 * rendered as OQ-4 placeholder controls until live deep-links land (D5). External
 * refs (the linked GitHub repo + branch) open in a new tab ONLY when their `href`
 * passes `validateUrlScheme`.
 *
 * Reconciliation R2: schema-v1 carries `contextReferences` as a bare `string[]`; this
 * read model enriches them. `label` is UNTRUSTED runner-derived text (React-escaped);
 * `href` is validated before an `<a>` is emitted.
 */
export interface ImplementationPlanContextRef {
  readonly kind: 'spec' | 'repository' | 'branch' | 'other';
  /** UNTRUSTED — the human-readable label (React-escaped). */
  readonly label: string;
  /** Optional target; validated via `validateUrlScheme` before any `<a>` render. */
  readonly href?: string;
  /** `true` → in-app navigation (placeholder); `false` → external `<a target="_blank">`. */
  readonly internal: boolean;
}

/**
 * The implementation-plan variant (Epic 3 scope) — fully rendered by
 * `ImplementationPlanArtifactRenderer` (story 3.26). Carries the structured steps +
 * context references on top of the shared base, plus the DORMANT state flags mirrored
 * from the spec variant (D3) so the panel's stale/superseded/incomplete banners are
 * reachable for this variant too (fixtures only — no live source).
 */
export interface ImplementationPlanArtifactView extends ArtifactViewBase {
  readonly artifactType: 'implementationPlan';
  /**
   * Ordered structured steps (story 3.26 AC2); enriched from schema-v1 `string[]` (R2).
   * OPTIONAL (R3 reconciliation) — the live story-3a-9 `ArtifactDetail` wire DTO carries
   * NO structured steps (it flattens the plan into the markdown `body`), so a live
   * impl-plan artifact renders body-only; the rich step rendering is fixture-driven until
   * a future read-model story maps the schema strings into this slot.
   */
  readonly steps?: readonly ImplementationPlanStep[];
  /** Linked spec / repo / branch references (story 3.26 AC2, story 3.9 AC2). OPTIONAL (R3). */
  readonly contextReferences?: readonly ImplementationPlanContextRef[];
  /** DORMANT (D3) — backend flags with no live source yet; fixtures only. */
  readonly stale?: boolean;
  readonly superseded?: boolean;
  readonly truncated?: boolean;
}

/** The PR lifecycle state (story 3.15 AC1 — sourced from `integration_links.external_metadata.prState`). */
export type PrState = 'draft' | 'open' | 'merged' | 'closed';

/**
 * Whether `value` is one of the four valid {@link PrState} lifecycle values. Use this to narrow an
 * untyped wire value (`prState?: string | null`) before building a {@link PrLinkage} — an unexpected
 * value must degrade to "no linkage" (`null`), never be cast straight into a `PrLinkage` where it
 * would fail `isValidPrLinkage` and red the ENTIRE artifact view instead of degrading gracefully.
 */
export function isPrState(value: unknown): value is PrState {
  return value === 'draft' || value === 'open' || value === 'merged' || value === 'closed';
}

/**
 * BACKEND-TRUTH PR linkage (story 3.27 AC3 — the metadata-spoofing boundary). Sourced
 * from `integration_links` (NOT runner-emitted): the displayed PR reference + state come
 * from HERE, never from the runner-emitted artifact. `null` when the run has no linked PR
 * (the renderer then shows only the runner-emitted branch/commit). The reconciliation
 * pattern: a live read model omits unlinked fields → `prLinkage` is `null` (the
 * `WorkflowDetail` wire serializes nullable fields as JSON null — guard `!= null`).
 */
export interface PrLinkage {
  /** Authoritative PR reference `org/repo#42` (TRUSTED). */
  readonly prReference: string;
  /** Authoritative PR lifecycle state (TRUSTED). */
  readonly prState: PrState;
  /** Optional canonical PR URL from `integration_links` (preferred over a derived URL). */
  readonly prUrl?: string;
  /** Optional last-sync instant for the AC6 "(last synced X ago)" affordance. */
  readonly lastSyncedAt?: string;
  /** AC6 — `false` when GitHub was unreachable on the last reconcile (cached-state path). */
  readonly githubReachable?: boolean;
}

/**
 * The PR-output variant (Epic 3 scope, story 3.27) — fully rendered by
 * {@link PrOutputArtifactRenderer}. Carries the runner-emitted (UNTRUSTED) branch /
 * commit / diff on top of the shared base, plus the backend-truth `prLinkage` slot
 * (story 3.15 / AC3). `body` (inherited) is the UNTRUSTED markdown PR description.
 *
 * `diffReference` on the runner-result wire is a STORAGE REF, not the diff bytes — this
 * frontend-owned view carries the RESOLVED `diff` text because there is no live
 * artifact-read endpoint (Dev Notes "Central reconciliation"); do NOT invent a fetch.
 */
export interface PrOutputArtifactView extends ArtifactViewBase {
  readonly artifactType: 'prOutput';
  /** UNTRUSTED runner-emitted source branch (story 1.6 AC4). */
  readonly branch: string;
  /** UNTRUSTED runner-emitted commit SHA (7–40 hex on the wire; rendered short-form). */
  readonly commitSha: string;
  /** UNTRUSTED runner-emitted unified diff (the resolved `diffReference` content). */
  readonly diff: string;
  /** BACKEND-TRUTH PR linkage; `null`/absent when the run has no linked PR (AC3). */
  readonly prLinkage?: PrLinkage | null;
}

/**
 * The discriminated union the panel dispatches on. The discriminator `artifactType`
 * matches the runner-contracts schema-v1 values (story 1.6 AC4); an unknown value
 * (a future build's type) falls back to a safe "unsupported artifact type" render.
 */
export type ArtifactView = SpecArtifactView | ImplementationPlanArtifactView | PrOutputArtifactView;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function hasSharedArtifactFields(value: Record<string, unknown>): boolean {
  return (
    typeof value.artifactId === 'string' &&
    typeof value.title === 'string' &&
    typeof value.version === 'number' &&
    Number.isFinite(value.version) &&
    typeof value.classification === 'string' &&
    typeof value.body === 'string' &&
    typeof value.createdAt === 'string' &&
    // Story 4.20 (OQ-2) — the optional Compare-Mode baseline id: absent/null/string only.
    (value.parentArtifactId === undefined ||
      value.parentArtifactId === null ||
      typeof value.parentArtifactId === 'string')
  );
}

/** Whether `value` is a valid {@link ImplementationPlanStep} (string `summary`; optional fields typed). */
function isImplementationPlanStep(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.summary === 'string' &&
    (value.detail === undefined || typeof value.detail === 'string') &&
    (value.estimatedComplexity === undefined || typeof value.estimatedComplexity === 'string')
  );
}

/** Whether `value` is a valid {@link ImplementationPlanContextRef} (string `label` + boolean `internal`). */
function isImplementationPlanContextRef(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.label === 'string' &&
    typeof value.internal === 'boolean' &&
    (value.kind === 'spec' ||
      value.kind === 'repository' ||
      value.kind === 'branch' ||
      value.kind === 'other') &&
    (value.href === undefined || typeof value.href === 'string')
  );
}

/**
 * Whether `value` is a valid backend-truth {@link PrLinkage} slot. Accepts `undefined`
 * or `null` (an unlinked run — the wire sends JSON null), else requires a string
 * `prReference` + a `prState` from the four lifecycle values, with the optional
 * `prUrl`/`lastSyncedAt` typed as strings and `githubReachable` as a boolean when present.
 */
function isValidPrLinkage(value: unknown): boolean {
  if (value === undefined || value === null) {
    return true;
  }
  if (!isRecord(value)) {
    return false;
  }
  return (
    typeof value.prReference === 'string' &&
    isPrState(value.prState) &&
    (value.prUrl === undefined || typeof value.prUrl === 'string') &&
    (value.lastSyncedAt === undefined || typeof value.lastSyncedAt === 'string') &&
    (value.githubReachable === undefined || typeof value.githubReachable === 'boolean')
  );
}

/** Whether the optional spec/impl-plan dormant flags on `value` are each a boolean when present. */
function hasValidDormantFlags(value: Record<string, unknown>): boolean {
  return (
    (value.stale === undefined || typeof value.stale === 'boolean') &&
    (value.superseded === undefined || typeof value.superseded === 'boolean') &&
    (value.truncated === undefined || typeof value.truncated === 'boolean')
  );
}

/**
 * Runtime guard for future artifact-read data. The hook is a disabled stub today,
 * but when it becomes live this prevents partial summary shapes from being cast into
 * renderer props and crashing on missing body/metadata fields.
 */
export function isArtifactView(value: unknown): value is ArtifactView {
  if (!isRecord(value) || !hasSharedArtifactFields(value)) {
    return false;
  }

  if (value.artifactType === 'implementationPlan') {
    // R3 — steps/contextReferences are OPTIONAL (the live wire DTO omits them); when
    // present each must be a well-formed array, so a partial summary-like cast is still
    // rejected before it reaches the renderer.
    const stepsValid =
      value.steps === undefined ||
      (Array.isArray(value.steps) && value.steps.every(isImplementationPlanStep));
    const refsValid =
      value.contextReferences === undefined ||
      (Array.isArray(value.contextReferences) &&
        value.contextReferences.every(isImplementationPlanContextRef));
    return stepsValid && refsValid && hasValidDormantFlags(value);
  }

  if (value.artifactType === 'prOutput') {
    // Story 3.27 — the prOutput branch now validates the runner-emitted string fields
    // (branch/commitSha/diff) + the backend-truth prLinkage shape, so a partial summary
    // can no longer be cast into the renderer props and crash on a missing diff/ref.
    return (
      typeof value.branch === 'string' &&
      typeof value.commitSha === 'string' &&
      typeof value.diff === 'string' &&
      isValidPrLinkage(value.prLinkage)
    );
  }

  if (value.artifactType !== 'spec') {
    return false;
  }

  const changeSummary = value.changeSummary;
  const hasValidChangeSummary =
    changeSummary === undefined ||
    changeSummary === null ||
    (isRecord(changeSummary) &&
      typeof changeSummary.before === 'string' &&
      typeof changeSummary.after === 'string');

  return (
    hasValidChangeSummary &&
    (value.checksum === undefined || typeof value.checksum === 'string') &&
    hasValidDormantFlags(value)
  );
}

/**
 * Human-readable label for an artifact type — used by the type `Badge` and the
 * panel's unsupported-type fallback. Mirrors the `RunContextStrip` display-label
 * idiom (`implementationPlan` → `implementation-plan`). Lives in this `.ts` sibling
 * so renderers/panels can import it without tripping the `react-refresh` rule on a
 * `.tsx` exporting a non-component function (`frontend-react-refresh-no-fn-exports`).
 */
export function artifactTypeLabel(artifactType: string): string {
  switch (artifactType) {
    case 'spec':
      return 'spec';
    case 'implementationPlan':
      return 'implementation-plan';
    case 'prOutput':
      return 'pr-output';
    default:
      return artifactType;
  }
}

/** A derived section anchor (AC8) — a heading the anchor nav can jump to. */
export interface SectionAnchor {
  /** Slugified, de-duplicated id (best-effort scroll target; NOT a renderer-emitted id, T-ANCHOR). */
  readonly id: string;
  /** The heading text, used both as the visible label AND the scroll text-match key. */
  readonly text: string;
  /** Heading depth 1–6 (the count of leading `#`). */
  readonly level: number;
}

/**
 * Story 2.17 (AC4) — the single state the `ArtifactReviewPanel` renders. Mirrors
 * `RunContextState`/`QueueState`: exactly one branch is reachable per call, and the
 * value is exposed as `data-artifact-panel-state` for tests.
 *
 * `superseded` carries the AC4 "conflicting / superseded" meaning (the backend
 * explicitly marked the artifact conflicting — the strongest treatment); `stale` is
 * the weaker "a newer version exists" treatment; `incomplete` is the partial/truncated
 * treatment. `empty` is "not-yet-generated".
 */
export type ArtifactPanelState =
  | 'error'
  | 'loading'
  | 'empty'
  | 'superseded'
  | 'stale'
  | 'incomplete'
  | 'default';

export interface ResolveArtifactPanelStateInput {
  readonly isError: boolean;
  /** Genuinely fetching (NOT the disabled-stub idle state, which maps to `empty`). */
  readonly isLoading: boolean;
  /** The resolved artifact; `undefined` today (disabled `useArtifact` stub) → `empty`. */
  readonly artifact: ArtifactView | undefined;
}

/**
 * Pure state precedence (AC4): error → loading → empty/not-yet-generated →
 * conflicting/superseded → stale → incomplete → default. The dormant flags
 * (`superseded`/`stale`/`truncated`) exist on the spec AND implementation-plan variants
 * (D3) and have NO live backend source yet (T4) — they fire ONLY from constructed
 * fixtures, never fabricated
 * data. The only states reachable from the live route today are `loading → empty`
 * (the disabled `useArtifact` stub never resolves) and `error`.
 */
export function resolveArtifactPanelState({
  isError,
  isLoading,
  artifact,
}: ResolveArtifactPanelStateInput): ArtifactPanelState {
  if (isError) {
    return 'error';
  }
  if (isLoading) {
    return 'loading';
  }
  if (artifact === undefined) {
    return 'empty';
  }
  // The dormant flags live on the spec AND implementation-plan variants (D3) and have
  // NO live backend source yet (T4) — they fire ONLY from constructed fixtures.
  if (artifact.artifactType === 'spec' || artifact.artifactType === 'implementationPlan') {
    if (artifact.superseded === true) {
      return 'superseded';
    }
    if (artifact.stale === true) {
      return 'stale';
    }
    if (artifact.truncated === true) {
      return 'incomplete';
    }
  }
  return 'default';
}

/**
 * Story 2.17 (AC9) / Story 4.20 (AC9) — whether a Compare-Mode entry is eligible.
 * The panel derives this PURELY from the backend-reported `actions[]` + whether a
 * comparable earlier revision exists; it NEVER computes action eligibility locally
 * (the backend owns it).
 *
 * Story 4.20 renamed the checked literal from the story-2.17 anticipated `'compare'`
 * to the registered backend action `'enter_compare_mode'` (the `AllowedAction` value
 * surfaced by `WorkflowInspectionService.appendCompareOverlay`). A comparable revision
 * exists when the artifact is past v1 (`hasComparableRevision`) — the FE re-gates the
 * broad backend action on the concrete per-artifact version so a v1 artifact never
 * offers a compare with no baseline.
 */
export function canEnableCompare(
  actions: readonly string[] | undefined,
  hasComparableRevision: boolean,
): boolean {
  return actions !== undefined && actions.includes('enter_compare_mode') && hasComparableRevision;
}

/** Whether an artifact has an earlier revision Compare Mode could diff against (spec v2+). */
export function hasComparableRevision(artifact: ArtifactView | undefined): boolean {
  return artifact !== undefined && artifact.version > 1;
}

/** Slugify heading text → a stable id (lowercase, non-alphanumerics → single hyphen). */
function slugify(text: string): string {
  const slug = text
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug.length > 0 ? slug : 'section';
}

const ATX_HEADING = /^(#{1,6})\s+(.+?)(?:\s+#+)?\s*$/;
const FENCE = /^\s*(`{3,}|~{3,})/;

interface OpenFence {
  readonly marker: '`' | '~';
  readonly length: number;
}

/**
 * Story 2.17 (AC8 / T-ANCHOR) — derive the section-anchor list from the RAW markdown
 * `source`. `SafeMarkdownRenderer`'s custom `h1`/`h2`/… components pass only
 * `children` (they strip `id`/slug — verified at `SafeMarkdownRenderer.tsx:317`), so
 * anchors CANNOT target renderer-emitted heading ids. We parse ATX headings
 * (`#`/`##`/`###` … up to `######`) directly from `source`, slugify the text → `id`,
 * and de-duplicate collisions by appending `-1`, `-2`, …. Headings inside fenced code
 * blocks (``` ``` ``` / `~~~`) are ignored — they are inert code, not document structure.
 *
 * Pure + deterministic — unit-tested independently of the renderer.
 */
export function deriveSectionAnchors(source: string): SectionAnchor[] {
  const anchors: SectionAnchor[] = [];
  const seen = new Map<string, number>();
  let openFence: OpenFence | null = null;

  for (const rawLine of source.split('\n')) {
    const fenceMatch = FENCE.exec(rawLine);
    if (fenceMatch !== null) {
      const fence = fenceMatch[1];
      const marker = fence?.[0]; // '`' or '~'
      if ((marker === '`' || marker === '~') && fence !== undefined) {
        const length = fence.length;
        if (openFence === null) {
          openFence = { marker, length };
        } else if (openFence.marker === marker && length >= openFence.length) {
          openFence = null;
        }
      }
      continue;
    }
    if (openFence !== null) {
      continue; // inside a fenced code block — ignore everything
    }
    const headingMatch = ATX_HEADING.exec(rawLine);
    if (headingMatch === null) {
      continue;
    }
    const level = headingMatch[1]?.length ?? 1;
    const text = (headingMatch[2] ?? '').trim();
    const base = slugify(text);
    const priorCount = seen.get(base);
    const id = priorCount === undefined ? base : `${base}-${priorCount}`;
    seen.set(base, (priorCount ?? 0) + 1);
    anchors.push({ id, text, level });
  }

  return anchors;
}
