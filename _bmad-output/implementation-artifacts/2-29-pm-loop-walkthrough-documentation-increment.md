# Story 2.29: PM-Loop Walkthrough Documentation Increment

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager joining the pilot,
I want a `docs/pm-loop-walkthrough.md` that walks me through opening a run from the queue, reading a specification, answering clarification questions, understanding the visible incorporation lifecycle, and approving or rejecting with structured feedback — end-to-end with annotated diagrams,
so that I can complete the PM review loop on my first pilot run unaided (NFR42 satisfied for the PM persona) — and the make-or-break refinement (answered ≠ incorporated) is set as an explicit expectation rather than a surprise.

## Scope Decisions (read first — these resolve epic-vs-reality tensions)

**This is a PURE DOCUMENTATION story. It produces exactly one new markdown file (`docs/pm-loop-walkthrough.md`), one README link, and one glossary update. It touches ZERO application/frontend/backend code, no tests, no CI workflow logic.** The walkthrough must describe the UI **exactly as it is implemented today** — every label, state name, and message below was verified against the live `deliveryline-frontend` source. **The epic's AC text drifts from the live UI in several places; the reconciliations (R1–R11) below pin the doc to reality. Where epic AC wording and the live UI disagree, the live UI string wins.** Quote the real strings verbatim — a walkthrough that names a button or state the PM can't find is worse than no walkthrough.

- **R1 — Clarification lifecycle vocabulary drift (AC3, AC12 — the #1 accuracy hazard).** The epic AC3 says the lifecycle is "submitted → answered → accepted → incorporated / superseded / rejected_invalid". The **live UI** is more nuanced and the doc MUST use the live strings:
  - **Backend lifecycle statuses** (`clarificationView.ts:30-37`): `open`, `answered`, `accepted`, `incorporated`, `superseded`, `rejected_invalid` (+ `unknown` sentinel for hard-deleted legacy rows). **Note: the backend "first" state is `open`, NOT `submitted`.**
  - **The visible 3-pill lifecycle indicator** (`LIFECYCLE_STAGES = ['submitted', 'accepted', 'incorporated']`, `clarificationView.ts:256`; rendered by `LifecycleIndicator` in `ClarificationRegion.tsx:97-141`) collapses the chain to **submitted › accepted › incorporated** and highlights the current position.
  - **Per-item status labels** the PM actually reads (`clarificationView.ts:167-177`): `Open` · `In progress` · **`Answered · pending incorporation`** · `Accepted` · `Incorporated` · `Superseded` · `Rejected` · `Invalid answer`.
  - **The make-or-break anti-pattern (AC3) must be phrased against the REAL label:** the canonical failure is approving while a clarification still shows **"Answered · pending incorporation"** (the spec is not yet rebuilt with your input). The instruction is: **wait until the item shows "Incorporated" (and the lifecycle indicator advances past the middle pill) before approving** — do NOT say "never advances past 'answered'" using a label that isn't on screen.

- **R2 — The rejection dialog is region-local, NOT story 2.23's `RationaleCaptureDialog` (AC4).** The "rejection-with-feedback dialog" the PM sees is the **local `RejectionDialog` inside `ApprovalDecisionBar.tsx:142-223`** (title **"Reject with feedback"**, a **"Reason"** textarea, a **"Kind of rework needed"** radio group, confirm button **"Confirm rejection"**, validation message *"Add a reason and select the kind of rework needed before rejecting."*). Document THIS dialog. Do not describe story 2.23's `RationaleCaptureDialog` — it is a different, general-purpose overlay not used on the spec-approval path.

- **R3 — Rework-taxonomy casing (AC5).** Wire/enum values are **UPPERCASE** (`ApprovalDecisionBar.tsx:63-68`): `MISSING_SCOPE` / `UNCLEAR_SPECIFICATION` / `MISUNDERSTOOD_IMPLEMENTATION` (typed from `RejectSpecRequest.taggedFeedback`). The **user-facing radio labels are Title Case**: **"Missing scope"** / **"Unclear specification"** / **"Misunderstood implementation"**. The doc's taxonomy section uses the **Title-Case labels** as headings (what the PM clicks); mention the UPPERCASE tag once when explaining that the choice feeds the Epic-5 AR34b rework-rate measurement, then stay in label-case.

- **R4 — Version-mismatch copy (AC7).** The epic AC7 nicknames this "Spec was updated"; the **live UI does NOT use that phrase.** `APPROVAL_VERSION_MISMATCH` (`approvalDecisionView.ts:349`) renders the Decision Bar in its **`stale` state** (not generic `error`) with the message **"The specification changed since this view loaded. It is now at version {N}. Review the new version before approving."** and a **"Refresh and review"** CTA (`ApprovalDecisionBar.tsx:345-368`). Quote the real message; the "What if I see…?" section heading may keep the friendly framing but the screenshot/callout MUST show the real copy.

- **R5 — Decision Bar states + blocked reason (AC4).** Bar states (`approvalDecisionView.ts:39-47`): `ready` · `blocked` · `stale` · `disabled` · `submitting` · `success` · `error` · `locked`. The doc only needs **`ready`** (actions available) and **`blocked`** (no action; reason shown). The blocked reason when clarifications are pending (`approvalDecisionView.ts:294-297`): **"{N} clarification(s) pending incorporation — approval blocked"**. Consequence hints: approve → **"Approval will transition the run to Executing."**; reject → **"Rejection sends the specification back for rework."** (`approvalDecisionView.ts:307-308`). Primary button **"Approve specification"**, secondary **"Reject with feedback"** (`ApprovalDecisionBar.tsx:414-441`).

- **R6 — Role label (AC6).** The audit role string is **`product_reviewer`**, rendered by `AuditRoleLabel.tsx` with the clarifier **"recorded for audit only — not an enforced permission"** (`AuditRoleLabel.tsx:24`) and an `(audit label)` marker. The AC6 callout box must quote that exact clarifier and state the MVP has **no role-based access enforcement** — the role is recorded for traceability, not authorization.

- **R7 — Routes + queue vocabulary (AC1).** Queue route is **`/workflows`** (root `/` redirects to it; `routes/index.tsx:10-16`). Run detail is **`/workflows/$workflowRunId`**. Submit form is **`/submit`**. A run "needing review" is one in backend state **`WaitingForSpecApproval`**, shown as a **`warning`-styled `WorkflowStateBadge`** (`workflowStateMapping.ts:35`). Queue empty-state text (`QueueShell.tsx:235-239`): **"No specifications awaiting review. New runs from Linear appear here once submitted — or submit a run from a Linear ticket."** with a **"Submit a run"** CTA.

- **R8 — Docs index = README "Quick links" (AC10).** **There is no `docs/index.md`** — the glossary states the "full documentation index" is owned by Epic 6 stories 6.1/6.2. The de-facto docs index today is the **README.md "Quick links" section** (it already lists `quickstart.md`, `failure-recovery-walkthrough.md`, `glossary.md`, etc.). **Encoded default: satisfy AC10 by adding a Quick-links entry in `README.md` pointing at `docs/pm-loop-walkthrough.md`** (mirror the existing quickstart / failure-recovery entries). Do NOT invent a `docs/index.md` — that would collide with the Epic-6 6.1 deliverable. The "foundation-gate-equivalent E2 close gate verifies its presence" clause is an **epic-close checklist concern, not a this-story deliverable** — there is no such gate today; flag it in Completion Notes (the doc-increment rule is enforced at Epic-2 close, not by this story). The README link being present + lychee-resolvable is this story's AC10 obligation.

- **R9 — Glossary update is in-scope (AC12 + glossary discipline).** `docs/glossary.md` carries a hard rule: *"Any doc that introduces a new term beyond this canonical set must add an entry here in the same PR."* The seven PRD concepts (ticket, spec, run, artifact, review, failure, recovery action) already exist. The **clarification incorporation-lifecycle vocabulary is new** to the glossary. **Encoded default: add a `clarification` entry** (and fold the lifecycle states `open / answered / accepted / incorporated / superseded / rejected_invalid` into its body) to `glossary.md` in this same change, cross-linked to the walkthrough. Keep it terse — Epic-6 6.2 will normalize wording later; this story just registers the term so NFR43 concept-sprawl tracking stays honest.

- **R10 — Diagrams over screenshots (AC4).** There is **no screenshot-capture pipeline** in this repo, and binary PNGs rot against an evolving UI and bloat the tree. AC4 explicitly permits "annotated diagrams (Mermaid OK)". **Encoded default: use Mermaid diagrams + fenced ASCII layout sketches**, annotated to label each required surface. AC4's required illustration set: (1) the queue page with a run needing review, (2) the tri-pane shell with an open run, (3) the Clarification Region lifecycle indicator, (4) the Decision Bar in **both** `ready` and `blocked` states, (5) the rejection dialog showing the three rework-taxonomy radios. If Alex later wants real PNGs, that is a follow-up — do not block this story on a capture pipeline.

- **R11 — The mandatory "Logging instrumentation" task is N/A here.** This story emits only markdown — there is no service, no branch, no SLF4J surface to instrument. The cross-cutting logging task is marked **N/A with rationale** rather than padded with fake subtasks. (This is the honest application of the project-wide standard, which targets touched *services*; a docs increment touches none.)

## Acceptance Criteria

1. **Given** `docs/pm-loop-walkthrough.md`, **Then** it follows a linear sequence: prerequisites (DeliveryLine running locally per quickstart from story 1.22; a governed run exists in `WaitingForSpecApproval` state) → open the review queue (`/workflows`) → identify a run needing review (the `WaitingForSpecApproval` badge) → open the run (`/workflows/$workflowRunId`) → read the specification → understand the run context strip → answer any open clarification questions → understand the visible incorporation lifecycle → approve OR reject with structured feedback → see the workflow advance (`→ Executing`) or rebuild (rejection sends back for rework) → return to the queue.
2. **Given** target completion time, **Then** the doc states "~10 minutes from opening the queue to completing your first review decision".
3. **Given** the make-or-break refinement, **Then** a dedicated section "What you'll see when you answer a clarification" explains the lifecycle using the **live per-item labels** (`Open` → `Answered · pending incorporation` → `Accepted` → `Incorporated`, with `Superseded` / `Rejected` as terminal off-paths) and the 3-pill indicator (submitted › accepted › incorporated), with the canonical anti-pattern called out: **"If you submit an answer and the item still reads 'Answered · pending incorporation' (the lifecycle indicator has not reached 'Incorporated'), the spec is not yet rebuilt with your input — wait for 'Incorporated' before approving"** — directly preempting the PRD's documented make-or-break failure mode (R1).
4. **Given** annotated Mermaid/ASCII diagrams (R10), **Then** at minimum these are illustrated: the queue page showing a run needing review; the tri-pane shell with an open run; the Clarification Region lifecycle indicator; the Decision Bar in **both** `ready` and `blocked` states (blocked when clarifications are pending, with the reason "{N} clarification(s) pending incorporation — approval blocked"); the **region-local rejection dialog** (`ApprovalDecisionBar` `RejectionDialog`, R2) with the rework-taxonomy radios.
5. **Given** the rework taxonomy from story 2.10 + AR34a, **Then** a section explains each tag using the **Title-Case labels** — **"Missing scope"** / **"Unclear specification"** / **"Misunderstood implementation"** — with a concrete example of when to use each, noting the choice feeds the Epic-5 AR34b rework-rate measurement (R3).
6. **Given** the role-label semantics from story 2.25 / UX-DR21, **Then** a callout box clarifies: "Your role appears as **`product_reviewer`** for audit purposes — the clarifier reads *'recorded for audit only — not an enforced permission'*. The MVP does not enforce role-based access; anyone with local DeliveryLine access can perform any action. The role is recorded for traceability, not authorization." (R6).
7. **Given** version-mismatch handling from story 2.19 AC6, **Then** a "What if the spec changed while I was reviewing?" section explains the `APPROVAL_VERSION_MISMATCH` → Decision Bar **`stale`** state, quoting the real message **"The specification changed since this view loaded. It is now at version {N}. Review the new version before approving."** and the **"Refresh and review"** CTA, with the rule: refresh, re-read the new version, decide again — never approve a spec you didn't review (R4).
8. **Given** cross-platform usability, **Then** the walkthrough is browser-based and contains no OS-specific instructions — works identically on Windows / macOS / Linux per story 1.17 supported-environment matrix.
9. **Given** the lychee link-check CI step from story 1.22 AC8 (`docs-link-check` job, `ci.yml:147`), **Then** all internal markdown links + in-file anchors in `pm-loop-walkthrough.md` resolve to real files/anchors (internal failures gate the build). Cross-references to stories 1.22 / 2.10 / 2.19 are made as **prose references** (story numbers are not files); the only markdown links are to real `docs/` files (`quickstart.md`, `glossary.md`, `supported-environments.md`, `failure-recovery-walkthrough.md`) and the README — never to `_bmad-output/` planning artifacts.
10. **Given** documentation-increment acceptance per Epic 2's doc-increment rule (pre-mortem refinement R7), **Then** `pm-loop-walkthrough.md` is visible from the docs index — **the README.md "Quick links" section** (the equivalent index; no `docs/index.md` exists, that is the Epic-6 6.1 deliverable) gains an entry linking the walkthrough (R8). The Epic-2 close gate that verifies its presence is an epic-close checklist concern, documented in Completion Notes, not implemented here.
11. **Given** a PM-validator placeholder (parallel to story 1.22 AC7), **Then** the doc includes, near the top, the placeholder line **`> **PM-loop validator:** \`_____________________________\` (to be named before Epic 2 close)`** — matching the format already used in `quickstart.md` and `failure-recovery-walkthrough.md`.
12. **Given** NFR43 (minimize new concepts), **Then** the walkthrough uses only the PRD concept set (ticket, spec, run, artifact, review, failure, recovery action) plus the clarification incorporation-lifecycle vocabulary (open / answered / accepted / incorporated / superseded / rejected_invalid); because that lifecycle vocabulary is new to `docs/glossary.md`, a `clarification` glossary entry registering those terms is added in the same change (R9, glossary discipline).

## Tasks / Subtasks

- [x] **Task 1 — Author `docs/pm-loop-walkthrough.md` skeleton + prerequisites + linear sequence (AC1, AC2, AC8, AC11)**
  - [x] Create `docs/pm-loop-walkthrough.md`. Open with a one-line purpose + the **PM-loop validator placeholder** line (AC11, exact format from `quickstart.md:9` / `failure-recovery-walkthrough.md:2`).
  - [x] State the **target time**: "~10 minutes from opening the queue to completing your first review decision" (AC2).
  - [x] Prerequisites section: DeliveryLine running locally (link `quickstart.md`); a governed run already sitting in **`WaitingForSpecApproval`** (note briefly that reaching that state requires the spec stage to have produced a spec — the PM does not do this; it is upstream). Browser-only, no OS-specific steps (AC8) — cross-link `supported-environments.md`.
  - [x] Lay out the **linear sequence** as numbered steps mirroring AC1, using the real routes (`/workflows` → `/workflows/$workflowRunId`) and the `failure-recovery-walkthrough.md` step-by-step house style (TL;DR optional, then numbered Steps, then tables).

- [x] **Task 2 — "Reading the run context strip" + tri-pane orientation (AC1, AC4)**
  - [x] Describe the **tri-pane shell** (story 2.7 artifact-primacy layout) and where each region sits when a run is open.
  - [x] Document the **Run Context Strip** fields exactly (`RunContextStrip.tsx:149-184`): **Run** (run id), the **WorkflowStateBadge**, **Stale** / **Escalated** chips, **Actor** (`Identity (type)`), **Revision** (artifact type + version, e.g. `implementation-plan v2`), **Last transition** (relative time, UTC on hover), **Trigger** (Linear ref), **Branch** (always "Not reported" in Epic 2 — note this is expected, not a bug).
  - [x] Add the **tri-pane Mermaid/ASCII diagram** annotating queue / artifact-review / context-strip regions (AC4 item 1+2).

- [x] **Task 3 — "What you'll see when you answer a clarification" — the make-or-break section (AC3, AC4, R1)**
  - [x] Describe the **Clarification Region** and the **3-pill lifecycle indicator** (submitted › accepted › incorporated).
  - [x] Table the **live per-item labels** (`Open`, `In progress`, `Answered · pending incorporation`, `Accepted`, `Incorporated`, `Superseded`, `Rejected`, `Invalid answer`) → plain-language meaning for the PM.
  - [x] Call out the **canonical anti-pattern** verbatim per AC3/R1: do not approve while an item still reads **"Answered · pending incorporation"** — wait for **"Incorporated"**.
  - [x] Add the **lifecycle-indicator diagram** (AC4 item 3).

- [x] **Task 4 — "Approving or rejecting" — Decision Bar + rejection dialog + rework taxonomy (AC4, AC5, R2, R3, R5)**
  - [x] Document the **Decision Bar** primary/secondary buttons ("Approve specification" / "Reject with feedback") and the two states to know: **`ready`** vs **`blocked`** (blocked message "{N} clarification(s) pending incorporation — approval blocked").
  - [x] State the **consequence hints**: approve → "Approval will transition the run to Executing."; reject → "Rejection sends the specification back for rework."
  - [x] Document the **region-local rejection dialog** (R2): title "Reject with feedback", "Reason" textarea, "Kind of rework needed" radios, "Confirm rejection".
  - [x] **Rework-taxonomy** subsection (AC5/R3): the three Title-Case labels with one concrete example each; one sentence on the UPPERCASE tag feeding Epic-5 AR34b measurement.
  - [x] Add the **Decision Bar (ready + blocked) + rejection dialog** diagrams (AC4 items 4+5).

- [x] **Task 5 — Edge sections: role label + version mismatch (AC6, AC7, R4, R6)**
  - [x] **Role callout box** (AC6/R6): `product_reviewer`, the "recorded for audit only — not an enforced permission" clarifier, no RBAC enforcement in the MVP.
  - [x] **"What if the spec changed while I was reviewing?"** section (AC7/R4): `APPROVAL_VERSION_MISMATCH` → `stale` Decision Bar state, the real message + "Refresh and review" CTA, the never-approve-unreviewed rule.

- [x] **Task 6 — Wire into docs index + glossary (AC9, AC10, AC12, R8, R9)**
  - [x] Add a **README.md "Quick links" entry** linking `docs/pm-loop-walkthrough.md` (mirror the quickstart / failure-recovery entries) — this is the AC10 docs-index obligation.
  - [x] Add a **`clarification` glossary entry** to `docs/glossary.md` registering the incorporation-lifecycle vocabulary (`open / answered / accepted / incorporated / superseded / rejected_invalid`), cross-linked to the walkthrough (AC12/R9). Keep terse.
  - [x] **Verify all internal links resolve** (AC9): lychee not installed locally — hand-checked every relative link + anchor (all resolve: `quickstart.md`, `failure-recovery-walkthrough.md`, `supported-environments.md`, `glossary.md`, `glossary.md#clarification`). Intra-doc Step anchors were converted to prose to avoid GitHub double-hyphen em-dash slug mismatch. Story cross-refs (1.22/2.10/2.19) stay prose, NOT links. No links to `_bmad-output/`.

- [x] **Task 7 — Completion-notes obligations (AC10)**
  - [x] In Completion Notes, document that the Epic-2 close gate verifying the doc's presence is an **epic-close checklist concern** (no foundation-gate-equivalent CI gate is added by this story), and that the docs index is the README Quick-links section pending the Epic-6 6.1 `docs/index.md`.

- [ ] ~~**Logging instrumentation**~~ — **N/A (R11).** This story produces only markdown (one new doc, one README link, one glossary entry); it touches no service, branch, or SLF4J surface. The project-wide logging standard targets touched code, of which there is none. No log assertions are possible or required.

## Dev Notes

### Live-UI string inventory (READ BEFORE WRITING — copy these verbatim)

Every string the walkthrough quotes was verified against `deliveryline-frontend/src`. **If the UI and this table ever disagree at write time, re-grep the source and trust the code, not this table.**

| Concept | Exact live string(s) | Source |
|---|---|---|
| Queue route / run-detail route / submit route | `/workflows` · `/workflows/$workflowRunId` · `/submit` (root `/` redirects to `/workflows`) | `routes/workflows/index.tsx`, `routes/workflows/$workflowRunId/index.tsx`, `routes/submit/index.tsx`, `routes/index.tsx:10-16` |
| Review-needed run state | backend `WaitingForSpecApproval` → `warning` state badge | `workflowStateMapping.ts:35` |
| Queue empty-state | "No specifications awaiting review. New runs from Linear appear here once submitted — or submit a run from a Linear ticket." | `QueueShell.tsx:235-239` |
| Clarification backend statuses | `open` · `answered` · `accepted` · `incorporated` · `superseded` · `rejected_invalid` (+ `unknown`) | `clarificationView.ts:30-37` |
| Lifecycle indicator (3 pills) | `['submitted','accepted','incorporated']` | `clarificationView.ts:256`; rendered `ClarificationRegion.tsx:97-141` |
| Per-item labels | `Open` · `In progress` · `Answered · pending incorporation` · `Accepted` · `Incorporated` · `Superseded` · `Rejected` · `Invalid answer` | `clarificationView.ts:167-177` |
| Decision Bar buttons | "Approve specification" · "Reject with feedback" | `ApprovalDecisionBar.tsx:414-441` |
| Approve / reject consequence | "Approval will transition the run to Executing." / "Rejection sends the specification back for rework." | `approvalDecisionView.ts:307-308` |
| Blocked-by-clarifications reason | "{N} clarification(s) pending incorporation — approval blocked" | `approvalDecisionView.ts:294-297` |
| Rejection dialog | title "Reject with feedback", "Reason" textarea, "Kind of rework needed" radios, "Confirm rejection", validation "Add a reason and select the kind of rework needed before rejecting." | `ApprovalDecisionBar.tsx:142-223` |
| Rework taxonomy | `MISSING_SCOPE`/"Missing scope" · `UNCLEAR_SPECIFICATION`/"Unclear specification" · `MISUNDERSTOOD_IMPLEMENTATION`/"Misunderstood implementation" | `ApprovalDecisionBar.tsx:63-68` |
| Version mismatch | code `APPROVAL_VERSION_MISMATCH` → `stale` state; "The specification changed since this view loaded. It is now at version {N}. Review the new version before approving." + "Refresh and review" | `approvalDecisionView.ts:349`, `ApprovalDecisionBar.tsx:345-368` |
| Role label | `product_reviewer` + clarifier "recorded for audit only — not an enforced permission" | `AuditRoleLabel.tsx:24` |
| Run Context Strip fields | Run · state badge · Stale/Escalated chips · Actor (`Identity (type)`) · Revision (`type vN`) · Last transition (relative) · Trigger (Linear ref) · Branch ("Not reported" in E2) | `RunContextStrip.tsx:149-184` |

### Doc house style — model on the two existing walkthroughs

- **Primary model:** `docs/failure-recovery-walkthrough.md` — the closest structural analog (a numbered, end-to-end operator walkthrough with a validator placeholder at the top, TL;DR, numbered Steps, decision tables, and a "What is NOT in Epic X" closer). Match its tone, heading depth, and table style.
- **Secondary model:** `docs/quickstart.md` — for the prerequisites framing, the validator-placeholder line format, and the "Concepts you just used" footer pattern that cross-links the glossary.
- Keep it browser-only and OS-neutral (AC8) — unlike the CLI docs, this walkthrough has no shell commands; do not copy the bash blocks.

### Link-check contract (AC9 — this WILL gate the build)

- The `docs-link-check` job (`ci.yml:147-175`) runs **lychee** over `docs/*.md docs/**/*.md README.md`; **internal (relative + anchor) link failures FAIL the build** (`fail: true`). External http(s) links are a separate non-blocking pass.
- Therefore: every relative link and every `#anchor` in the new doc must resolve. Anchors are GitHub-slugged from headings (lowercase, spaces→`-`, punctuation stripped) — if you link `failure-recovery-walkthrough.md#some-heading`, that heading must exist there.
- **Story numbers (1.22, 2.10, 2.19, 2.25, 1.17) are not files** — reference them in prose ("per story 2.19's version-binding") with no markdown link. The only links are to real `docs/` files and the README. **Never link to `_bmad-output/…`** (outside the docs tree; noise and brittle).

### Architecture / boundary notes

- This is a **docs-only** change. No ArchUnit, no test tiers, no OpenAPI/schema regen, no Flyway, no DomainErrorCode/registry surface — none of the usual backend fan-out memories apply. The relevant CI tier is **only** `docs-link-check` (and `format-static-checks` if a prettier/markdown formatter touches docs — verify whether `docs/**/*.md` is in the prettier glob; the existing walkthroughs were committed without it reformatting them, so markdown is likely outside the frontend prettier scope, but run a local format check to be safe).
- **Do not create `docs/index.md`** (R8) — it is the Epic-6 6.1 deliverable; collision would create rework. Use the README Quick-links section as the index.

### Project Structure Notes

- **New:** `docs/pm-loop-walkthrough.md`.
- **Modified:** `README.md` (one Quick-links entry), `docs/glossary.md` (one `clarification` entry + the "Linked from" footer if you choose to register the back-link).
- No source, test, pom, or workflow files change.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.29] — story + 12 ACs (note: several AC strings drift from the live UI; reconciled in R1–R11 above)
- [Source: docs/failure-recovery-walkthrough.md] — primary structural model (validator placeholder, numbered steps, tables, "What is NOT in Epic" closer)
- [Source: docs/quickstart.md#L1-L40] — prerequisites + validator-placeholder line format + glossary-footer pattern
- [Source: docs/glossary.md] — glossary discipline rule + the seven canonical PRD concepts; the `clarification` entry lands here (AC12)
- [Source: README.md#L12-L20] — the "Quick links" docs index (AC10 target)
- [Source: .github/workflows/ci.yml#L147-L175] — `docs-link-check` lychee job (AC9 gate; internal links fail the build)
- [Source: deliveryline-frontend/src/features/workflows/clarificationView.ts#L30-L256] — clarification lifecycle statuses, labels, and `LIFECYCLE_STAGES` (R1, AC3, AC12)
- [Source: deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx#L63-L564] — decision-bar buttons, rejection dialog, rework taxonomy, version-mismatch copy (R2–R5, AC4/AC5/AC7)
- [Source: deliveryline-frontend/src/features/workflows/approvalDecisionView.ts#L33-L449] — bar states, blocked reason, consequence hints, `APPROVAL_VERSION_MISMATCH` mapping (R4/R5)
- [Source: deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx#L149-L184] — run-context-strip fields (Task 2)
- [Source: deliveryline-frontend/src/features/workflows/QueueShell.tsx#L235-L239] — queue empty-state copy (R7)
- [Source: deliveryline-frontend/src/components/feedback/AuditRoleLabel.tsx#L24] — role clarifier (R6, AC6)
- [Source: deliveryline-frontend/src/features/workflows/components/workflowStateMapping.ts#L35] — `WaitingForSpecApproval` badge mapping (R7)

### Open Questions (for Alex — non-blocking; defaults encoded)

1. **Diagrams vs screenshots (R10):** encoded default = Mermaid + annotated ASCII (no capture pipeline; PNGs rot). Flag if real screenshots are wanted (would need a capture step + a place to store binaries).
2. **Docs index (R8):** encoded default = README Quick-links entry (no `docs/index.md`; that's Epic-6 6.1). Confirm you don't want a minimal `docs/index.md` stub now instead.
3. **Glossary depth (R9):** encoded default = a single `clarification` entry folding in the lifecycle vocabulary. If you'd rather defer all lifecycle terms wholesale to Epic-6 6.2, say so — but glossary discipline says register-in-same-PR, so the default registers it now.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8, 1M context)

### Debug Log References

None. Pure-documentation story — no build/test/runtime debugging surface.

### Completion Notes List

- **Pure-documentation increment.** One new doc (`docs/pm-loop-walkthrough.md`), one README Quick-links entry, one `clarification` glossary entry. Zero application / frontend / backend / test / CI-logic changes — confirmed against the File List below.
- **Live-UI fidelity (R1–R11).** Every quoted label, state name, route, and message was taken verbatim from the live `deliveryline-frontend` source (the Dev Notes string-inventory table). Where the epic AC wording drifted from the shipped UI, the live string won — notably: the clarification lifecycle starts at `open` (not `submitted`); the per-item label is "Answered · pending incorporation"; the version-mismatch copy is the real "The specification changed since this view loaded…" message rendered in the bar's `stale` state (UI chip "Out of date"), not AC7's "Spec was updated" nickname; the blocked chip reads "No decision available"; the rejection dialog is `ApprovalDecisionBar`'s region-local `RejectionDialog`, not story 2.23's general-purpose `RationaleCaptureDialog`; rework labels are Title-Case ("Missing scope" / "Unclear specification" / "Misunderstood implementation") over UPPERCASE wire tags.
- **AC10 — docs index + epic-close gate (per Task 7).** AC10's docs-index obligation is satisfied by the **README.md "Quick links" entry** — there is intentionally **no `docs/index.md`** (that file is the Epic-6 story 6.1 deliverable; creating it here would collide). The "Epic-2 close gate that verifies the doc's presence" clause is an **epic-close checklist concern, not a deliverable of this story** — no foundation-gate-equivalent CI gate is added here, and none exists today. The doc-increment rule is enforced at Epic-2 close, not by this story; this story's AC10 obligation is the README link being present and lychee-resolvable, which it is.
- **AC9 link-check.** lychee is **not installed locally**, so the `docs-link-check` gate (`ci.yml:147`) could not be executed here; all internal links were hand-verified to resolve to real files: `quickstart.md`, `failure-recovery-walkthrough.md`, `supported-environments.md`, `glossary.md`, and the anchor `glossary.md#clarification` (the `### clarification` heading was added in this same change, so the anchor resolves). Intra-document Step anchors were deliberately written as prose rather than `#` links because em-dash headings ("## Step 1 — …") GitHub-slugify to double-hyphen anchors and would dead-link; lychee does not run with `--include-fragments`, but the prose form is correct on GitHub regardless.
- **No format gate.** No root prettier config and `docs/**/*.md` is outside the frontend prettier scope (the two existing walkthroughs were committed unformatted), so `format-static-checks` does not gate this markdown.
- **AC4 diagrams.** Mermaid + fenced ASCII used throughout (R10) — there is no screenshot pipeline in the repo. All five required illustrations are present: queue with a review-needed run, tri-pane shell, clarification lifecycle indicator, Decision Bar in both `ready` and `blocked` states, and the rejection dialog with the three rework radios.
- **Logging task N/A (R11).** No service / branch / SLF4J surface exists in a markdown-only change; the cross-cutting logging task is honestly marked N/A rather than padded.

### File List

**Added**
- `docs/pm-loop-walkthrough.md` — the PM spec-review-loop walkthrough (main deliverable).

**Modified**
- `README.md` — added a "Quick links" entry pointing at `docs/pm-loop-walkthrough.md` (AC10 docs-index obligation).
- `docs/glossary.md` — added the `### clarification` entry registering the incorporation-lifecycle vocabulary (AC12 glossary discipline) and a "Linked from" back-reference to the walkthrough.

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2026-06-13 | 0.1 | Story drafted (create-story) — 12 ACs, 7 tasks, R1–R11 live-UI reconciliations, live-string inventory. | create-story |
| 2026-06-13 | 1.0 | Implemented: authored `docs/pm-loop-walkthrough.md`, README Quick-links entry, `clarification` glossary entry. Links hand-verified (lychee absent locally). Status → review. | dev-story (Amelia) |

## Review Findings

_bmad-code-review 2026-06-13 — 3-layer adversarial review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the story-scoped working-tree diff (3 files: `docs/pm-loop-walkthrough.md` NEW +316; `docs/glossary.md` +14; `README.md` +1). Acceptance Auditor: **12/12 ACs MET, 0 R-violations**. Triage: 0 decision / 2 patch / 4 defer / 16 dismissed. The two patches are live-source verbatim-fidelity misses confirmed against `deliveryline-frontend/src`; all Blind-Hunter "High/Critical" internal-consistency flags were dismissed as faithful renderings of the deliberately-dual live UI vocabulary (3-pill `LIFECYCLE_STAGES` vs per-item status labels) or false positives from its no-file-access vantage (sibling links, glossary anchor, and story refs 2-11/2-12 all verified to resolve)._

- [x] [Review][Patch] Queue heading quoted as "Review queue" but the live `<h1>` is "Run review queue" — the single most prominent on-screen string the PM lands on first [docs/pm-loop-walkthrough.md:78 vs deliveryline-frontend/src/features/workflows/QueueShell.tsx:200] — FIXED: ASCII mockup now reads "Run review queue"
- [x] [Review][Patch] Clarification status table omits the live labels "In progress" and "Invalid answer" — Task 3 enumerated all 8 labels; "In progress" is a happy-path state a PM hits the moment they start typing an answer, with no doc anchor today [docs/pm-loop-walkthrough.md:142-149 vs deliveryline-frontend/src/features/workflows/clarificationView.ts:168,174] — FIXED: added "In progress" and "Invalid answer" rows (table now lists all 8 live labels)
- [x] [Review][Defer] Dual lifecycle representation may confuse — the headline 3-pill indicator (`submitted › accepted › incorporated`) and the Mermaid/per-item set (`Open → Answered → Accepted → Incorporated`) are both faithful to the live UI but are never bridged with one sentence [docs/pm-loop-walkthrough.md:135,154-163] — deferred, clarity polish (both representations are accurate)
- [x] [Review][Defer] Superseded "Safe to approve?" cell reads "See the note shown" — the one genuinely ambiguous case is punted; could be strengthened to "No — check for a follow-up first" [docs/pm-loop-walkthrough.md:148] — deferred, wording polish (not incorrect)
- [x] [Review][Defer] ASCII diagram right-border alignment is ragged in the tri-pane and queue mockups [docs/pm-loop-walkthrough.md:51-61,77-83] — deferred, cosmetic
- [x] [Review][Defer] Role section's unqualified "Anyone … can perform any action" reads against the two documented state-machine gates (blocked-approve, out-of-date) — one clause distinguishing permission-gating from state-gating would prevent a careful reader's double-take [docs/pm-loop-walkthrough.md:283-287] — deferred, clarity polish
