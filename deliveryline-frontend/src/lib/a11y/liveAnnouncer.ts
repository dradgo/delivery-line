/**
 * Story 4.24 review (P3) — a document-level polite live announcer.
 *
 * Some announcements must survive the announcing component's own unmount. The failure-classification
 * dialog closes itself on success (`handleOpenChange(false)`), so an in-dialog `aria-live` region is
 * torn down before it can ever speak the confirmation. This singleton owns ONE visually-hidden polite
 * region appended to `<body>` (created lazily, reused thereafter) so any component can announce a
 * message that outlives its render tree.
 *
 * Pure DOM (no React, no JSX) — safe to call from an event handler or a mutation `onSuccess`. The
 * announced TEXT must still come from the shared `announcements.ts` vocabulary (this module never
 * inlines wording).
 */
let region: HTMLElement | null = null;

function ensureRegion(): HTMLElement {
  if (region !== null && region.isConnected) {
    return region;
  }
  const el = document.createElement('div');
  el.setAttribute('role', 'status');
  el.setAttribute('aria-live', 'polite');
  el.setAttribute('data-testid', 'global-live-announcer');
  // Visually hidden but exposed to assistive tech (mirrors the `.sr-only` utility).
  Object.assign(el.style, {
    position: 'absolute',
    width: '1px',
    height: '1px',
    margin: '-1px',
    padding: '0',
    overflow: 'hidden',
    clip: 'rect(0 0 0 0)',
    whiteSpace: 'nowrap',
    border: '0',
  } satisfies Partial<CSSStyleDeclaration>);
  document.body.appendChild(el);
  region = el;
  return el;
}

/**
 * Announce a polite message that outlives the caller's unmount. Clears the region first, then sets
 * the text on the next tick so an identical consecutive message is still perceived as a change.
 */
export function announce(message: string): void {
  if (message === '') {
    return;
  }
  const el = ensureRegion();
  el.textContent = '';
  window.setTimeout(() => {
    el.textContent = message;
  }, 50);
}
