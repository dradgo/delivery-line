/**
 * Story 2.6 (AC7) — Idempotency-Key minter.
 *
 * The backend's idempotency service (story 1.9) keys retries by an
 * `Idempotency-Key` request header. The contract wants a **UUID v7**
 * (timestamp-ordered) key — distinct from the correlation header, which is a
 * UUID v4 (`src/lib/api/correlation.ts`, `newCorrelationId()`). These are two
 * different headers minted by two different generators; do NOT conflate them
 * (story 2.6 TRAP 2).
 *
 * `crypto.randomUUID()` only emits v4, so v7 is hand-assembled here:
 *   • bytes 0–5  : 48-bit big-endian Unix-millis timestamp (time-ordered prefix)
 *   • byte 6     : version nibble `0111` (= 7) over 4 random bits
 *   • byte 8     : variant bits `10` over 6 random bits (RFC 4122)
 *   • all other bits: cryptographically random
 */

/** The canonical idempotency request header (backend idempotency service, story 1.9). */
export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';

const HEX = Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0'));

/**
 * Mint a fresh UUIDv7 for an outbound mutation. Time-ordered so the backend (and
 * any operator inspecting raw keys) sees creation order; the random tail keeps it
 * collision-free. A new key marks a NEW mutation attempt — internal retries of the
 * same attempt MUST reuse the key the hook minted at mutation-start (AC7).
 */
export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);

  // 48-bit millisecond timestamp, big-endian, into bytes[0..5].
  let ts = Date.now();
  for (let i = 5; i >= 0; i--) {
    bytes[i] = ts & 0xff;
    ts = Math.floor(ts / 256);
  }

  // Version 7 in the high nibble of byte 6; RFC-4122 variant in byte 8.
  bytes[6] = (bytes[6]! & 0x0f) | 0x70;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;

  const h = (i: number) => HEX[bytes[i]!];
  return (
    `${h(0)}${h(1)}${h(2)}${h(3)}-${h(4)}${h(5)}-${h(6)}${h(7)}-` +
    `${h(8)}${h(9)}-${h(10)}${h(11)}${h(12)}${h(13)}${h(14)}${h(15)}`
  );
}
