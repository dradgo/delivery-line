import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Story 2.6 (Task 7) — Vitest config for the data-layer + (story 2.27) full
// component / route / a11y / sanitization suite.
//   • Deliberately does NOT load the TanStack Router plugin (no route-tree codegen
//     during unit tests) — only `@vitejs/plugin-react` for JSX in hook tests.
//   • jsdom env so React hooks (`renderHook`) and the fetch-based client run.
//   • MSW lifecycle is wired in `src/test/setup.ts`.
//   • `include` stays `src/**/*.test.{ts,tsx}` so Playwright `e2e/**` specs (which
//     run in a real browser, NOT jsdom) are never collected here (story 2.27 S3).
//
// Story 2.27 (Task 5, AC10) — coverage thresholds enforced via the already-installed
// `@vitest/coverage-v8`. `npm run test:coverage` (the Maven `npm-run-test` execution)
// fails the build if a path falls below its documented floor — see frontend/README.md
// "Test suite & coverage" for the per-path rationale. v8 reports into target/ so
// `mvn -pl deliveryline-frontend clean` removes it.
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
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      reportsDirectory: './target/coverage',
      // Only instrument shipped source — never test files, fixtures, generated
      // artifacts (routeTree.gen.ts, schema.d.ts), the dev harness, or barrels.
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/**/*.d.ts',
        'src/test/**',
        'src/dev/**',
        'src/main.tsx',
        'src/routeTree.gen.ts',
        'src/lib/api/schema.d.ts',
      ],
      // AC10 per-path line floors. Numbers + rationale documented in README.md;
      // they guard against regression, so they sit just under measured coverage
      // (never above — that would red the build on day one, OQ-4).
      thresholds: {
        // Floors sit just under measured coverage so they guard against regression
        // without redding the build on day one (OQ-4). Measured at story 2.27:
        // sanitization 88.1%, queryKeys 93.8%, features/workflows ~90%.
        'src/lib/sanitization/**': { lines: 86 },
        'src/lib/queryKeys/**': { lines: 90 },
        'src/features/workflows/**': { lines: 85 },
      },
    },
  },
});
