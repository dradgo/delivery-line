// Story 2.28 — test-only stub of a Vite content-hashed asset. The real assets are emitted by the
// frontend build with hashed filenames (e.g. index-<hash>.js); this stub stands in so the wired
// cache-header test can assert `/assets/**` carries `max-age=31536000, immutable` without a
// frontend build. Content is irrelevant — only the response headers are asserted.
export const stub = true;
