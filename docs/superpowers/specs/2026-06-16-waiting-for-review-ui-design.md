# Spec — WaitingForReview review experience UI (sub-project #3)

Date: 2026-06-16
Status: design (story input)

## Context

Sub-project #3 of "Option X". The user-facing payoff: at `WaitingForReview` the operator can read
the implementation artifact and act on it (accept / reject / takeover). Builds on #2b (artifacts
`available`) and ideally #1 (correct two-phase: a real plan to review, then a real PR to review).

### What already exists (verified in source)

- `WorkflowDecisionBar.tsx` routes `WaitingForReview → ImplementationReviewDecisionBarContainer`
  (accept/reject/takeover) and `Failed → recovery_operator`; everything else → `spec_approval`.
- `approvalDecisionView.ts` has the full `implementation_review` mode: `resolveImplementationArtifact`
  (prefers `implementationPlan`/`prOutput`, highest version, "live as of 3a-9"),
  `deriveImplementationExpectedVersions`, `buildImplementationContextLabel`, consequence hints,
  and `resolveApprovalBarState` gating (`blocked` unless artifactId + context-bundle version).
- Hooks exist: `useAcceptImplementation`, `useRejectImplementation`, `useTakeoverWorkflow`.
- Reusable PR/diff building blocks from story 3.31: `githubRef.ts`, `PrStateBadge`,
  `SafeUnifiedDiffRenderer` / `parseUnifiedDiff`.
- Backend endpoints exist (stories 3.20–3.25): accept-implementation, reject-implementation,
  takeover; `TechnicalApprovalService` drives the transitions.

### The gaps

1. **Developer-role wiring.** `getAllowedActions` defaults to `product_reviewer` (the
   `ApprovalReviewerRoleResolver` `@Value` fallback). Accept/reject/takeover are only returned for
   the `developer` role. So the decision bar is `blocked`/inert. Per the user's decision
   ("one user, multiple roles for now"): the single operator should carry the `developer` role at
   `WaitingForReview` — the UI must request allowed-actions (and send decisions) as `developer`.
2. **No prOutput renderer.** The generic artifact viewer renders the artifact body as markdown; a
   `prOutput` is JSON (branch/commitSha/prReference/diffReference) and likely fails the
   `isArtifactView` guard → renders `error`. Needs a dedicated renderer: PR link (via `githubRef` +
   `PrStateBadge`) + unified diff (via `SafeUnifiedDiffRenderer`).
3. **Stale embedded SPA.** The running app serves an SPA bundle predating the implementation_review
   bar; a `mvn package` rebuild is required regardless (also called out in #2b).

## Scope

In:
1. Developer-role wiring so the implementation_review bar's actions appear and fire at
   `WaitingForReview` for the single operator.
2. A prOutput review renderer (PR link + state badge + unified diff) on the artifact-viewer route
   (or an inline review panel), reusing the 3.31 building blocks.
3. Plan-phase rendering: when the review artifact is an `implementationPlan`, render its steps
   (the `implementationPlan` artifact shape) for review.
4. Accept / reject (developer taxonomy) / takeover wired end-to-end to the existing endpoints,
   including the post-decision success summary + the takeover preserved-PR affordance.

Out:
- Backend availability (#2b) and runner two-phase (#1).
- Header-based role attribution (story 2.13) — out of scope; single-operator-all-roles for now.

## Design

### Role wiring

- Decide the mechanism (story design): simplest is the UI requesting allowed-actions as
  `developer` when `currentState === 'WaitingForReview'` (single-user-all-roles), and sending the
  developer reviewer role on the accept/reject/takeover calls. Keep it isolated so a future
  story-2.13 header attribution swaps in cleanly. (Note: backend
  `assertNoConflictingRepoLink` / accept PR-link gate still require the `github_pr` link from #1
  for a `prOutput` accept to complete — sequence #3 after #1, or test against a plan-phase accept.)

### prOutput / plan rendering

- `prOutput`: a dedicated review panel — summary + PR link (`PrStateBadge` + `githubRef` URL
  hardening) + unified diff (`SafeUnifiedDiffRenderer` / `parseUnifiedDiff`). The diff source is the
  artifact payload / `diffReference`.
- `implementationPlan`: render the ordered steps for review.
- Decide where it renders: the existing artifact-viewer route vs. an inline review panel on the
  detail page. The detail page currently only links to the spec (`resolveSpecArtifactId`); #2b adds
  an implementation-artifact link — #3 makes the target render properly.

### Decision actions

- Accept / reject / takeover already have hooks + a container; wire them to fire (gated on the
  developer role from above), preserving the kept-alive success/announcement behavior in
  `WorkflowDecisionBar` (the bar stays mounted through the post-decision state flip).

## Likely story breakdown

- Story A — developer-role wiring at `WaitingForReview` (FE allowed-actions/decision role) + a
  thin contract test.
- Story B — prOutput review renderer (PR link + diff) reusing 3.31 components.
- Story C — implementationPlan review rendering + the plan-phase accept/reject path.
- (Takeover may fold into A/B since the container already supports it.)

## Verification

- With the developer role, the implementation_review bar renders accept/reject/takeover and a
  decision transitions the run (vitest + a manual run after `mvn package`).
- The prOutput renders as a PR link + diff (not `error`); the implementationPlan renders its steps.

## Risks / notes

- A `prOutput` accept needs the `github_pr` link (backend PR-link gate) — which only exists once #1
  persists it. Sequence #3 after #1 for a complete `prOutput` accept; plan-phase accept/reject and
  takeover do not need the link.
- Respect `frontend-react-refresh-no-fn-exports` (helpers in `.ts`, not `.tsx`) and
  `exactOptionalPropertyTypes` when extending the ArtifactView variants.
