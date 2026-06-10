# Supported environments

This page is the single source of truth for the OS / shell / container-runtime / language-runtime
combinations that DeliveryLine's CLI and bundled jar are tested against. The `deliveryline doctor`
command's `supported-environment` check (story 1.17) consults this matrix at runtime and emits
`PASS`, `WARN` (near-miss), or `FAIL` (`DOCTOR_UNSUPPORTED_ENVIRONMENT`) based on what it detects.

## Support matrix

| OS | Shell | Container runtime | Java | Node | Known-issue footnote |
|---|---|---|---|---|---|
| Windows 11 Pro / Enterprise | PowerShell 5.1 or 7+ | Docker Desktop 4.x | Temurin / Adoptium 21 | 20.19+ or 22.12+ (Epic 2) | (a) |
| macOS 14+ (Sonoma) | zsh or bash | Docker Desktop 4.x | Temurin / Adoptium 21 | 20.19+ or 22.12+ (Epic 2) | (b) |
| Ubuntu 22.04+ LTS | bash | Docker Engine 24+ | Temurin / Adoptium 21 | 20.19+ or 22.12+ (Epic 2) | (c) |
| WSL2 (Ubuntu 22.04+, treated as Linux) | bash | Docker Desktop WSL2 integration | Temurin / Adoptium 21 | 20.19+ or 22.12+ (Epic 2) | (d) |

### Near-miss WARN rows

`doctor` emits a `supported-environment: WARN` (not `FAIL`) when it detects one of these
untested-but-likely-compatible combinations. The `DOCTOR_UNSUPPORTED_ENVIRONMENT` error code is still
present in the report, but `overall` does not flip to `FAIL` on `WARN`:

- Windows 10 + PowerShell 5.1/7+ + Docker Desktop — likely compatible but not exercised in CI.
- macOS 13 (Ventura) + zsh + Docker Desktop — Sonoma is the supported floor.
- Ubuntu 20.04 LTS + bash + Docker Engine 24+ — kernel/glibc differences may surface; track upstream
  before promoting to PASS.

Combinations outside the matrix and the near-miss list (e.g., Solaris, AIX, Alpine, BSDs) fail with
`DOCTOR_UNSUPPORTED_ENVIRONMENT` and exit code `401`.

## Browser & device support (web UI — Epic 2 onward)

The bundled web UI (the Vite SPA served by the jar) is supported on the evergreen browsers below;
the smallest mobile target the responsive layout is designed and validated against is a Samsung
Galaxy S23+ class device. The breakpoint matrix and structural-collapse contract are documented in
[`deliveryline-frontend/src/features/workflows/RESPONSIVE.md`](../deliveryline-frontend/src/features/workflows/RESPONSIVE.md);
real-device critical-flow validation runs from
[`docs/testing/responsive-real-device-checklist.md`](testing/responsive-real-device-checklist.md)
before Epic 2 closes.

| Browser | Versions | Notes |
|---|---|---|
| Chrome | current + n-1 (evergreen) | Desktop + Chrome for Android. |
| Firefox | current + n-1 (evergreen) | |
| Safari | current + n-1 (evergreen) | macOS + iOS-class hardware. |
| Edge | current + n-1 (evergreen) | Chromium-based. |
| Internet Explorer / legacy engines | — | **Not supported** — excluded. |

| Device class | Target | Notes |
|---|---|---|
| Smallest supported mobile | Samsung Galaxy S23+ class (≈ 384–393 CSS px wide) | Reference device for the responsive real-device checklist (UX-DR24). Documented equivalents accepted. |

> Executable cross-browser / mobile-viewport verification (Playwright) is owned by **story 2.27**;
> story 2.26 documents this policy and ships the manual real-device checklist (the automated
> enforcement is deferred with rationale — see `RESPONSIVE.md` §4).

## Known-good quickstart (≤ 10 minutes)

> Prerequisites that are not listed here but are required: `git`, the Docker runtime listed in the
> matrix row above, and `Temurin / Adoptium 21`. Node is only needed for Epic 2 onward.

### Windows 11 (PowerShell)

```powershell
# Clone and prepare local env
git clone https://github.com/<your-org>/deliveryline.git
Set-Location deliveryline
Copy-Item .env.example .env

# Start Postgres (and the observability profile once Epic 3 lands)
.\scripts\start-all.ps1

# Verify the install — this is the contract the CI smoke job pins
.\scripts\doctor.ps1 --only supported-environment,java-version --format json
```

### macOS (zsh or bash)

```bash
git clone https://github.com/<your-org>/deliveryline.git
cd deliveryline
cp .env.example .env

./scripts/start-all.sh
./scripts/doctor.sh --only supported-environment,java-version --format json
```

### Ubuntu 22.04+ (bash)

```bash
sudo apt-get update && sudo apt-get install -y git temurin-21-jdk docker-ce docker-ce-cli containerd.io
git clone https://github.com/<your-org>/deliveryline.git
cd deliveryline
cp .env.example .env

./scripts/start-all.sh
./scripts/doctor.sh --only supported-environment,java-version --format json
```

### WSL2 (Ubuntu 22.04 on Windows 11 host)

```bash
# Inside the WSL2 shell, after enabling Docker Desktop's WSL2 integration in Settings -> Resources.
git clone https://github.com/<your-org>/deliveryline.git
cd deliveryline
cp .env.example .env

./scripts/start-all.sh
./scripts/doctor.sh --only supported-environment,java-version --format json
```

If Docker Desktop's WSL2 integration is disabled, the host Postgres on `localhost:5432` is
unreachable from the WSL2 shell. Either enable WSL2 integration (recommended) or override
`POSTGRES_HOST_PORT` in `.env` and re-bind. The unified `docker-compose.yml` binds Postgres to
`localhost:5432` on the host, which Docker Desktop's WSL2 integration forwards transparently when
enabled.

## Known-issue footnotes

(a) **Windows**: Long-path support validated for story 2.1 — no path under
`deliveryline-frontend/node_modules/` exceeds 260 chars in the current dependency tree
(React 18 + Vite 8 + TypeScript 6). If a future transitive dep changes this, enable
`LongPathsEnabled` in the registry (`reg add HKLM\SYSTEM\CurrentControlSet\Control\FileSystem
/v LongPathsEnabled /t REG_DWORD /d 1 /f`, admin required) and document the mitigation in
`deliveryline-frontend/README.md`. PowerShell 5.1 ships by default on Windows 11 and is the
version the scripts target; PowerShell 7+ is also supported.

(b) **macOS**: The file-watcher count limit (`maxfiles`) may need raising for large clones —
`launchctl limit maxfiles 524288 524288`. The `os.arch` is `aarch64` on Apple Silicon and `x86_64`
on Intel Macs; both are accepted by `doctor`.

(c) **Ubuntu**: The Docker daemon must be running before `start-all.sh` — `sudo systemctl start
docker` if you have not enabled it at boot. Ubuntu 20.04 LTS is treated as a near-miss WARN; we
recommend upgrading to 22.04+ before piloting.

(d) **WSL2**: Clock-skew with the Windows host can break Postgres SSL — keep `hwclock` synced
inside WSL2 (`sudo hwclock -s`). `doctor` detects WSL2 by reading `/proc/version` and matching
`Microsoft` or `WSL`. If Docker Desktop's WSL2 integration is disabled, the `docker-availability`
check fails or warns separately (this is intentional — the `supported-environment` check classifies
the OS+shell combination only, and surfaces a `notes` field reminding you to enable WSL2
integration). The root README is created in story 1.22.

## Related references

- [`docs/cli/doctor.md`](cli/doctor.md) — full doctor CLI surface, exit-code semantics, and JSON
  schema.
- [`docs/cli/README.md`](cli/README.md) — CLI exit-code bands and command-suite index.
- [`docker-compose.yml`](../docker-compose.yml) — unified compose file (AR24) with the named
  `deliveryline-postgres-data` volume that `scripts/reset-local.{ps1,sh}` removes.
- [`.env.example`](../.env.example) — `DELIVERYLINE_HOME`, `POSTGRES_HOST_PORT`, and the other
  knobs the quickstart blocks rely on.
