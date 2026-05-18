# DeliveryLine — Quickstart

DeliveryLine is a governed delivery-pipeline runtime: it submits Linear tickets for AI-assisted
execution against a real append-only audit trail, with `submit` / `status` / `history` / `retry`
as the Epic-1 CLI surface.

**Target time:** ~15 minutes from zero to first governed run.

> **Pilot-installer validator:** `_____________________________` (to be named before Epic 1 close)

This doc is the **happy-path first-run flow**. For a failed run, jump to
[`failure-recovery-walkthrough.md`](failure-recovery-walkthrough.md). For depth on each install
step (per-OS package commands, `.env` key reference, troubleshooting) see
[`setup-local.md`](setup-local.md). For OS / Java / Docker support matrix see
[`supported-environments.md`](supported-environments.md).

---

## Prerequisites

You need these on PATH before step 1:

- **Java 21** — Temurin / Adoptium 21. Install detail per OS:
  [`setup-local.md#install-java-21`](setup-local.md#install-java-21).
- **Docker** — Docker Desktop 4.x on Windows or macOS, or Docker Engine 24+ on Ubuntu. Install
  detail: [`setup-local.md#install-docker`](setup-local.md#install-docker).
- **git** — any modern version.
- **Windows shell** — PowerShell 5.1 or 7+. PowerShell Core 6.x and older Windows PowerShell
  versions are not supported. Verify with `$PSVersionTable.PSVersion`.
- **≥ 4 GB RAM free** for the Postgres container + the backend JVM.

> **WSL2 users:** follow the **bash** variants throughout this doc — WSL2 is a Linux shell. The
> only WSL2-specific setup is enabling Docker Desktop's WSL2 integration (Settings → Resources
> → WSL Integration). Detail: [`setup-local.md#install-docker`](setup-local.md#install-docker).

---

## 0. Choose your environment

DeliveryLine supports four environments for Epic 1. Confirm your OS is one of these before
continuing — the supported-environment matrix is the source of truth; this doc gives the short
version.

| OS / shell | Notes |
|---|---|
| Windows 11 (PowerShell 5.1 or 7+) | Follow the `PowerShell (Windows)` blocks. Docker Desktop required. |
| macOS 14+ (Sonoma) | Follow the `bash` blocks. Docker Desktop required. |
| Ubuntu 22.04 LTS or 24.04 LTS | Follow the `bash` blocks. Docker Engine 24+ (no Docker Desktop on Linux). |
| WSL2 Ubuntu (under Windows 11) | Follow the `bash` blocks inside the WSL2 shell. Docker Desktop on the Windows host with WSL2 integration enabled. |

Detail per OS: [`supported-environments.md`](supported-environments.md). The Spring Boot CLI's
`supported-environment` check (run in step 5) consults this same matrix at runtime, so a
mismatch surfaces as a `doctor` warning, not a silent runtime crash.

---

## 1. Clone

### PowerShell (Windows)

```powershell
git clone https://github.com/YOUR-ORG/deliveryline.git deliveryline
Set-Location deliveryline
```

### bash (macOS / Linux / WSL2)

```bash
git clone https://github.com/YOUR-ORG/deliveryline.git deliveryline
cd deliveryline
```

> Replace `YOUR-ORG` with the GitHub organization or username that hosts the repo — e.g. if
> the URL is `https://github.com/acme-co/deliveryline`, use `acme-co`. The explicit
> `deliveryline` target on `git clone` forces the working-directory name to match the
> subsequent `Set-Location` / `cd` step regardless of how the remote repo is named.

---

## 2. Configure `.env`

The Docker Compose file references `${POSTGRES_PASSWORD:?...}` and a few other env vars —
copy the shipped `.env.example` **before** starting Postgres. The `:?` syntax has no default;
Compose-up aborts if the variable is unset.

### PowerShell (Windows)

```powershell
Copy-Item .env.example .env
```

### bash (macOS / Linux / WSL2)

```bash
cp .env.example .env
```

For the **mock-only first-run path** this doc walks, no `.env` key needs to be edited — the
shipped values in `.env.example` are sufficient:

| Key | Shipped value | Edit when |
|---|---|---|
| `LINEAR_API_KEY` | empty | You want to point at a real Linear workspace (Epic 1 ships `linear-mock`). |
| `GITHUB_TOKEN` | empty | Epic 3 GitHub adapter — not consumed today. |
| `DELIVERYLINE_HOME` | empty (resolves to `./deliveryline-data`) | You want artifacts stored elsewhere. |
| `POSTGRES_PASSWORD` | `deliveryline` | You hardened the local Postgres credentials. Required (no Compose-side default); `.env.example` ships a value so the copy alone is enough. |
| `POSTGRES_HOST_PORT` | `5432` | Port 5432 is already in use on the host. |

Full per-key reference: [`setup-local.md#configure-env`](setup-local.md#configure-env).

> **Do NOT commit `.env`** — it is in `.gitignore` per story 1.1 AC6.

---

## 3. Start Postgres

The unified Docker Compose file ships Postgres 17 with a named volume
(`deliveryline-postgres-data`) so your run state survives container restarts. The recommended
entrypoint is `scripts/start-all` — it wraps `docker compose --profile observability up -d`
so the same script keeps working when Epic 3 adds Prometheus / Grafana / ELK.

### PowerShell (Windows)

```powershell
.\scripts\start-all.ps1
```

### bash (macOS / Linux / WSL2)

```bash
./scripts/start-all.sh
```

<details>
<summary>What this does under the hood</summary>

```bash
docker compose --profile observability up -d
```

Epic 1 ships only the `postgres` service in `docker-compose.yml`; the observability profile is
empty until Epic 3 lands. `docker compose --profile observability up -d` is a no-op against
profile-tagged services that do not yet exist — it is not an error.

</details>

Verify the container is running:

### PowerShell (Windows)

```powershell
docker compose ps
```

### bash (macOS / Linux / WSL2)

```bash
docker compose ps
```

The `postgres` service should appear with `STATUS` of `Up X seconds (healthy)`. For the first
~30 seconds after `start-all` you'll see `health: starting` instead — Postgres has a 30-second
healthcheck start period in `docker-compose.yml`. Wait for `(healthy)` before continuing.

---

## 4. Build the CLI jar

DeliveryLine ships its CLI as a runnable Spring Boot jar (story 1.21). Build it once; every
`deliveryline` command below invokes it via `java -jar`.

### PowerShell (Windows)

```powershell
.\mvnw -B -ntp -pl deliveryline-backend -am package -DskipTests
$env:DELIVERYLINE_JAR = (Get-ChildItem deliveryline-backend\target\deliveryline-backend-*.jar |
  Where-Object { $_.Name -notlike '*.original*' } |
  Select-Object -First 1).FullName
```

### bash (macOS / Linux / WSL2)

```bash
./mvnw -B -ntp -pl deliveryline-backend -am package -DskipTests
export DELIVERYLINE_JAR=$(ls deliveryline-backend/target/deliveryline-backend-*.jar | grep -v '\.original$' | head -1)
```

The build takes ~60 seconds on a fresh checkout; subsequent rebuilds are incremental. The
exported `DELIVERYLINE_JAR` variable is consumed by every command below — keep the same shell
session open, or re-export it before running steps 5–7.

> The `.\scripts\doctor.{ps1,sh}` wrappers remain available as a fallback path that boots
> Spring Boot via `mvnw spring-boot:run` (slower; no jar required). They are not the primary
> invocation path — they spin up a full Spring context per command, which is wasteful for
> quickstart's three back-to-back invocations.

---

## 5. Run `doctor`

`deliveryline doctor` runs a fixed set of prerequisite checks (JVM, Postgres, Flyway,
supported-environment). Reference: [`cli/doctor.md`](cli/doctor.md).

### PowerShell (Windows)

```powershell
$env:SPRING_PROFILES_ACTIVE = 'demo'
java -jar $env:DELIVERYLINE_JAR deliveryline doctor
```

### bash (macOS / Linux / WSL2)

```bash
export SPRING_PROFILES_ACTIVE=demo
java -jar "$DELIVERYLINE_JAR" deliveryline doctor
```

The `demo` profile activates the `runners.mock + linear-mock` profile group so subsequent
`submit` calls hit the fixture adapters instead of real Linear / runner services.

Expected last line:

```text
overall: PASS
```

A clean-install sample report is in [`cli/doctor.md`](cli/doctor.md). If `overall: FAIL`, the
report's `remediation:` line tells you the next step (e.g. `DOCTOR_POSTGRES_UNREACHABLE` →
step 3 of this quickstart was not run yet, or Postgres has not finished its healthcheck
start-period).

<details>
<summary>Fallback: doctor via the wrapper script</summary>

If the jar build in step 4 failed and you still need a doctor read, the `scripts/doctor.*`
wrappers invoke `mvnw spring-boot:run` directly. They require `.env` to exist (step 2) and
Postgres to be running (step 3) — they cannot run earlier.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'demo'; .\scripts\doctor.ps1
```

```bash
SPRING_PROFILES_ACTIVE=demo ./scripts/doctor.sh
```

</details>

---

## 6. Submit your first run

Submit a fixture Linear ticket (`LIN-101`) for governed execution. The `linear-mock` profile
(activated by `SPRING_PROFILES_ACTIVE=demo` in step 5) resolves the ticket from a fixture file
— no real Linear API call. Full reference:
[`cli/workflow-commands.md`](cli/workflow-commands.md).

### PowerShell (Windows)

```powershell
java -jar $env:DELIVERYLINE_JAR deliveryline submit `
  --ticket LIN-101 `
  --actor-identity YOUR_NAME `
  --actor-type human `
  --correlation-id quickstart-1
```

### bash (macOS / Linux / WSL2)

```bash
java -jar "$DELIVERYLINE_JAR" deliveryline submit \
  --ticket LIN-101 \
  --actor-identity YOUR_NAME \
  --actor-type human \
  --correlation-id quickstart-1
```

> Replace `YOUR_NAME` with your username — e.g., `--actor-identity alex`. The unbracketed
> placeholder is deliberate: angle-bracket placeholders like `<your-name>` parse as shell
> input-redirection in both bash and PowerShell and fail with cryptic errors if you forget to
> substitute. The `--actor-identity` value is recorded against every workflow event you
> trigger.

Successful output:

```text
run_abc1234 submitted (state: Inbox)
```

The first token (`run_abc1234`) is the **run ID** — copy it for step 7.

> The mock runner picks up your new run within ~1 second of `submit` and begins advancing it
> automatically. By the time you run `status` in step 7, the run will have moved past `Inbox`
> — typically to `Executing` or beyond. This is expected mock-mode behavior; the runner
> auto-advances through every state including the spec-review gate that would block on a
> human approval in real Epic 2+ flows.

---

## 7. Inspect status and history

Use the run ID you just copied. Keep the same shell session open so `SPRING_PROFILES_ACTIVE`
and `DELIVERYLINE_JAR` are still set — if you opened a new terminal, re-export both before
running these commands.

### PowerShell (Windows)

```powershell
java -jar $env:DELIVERYLINE_JAR deliveryline status run_abc1234
java -jar $env:DELIVERYLINE_JAR deliveryline history run_abc1234
```

### bash (macOS / Linux / WSL2)

```bash
java -jar "$DELIVERYLINE_JAR" deliveryline status run_abc1234
java -jar "$DELIVERYLINE_JAR" deliveryline history run_abc1234
```

> Substitute `run_abc1234` with the run ID from your previous step. It is the first token of
> the `submit` command's stdout.

JSON mode for tooling consumers — same arguments, plus `--format json`:

### PowerShell (Windows)

```powershell
java -jar $env:DELIVERYLINE_JAR deliveryline status run_abc1234 --format json
java -jar $env:DELIVERYLINE_JAR deliveryline history run_abc1234 --format json
```

### bash (macOS / Linux / WSL2)

```bash
java -jar "$DELIVERYLINE_JAR" deliveryline status run_abc1234 --format json
java -jar "$DELIVERYLINE_JAR" deliveryline history run_abc1234 --format json
```

Both JSON shapes are pinned by `workflow-status.v1` / `workflow-history.v1` schemas — see
[`cli/workflow-commands.md`](cli/workflow-commands.md) for the contract.

---

## 8. Interpret what you saw

Text-mode `status` output for a mock run typically looks like this:

```text
current state: Executing
current actor: amelia/agent
last event type: workflow.stateChanged
last event timestamp: 2026-05-15T09:05:00Z
linked ticket: linear:LIN-101
next safe action: await_outcome
```

Field by field:

- **`current state`** — the canonical workflow state from the state-transition table. Possible
  values: `Inbox`, `Planned`, `Investigating`, `WaitingForSpecApproval`, `Executing`,
  `WaitingForReview`, `Paused`, `Failed`, `Completed`, `TakenOver`, `Reconciled`. The
  `WaitingForSpecApproval` and `WaitingForReview` gates are human-approval steps in real
  flows; the mock-mode runner auto-approves them in Epic 1 so the run flows through to
  `Completed` without operator intervention.
- **`current actor`** — `<identity>/<actorType>` of the actor whose action produced the last
  event. `amelia/agent` here is the mock runner advancing the workflow; `YOUR_NAME/human` on
  the `submit` event.
- **`last event type`** — the type from the workflow-event registry (e.g.
  `workflow.stateChanged`, `runner.dispatchSubmitted`, `recovery.retried`).
- **`last event timestamp`** — ISO-8601 UTC. The wall-clock the event was appended.
- **`linked ticket`** — `<type>:<externalRef>` of the integration link (omitted when there is
  no active link).
- **`next safe action`** — the operator's decision aid: `await_outcome` (workflow is
  progressing — watch), `view_only` (terminal — nothing to do), `retry` (re-dispatch a failed
  run), or `await_manual_reconciliation` (Failed run with a partial artifact write — do not
  retry). Full matrix:
  [`cli/workflow-commands.md`](cli/workflow-commands.md).

`deliveryline history` shows every appended event in chronological order, one line each. The
mock runner advances the workflow through several states automatically; expect to see
`workflow.stateChanged` events stepping the run through
`Inbox → Planned → … → Executing → Completed`.

---

## If something went wrong

- The submit returned an error — check the exit-code band against
  [`cli/README.md`](cli/README.md) (`1xx` = your input; `2xx` = idempotency conflict; `3xx` =
  runner failure; `4xx` = infrastructure).
- `doctor` reports `FAIL` — the remediation line on the failing check tells you what to do
  next. For `DOCTOR_UNSUPPORTED_ENVIRONMENT` see
  [`supported-environments.md`](supported-environments.md); for everything else see
  [`cli/doctor.md`](cli/doctor.md).
- A run reached `Failed` — read the failure-recovery walkthrough:
  [`failure-recovery-walkthrough.md`](failure-recovery-walkthrough.md).
- You want to start fresh — see
  [`setup-local.md#reset-local-state`](setup-local.md#reset-local-state) for how to wipe local
  artifacts and the Postgres volume.

---

## Concepts you just used

These four PRD-canonical concepts surface in this quickstart. Full definitions live in
[`glossary.md`](glossary.md).

- **ticket** — an external work item (`LIN-101`) submitted for governed execution.
- **run** — one governed execution of a ticket; everything in `deliveryline status` /
  `history` is keyed by run ID.
- **artifact** — the durable output of a workflow stage (spec, implementation plan, PR
  reference). Not surfaced in this quickstart's commands; introduced fully in Epic 2.
- **failure** — a terminal-or-recoverable error state for a run; see the failure-recovery
  walkthrough above.
