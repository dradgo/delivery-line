# Responsive Manual Checklist — Real-Device Critical-Flow Validation

> **Story 2.26 (AC6, AC9, D2, D4) deliverable.** Automated responsive coverage
> (`jsdom` resize-based structural tests via `@/test/matchMedia`, the breakpoint
> matrix, and the mobile/tablet axe scans) ships in CI via the `frontend-build-tests`
> job. This document covers what `jsdom` **cannot** assert — it has no layout engine,
> so it cannot measure real pixels (touch-target size, no-horizontal-scroll,
> sticky-footer reachability) or real-browser behaviour. Those are verified here on a
> real device, and the executable **cross-browser / mobile-viewport Playwright**
> automation is owned by **story 2.27** (its AC8/AC9 reference "per story 2.26
> AC11/AC9").

## Execution requirement (E2 close gate)

**This checklist MUST be executed at least once before Epic 2 is closed.** It is the
real-device companion to the automated responsive gates and is referenced from the
**story-2.28 E2-close gate** (story 2.28 is backlog; until it formalizes the gate,
this document *is* the gate reference, mirroring how
[`a11y-screen-reader-checklist.md`](./a11y-screen-reader-checklist.md) is wired).
Executing the manual run is an E2-close activity — the story-2.26 deliverable is this
checklist itself, not the run.

Record each run in the [Sign-off log](#sign-off-log) below.

## Reference device & browsers

| Tier                       | Target                                                                                       |
| -------------------------- | -------------------------------------------------------------------------------------------- |
| Smallest supported mobile  | **Samsung Galaxy S23+ class** (≈ 384–393 CSS px wide) — or a documented equivalent.          |
| Supported desktop browsers | Modern **Chrome, Firefox, Safari, Edge** — current + n-1 major versions (evergreen).         |
| Excluded                   | Internet Explorer (all) and any legacy / non-evergreen engine.                               |

The breakpoint matrix and collapse contract under test are documented in
[`deliveryline-frontend/src/features/workflows/RESPONSIVE.md`](../../deliveryline-frontend/src/features/workflows/RESPONSIVE.md);
the device/browser policy is recorded in
[`supported-environments.md`](../supported-environments.md).

---

## Critical journey — queue → run → read artifact → clarify → decide

Run on a real Galaxy S23+ (or documented equivalent), in portrait, on at least Chrome
for Android and Safari on iOS-class hardware where available.

- [ ] **Queue.** `/workflows` loads as a single full-width column. Run rows are
      tappable; no horizontal scroll appears at any point.
- [ ] **Open a run.** Tapping a row opens the run; the persistent `MobileTopBar`
      shows the **run identity + current-state badge** (icon **+** text, never
      color-alone) and they remain visible while scrolling the artifact.
- [ ] **Read the current artifact.** The artifact occupies the central column and is
      readable without pinch-zoom; long content scrolls the main pane only (the top
      bar and sticky footer stay put).
- [ ] **Disclose supporting context.** The right-panel toggle opens the supporting
      context as a slide-out drawer over the artifact (Run Context Strip 2.16,
      Clarification Region 2.18); closing it returns focus to the artifact. The
      artifact is never occluded except while the drawer is intentionally open.
- [ ] **Navigation.** The hamburger opens the left navigation drawer; queue
      navigation works; selecting a destination closes the drawer.
- [ ] **Answer a clarification.** The clarification control is reachable and operable
      by touch.
- [ ] **Approve / reject-with-reason.** The `Approve` / `Reject with feedback`
      primary action is in the **sticky footer** and reachable without hunting at the
      bottom of a long artifact; the rejection rationale dialog is usable on the
      narrow viewport.
- [ ] **Compare Mode (mobile bounded state — story 4.21 / UX-DR23).** Open Compare
      Mode on a **v2+ artifact** (one with a prior revision, so a real baseline
      compares). Confirm the mobile bounded state per `RESPONSIVE.md` §3:
  - [ ] It opens as a **dedicated full-screen takeover** (covers the nav rail and the
        supporting-context panel) — **not** a compressed side-by-side; only one
        revision is shown at a time.
  - [ ] The persistent top bar shows the **revision A/B labels**, an always-visible
        **before/after toggle**, and the **exit (X)** control; the toggle flips which
        single revision the column shows (spec section-by-section / plan
        step-by-step). For a PR-output compare the per-file diffs render one file at a
        time and the before/after toggle is hidden (the diff is inherently
        before/after).
  - [ ] The **Previous / Next change** buttons advance through the changed regions in
        the shown revision (they replace the desktop J/K shortcuts).
  - [ ] The **exit (X)** returns to the originating review context, preserving the run
        + artifact selection; the body scrolls beneath the persistent top bar.
  - [ ] **Tap targets** — the before/after toggle, the prev/next buttons, and the exit
        control are each ≥ 44×44 CSS px (the `min-h-touch` / `min-w-touch` floor).

## Pixel-level checks (jsdom cannot measure these — D2/D4)

- [ ] **Touch targets ≥ 44px.** Interactive controls (queue rows, the decision-bar
      buttons, icon-only toggles, the dismiss/close controls) are at least 44×44 CSS
      px on the real device — the committed floor (`min-h-touch` / `min-w-touch`,
      story 2.25 `tailwind.config.ts`). This is the live-viewport verification story
      2.25 deferred here (D4); the static class-presence test lives in 2.25's
      `touch-target.test.tsx`.
- [ ] **No horizontal scroll.** No screen of the critical journey introduces a
      horizontal scrollbar at the mobile breakpoint.
- [ ] **Sticky-footer reachable.** The decision bar stays anchored to the bottom and
      is tappable even with a long artifact and the on-screen keyboard raised.
- [ ] **Run identity always visible.** The run identity + current-state badge never
      disappear during scroll or while a drawer is open (UX-DR23 non-collapsible set).

## Breakpoint sweep (desktop browser, resize)

- [ ] Resizing a desktop browser desktop → tablet → mobile and back never loses run
      identity or the current-state badge, and never breaks the layout into overlap or
      horizontal scroll.
- [ ] The right context panel collapses to a drawer **before** the main artifact pane
      narrows (artifact primacy — `RESPONSIVE.md` §2).

---

## Sign-off log

| Date      | Device / browser            | Tester | Result / notes |
| --------- | --------------------------- | ------ | -------------- |
| _pending_ | Galaxy S23+ / Chrome Android |        |                |
| _pending_ | iOS-class / Safari          |        |                |
| _pending_ | Desktop resize / Chrome     |        |                |

> **Compare Mode (mobile bounded state — story 4.21) sign-off note.** The Compare-Mode
> full-screen takeover is a jsdom-unverifiable surface (D2/D4): the `fixed inset-0`
> coverage of the nav rail + context panel, the ≥ 44×44 CSS-px tap targets on the
> before/after toggle + prev/next + exit, and keyboard focus staying trapped within the
> takeover (no Tab escape into the covered chrome) MUST be confirmed on a Galaxy S23+
> class device against a **v2+ artifact**. Record the result in the row above and note
> any deviation from `RESPONSIVE.md` §3.
