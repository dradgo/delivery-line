import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
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
function resolvePort(raw: string | undefined): number {
  if (raw === undefined || raw === '') return 5173;
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 && parsed < 65536 ? parsed : 5173;
}
const port = resolvePort(process.env.PORT);

export default defineConfig({
  plugins: [react()],
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
    strictPort: false,
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
