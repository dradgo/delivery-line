# DeliveryLine — Local setup (depth reference)

> **Pilot-installer validator:** `_____________________________` (to be named before Epic 1 close)

This doc is the depth reference behind [`quickstart.md`](quickstart.md). Use it when a
quickstart step needs more context — install detail per OS, troubleshooting, or full option
reference. If you have not run through the quickstart yet, start there first.

---

## Supported environments

The full OS / shell / container-runtime / Java / Node matrix lives in
[`supported-environments.md`](supported-environments.md). That file is the source of truth —
`deliveryline doctor`'s `supported-environment` check (story 1.17) consults it at runtime. This
doc links into specific rows where relevant; it does not duplicate the matrix.

---

## Install Java 21

DeliveryLine targets **Temurin / Adoptium 21**. Other OpenJDK distributions (Oracle, Zulu,
Corretto) at major version 21 are likely compatible but only Temurin is covered by the
supported-environment matrix.

### PowerShell (Windows)

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

After install, open a new PowerShell session so `JAVA_HOME` and the updated `PATH` are picked up.

### bash (macOS)

```bash
brew install --cask temurin@21
```

`brew --cask temurin@21` installs the JDK into `/Library/Java/JavaVirtualMachines/`. If you use
`jenv` or another JVM switcher, add the new install to it after the brew completes.

### bash (Ubuntu 22.04+)

```bash
sudo apt-get install -y wget apt-transport-https gnupg
sudo install -m 0755 -d /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/keyrings/adoptium.asc
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-21-jdk
```

The `sudo install -m 0755 -d /etc/apt/keyrings` line ensures the keyring
directory exists on Ubuntu 22.04 (it is not pre-created); skipping it makes the
following `tee` call fail with "No such file or directory". The Adoptium apt
repository auto-resolves `$(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release)`
to your Ubuntu codename (`jammy`, `noble`, etc.) so the same block works on
22.04 LTS and 24.04 LTS.

### bash (WSL2 Ubuntu)

Same as Ubuntu — inside the WSL2 shell:

```bash
sudo apt-get install -y wget apt-transport-https gnupg
sudo install -m 0755 -d /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/keyrings/adoptium.asc
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-21-jdk
```

> Install Java inside the WSL2 distro, not on the Windows host — `deliveryline doctor` and the
> backend run inside WSL2 when you use the WSL2 shell.

### Verify

After install, in a fresh shell:

#### PowerShell (Windows)

```powershell
java -version
```

#### bash (macOS / Linux / WSL2)

```bash
java -version
```

Expected output starts with `21` — e.g.:

```text
openjdk version "21.0.5" 2024-10-15 LTS
OpenJDK Runtime Environment Temurin-21.0.5+11 (build 21.0.5+11-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.5+11 (build 21.0.5+11-LTS, mixed mode, sharing)
```

If `java -version` reports an older major (17, 11), check that the Temurin install path
appears before older JDK installs on `PATH`, or set `JAVA_HOME` explicitly to the Temurin 21
home directory.

---

## Install Docker

DeliveryLine uses Docker for Postgres (Epic 1) and for runner images + observability (Epic 3).
Allocate **≥ 4 GB RAM and ≥ 2 vCPU** to the Docker VM on Windows/macOS — the default
allocation is enough for Epic 1 but Epic 3 will push it.

### PowerShell (Windows) — Docker Desktop 4.x

Download from [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/) and run the installer. After install, launch Docker Desktop once
to complete first-run setup (it provisions the WSL2 backend or the Hyper-V VM depending on your
choice).

Resource allocation: open Docker Desktop → Settings → Resources → Advanced. Set Memory to
≥ 4 GB and CPUs to ≥ 2.

### bash (macOS) — Docker Desktop 4.x

Either install through the GUI:

```bash
brew install --cask docker
```

Or download from [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/). Launch Docker Desktop once to grant the
privileged helper and provision the VM.

Resource allocation: Docker Desktop → Settings → Resources. Set Memory to ≥ 4 GB and CPUs to
≥ 2.

### bash (Ubuntu 22.04+) — Docker Engine 24+

Follow [https://docs.docker.com/engine/install/ubuntu/](https://docs.docker.com/engine/install/ubuntu/). Summary:

```bash
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

Add your user to the `docker` group so you can run `docker` without `sudo` (log out and back in
after this):

```bash
sudo usermod -aG docker $USER
```

### bash (WSL2) — Docker Desktop WSL2 integration

On the Windows host, install Docker Desktop (see the Windows section above). Then enable
WSL2 integration: open Docker Desktop → Settings → Resources → WSL Integration → toggle on
the WSL2 distros you use. Inside the WSL2 shell, `docker` and `docker compose` will resolve
to the Docker Desktop daemon transparently.

> Two distinct WSL2 concerns to keep separate:
> - **Docker Desktop WSL2 integration** (Settings → Resources → WSL Integration) controls
>   whether the `docker` and `docker compose` CLIs inside the WSL2 distro talk to the
>   Docker Desktop daemon on Windows. If this is off, `docker version` fails inside WSL2
>   with `Cannot connect to the Docker daemon`.
> - **WSL2 networking mode** controls whether `localhost:5432` inside WSL2 reaches the
>   Postgres container that Docker Desktop publishes on the Windows host's loopback. On
>   Windows 11 with default mirrored networking (Windows 11 22H2+), `localhost` works
>   transparently. On older NAT-mode WSL2, you reach the host via the gateway IP
>   (`/etc/resolv.conf`'s `nameserver` line) or by setting `.wslconfig` `networkingMode=mirrored`.
>
> Pilot path: enable Docker Desktop WSL2 integration, and (Windows 11 only) verify mirrored
> networking is active before troubleshooting `POSTGRES_HOST_PORT`. See
> [`supported-environments.md`](supported-environments.md#known-issue-footnotes) footnote (d).

### Verify

#### PowerShell (Windows)

```powershell
docker version
docker compose version
```

#### bash (macOS / Linux / WSL2)

```bash
docker version
docker compose version
```

`docker version` should print a Client + Server section without `Cannot connect to the Docker
daemon`. `docker compose version` should report v2.x — the `docker compose` (subcommand) form
is required; the legacy `docker-compose` (hyphenated binary) is not supported.

---

## Configure `.env`

The shipped `.env.example` documents every environment variable consumed by Docker Compose and
the Spring Boot backend. Copy it once:

### PowerShell (Windows)

```powershell
Copy-Item .env.example .env
```

### bash (macOS / Linux / WSL2)

```bash
cp .env.example .env
```

> **Do NOT commit `.env`** — it is in `.gitignore` per story 1.1 AC6. If you ever paste a real
> Linear or GitHub token into `.env`, it stays on your machine only.

### Per-key reference

| Key | Purpose | Required for | Default behavior if blank | Where to get the value |
|---|---|---|---|---|
| `LINEAR_API_KEY` | Linear API authentication for ticket intake. | Real Linear workspace integration. **Not** required for `linear-mock` profile (Epic 1 default). | Linear adapter calls return mock fixtures; the unused key field is ignored. | [linear.app/settings/api](https://linear.app/settings/api) |
| `GITHUB_TOKEN` | GitHub PAT for PR linkage. | Epic 3 GitHub adapter — **not consumed in Epic 1**. | No GitHub calls happen yet. | [github.com/settings/tokens](https://github.com/settings/tokens) (needs `repo` scope) |
| `DELIVERYLINE_HOME` | Base directory for runtime artifact state. | All flows (Epic 1+). | Resolves to `./deliveryline-data` under the repo root. | Pick any absolute or relative path with write permission. |
| `POSTGRES_PASSWORD` | Local Postgres user password. | All flows — `docker-compose.yml` requires it via `${POSTGRES_PASSWORD:?...}` (no compose-side default). | Compose-up **fails** if the variable is unset or empty; `.env.example` ships the value `deliveryline` so the `cp .env.example .env` step alone is sufficient. | Pick any non-empty string for local; never reuse a real production password here. |
| `POSTGRES_HOST_PORT` | Host port the Postgres container binds to. | Only when the default `5432` collides with another local Postgres. | `5432`. | Pick any free TCP port — e.g. `5433`. |
| `ELASTIC_HOST_PORT` | Host port Elasticsearch binds to (observability stack, story 3.7). | Only when running the `observability` profile AND the default `9200` collides. | `9200`. | Pick any free TCP port. |
| `LOGSTASH_HOST_PORT` | Host port the Logstash TCP/JSON ingest binds to (story 3.7). | Only when running the `observability` profile AND the default `5044` collides. | `5044`. | Pick any free TCP port. |
| `LOGSTASH_HOST` / `LOGSTASH_PORT` | Destination the **backend's** Logstash appender dials (`logback-spring.xml`, observability profile). Distinct from `LOGSTASH_HOST_PORT` (the published container port). | Only when you remap `LOGSTASH_HOST_PORT` or run the backend off-host from Logstash. | `localhost` / `5044` (matches the `LOGSTASH_HOST_PORT` default). | Set both to the host/port where the backend can reach Logstash; keep `LOGSTASH_PORT` in sync with any `LOGSTASH_HOST_PORT` remap. |
| `KIBANA_HOST_PORT` | Host port Kibana binds to (story 3.7). | Only when running the `observability` profile AND the default `5601` collides. | `5601`. | Pick any free TCP port. |
| `ES_JAVA_OPTS` | Elasticsearch JVM heap sizing for the observability stack (story 3.7). | Tune when the default heap is too small/large for your host. | `-Xms512m -Xmx512m`. | Set matching `-Xms`/`-Xmx` (e.g. `-Xms1g -Xmx1g`); keep them equal. |

The observability keys only matter under the `observability` Docker Compose profile (brought up by
`scripts/start-all.{ps1,sh}`); the backend runs identically without them (AR25). `doctor` WARNs
(never FAILs) when that profile is active on a host with under 8 GB of RAM.

### Linux: raise `vm.max_map_count` for Elasticsearch (story 3.7)

Elasticsearch requires the host kernel setting `vm.max_map_count` to be at least `262144`, or the
`elasticsearch` container exits at startup with a bootstrap check failure. This applies to Linux and
WSL2 hosts (Docker Desktop on Windows/macOS sets it inside its own VM automatically). Apply it
before `scripts/start-all.sh`:

```bash
sudo sysctl -w vm.max_map_count=262144
# Persist across reboots:
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-deliveryline-elasticsearch.conf
```

### Reserved keys (later metrics story)

`.env.example` still reserves these keys for the later Prometheus + Grafana metrics story (3.19);
they are commented out and have no effect today. **Do not** set them yet:

- `PROMETHEUS_HOST_PORT`
- `GRAFANA_HOST_PORT`

---

## Choose a Spring profile

`application.yml` defines three profile groups, all of which activate
`runners.mock + linear-mock` as their member profiles:

| Profile | Operator intent | Use it when |
|---|---|---|
| `local` | Active development. | You are hot-reloading code, running with `spring-boot-devtools`, or attaching a debugger. |
| `demo` | Stable show-and-tell. | You are demoing the product to a stakeholder; no restarts, no devtools, no debugger. |
| `test` | CI integration tests via Testcontainers. | **Do not** use as a runtime profile — `@SpringBootTest` activates it automatically. |

Functional behavior is identical for `local` and `demo` — both group to the same mock adapters.
The split exists so operator intent is visible in the active-profile log line (and in the
`doctor` `spring-profile` check). The source-of-truth definition lives in
`deliveryline-backend/src/main/resources/application.yml`; defer to the YAML for any future
profile-group changes.

### Set the profile

### PowerShell (Windows)

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw -pl deliveryline-backend spring-boot:run
```

### bash (macOS / Linux / WSL2)

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw -pl deliveryline-backend spring-boot:run
```

If you prefer to launch via the helper script, `scripts/doctor.sh` and `scripts/doctor.ps1`
already pass the right defaults — but they invoke the `doctor` command, not the full Spring
Boot server. To boot the full server, use the `mvnw` invocations above (or run the packaged
jar produced by story 1.21).

---

## Database migrations

Flyway runs every migration in
`deliveryline-backend/src/main/resources/db/migration/` automatically on the first Spring Boot
start — there is no manual `flyway migrate` step. The Epic-1 baseline is migration `V1` (story
1.4 central registries → core workflow tables); `V2` adds artifact-failure columns.

Flyway is idempotent — restarting the app against an already-migrated database is a no-op. The
`doctor` `flyway-state` check flags `PENDING` / `FAILED` / `OUT_OF_ORDER` migrations as `FAIL`
so you find drift fast. If a migration fails partway, fix the underlying cause first; only then
consider [resetting local state](#reset-local-state).

---

## REST API & localhost binding

DeliveryLine exposes a REST surface (story 6.9) alongside the CLI. In Phase 1 the API is
**unauthenticated**, so it binds **loopback-only by default** — that loopback restriction *is* the
security control.

- Default bind: `server.address: 127.0.0.1`, `server.port: 8080` (both in `application.yml`).
- Read endpoints (the same `WorkflowInspectionService` the CLI `status`/`history` commands use):
  - `GET /api/v1/workflows` — review queue (optional `?state=` filter, `?limit=`),
  - `GET /api/v1/workflows/{workflowRunId}` — single-run detail,
  - `GET /api/v1/workflows/{workflowRunId}/events` — full event history.
- OpenAPI (loopback-bound like everything else):
  - `GET /v3/api-docs` — the OpenAPI JSON document,
  - `GET /swagger-ui.html` — Swagger UI.
  - The committed reference snapshot lives at
    `deliveryline-backend/src/main/resources/openapi/openapi.json`; CI fails on drift
    (`OpenApiSnapshotContractTest`). Regenerate after an intentional API change with
    `./mvnw -pl deliveryline-backend -Dit.test=OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true integration-test`,
    then review and commit the diff.

### Binding to a non-loopback address (development only)

Startup **fails closed** with `DOCTOR_REST_BIND_UNAVAILABLE` if the effective bind address is not
loopback. To deliberately bind a network-reachable interface (e.g. to reach the UI from another
device on your LAN during development), set **both**:

```yaml
deliveryline:
  rest:
    bind-address: 0.0.0.0          # overrides server.address
    unsafe-network-bind: true      # required acknowledgement
```

With `unsafe-network-bind: true` the app starts but logs a `WARN` naming the bound address and the
security implication. **The REST API has no authentication in Phase 1 — only enable this on a
trusted network.** The same loopback check is surfaced by `doctor` (`rest-bind-address` probe).

---

## Code quality checks

The backend enforces formatting, style, and bug-pattern detection mechanically so consistency
does not depend on reviewer discipline (story 2.30):

| Tool | Scope | Wired into |
|---|---|---|
| **Spotless** (Google Java Format) | Formatting — indentation, wrapping, import order, unused imports. | `mvn verify`; CI `format-static-checks`. |
| **Checkstyle** | Style/naming — Java naming conventions, no wildcard/unused imports, issue-referenced TODOs, forbidden `System.out`/`System.err`/`printStackTrace`/`Thread.sleep` in production code. Committed ruleset: `config/checkstyle/checkstyle.xml`. | `mvn verify`; CI `format-static-checks`. |
| **SpotBugs** | Bug patterns. Fails the build on `HIGH`-severity findings; `MEDIUM` is reported but non-failing. | `mvn verify`; CI `format-static-checks`. |

`mvn verify` runs all three. To run them directly without the full test suite:

```bash
./mvnw -pl deliveryline-backend -am spotless:apply              # auto-fix formatting
./mvnw -pl deliveryline-backend -am spotless:check checkstyle:check
```

A failing CI `format-static-checks` tier blocks the merge (it feeds `foundation-gate`).

### Optional pre-commit hook (recommended, not required)

You can opt into a local git pre-commit hook that runs Spotless + Checkstyle before each commit,
so formatting/style problems are caught before they reach CI. SpotBugs is intentionally excluded
from the hook — it is too slow for a commit-time check. **The hook is optional**; the CI tier is
the real gate.

#### PowerShell (Windows)

```powershell
.\scripts\install-git-hooks.ps1                # install
.\scripts\install-git-hooks.ps1 -Uninstall     # remove
```

#### bash (macOS / Linux / WSL2)

```bash
./scripts/install-git-hooks.sh                 # install
./scripts/install-git-hooks.sh --uninstall     # remove
```

The installer is non-destructive: if a non-DeliveryLine `pre-commit` hook already exists it
refuses to overwrite it unless you pass `--force`/`-Force` (which backs the existing hook up to a
`pre-commit.backup` file in your active Git hooks directory first). The scripts resolve that hook
directory via `git rev-parse --git-path hooks`, so they also work with linked worktrees and custom
`core.hooksPath` setups. To skip the hook for a single commit, use `git commit --no-verify`.

---

## Reset local state

`scripts/reset-local.{ps1,sh}` wipes local DeliveryLine state and removes the Flyway-managed
schema. Use it when switching between incompatible schema versions or starting a clean demo.

### PowerShell (Windows)

```powershell
.\scripts\reset-local.ps1
```

### bash (macOS / Linux / WSL2)

```bash
./scripts/reset-local.sh
```

### What gets removed

- The named Docker volume `deliveryline-postgres-data` (declared in `docker-compose.yml`). The
  database is destroyed; the next `start-all` boots a fresh empty Postgres.
- All files under `${DELIVERYLINE_HOME}/artifacts` (defaults to
  `./deliveryline-data/artifacts`). Spec, plan, and PR-reference artifacts written by prior
  runs are gone.
- The Flyway schema-state — implicit, because the database is wiped.

### What survives

- Your `.env` file (still on disk, still ignored by git).
- Your source code and IDE state.
- The repo's git history.

### When to reset

- After a **failed Flyway migration** that you cannot replay forward — wipe and re-apply from
  `V1`.
- When **switching between incompatible schema versions** (e.g. checking out a branch with a
  different migration set).
- When **starting a clean demo** — you want a deterministic empty database.

> The script refuses to delete `DELIVERYLINE_HOME` if it resolves to a system root
> (`/`, `/usr`, `/home`, the repo root, the Windows user-profile root, or a reparse point).
> If you hit that refusal, set `DELIVERYLINE_HOME` to an absolute project-scoped path before
> re-running.

---

## Reproduce the runner-image CI locally

The CI `runner-image-build`, `runner-image-self-test`, and `runner-contract-real`
jobs (story 3.34) build the **real** Codex + Claude runner images, run each
image's `--self-test`, and run the end-to-end `RealRunnerContractIT`. Run the
exact same commands locally on **Linux / Docker** (WSL2 works — see
[Install Docker](#install-docker)) to reproduce a CI failure without pushing.

> **Network:** the real build does a network `npm install` of `@openai/codex`,
> `@anthropic-ai/claude-code`, and `@fission-ai/openspec` at their pinned
> versions. A transient npm-registry failure is an infrastructure flake, not a
> contract break — retry the build.

### 1. Build both REAL runner images

The runner services live behind the `runners` Compose profile, so a **bare**
`docker compose build` builds **neither** of them. You must activate the profile:

```bash
docker compose --profile runners build
```

This builds `deliveryline/codex-runner:latest` and
`deliveryline/claude-runner:latest` with the production toolchain
(`INSTALL_*_CLI=true`). The CI job runs this same command; it additionally
layers a cache-only override ([`docker-compose.ci.yml`](../docker-compose.ci.yml))
that does not change the build graph, so your local build is byte-identical.

### 2. Run each image's `--self-test`

```bash
docker compose run --rm codex-runner --self-test
docker compose run --rm claude-runner --self-test
```

Each prints a structured `OK` block and exits `0` when `node`, the runner
helper, the agent CLI version pin, the OpenSpec CLI pin, and the vendored
superpowers skills are all present. Any miss prints `SELF-TEST FAIL: …` and
exits `1`. (`docker compose run` auto-activates the `runners` profile for a
named service, so no `--profile` flag is needed here.)

### 3. Run the real-runner contract integration test

```bash
./mvnw -Pdocker-runner-it -pl deliveryline-backend -am verify -Dit.test=RealRunnerContractIT
```

The `-Pdocker-runner-it` profile clears the default Failsafe exclusion that
hides `docker-runner-it`-tagged tests; `-am` keeps the freshest
`deliveryline-runner-contracts` jar on the classpath. `RealRunnerContractIT`
builds its own offline mock images and drives the broker → adapter →
contract-validator → persistence path against them.

See [`scripts/start-all.ps1`](../scripts/start-all.ps1) /
[`scripts/start-all.sh`](../scripts/start-all.sh) for the full local-stack
bring-up and [`docker-compose.yml`](../docker-compose.yml) for the runner
service definitions.

---

## Troubleshooting

Most-likely first-run failures and their fixes. If none of these match, run
`deliveryline doctor --format json` and inspect the `remediation:` line on the failing check
— it is designed to be operator-actionable.

### Postgres port 5432 already in use

**Symptom:** `docker compose up -d` reports `port is already allocated` or `bind: address
already in use`.

**Fix:** override `POSTGRES_HOST_PORT` in `.env`:

```bash
POSTGRES_HOST_PORT=5433
```

Then re-run `docker compose up -d`.

### Docker daemon not running

**Symptom:** `docker version` reports `Cannot connect to the Docker daemon`, or `doctor`
reports `docker-availability: WARN Docker unreachable`.

**Fix:**

- **Windows / macOS:** launch Docker Desktop (Start menu / Spotlight). Wait for the system tray
  icon to go solid (≈ 30 s).
- **Ubuntu:** `sudo systemctl start docker` (or `sudo systemctl enable --now docker` to start
  at boot).

### Java 21 not on PATH

**Symptom:** `java -version` reports `Java 17` (or older), or `doctor` reports
`java-version: FAIL`.

**Fix:** see [Install Java 21](#install-java-21) above. After install, open a new shell so
`PATH` is refreshed; or set `JAVA_HOME` explicitly to the Temurin 21 install root and prepend
`$JAVA_HOME/bin` to `PATH`.

### `doctor` reports `DOCTOR_UNSUPPORTED_ENVIRONMENT`

**Symptom:** `supported-environment: FAIL` or `WARN` in the doctor report, with
`errorCode = DOCTOR_UNSUPPORTED_ENVIRONMENT`.

**Fix:** check your OS + shell against
[`supported-environments.md`](supported-environments.md). The near-miss WARN list (Windows 10,
macOS 13 Ventura, Ubuntu 20.04 LTS) is documented in that file — `WARN` does not flip
`overall` to `FAIL`. Combinations outside the matrix and the near-miss list (e.g. Alpine, BSD)
are unsupported in Epic 1.

### `doctor` reports `DOCTOR_POSTGRES_UNREACHABLE`

**Symptom:** `postgres-connectivity: FAIL Postgres unreachable: Connection refused`.

**Fix:** step 2 of the quickstart (start the Postgres container) was not run yet. Run
`scripts/start-all.sh` (or `.ps1`) and re-check. If Postgres is running but the connection
still fails, verify `POSTGRES_HOST_PORT` in `.env` matches what `docker compose ps` reports.

---

## See also

- [`quickstart.md`](quickstart.md) — the linear copy-paste first-run flow.
- [`supported-environments.md`](supported-environments.md) — OS / shell / container-runtime /
  Java / Node matrix consumed by `doctor`.
- [`cli/README.md`](cli/README.md) — CLI exit-code bands and command-suite index.
- [`cli/doctor.md`](cli/doctor.md) — full `doctor` reference (every check, every exit code,
  JSON schema).
- [`failure-recovery-walkthrough.md`](failure-recovery-walkthrough.md) — when a governed run
  hits `Failed`, what the operator does next.
- [`glossary.md`](glossary.md) — PRD-canonical concept set (Epic 1 seed).
