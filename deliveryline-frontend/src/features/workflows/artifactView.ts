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

/** The implementation-plan variant (Epic 3 scope) — stub-rendered in E2. */
export interface ImplementationPlanArtifactView extends ArtifactViewBase {
  readonly artifactType: 'implementationPlan';
}

/** The PR-output variant (Epic 3 scope) — stub-rendered in E2. */
export interface PrOutputArtifactView extends ArtifactViewBase {
  readonly artifactType: 'prOutput';
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
    typeof value.createdAt === 'string'
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

  if (value.artifactType === 'implementationPlan' || value.artifactType === 'prOutput') {
    return true;
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
    (value.stale === undefined || typeof value.stale === 'boolean') &&
    (value.superseded === undefined || typeof value.superseded === 'boolean') &&
    (value.truncated === undefined || typeof value.truncated === 'boolean')
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
 * (`superseded`/`stale`/`truncated`) exist only on the spec variant and have NO live
 * backend source yet (T4) — they fire ONLY from constructed fixtures, never fabricated
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
  if (artifact.artifactType === 'spec') {
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
 * Story 2.17 (AC9) — whether a Compare-Mode entry is eligible. The panel derives
 * this PURELY from the backend-reported `actions[]` + whether a comparable earlier
 * revision exists; it NEVER computes action eligibility locally (the backend owns it).
 *
 * `'compare'` is the anticipated backend action name (Compare Mode is Epic 4); a
 * comparable revision exists when a spec is past v1 (an earlier revision to diff
 * against). Live today the `useAllowedActions` stub is disabled → `actions` is
 * `undefined` → this returns `false` (the safe default). Tested via the mocked hook.
 */
export function canEnableCompare(
  actions: readonly string[] | undefined,
  hasComparableRevision: boolean,
): boolean {
  return actions !== undefined && actions.includes('compare') && hasComparableRevision;
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
