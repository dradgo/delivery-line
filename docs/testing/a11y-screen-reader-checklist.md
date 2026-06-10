# Accessibility Manual Checklist — Screen Reader + Keyboard-Only Journeys

> **Story 2.25 (AC3, AC9, AC11) deliverable.** Automated WCAG-2.1-AA coverage
> (axe-core scans, keyboard-operability tests, contrast + announcement-vocabulary
> gates) ships in CI via the `frontend-build-tests` job. This document covers what
> automation **cannot** assert: real assistive-technology behaviour (NVDA /
> VoiceOver speech) and end-to-end keyboard-only journeys on real browsers.

## Execution requirement (E2 close gate)

**This checklist MUST be executed at least once before Epic 2 is closed.** It is the
manual companion to the automated a11y gates and is referenced from the **story-2.28
E2-close gate** (story 2.28 is backlog; until it formalizes the gate, this document
*is* the gate reference). Executing the manual run is an E2-close activity — the
story-2.25 deliverable is this checklist itself, not the run.

Record each run in the [Sign-off log](#sign-off-log) below.

## Tooling

| Platform | Screen reader | Browser |
| --- | --- | --- |
| Windows | NVDA (latest) | Firefox + Chrome |
| macOS | VoiceOver | Safari |

Cross-browser **keyboard** automation of these journeys is owned by story 2.27
(Playwright); this manual pass is the interim and the SR-speech authority.

---

## Critical journey 1 — queue → run → spec → clarification → approve

Run once with NVDA and once with VoiceOver. Keyboard only (no mouse / trackpad).

- [ ] **Queue load.** On `/workflows` cold load, the polite announcer speaks the
      queue state — "Loading the review queue", then "Review queue loaded: N runs
      available" (it must NOT stay silent on first paint — the deferred 2.20
      cold-load gap). Skeleton rows are `aria-hidden` and not announced.
- [ ] **Queue navigation.** Tab reaches each run row in visual order; focus is
      visible at all times (the `--ring-focus` ring). Each row's accessible name
      reads identity + state + primary attention signal + last-updated.
- [ ] **Open a run.** Enter AND Space both open the focused row. Focus lands
      inside the run view; landmarks (`<nav>` / `<main>` / `<aside>`) are
      navigable by SR landmark commands.
- [ ] **Read the spec.** The spec artifact renders as structured headings/regions;
      section anchors are reachable and activate by keyboard. No content sits
      outside a landmark.
- [ ] **Answer a clarification.** Arrow keys rove between questions; selecting one
      reveals its answer control; submitting announces the lifecycle transition
      ("Clarification …"). When several advance at once, the announcer speaks the
      COUNT ("N clarifications updated"), not just one.
- [ ] **Approve the spec.** The approve control is reachable and activates by
      keyboard; on success the polite region announces the recorded outcome
      ("Specification approved. Decision recorded.") — not a toast-only signal.
- [ ] **No double-speech.** Stacked polite regions (e.g. shell announcer vs an
      `ErrorState`) never announce the same event twice.

## Critical journey 2 — queue → spec → reject-with-feedback

- [ ] **Open the rejection dialog.** It receives focus on open; it is a labelled
      `dialog`/`alertdialog` with an accessible name + description.
- [ ] **Focus containment.** Tab / Shift+Tab stay within the dialog (focus trap).
- [ ] **Tagged feedback.** The rework-reason controls are reachable and operable by
      keyboard; the required-field validation error is announced.
- [ ] **Confirm rejection.** The confirm control activates by keyboard; on success
      the outcome is announced ("Specification rejected. Decision recorded.").
- [ ] **Dismiss.** On close, focus returns to the control that opened the dialog
      (WCAG 2.4.3).

## Keyboard-only journey checklist (AC3 / D2)

Perform both journeys above using ONLY the keyboard (unplug / ignore the mouse):

- [ ] Every interactive control is reachable by Tab; Tab order matches visual order.
- [ ] Focus is visible on every focused element (never lost or invisible).
- [ ] Enter / Space activate buttons; Escape closes dismissible overlays (and is
      intentionally inert on non-dismissible critical warnings).
- [ ] No keyboard trap except intentional modal focus containment.
- [ ] Touch targets are comfortably large (≥44px) — sanity-check on a mobile
      viewport (full px verification is story 2.26).

---

## Code-review checklist (carry into the E2-close / PR review gate)

- [ ] **No role-based action gating (AC9).** No component gates actions on an audit
      role (e.g. `actorRole === 'product_reviewer'`). All gating routes through
      `useAllowedActions`. Enforced by the `no-role-based-action-gating` ESLint rule;
      this line is the human backstop for patterns the rule cannot see.
- [ ] **Audit-role labels are honest (AC8).** Any rendered actor role uses
      `<AuditRoleLabel>` (recorded audit label — "not an enforced permission"),
      never bare role text. Enforced by `no-bare-actor-role-text`.

---

## Sign-off log

| Date | Journey(s) | Screen reader / browser | Tester | Result / notes |
| --- | --- | --- | --- | --- |
| _pending_ | 1 + 2 | NVDA / Firefox | | |
| _pending_ | 1 + 2 | VoiceOver / Safari | | |
