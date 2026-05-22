import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Story 2.6 (Task 7) — MINIMAL Vitest config for the data-layer test suite only.
//   • Deliberately does NOT load the TanStack Router plugin (no route-tree codegen
//     during unit tests) — only `@vitejs/plugin-react` for JSX in hook tests.
//   • jsdom env so React hooks (`renderHook`) and the fetch-based client run.
//   • MSW lifecycle is wired in `src/test/setup.ts`.
// Story 2.27 (backlog) stands up the FULL suite (component / route / a11y /
// sanitization / cross-browser); this config is intentionally narrow.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost/' } },
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
