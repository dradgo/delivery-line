/**
 * Story 2.19 (AC1, AC2, AC3, AC5, AC6, AC7, AC13) — the frontend-owned
 * `ApprovalDecisionView` contract + pure view-model helpers + the reason-code
 * mapping table.
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): 2.19 is the data-layer story — it lights
 * up THREE live seams (`useAllowedActions` flips live, `useApproveSpec` relocated
 * live, `useRejectSpec` authored live). The dormancy boundary narrows to ONE missing
 * field: `artifactId` (no live read model exposes the spec's id). So the bar reads
 * REAL allowed-actions + version stamp and renders gating/staleness for real, but it
 * cannot FIRE a mutation until an `artifactId` source ships — until then the view
 * resolves to `blocked` ("specification not yet available for a decision"). Never
 * fabricate an `artifactId` (T-ARTIFACTID).
 *
 * Two epic ACs reference fields ABSENT from the live schema → frontend-derived /
 * DORMANT, fed by fixtures:
 *   • AC5(c) `disabledActions: { [action]: reasonCode }` — `AllowedActions` carries
 *     ONLY `actions[]` + `versionStamp`, never a reason code. The reason-map table
 *     below holds the contract; live the bar shows the generic blocked explanation.
 *   • AC13 `pendingClarifications` — absent from `WorkflowDetail`; the "{N} pending"
 *     message is fixture-driven, live an absent `approve_spec` shows generic blocked.
 *
 * All helpers/maps live in this `.ts` sibling (NOT the `.tsx`) so the bar/container
 * import them without tripping `react-refresh/only-export-components`
 * (`frontend-react-refresh-no-fn-exports`).
 *
 * @see ./clarificationView.ts — the story-2.18 dormant/live view-model this mirrors.
 */
import type { WorkflowDetail } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';

/** The composite's variant mode (AC1). `spec_approval` is fully implemented in E2; the others are E3/E4 stubs. */
export type ApprovalBarMode = 'spec_approval' | 'implementation_review' | 'recovery_operator';

/** The composite's layout variant (AC4). */
export type ApprovalBarLayout = 'sticky_footer' | 'inline_section';

/** The 8 render states (AC3), stamped onto `data-approval-bar-state`. */
export type ApprovalBarState =
  | 'ready'
  | 'blocked'
  | 'stale'
  | 'disabled'
  | 'submitting'
  | 'success'
  | 'error'
  | 'locked';

/**
 * The decision-action wire union. The backend reports raw strings in
 * `AllowedActions.actions[]`; unrecognized values coerce to `'unknown'` and are
 * dropped (forward-compat, UX-DR6 — mirrors 2.18's `unknown`-sentinel discipline).
 *
 * Story 3.30 adds `retry` — the recovery_operator mode gates its primary action on
 * this being present in the live allowed-actions (the backend reports it for a
 * `Failed` run that can re-execute; `AllowedAction.RETRY`). Scope discipline (AC5):
 * NO deeper recovery actions (reconcile/resume/rerun) are recognized here in E3.
 */
export type DecisionAction =
  | 'approve_spec'
  | 'reject_spec'
  | 'answer_clarification'
  | 'retry'
  // Story 3.28 (R7) — the developer technical-review actions. Wire values from
  // `AllowedAction.java` (`accept_implementation` / `reject_implementation` /
  // `takeover_workflow`); they MUST also live in `KNOWN_ACTIONS` below or `coerceAction`
  // drops them to `'unknown'` and the implementation_review bar is permanently `blocked`.
  | 'accept_implementation'
  | 'reject_implementation'
  | 'takeover_workflow'
  // Story 3f-4 (advisory split-proposal channel) — the three governed split actions. The
  // matrix advertises `request_split` when NO proposal is open, and `repropose_split` +
  // `continue_as_single` when one IS open. They MUST also live in `KNOWN_ACTIONS` below or
  // `coerceAction` drops them to `'unknown'` and the split affordances never render.
  | 'request_split'
  | 'repropose_split'
  | 'continue_as_single'
  | 'unknown';

/** The rework taxonomy (story 2.10) — the schema's UPPERCASE wire enum (T-TAGGED-UPPERCASE). */
export type TaggedFeedback = components['schemas']['RejectSpecRequest']['taggedFeedback'];

/**
 * Story 3.28 (R5) — the developer rejection taxonomy. The generated `taggedFeedback`
 * union carries ALL 8 wire values (spec + developer); the backend enforces the role
 * subset (`INVALID_REJECTION_TAXONOMY`). The UI offers ONLY the 5 developer values via
 * `DEVELOPER_TAGGED_FEEDBACK_OPTIONS` — distinct from the spec `TaggedFeedback` set.
 */
export type DeveloperTaggedFeedback =
  components['schemas']['RejectImplementationRequest']['taggedFeedback'];

/**
 * Story 3b-4 — the reviewer role the single operator carries at `WaitingForReview`.
 *
 * Per the user's "one user, multiple roles for now" decision, the operator IS the
 * `developer` at this state: the `implementation_review` container requests
 * allowed-actions with this role (so the matrix returns accept/reject/takeover instead of
 * the default `product_reviewer`'s `[view_only]`) and sends it as `reviewerRole` on the
 * three decisions (which the REST endpoints gate to equal `"developer"`).
 *
 * Kept behind one constant (isolated, here in the `.ts` sibling — not the `.tsx`, per
 * `frontend-react-refresh-no-fn-exports`) so the deferred story-2.13 header-based role
 * attribution has exactly ONE swap point. Do NOT thread a role-provider abstraction now.
 */
export const DEVELOPER_REVIEWER_ROLE = 'developer';

/**
 * The actor role the `recovery_operator` bar requests its allowed-actions as. The backend FAILED
 * matrix (WorkflowInspectionService.getAllowedActions) returns `retry` ONLY for `workflow_owner`;
 * every other role gets `[view_only, view_diagnostics]`. The recovery bar must therefore request
 * `workflow_owner` or it renders "View only" even when the run is retryable (the header's
 * `nextSafeAction=retry` comes from the role-agnostic RecoveryService.describe, so the two
 * disagreed). One constant, mirroring {@link DEVELOPER_REVIEWER_ROLE}.
 */
export const RECOVERY_OPERATOR_ROLE = 'workflow_owner';

/** The live `AllowedActions.versionStamp` parts the bar consumes (AC6). */
export interface ApprovalVersionStamp {
  readonly currentContextBundleVersion?: number | null;
  readonly currentSpecArtifactVersion?: number | null;
  readonly lastEventId?: string | null;
  readonly workflowState: string;
}

/** The persisted post-submit decision outcome (AC9). */
export interface DecisionSummary {
  /**
   * Which decision landed. `approved`/`rejected` are the spec-approval outcomes;
   * story 3.28 adds the developer-review outcomes `accepted` / `takenover` (reject
   * reuses `rejected`).
   */
  readonly decision: 'approved' | 'rejected' | 'accepted' | 'takenover';
  /** The resulting workflow state from `WorkflowStateChangeResponse.currentState`. */
  readonly resultingState: string;
  /** ISO timestamp the decision was recorded (pinned in tests). */
  readonly decidedAt: string;
  /** Who recorded it (may be UNTRUSTED if echoed from agent output — sanitize on render). */
  readonly actor?: string | undefined;
  /** The linked event reference for audit (AC9) — `WorkflowStateChangeResponse.correlationId`. */
  readonly correlationId?: string | undefined;
  /**
   * Story 3.28 (R9 / AC7) — takeover-only: the preserved GitHub PR reference
   * (`TakeoverResponse.preservedPrReference`, e.g. `org/repo#42`) the post-takeover bar
   * links to. UNTRUSTED runner-derived text — must pass `githubRef.ts` URL hardening
   * before becoming an `href`. `null`/absent → render the read-only label WITHOUT a link.
   */
  readonly preservedPrReference?: string | undefined;
  /** Story 3.28 (R9) — takeover-only informational counts (null/absent on idempotent replay). */
  readonly cancelledInFlightCount?: number | undefined;
  readonly cancelledQueuedCount?: number | undefined;
}

/**
 * The resolved prop the presentational bar consumes. The container maps live
 * `useAllowedActions` + `useWorkflowDetail` into this; tests construct it directly
 * (the 2.17/2.18 fixture discipline).
 */
export interface ApprovalDecisionView {
  readonly workflowRunId: string;
  readonly mode: ApprovalBarMode;
  readonly layout: ApprovalBarLayout;
  /** Backend-reported allowed actions (coerced + unknowns dropped). */
  readonly actions: DecisionAction[];
  readonly versionStamp?: ApprovalVersionStamp | undefined;
  /** Backend `currentState` (for the consequence hint + stale comparison). */
  readonly currentState: string;
  /** The decision-context line (AC2) — e.g. "Approve specification v3 by Alex". */
  readonly decisionContextLabel: string;
  /**
   * THE dormancy boundary (T-ARTIFACTID): the spec artifact id the mutation fires
   * against. No live read model exposes it; when unresolved the view is `blocked`.
   */
  readonly artifactId?: string | undefined;
  /** DORMANT (AC13) — fixture-driven count of clarifications blocking approval. */
  readonly pendingClarifications?: number | undefined;
  /** DORMANT (AC5c) — reason codes per withheld action; live the backend supplies none. */
  readonly disabledReasons?: Partial<Record<DecisionAction, string>> | undefined;
  /** The persisted post-submit / prior-decision outcome (AC9). */
  readonly lastDecision?: DecisionSummary | undefined;
}

/** The free-form + tagged rejection draft captured by the rationale dialog (AC8). */
export interface RejectionDraft {
  /** Reviewer-authored free text — pass through, NEVER log (T-LOG-PII). */
  readonly reasonText: string;
  readonly taggedFeedback: TaggedFeedback;
}

/** Story 3.28 — the developer rejection draft (the developer taxonomy variant of {@link RejectionDraft}). */
export interface ImplementationRejectionDraft {
  /** Reviewer-authored free text — pass through, NEVER log (T-LOG-PII). */
  readonly reasonText: string;
  readonly taggedFeedback: DeveloperTaggedFeedback;
}

/** The two version ints a mutation request carries (AC6) — there is no single stamp field (T-VERSIONSTAMP). */
export interface ExpectedVersions {
  readonly expectedArtifactVersion: number;
  readonly expectedContextBundleVersion: number;
}

/** Mutation status the bar renders feedback for. */
export type ApprovalMutationStatus = 'idle' | 'pending' | 'success' | 'error';

/** The live mutation state driving `submitting`/`success`/`error`/`stale`. */
export interface ApprovalMutationState {
  readonly status: ApprovalMutationStatus;
  /** Stable ProblemDetails `code` on failure (never a raw message — T-LOG-PII). */
  readonly errorCode?: string | undefined;
}

/** Local UI overlay the container controls (prior-decision lock, UI-side stale). */
export interface ApprovalLocalUi {
  /** A decision was ALREADY made before load → read-only `locked` view (AC3). */
  readonly locked?: boolean | undefined;
  /** UI-side stale detection (version stamp moved) independent of a mutation error. */
  readonly stale?: boolean | undefined;
}

/** The known wire action values; anything else coerces to `'unknown'` (UX-DR6). */
const KNOWN_ACTIONS: ReadonlySet<string> = new Set<DecisionAction>([
  'approve_spec',
  'reject_spec',
  'answer_clarification',
  'retry',
  // Story 3.28 (R7) — the developer technical-review actions.
  'accept_implementation',
  'reject_implementation',
  'takeover_workflow',
  // Story 3f-4 — the advisory split-proposal channel actions.
  'request_split',
  'repropose_split',
  'continue_as_single',
  'unknown',
]);

/**
 * Coerce one raw backend action string to the known union — an unrecognized value
 * degrades to `'unknown'` rather than crashing the view (forward-compat, UX-DR6).
 */
export function coerceAction(action: string): DecisionAction {
  return KNOWN_ACTIONS.has(action) ? (action as DecisionAction) : 'unknown';
}

/**
 * Map the live `AllowedActions.actions[]` (raw strings) into the typed union,
 * coercing unknowns to `'unknown'` and DROPPING them (the bar never renders an
 * action it does not understand — UX-DR6 forward-compat).
 */
export function normalizeActions(actions: readonly string[] | undefined): DecisionAction[] {
  if (actions === undefined) {
    return [];
  }
  const result: DecisionAction[] = [];
  for (const raw of actions) {
    const coerced = coerceAction(raw);
    if (coerced !== 'unknown') {
      result.push(coerced);
    }
  }
  return result;
}

/**
 * Compute the single PRIMARY-styled action (AC7). In `spec_approval` the affirmative
 * decision (`approve_spec`) is the only primary; `reject_spec` is ALWAYS a secondary
 * control (AC2), so it is never returned as primary. `approve_spec` therefore "wins"
 * trivially. Returns `null` when no affirmative primary is available → the bar renders
 * `blocked` rather than promoting an unavailable action (T-ONE-PRIMARY).
 */
export function resolvePrimaryAction(actions: DecisionAction[]): DecisionAction | null {
  return actions.includes('approve_spec') ? 'approve_spec' : null;
}

/**
 * Derive the two expected-version ints the mutation request requires (AC6) from the
 * live version stamp. Both `currentSpecArtifactVersion` + `currentContextBundleVersion`
 * must be present integers; if either is null/absent the request CANNOT be built →
 * returns `null`, which contributes to `blocked` (T-VERSIONSTAMP).
 */
export function deriveExpectedVersions(
  versionStamp: ApprovalVersionStamp | undefined,
): ExpectedVersions | null {
  if (versionStamp === undefined) {
    return null;
  }
  const { currentSpecArtifactVersion, currentContextBundleVersion } = versionStamp;
  if (
    typeof currentSpecArtifactVersion !== 'number' ||
    typeof currentContextBundleVersion !== 'number'
  ) {
    return null;
  }
  return {
    expectedArtifactVersion: currentSpecArtifactVersion,
    expectedContextBundleVersion: currentContextBundleVersion,
  };
}

/**
 * Whether the bar's loaded view is stale against a freshly-fetched stamp (AC6) —
 * UI-side detection independent of a 409. A new `lastEventId`, a higher
 * `currentSpecArtifactVersion`, OR a higher `currentContextBundleVersion` means the run
 * moved since the bar loaded (a context-bundle bump alone still invalidates the
 * `expectedContextBundleVersion` the mutation would send).
 */
export function isStaleAgainst(
  view: ApprovalDecisionView,
  latest: ApprovalVersionStamp | undefined,
): boolean {
  if (view.versionStamp === undefined || latest === undefined) {
    return false;
  }
  const loaded = view.versionStamp;
  if (
    loaded.lastEventId != null &&
    latest.lastEventId != null &&
    loaded.lastEventId !== latest.lastEventId
  ) {
    return true;
  }
  if (
    typeof loaded.currentSpecArtifactVersion === 'number' &&
    typeof latest.currentSpecArtifactVersion === 'number' &&
    latest.currentSpecArtifactVersion > loaded.currentSpecArtifactVersion
  ) {
    return true;
  }
  if (
    typeof loaded.currentContextBundleVersion === 'number' &&
    typeof latest.currentContextBundleVersion === 'number' &&
    latest.currentContextBundleVersion > loaded.currentContextBundleVersion
  ) {
    return true;
  }
  return false;
}

/**
 * AC5(c) reason-code → localized-text table (DORMANT — fed by fixtures until the
 * backend supplies `disabledActions`). The live fallback is the generic explanation
 * so a withheld control NEVER renders bare (T-NO-DISABLEDACTIONS).
 */
const DISABLED_REASON_TEXT: Readonly<Record<string, string>> = {
  CLARIFICATIONS_PENDING:
    'Clarifications are pending incorporation — approval is blocked until they are resolved.',
  ARTIFACT_UNAVAILABLE: 'The specification is not yet available for a decision.',
  ROLE_RESTRICTED: 'Your recorded role cannot take this action in the current state.',
  TERMINAL_STATE: 'This run has reached a terminal state — no decision is available.',
};

/** The generic, never-bare fallback explanation (live posture — AC5c). */
export const GENERIC_DISABLED_REASON = 'No decision is available in the current state.';

/** The blocked explanation when the spec is not yet decidable (T-ARTIFACTID). */
export const ARTIFACT_UNAVAILABLE_REASON = 'The specification is not yet available for a decision.';

/**
 * Resolve a reason code to its localized text (AC5c). An unknown/absent code degrades
 * to the generic explanation — a withheld control is never explained by a bare/empty
 * string (T-NO-DISABLEDACTIONS).
 */
export function mapDisabledReason(reasonCode: string | undefined): string {
  if (reasonCode === undefined) {
    return GENERIC_DISABLED_REASON;
  }
  return DISABLED_REASON_TEXT[reasonCode] ?? GENERIC_DISABLED_REASON;
}

/** The AC13 "{N} clarifications pending incorporation — approval blocked" line (DORMANT). */
export function pendingClarificationsMessage(count: number): string {
  const noun = count === 1 ? 'clarification' : 'clarifications';
  return `${count} ${noun} pending incorporation — approval blocked`;
}

/**
 * The static immediate-consequence hint (AC2), keyed by mode + action. Derived from a
 * fixed map, NOT live state (Task 4). Stub modes carry no consequence (no live action).
 */
const CONSEQUENCE_HINTS: Readonly<
  Record<ApprovalBarMode, Partial<Record<DecisionAction, string>>>
> = {
  spec_approval: {
    approve_spec: 'Approval will transition the run to Executing.',
    reject_spec: 'Rejection sends the specification back for rework.',
    // Story 3f-4 — advisory split channel (front-half only; nothing is committed this epic).
    request_split: 'Requests an advisory split proposal; nothing is committed.',
    continue_as_single: 'Dismisses the proposal and keeps one ticket.',
    repropose_split: 'Re-runs the proposal with your feedback.',
  },
  // Story 3.28 (AC2) — the short inline hints; takeover's FULL consequence text lives
  // in the confirm dialog (`CONFIRMATION_CATALOG.takeoverWorkflow`).
  implementation_review: {
    accept_implementation: 'Accepting advances the run past technical review.',
    reject_implementation: 'Rejection sends the implementation back for rework.',
    takeover_workflow: 'Taking over stops orchestration and hands the run to a developer.',
    // Story 3f-4 — advisory split channel (front-half only; nothing is committed this epic).
    request_split: 'Requests an advisory split proposal; nothing is committed.',
    continue_as_single: 'Dismisses the proposal and keeps one ticket.',
    repropose_split: 'Re-runs the proposal with your feedback.',
  },
  // Story 3.30 (AC3) — the short inline hint; the FULL consequence text lives in the
  // retry confirmation dialog (`CONFIRMATION_CATALOG.retryOrRecoverConsequential`).
  recovery_operator: {
    retry: 'Retry re-executes the last failed step with a fresh runner.',
  },
};

/** Resolve the immediate-consequence hint for a mode + action (AC2), or undefined. */
export function resolveConsequenceHint(
  mode: ApprovalBarMode,
  action: DecisionAction,
): string | undefined {
  return CONSEQUENCE_HINTS[mode][action];
}

/**
 * Map the live view + mutation + local UI to the single AC3 render state. Precedence:
 * `locked > error > stale > submitting > disabled(stub-mode) > blocked > success > ready`.
 * The stub-mode `disabled` check is resolved BEFORE `blocked` so an E3/E4 stub reads as
 * a deliberate control restriction rather than a missing-artifact block (it only fires
 * for non-`spec_approval` modes; for `spec_approval` the `blocked` check governs). A
 * `APPROVAL_VERSION_MISMATCH` mutation error renders as `stale` (refresh-and-retry), NOT
 * a generic `error` (AC6).
 *
 * `blocked` covers BOTH "no affirmative primary action" (AC7) AND "cannot build a
 * firing request" — no resolvable `artifactId` (T-ARTIFACTID) or null version stamp
 * (T-VERSIONSTAMP). `disabled` covers the mode-specific control restriction: the
 * E3/E4 stub modes have no live decision.
 */
export function resolveApprovalBarState(
  view: ApprovalDecisionView,
  mutation: ApprovalMutationState,
  localUi: ApprovalLocalUi = {},
): ApprovalBarState {
  if (localUi.locked === true) {
    return 'locked';
  }
  if (mutation.status === 'error') {
    return mutation.errorCode === 'APPROVAL_VERSION_MISMATCH' ? 'stale' : 'error';
  }
  if (localUi.stale === true) {
    return 'stale';
  }
  if (mutation.status === 'pending') {
    return 'submitting';
  }
  // Story 3.30 — the recovery_operator mode is a REAL decision path (no longer a
  // stub): `ready` when the run is `Failed` AND the live allowed-actions include
  // `retry`; `success` after a recorded retry; otherwise `disabled` (View only —
  // retry is not a safe action). Resolved before the generic non-spec_approval
  // `disabled` fallthrough.
  if (view.mode === 'recovery_operator') {
    if (mutation.status === 'success') {
      return 'success';
    }
    return canRetry(view) ? 'ready' : 'disabled';
  }
  // Story 3.28 — the implementation_review mode is a REAL decision path (no longer a
  // stub). Mirrors spec_approval's `primary === null || !canFire → blocked → success →
  // ready`, BUT the primary is `accept_implementation` and `canFire` reads the resolved
  // implementation `artifactId` + a derivable version stamp. Resolved BEFORE the generic
  // non-spec_approval `disabled` fallthrough (exactly how recovery_operator was carved
  // out above). NOTE: `Take over` is modeled as an ALWAYS-available secondary by the
  // renderer (it needs no artifactId/version), so a `blocked` accept still renders the
  // takeover control — the state here only governs the accept/reject primary path.
  if (view.mode === 'implementation_review') {
    // `success` is checked BEFORE `blocked` (mirroring recovery_operator): after a decision
    // lands the run leaves WaitingForReview and the refetched allowed-actions drop
    // accept/reject — without this, the post-decision summary (AC6) + takeover PR
    // affordance (AC7) would be torn down to `blocked` the instant the run advances.
    if (mutation.status === 'success') {
      return 'success';
    }
    const primary = view.actions.includes('accept_implementation') ? 'accept_implementation' : null;
    // canFire reads the resolved implementation `artifactId` (R8) + the context-bundle
    // version (R3) — NOT `deriveExpectedVersions`, which gates on the SPEC version
    // (`currentSpecArtifactVersion`) that accept/reject-implementation never send. The
    // container sets `artifactId` only when the full firing request is buildable (the impl
    // artifact has a numeric version), so an `artifactId` here implies a derivable version.
    const canFire =
      view.artifactId !== undefined &&
      typeof view.versionStamp?.currentContextBundleVersion === 'number';
    if (primary === null || !canFire) {
      return 'blocked';
    }
    return 'ready';
  }
  // The remaining mode is `spec_approval` (recovery_operator + implementation_review are
  // handled above; the `ApprovalBarMode` union is exhaustive — a new mode would add its own
  // branch, exactly like the two above).
  const primary = resolvePrimaryAction(view.actions);
  const canFire =
    view.artifactId !== undefined && deriveExpectedVersions(view.versionStamp) !== null;
  if (primary === null || !canFire) {
    return 'blocked';
  }
  if (mutation.status === 'success') {
    return 'success';
  }
  return 'ready';
}

/**
 * Story 3.30 (AC3, AC5) — whether the recovery_operator mode can offer `Retry failed
 * step`. Gated on TWO live signals: the run is `Failed` AND the backend-reported
 * allowed-actions include `retry`. Both must hold — a `Failed` run whose allowed-
 * actions omit `retry` (e.g. retry not safe) resolves to `View only`. The frontend
 * NEVER infers retry-eligibility locally; it reads `useAllowedActions` (no permission-
 * inference module — AC11 / OQ-5 of 2.19).
 */
export function canRetry(view: ApprovalDecisionView): boolean {
  return view.currentState === 'Failed' && view.actions.includes('retry');
}

/** Compose the recovery decision-context line (AC3) from the live read model. */
export function buildRecoveryContextLabel(detail: WorkflowDetail | undefined): string {
  const stage = detail?.failedStage;
  // `failedStage` is typed `?: string`, but the wire serializes it as JSON `null` once the run
  // leaves Failed (a successful retry flips it to Executing while the recovery bar stays mounted
  // through its `success` state). A `!== undefined` guard misses null → `null.trim()` crashes the
  // whole route (`[[workflowdetail-wire-sends-null-not-undefined]]`). `typeof === 'string'` covers
  // null, undefined, AND non-strings.
  const stagePart =
    typeof stage === 'string' && stage.trim() !== '' ? ` at the ${stage} stage` : '';
  return `Recover the failed run${stagePart}`;
}

/** Exhaustiveness guard for the `mode` switch (AC1) — a new mode without a branch fails `tsc`. */
export function assertNeverMode(mode: never): never {
  throw new Error(`Unhandled ApprovalBarMode: ${String(mode)}`);
}

/**
 * THE artifactId seam (T-ARTIFACTID). No live read model exposes the spec artifact's
 * id today (`WorkflowDetail.latestArtifacts` = type/status/version only), so this
 * resolves to `undefined` → the bar renders `blocked`. The day a read model adds an
 * `artifactId` to the spec `latestArtifacts` entry, this ONE function returns it and
 * the bar goes fully live with ZERO component changes. It READS (never fabricates) the
 * forward-compat field; an absent field stays `undefined`. Lives in this `.ts` (not the
 * container `.tsx`) per `frontend-react-refresh-no-fn-exports`.
 */
export function resolveSpecArtifactId(detail: WorkflowDetail | undefined): string | undefined {
  const specArtifacts = (detail?.latestArtifacts ?? []).filter(
    (artifact) => artifact.artifactType === 'spec',
  );
  // Pick the HIGHEST-version spec entry — `latestArtifacts` order is not guaranteed to be
  // version order, so a plain `.find` could resolve a stale earlier spec id.
  let specArtifact: (typeof specArtifacts)[number] | undefined;
  for (const candidate of specArtifacts) {
    if (
      specArtifact === undefined ||
      (candidate.version ?? Number.NEGATIVE_INFINITY) >
        (specArtifact.version ?? Number.NEGATIVE_INFINITY)
    ) {
      specArtifact = candidate;
    }
  }
  const candidateId = (specArtifact as { artifactId?: unknown } | undefined)?.artifactId;
  return typeof candidateId === 'string' ? candidateId : undefined;
}

/** Compose the decision-context line (AC2) from the live read model. */
export function buildDecisionContextLabel(
  detail: WorkflowDetail | undefined,
  versionStamp: ApprovalVersionStamp | undefined,
): string {
  const version = versionStamp?.currentSpecArtifactVersion;
  const versionPart = typeof version === 'number' ? ` v${version}` : '';
  const actor = detail?.currentActorIdentity;
  // `currentActorIdentity` is wire-nullable (`?: string` but serialized as JSON null) — guard with
  // typeof so a null actor cannot `.trim()`-crash ([[workflowdetail-wire-sends-null-not-undefined]]).
  const actorPart = typeof actor === 'string' && actor.trim() !== '' ? ` by ${actor}` : '';
  return `Approve specification${versionPart}${actorPart}`;
}

/** A resolved implementation artifact (id + version) accept/reject fires against (story 3.28). */
export interface ResolvedImplementationArtifact {
  readonly artifactId: string;
  readonly version: number | undefined;
}

/**
 * Story 3.28 (R1) — resolve the implementation artifact under technical review. Mirrors
 * {@link resolveSpecArtifactId} but filters `artifactType ∈ {implementationPlan, prOutput}`
 * and picks the HIGHEST-version entry (`latestArtifacts` order is not guaranteed to be
 * version order). Live as of story 3a-9 (the read model populates `latestArtifacts[].
 * artifactId`). Returns undefined when no implementation artifact exists yet → accept/reject
 * render `blocked` (takeover stays available — it needs no artifact). READS (never
 * fabricates) the forward-compat `artifactId`; an absent id stays undefined.
 */
export function resolveImplementationArtifact(
  detail: WorkflowDetail | undefined,
): ResolvedImplementationArtifact | undefined {
  const all = detail?.latestArtifacts ?? [];
  // The technical review is of the PR output — prefer the highest-version `prOutput`, and
  // fall back to `implementationPlan` ONLY when no `prOutput` exists (3.28 review, D1). The
  // two types are INDEPENDENT version sequences, so a single cross-type "highest version"
  // pick could otherwise select the plan over the PR actually under review.
  const pickHighest = (type: 'prOutput' | 'implementationPlan') => {
    let chosen: (typeof all)[number] | undefined;
    for (const candidate of all) {
      if (candidate.artifactType !== type) {
        continue;
      }
      if (
        chosen === undefined ||
        (candidate.version ?? Number.NEGATIVE_INFINITY) >
          (chosen.version ?? Number.NEGATIVE_INFINITY)
      ) {
        chosen = candidate;
      }
    }
    return chosen;
  };
  const chosen = pickHighest('prOutput') ?? pickHighest('implementationPlan');
  const candidateId = (chosen as { artifactId?: unknown } | undefined)?.artifactId;
  if (typeof candidateId !== 'string') {
    return undefined;
  }
  return {
    artifactId: candidateId,
    version: typeof chosen?.version === 'number' ? chosen.version : undefined,
  };
}

/** Convenience: the resolved implementation artifact's id (or undefined) — drives `canFire`. */
export function resolveImplementationArtifactId(
  detail: WorkflowDetail | undefined,
): string | undefined {
  return resolveImplementationArtifact(detail)?.artifactId;
}

/**
 * Story 3.28 (R3 / OQ-1) — derive the two version ints for accept/reject-implementation.
 * Unlike spec approval, `expectedArtifactVersion` comes from the resolved IMPLEMENTATION
 * artifact's version (the backend's `ApprovalVersionBinder` compares against the artifact
 * identified by `artifactId`), NOT from `versionStamp.currentSpecArtifactVersion` (which is
 * the LATEST SPEC version at `WaitingForReview`). `expectedContextBundleVersion` comes from
 * the stamp. Returns null when either int is absent → contributes to `blocked`
 * (T-VERSIONSTAMP). Do NOT reuse {@link deriveExpectedVersions} here (it would send the
 * spec version as the artifact version).
 */
export function deriveImplementationExpectedVersions(
  artifact: ResolvedImplementationArtifact | undefined,
  versionStamp: ApprovalVersionStamp | undefined,
): ExpectedVersions | null {
  if (artifact === undefined || versionStamp === undefined) {
    return null;
  }
  const { version } = artifact;
  const { currentContextBundleVersion } = versionStamp;
  if (typeof version !== 'number' || typeof currentContextBundleVersion !== 'number') {
    return null;
  }
  return {
    expectedArtifactVersion: version,
    expectedContextBundleVersion: currentContextBundleVersion,
  };
}

/** Compose the implementation-review decision-context line (AC2) from the live read model. */
export function buildImplementationContextLabel(detail: WorkflowDetail | undefined): string {
  const version = resolveImplementationArtifact(detail)?.version;
  const versionPart = typeof version === 'number' ? ` v${version}` : '';
  const actor = detail?.currentActorIdentity;
  // `currentActorIdentity` is wire-nullable — typeof guard prevents a null `.trim()` crash
  // ([[workflowdetail-wire-sends-null-not-undefined]]).
  const actorPart = typeof actor === 'string' && actor.trim() !== '' ? ` by ${actor}` : '';
  return `Review implementation${versionPart}${actorPart}`;
}
