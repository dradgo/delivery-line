import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import { tanstackRouter } from '@tanstack/router-plugin/vite';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
// Story 2.1 — Vite config for the deliveryline-frontend module.
//   - build.outDir → 'target/dist' so `mvn -pl deliveryline-frontend clean` removes it.
//   - server.port → PORT env override (default 5173). Override on PowerShell with
//                   `$env:PORT=5174; npm run dev`, POSIX with `PORT=5174 npm run dev`.
//   - server.proxy['/api'] → http://localhost:8080 (Spring Boot backend) for local dev.
//   - server.host = true so the dev server binds on all interfaces (WSL2 / Docker Desktop
//                   can reach it via host-side localhost forwarding).
// Parse PORT with explicit guards: `Number("")` → 0 and `Number("abc")` → NaN
// both slip past a plain `?? 5173`, so validate explicitly and fall back to 5173.
// Warn loudly on a rejected non-empty PORT so a typo (e.g. `PORT=5174 ` with a
// trailing space, or `PORT=abc`) doesn't silently drop the dev server back to
// 5173 with no diagnostic.
function resolvePort(raw: string | undefined): number {
  if (raw === undefined || raw === '') return 5173;
  const parsed = Number(raw);
  if (Number.isInteger(parsed) && parsed > 0 && parsed < 65536) return parsed;
  console.warn(
    `[vite] Ignoring invalid PORT="${raw}" — expected an integer in 1..65535. Falling back to 5173.`,
  );
  return 5173;
}
const port = resolvePort(process.env.PORT);

export default defineConfig({
  // Story 2.5 — TanStack Router file-based routing.
  //   tanstackRouter() MUST run BEFORE react(): it transforms route files and
  //   (re)generates `src/routeTree.gen.ts` during both `vite dev` and `vite build`;
  //   the React plugin then processes the transformed output. Wrong ordering breaks
  //   the codegen pass.
  //   AC7 drift protection: the generated tree is GITIGNORED (never committed), so
  //   there is no stale committed file to diff. The protection is that this plugin
  //   regenerates the tree on every `npm run build` — the enforced Maven/CI path —
  //   so the build always compiles against a fresh tree and FAILS if a route file is
  //   malformed or the tree can't be generated. `npm run check:routes` additionally
  //   asserts the tree was emitted (file exists + non-empty) after build, catching a
  //   silently-disabled plugin. Defaults used: routesDirectory './src/routes',
  //   generatedRouteTree './src/routeTree.gen.ts', quoteStyle 'single'.
  plugins: [tanstackRouter({ target: 'react' }), react()],
  // Story 2.2 — `@/*` → ./src alias for shadcn/ui (mirrors tsconfig.app.json paths).
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: 'target/dist',
    emptyOutDir: true,
  },
  server: {
    port,
    // Fail loud if the resolved port is already in use rather than silently
    // rebinding to a random free port — the README documents a fixed dev port
    // (default 5173 / PORT override) as the `/api` proxy contract, and a silent
    // rebind would make that guidance wrong and confuse WSL2 host-forwarding.
    strictPort: true,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // Dev-only: backend target is plain HTTP (http://localhost:8080), so TLS
        // verification is irrelevant. If the target ever becomes https://, drop
        // `secure: false` so cert problems surface instead of being silenced.
        secure: false,
        // ws:true forwards WebSocket / SSE upgrade requests under /api so future
        // streaming endpoints (e.g., run-event subscriptions) work in dev mode.
        ws: true,
      },
    },
  },
});
