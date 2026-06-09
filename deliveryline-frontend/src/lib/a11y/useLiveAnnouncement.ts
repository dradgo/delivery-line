/**
 * Story 2.25 (Task 3 — AC5) — first-render-safe live-region announcement.
 *
 * An `aria-live` region only announces CHANGES to its content; content present at
 * mount is treated as the region's initial value and is NOT spoken. So a region
 * that mounts already holding "Loading the review queue" stays silent on a cold
 * load (the deferred 2.18 first-render gap + the 2.20 cold-load skeleton silence).
 *
 * `useLiveAnnouncement` defers the message by one commit: it returns `''` on the
 * first render, then the real message after mount, so even the first message is
 * observed as a change and announced. Subsequent message changes flow through
 * normally.
 *
 * Pure logic hook (no JSX) — safe to live in `.ts`.
 */
import { useEffect, useState } from 'react';

export function useLiveAnnouncement(message: string): string {
  const [announced, setAnnounced] = useState('');
  useEffect(() => {
    setAnnounced(message);
  }, [message]);
  return announced;
}
