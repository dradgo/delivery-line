# Story 3.7: ELK Stack Integration — Centralized Log Capture (Profile-Gated)

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- 2026-06-04 third code-review pass reopened the story: the Logstash gsub chain used Java/PCRE
     syntax (`$1` backreferences + `(?s)` flag) invalid in Logstash's JRuby/Joni engine, masked by a
     parity test that validated with java.util.regex. FIX APPLIED + fast-tier verified (conf → Ruby
     `\1`/`(?m)`/`(?i)`; LogstashRedactionParityTest now translates Ruby→Java and lints out Java-isms,
     5/0/0; spotless+checkstyle clean). REMAINING: Docker-tier Logstash run (Trap T11) to confirm the
     real engine accepts the corrected conf — the reason status is in-progress, not done. -->

> **Resolution (third review pass, 2026-06-04 — Decision: "Full fix + Docker verify"):** the Logstash
> `gsub` second-pass redaction was corrected from Java/PCRE syntax to JRuby/Joni (Ruby) syntax in
> `infra/observability/logstash/pipelines/deliveryline.conf`: `$1`→`\1` backreferences (QUERY_SECRET,
> JSON+YAML SECRET_FIELD, ENV_VALUE) and `(?s)`/`(?ms)`→`(?m)` + `(?im)`→`(?i)` inline flags
> (PEM/SSH private-key + ENVIRONMENT_BLOCK + ENV/YAML). `LogstashRedactionParityTest` was re-pointed
> at the real engine: it now translates each Ruby pattern/replacement to its Java equivalent before
> executing the chain (so the behavioral fixtures validate Logstash semantics, not a Java look-alike)
> AND adds `gsubUsesLogstashRubyRegexSyntaxNotJava` — a lint that rejects Java `$n` backrefs and the
> Java-only `(?s)` flag in the conf (the direct guard that would have caught this in the fast tier).
> Verified: `LogstashRedactionParityTest` 5/0/0, `spotless:check`+`checkstyle:check` 0 violations
> (PowerShell, [[rtk-hook-only-matches-bash]]). **Outstanding before `done`:** run the Docker-tier
> `ElkPipelineRoundTripIT` + a real Logstash pipeline load on WSL2/Linux+Docker to confirm the engine
> accepts the corrected conf and redacts end-to-end ([[wsl-linux-ci-reproduction]] / Trap T11).

## Story

As a workflow owner debugging runner failures or tracing audit trails across many runs,
I want Elasticsearch + Logstash + Kibana added to the unified `docker-compose.yml` under the `observability` profile, a Logstash pipeline ingesting structured Spring Boot logs (story 1.19) and redacted runner logs (story 3.6), a profile-gated Logstash appender on the backend, classification-driven shipping, a defense-in-depth second redaction pass, Kibana dashboards, an ADR, and a doctor memory warning,
so that AR25's observability decision (ELK + Prometheus/Grafana, replacing the prior Loki proposal) is concretely realized — and ops/diagnostic workflows have searchable log history **without ELK ever becoming required for normal operation, tests, or recovery**.

---

## ⚠️ READ FIRST — Central Reconciliations (the epic ACs describe an end-state; here is what is actually true in this repo today)

1. **ADR number collision (Decision D1).** AC9 literally says create `docs/adr/0003-elk-replaces-loki.md`. **`0003` is already taken** by `docs/adr/0003-runner-secrets-mvp-posture.md` (story 3.5 landed it at 0003, not the 0002 its own AC named). The next free sequential number is **`0023`** (existing: 0001, 0002, 0003, 0004, 0019, 0020, 0021, 0022). **→ Create `docs/adr/0023-elk-replaces-loki.md`.** The epic's `0003-...` filename is a stale planning-time number — do NOT overwrite the runner-secrets ADR.

2. **Single compose file, not a separate one (Decision D2).** `architecture.md` (lines 915–917, 1115–1117) sketches a separate `docker-compose.observability.yml` + `infra/observability/prometheus.yml`. That sketch is **superseded by ADR 0001 (unified-compose) + AR24 + epic AC1**, which mandate ONE `docker-compose.yml` with profile gating. **→ Add the 3 ELK services into the existing root `docker-compose.yml` under `profiles: ["observability"]`.** Mirror the `postgres` service style already in that file.

3. **Profile name is `observability` (Decision D3).** AR25 mentions `demo-observability` illustratively, but everything already shipped uses `observability`: `scripts/start-all.{ps1,sh}` already run `docker compose --profile observability up -d`, the compose comments reference the `observability` profile, and every epic AC says `observability`. **→ Spring profile AND compose profile = `observability`.** Do not invent `demo-observability`.

4. **The runner-log file path carries NO inline classification (Decision D5 — the headline reconciliation).** Story 3.6 writes plain-text `runner.stdout`/`runner.stderr` to `{DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/`. The `classification` lives in the DB column `raw_output_classification`, in `RunnerLogReference`, and in workflow-event details (`rawOutputClassification`) — **never inside the log files themselves**. So a Logstash `file` input cannot read a file's classification from the file. See D5 below for the resolution (fail-closed via the existing `RunnerLogShippingPolicy` seam). This is the single most important design decision in the story.

5. **The JSON appender today is `demo`-gated and console-only.** `logback-spring.xml` ships a `LoggingEventCompositeJsonEncoder` to **STDOUT under the `demo` profile only**, and a human PatternLayout under `local,test,!demo`. **There is NO TCP/Logstash-socket appender anywhere.** AC3(a)/AC7 require a NEW `observability`-gated `LogstashTcpSocketAppender` shipping to `:5044`. `net.logstash.logback:logstash-logback-encoder:8.0` is **already on the classpath** (no new dependency needed).

6. **Prometheus + Grafana are OUT OF SCOPE for this story (Decision D8).** AR25's stack is "ELK + Prometheus + Grafana", but story 3.7 delivers **only ELK**. `PROMETHEUS_HOST_PORT`/`GRAFANA_HOST_PORT` are already reserved (commented) in `.env.example`/`setup-local.md`; leave them reserved. Grafana dashboards + Prometheus alerts are owned by the later metrics story (3.19). (Epic line 394's phrase "Grafana dashboards from story 3.7 AC6" is a cross-reference typo — 3.7 AC6 is **Kibana** dashboards.)

---

## Acceptance Criteria

> Reconciled against the live repo. Each AC notes the concrete file(s) and the decision/trap it triggers.

1. **Three ELK services in the single `docker-compose.yml` under `profiles: ["observability"]`** — `elasticsearch` (single-node, version-pinned, `discovery.type=single-node`), `logstash` (mounted pipeline config), `kibana` (linked to elasticsearch). File remains one `docker-compose.yml`; profile gating keeps them out of default `docker compose up -d` and out of Spring Boot 4's docker-compose autoconfig wait (same reason the `runners` profile exists — see the comment block at `docker-compose.yml:21-34`). [D2, T2]

2. **`.env`-configurable ports added to `.env.example`** — `ELASTIC_HOST_PORT` (default 9200), `LOGSTASH_HOST_PORT` (default 5044), `KIBANA_HOST_PORT` (default 5601), and `ES_JAVA_OPTS` (default `-Xms512m -Xmx512m`). Replace the currently-commented placeholder lines (`.env.example` reserved block) with live entries using the `${VAR:-default}` style from `postgres`. Mirror the same into `docs/setup-local.md`'s "Reserved keys" section (promote them from reserved to active). [follows postgres port style at `docker-compose.yml:11`]

3. **`infra/observability/logstash/pipelines/deliveryline.conf`** ingests: (a) Spring Boot structured JSON logs via **TCP JSON on port 5044** (the backend's new `observability`-gated `LogstashTcpSocketAppender` ships here — see AC7); (b) **redacted, shippable** runner log files (story 3.6) via a file input. **(b) is gated by D5** — the file input watches a shippable ingest location that, by construction, only ever contains `shareable-redacted`-and-above logs (local-only never reaches it). `infra/observability/` currently holds only `.gitkeep`. [D5, T5]

4. **Classification-driven shipping filter** — only `shareable-redacted` and above reach Elasticsearch; `local-only` is dropped. For the TCP/JSON path: a Logstash filter `if [classification] == "local-only" { drop {} }` (the field name must match what the appender emits — see AC7/D4). For the runner-log file path: enforced upstream by D5 (fail-closed; nothing local-only is even visible to the input). A **contract test** injects a fixture document tagged `local-only` and asserts it is dropped before indexing; the existing `RunnerLogShippingPolicy.isShippable(...)` predicate (`application/runner/RunnerLogShippingPolicy.java`) is the stable contract this keys on — extend its test, do not redefine the predicate. [D5, T5]

5. **Defense-in-depth second redaction pass** — the Logstash pipeline runs a `grok`+`mutate` (or `gsub`) filter chain matching the **same secret categories** as `SensitivePayloadAnalyzer` / `RedactionPolicyService` (story 1.10), so drift past source-side redaction (story 3.6) is still caught before indexing. The canonical patterns live in `application/security/SensitivePayloadAnalyzer.java` (see Dev Notes for the full literal list). A test feeds a fixture that source-side redaction "missed" and asserts the pipeline strips it. [D6, T6 — drift risk]

6. **Kibana dashboards** under `infra/observability/kibana/dashboards/` as saved-objects JSON for: workflow-events (by type / failure category / time), runner-executions (dispatched/completed/timed-out/orphaned counts + duration heatmap), redaction-audit (redaction counts by source + secret-pattern category), failure-category distribution. Document the first-startup import mechanism in `infra/observability/README.md` (new file). [D8 — these are KIBANA dashboards, not Grafana; OQ-5 for import mechanism]

7. **AR25 hard rule — backend identical with or without ELK.** When `observability` is NOT active: no Logstash appender is configured (logs go to STDOUT only, exactly as today). When `observability` IS active: a `LogstashTcpSocketAppender` ships JSON to `${LOGSTASH_HOST:-localhost}:${LOGSTASH_PORT:-5044}`, **reusing the existing `Redacting*` providers** so shipped logs are source-redacted. A smoke/unit test runs the backend with `observability` disabled and asserts **zero Logstash connection attempts** (no `LogstashTcpSocketAppender` registered on root; mock the network layer per AC11). Edit `logback-spring.xml` — add a NEW additive `<springProfile name="observability">` block; do not break the existing `demo` / `local,test,!demo` blocks. [D4, T4, T9]

8. **`start-all.{ps1,sh}` already invoke `docker compose --profile observability up -d`** (story 1.17 pre-wired this — `scripts/start-all.sh:14`, `start-all.ps1:14`). Verify they bring up ELK now that the services exist; update the header comment that currently says the profile is "a no-op / empty in Epic 1". Document `start-all` as the "give me the full stack" wrapper. (Optional: add `stop-all` counterparts — none exist today; confirm scope with the dev-story.)

9. **ADR `docs/adr/0023-elk-replaces-loki.md`** (NOT `0003` — see Reconciliation #1) records: ELK chosen over Loki (richer query/full-text search, accepting higher memory footprint); Prometheus + Grafana retained for metrics (later story); index lifecycle management — commit a minimal **ILM policy** (e.g. 30-day retention) alongside the Logstash config (`infra/observability/elasticsearch/ilm-policy.json` or equivalent) and reference it from the ADR. Follow the repo ADR template: `## Status / ## Context / ## Decision / ## Consequences`. [D1, OQ-4]

10. **ES heap via `ES_JAVA_OPTS`** (default `-Xms512m -Xmx512m`, documented) + **doctor memory WARN**: doctor (story 1.16) **WARNs (never FAILs)** when the `observability` profile is active AND host total physical memory < 8 GB; SKIPs when `observability` is inactive. New doctor check following the three-sites doctor pattern in `DoctorService` (constant in the CHECK_* block + `STATIC_ORDER` + `runSingleProbe` switch) + `DoctorProbePort`/`DoctorProbeAdapter` probe + remediation string; bump `checksRun` 15→16. Detect profile via `environment.getActiveProfiles()` (pattern at `DoctorProbeAdapter` ~line 342) and host memory via `com.sun.management.OperatingSystemMXBean#getTotalMemorySize()`. [D7, T7 — new DomainErrorCode three-sites; OQ-3]

11. **Test suite** covers: ELK stack starts under `--profile observability up -d`; Logstash accepts a sample JSON log over TCP and indexes it in Elasticsearch; a `local-only`-classified doc is dropped by the filter; the double-redaction filter catches a fixture source-side missed; Kibana dashboards load on first startup; `observability`-disabled backend produces **zero** Logstash connection attempts (mock the network layer). Heavy ELK round-trip tests are Docker/Testcontainers-bound → tag for the Docker CI tier and name them `*IT` so they do NOT leak into the fast no-Docker Surefire tier (memory: [[springboot-testcontainers-test-must-be-IT]]). Cheap tests (shipping predicate, observability-disabled appender absence, grok parity vs fixtures) run in the fast tier. [D9, T9, T11]

12. **Cross-platform (AC12)** — ELK runs on Docker Desktop (Win/macOS) and Docker Engine (Linux/WSL2) with no OS-specific config; document the Linux `vm.max_map_count` requirement in `docs/setup-local.md` with remediation `sudo sysctl -w vm.max_map_count=262144`. Pin all three image versions. [T10]

## Tasks / Subtasks

- [x] **Task 1 — Compose services + env wiring** (AC: #1, #2, #8, #12)
  - [x] Add `elasticsearch`, `logstash`, `kibana` to the root `docker-compose.yml` under `profiles: ["observability"]`, version-pinned, `${VAR:-default}` ports, `discovery.type=single-node`, `ES_JAVA_OPTS` env, healthchecks (mirror `postgres` block), `logstash` mounts `infra/observability/logstash/pipelines/` and the runner-logs ingest dir, `kibana` linked to `elasticsearch`. [D2, T2]
  - [x] Promote the reserved `ELASTIC_HOST_PORT`/`LOGSTASH_HOST_PORT`/`KIBANA_HOST_PORT` (+ `ES_JAVA_OPTS`) lines in `.env.example` and `docs/setup-local.md` from commented placeholders to live defaults.
  - [x] Update `start-all.{ps1,sh}` header comment (profile is no longer empty); verify it brings up ELK.
  - [x] Add the Linux `vm.max_map_count` note + remediation to `docs/setup-local.md`. [T10]
- [x] **Task 2 — Profile-gated Logstash appender (backend)** (AC: #7)
  - [x] Add a NEW additive `<springProfile name="observability">` block in `logback-spring.xml` with a `net.logstash.logback.appender.LogstashTcpSocketAppender` → `${LOGSTASH_HOST:-localhost}:${LOGSTASH_PORT:-5044}`, encoder reusing `RedactingJsonProvider`/`RedactingMdcJsonProvider`/`RedactingStackTraceJsonProvider` + the same timestamp/level/logger/thread providers as the `demo` block, and a top-level `classification` field where available. Compose additively with existing blocks (no duplicate-root breakage). [D4, T4]
  - [x] Confirm `logstash-logback-encoder:8.0` already present — add NO new dependency.
  - [x] Test: observability-inactive → no `LogstashTcpSocketAppender` on root → zero connection attempts (logback-config assertion + mocked network). [T9]
- [x] **Task 3 — Logstash pipeline: ingest + classification filter + double redaction** (AC: #3, #4, #5)
  - [x] Author `infra/observability/logstash/pipelines/deliveryline.conf`: TCP/JSON input on 5044 + file input on the shippable runner-logs ingest dir (D5).
  - [x] Classification filter: `if [classification] == "local-only" { drop {} }` for the TCP path; runner-log path fail-closed via D5.
  - [x] Second-pass `grok`/`gsub`/`mutate` redaction matching `SensitivePayloadAnalyzer` categories (see Dev Notes literal list). [D6]
  - [x] Tests: local-only doc dropped (contract test extending `RunnerLogShippingPolicy`); double-redaction catches a source-missed fixture. [T5, T6]
- [x] **Task 4 — Runner-log shipping seam (D5 backend glue)** (AC: #3b, #4)
  - [x] Implement the fail-closed shippable-ingest mechanism: when a runner log is captured AND `RunnerLogShippingPolicy.isShippable(classification)` is true, the backend exposes the redacted log (with a per-line/per-doc `classification` envelope) to the Logstash-watched ingest location; local-only logs are never exposed. (See D5 for the two candidate implementations + recommendation; resolve OQ-1 first.)
  - [x] Reuse the existing `RunnerLogShippingPolicy` predicate; do not duplicate classification logic.
- [x] **Task 5 — Kibana dashboards + import** (AC: #6)
  - [x] Saved-objects JSON for the 4 dashboards under `infra/observability/kibana/dashboards/`.
  - [x] First-startup import mechanism + `infra/observability/README.md`. [OQ-5]
- [x] **Task 6 — Doctor memory WARN** (AC: #10)
  - [x] New doctor check (three-sites in `DoctorService` + `DoctorProbePort`/`DoctorProbeAdapter` probe + remediation), `checksRun` 15→16; SKIP when observability inactive, WARN when active AND <8 GB. New `DomainErrorCode` (e.g. `DOCTOR_OBSERVABILITY_LOW_MEMORY`) via the three-sites rule ([[new-domainerrorcode-three-sites]]) — verify `-Pfoundation-gate`. [D7, T7, OQ-3]
- [x] **Task 7 — ADR + ILM** (AC: #9)
  - [x] `docs/adr/0023-elk-replaces-loki.md` (Status/Context/Decision/Consequences) + minimal ILM policy file referenced from the ADR. [D1, OQ-4]
- [x] **Task 8 — ELK integration tests (Docker tier)** (AC: #11)
  - [x] `*IT`-named, Docker/Testcontainers, tagged for the observability/real-elk CI tier; covers stack-up, TCP index round-trip, filter drop, double-redaction, dashboard load. Skippable in PR CI. [D9, T11, [[springboot-testcontainers-test-must-be-IT]]]
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Backend touch here is small (the Logstash appender + the doctor probe + the D5 shipping glue). For any NEW backend service method (D5 glue, the doctor probe): SLF4J structured logs at entry/exit + decision branches.
  - [x] Use parameterized logging (`log.info("...", arg1)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (shipping decision taken, doctor check outcome), `WARN` for recoverable anomalies (Logstash unreachable, low-memory doctor warn), `ERROR` only for unhandled failures. `DEBUG` for hot-path detail.
  - [x] Carry context keys where relevant: `runnerExecutionId`, `workflowRunId`, `classification` (the shipping decision); never the log payload bytes.
  - [x] **Never log secrets, payload bytes, raw tokens, or PII.** The whole point of this story is redaction — the shipping glue must NEVER log the runner-log content it is shipping, only metadata (classification, byteSize, shippable yes/no).
  - [x] Add ≥1 focused assertion (list-appender / `OutputCaptureExtension`) per new branch — e.g. the "not shipped: local-only" decision and the doctor low-memory WARN.

## Dev Notes

### Architecture patterns & constraints

- **AR25 hard rule (architecture.md:524, 533, 539, 544–545, 268):** "Local observability stack… optional and profile-gated… normal MVP operation does not require Prometheus/Grafana/Loki." The backend MUST start and run **byte-for-byte identically** with `observability` off. This is enforced by AC7's "zero connection attempts" test.
- **ADR 0001 (unified-compose)** governs: ONE `docker-compose.yml`, services added behind profiles. Overrides the `docker-compose.observability.yml` sketch in `architecture.md:915-917`.
- **Spring Boot 4.0.6, Java 21.** Spring Boot's docker-compose autoconfig runs `docker compose up --wait` against the root compose file during `doctor-smoke`/`jar-smoke` — which is exactly why ELK must be profile-gated (a not-ready ELK service must not block app startup). See `docker-compose.yml:26-30`.
- **Profile groups** (`application.yml:11-17`): `local`/`test`/`demo` each fan out to `runners.mock, linear-mock, github-mock`. `observability` is a standalone profile layered on top (it can be active alongside `demo`). The logback `observability` block must compose additively.

### Existing logging surface (story 1.19) — reuse, do not reinvent

- `logback-spring.xml`: `demo` → JSON-to-STDOUT (composite encoder); `local,test,!demo` → human PatternLayout. The JSON `message`/`mdc`/`stack_trace` fields route through `RedactingJsonProvider` / `RedactingMdcJsonProvider` / `RedactingStackTraceJsonProvider` (`infrastructure/observability/`). **The new Logstash appender MUST reuse these same providers** so shipped logs are redacted at source (defense-in-depth layer 1; the Logstash grok is layer 2).
- MDC keys (`application/observability/MdcKeys.java`): `correlationId, workflowRunId, runnerExecutionId, artifactId, artifactOperationId, approvalId`. Pinned by `LoggingFieldNameContractTest` — if you add a `classification` top-level field to the shipped JSON, it is NOT an MDC key; do not add it to `MdcKeys` (that test rejects extras).
- `correlationId` is stamped by `infrastructure/observability/CorrelationIdFilter` (UUIDv7, HIGHEST_PRECEDENCE, cleared in finally).
- `logstash-logback-encoder:8.0` is at `deliveryline-backend/pom.xml:100-104` — already present.

### Story 3.6 contract this story keys on (the classification seam)

- `application/runner/RunnerLogShippingPolicy.isShippable(DataClassification)` → `false` iff `LOCAL_ONLY`. This is the stable forward seam; extend its test, never change the predicate.
- Redacted runner logs: `{DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/runner.stdout` + `runner.stderr` (plain text). Written by `adapters/files/LocalRunnerLogStore.java` (`RUNNER_LOGS_ROOT_SUBDIR = "runner-logs"`, atomic temp+rename). `{DELIVERYLINE_HOME}` ← `@Value("${deliveryline.home}")` (default `./`).
- Classification persisted in DB column `raw_output_classification` (Flyway V11) + `RunnerLogReference.classification()` + workflow-event detail `rawOutputClassification` (`WorkflowEventDetailKeys`). Default classification = `local-only`; elevates to `shareable-redacted` ONLY when both streams had zero detected categories AND `deliveryline.runner.allow-shareable-logs=true` (`RunnerProperties.allowShareableLogs()`, default false). **→ By default, runner logs are local-only and therefore NOT shipped.** That is intended.

### `DataClassification` values (domain/registry/DataClassification.java)

`LOCAL_ONLY("local-only")`, `SHAREABLE_REDACTED("shareable-redacted")`, `SHAREABLE_FULL("shareable-full")`, `DERIVED_PUBLIC_SAFE("derived-public-safe")`. Serialized via `.value()` (the kebab string). The Logstash filter keys on the literal `"local-only"`.

### Redaction patterns for the Logstash second pass (AC5 / D6)

Canonical source: `application/security/SensitivePayloadAnalyzer.java` (regexes) + `RedactionCategory`. The grok/gsub chain must cover the same categories (placeholders mirror `[REDACTED_*]`):

- `Authorization: Bearer|Basic <tok>` → `[REDACTED_AUTHORIZATION_HEADER]`
- `github_pat_[A-Za-z0-9_]{20,}`, `ghp_[A-Za-z0-9]{20,}` → `[REDACTED_GITHUB_TOKEN]`
- `lin_api_[A-Za-z0-9]{20,}` → `[REDACTED_LINEAR_API_KEY]`
- query secrets `[?&](token|apikey|access_token)=...` → `[REDACTED_QUERY_SECRET]`
- PEM/PKCS8/RSA/EC/DSA/OpenSSH private keys, cert+key bundles → `[REDACTED_*_PRIVATE_KEY]`
- `ssh-rsa|ed25519 ...` public keys → `[REDACTED_SSH_PUBLIC_KEY]`
- `Idempotency-Key: ...` → `[REDACTED_IDEMPOTENCY_KEY]`
- JSON/YAML/env secret fields (secret|token|apiKey|password|credential|client_secret|refresh_token|…) → `[REDACTED_SECRET_FIELD]` / `[REDACTED_ENV_VALUE]`
- local paths `C:\Users\...`, `/Users/...`, `/home/...` → `[REDACTED_LOCAL_PATH]`

Adversarial fixture set (parity testing): `deliveryline-backend/src/test/resources/redaction-fixtures/` + `fixtures-manifest.json`, enforced by `foundation/RedactionAdversarialFoundationContract.java` (story 1.23). Use these same fixtures to prove the Logstash grok strips what source-side "missed". `scripts/regen-redaction-policy.{ps1,sh}` generates the Java policy — consider deriving the Logstash patterns from the same manifest to avoid drift (OQ-2). **Drift between Java patterns and the hand-authored grok is trap T6.**

### Doctor pattern (AC10 / D7)

`application/diagnostics/DoctorService.java`: CHECK_* constant block + `STATIC_ORDER` list (15 today) + `runSingleProbe` switch (the doctor "three sites"). Result model `ProbeResult(DiagnosticsStatus, summary, errorCode, details)` with `DiagnosticsStatus = PASS|WARN|FAIL|SKIP`. Existing WARN exemplar: `DoctorProbeAdapter.probeGitBotIdentity()` (returns WARN + `DomainErrorCode.DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED.value()`). Profile detection: `environment.getActiveProfiles()` (~line 342). **No host-memory probe exists yet** — add one using `((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize()`. New `DomainErrorCode` requires the three-sites rule (`DomainErrorCode` enum + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json`) — verify with `-Pfoundation-gate` ([[new-domainerrorcode-three-sites]]).

### Decisions

- **D1** — ADR is `0023-elk-replaces-loki.md` (0003 taken). [Reconciliation #1]
- **D2** — Single `docker-compose.yml` + `observability` profile (ADR 0001 / AR24), not a separate file. [Reconciliation #2]
- **D3** — Profile name `observability` (matches shipped start-all + compose). [Reconciliation #3]
- **D4** — New additive `<springProfile name="observability">` Logstash TCP appender reusing the `Redacting*` providers; no new dependency. [Reconciliation #5]
- **D5 (headline)** — Runner-log files have no inline classification → **fail-closed via `RunnerLogShippingPolicy`**. Recommended: a thin backend seam exposes a redacted log to a Logstash-watched **shippable ingest location ONLY when `isShippable(classification)`** (each doc carries a `classification` envelope so the TCP-style filter is uniform). local-only is never exposed → never indexed. *Alternative:* write a sidecar `shipping.json` per `{rex}` dir and have Logstash `drop` any runner-log doc without a shippable marker — simpler pipeline but leaves local-only files visible to the file input (less fail-closed). **Resolve OQ-1 before Task 4.**
- **D6** — Logstash second redaction pass mirrors `SensitivePayloadAnalyzer` categories; parity-tested against the story-1.10/3.6 adversarial fixtures.
- **D7** — Doctor memory WARN via a new three-sites doctor check; SKIP when observability off; never FAIL. New `DomainErrorCode`.
- **D8** — ELK only; Prometheus/Grafana out of scope (reserved env vars stay reserved; 3.19 owns Grafana). AC6 = Kibana dashboards.
- **D9** — ELK round-trip tests are `*IT`, Docker-tier, skippable in PR CI; cheap predicate/config tests run fast tier.

### Traps (wiring hazards specific to this repo)

- **T1** ADR number collision (0003 → 0023).
- **T2** Single compose file (ignore the architecture's separate-file sketch).
- **T3** Profile is `observability`, not `demo-observability`.
- **T4** logback `<springProfile>` blocks are additive — the observability block must add the Logstash appender without breaking/duplicating the `demo`/`local,test,!demo` roots; logback merges appender-refs across blocks, so verify the resulting root appender set under each profile combo (`demo`, `demo,observability`, `local,observability`, none).
- **T5** Runner-log file classification gap → fail-closed (D5); never ship local-only.
- **T6** Logstash grok ↔ Java redaction drift → fixture parity test.
- **T7** New doctor `DomainErrorCode` needs three-sites + `checksRun` bump + `STATIC_ORDER` + switch ([[new-domainerrorcode-three-sites]]).
- **T8** If you add `deliveryline.observability.*` or any validated `@ConfigurationProperties` field, update `src/test/resources/application.yml` too — it shadows, not merges ([[validated-config-needs-test-yaml]]). (Note: `LOGSTASH_HOST`/`LOGSTASH_PORT` consumed in `logback-spring.xml` via `${...}` are env/property placeholders, NOT validated config — no test-yaml change needed for those.)
- **T9** AC7 "zero connection attempts": the `LogstashTcpSocketAppender` auto-reconnects, so it must ONLY be declared inside the `observability` springProfile block (never instantiated when the profile is off). Assert appender absence + mock the network.
- **T10** Cross-platform: ES `discovery.type=single-node`, `vm.max_map_count` Linux note, version-pinned images, heap via `ES_JAVA_OPTS`.
- **T11** Gates via PowerShell ([[rtk-hook-only-matches-bash]]); the ELK `*IT` needs a real Docker-on-Linux env — confirm on WSL2/Docker before claiming green ([[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]]). Don't claim the Docker-tier IT passes from a Windows-only run.

### Open questions (with recommendations)

- **OQ-1 (headline)** — D5 mechanism: shippable-ingest seam (rec.) vs sidecar marker. **Rec: shippable-ingest seam** (fail-closed, uniform `classification` filter for both paths).
- **OQ-2** — Auto-generate the Logstash grok from the redaction manifest (extend `regen-redaction-policy`) vs hand-author + parity test. **Rec: hand-author for MVP + fixture parity test; auto-gen deferred.**
- **OQ-3** — New `DomainErrorCode DOCTOR_OBSERVABILITY_LOW_MEMORY` (three-sites) vs a doctor-local non-registry code. **Rec: new DomainErrorCode** (consistency with existing doctor codes like `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED`).
- **OQ-4** — Commit an ILM policy JSON now vs document-only retention. **Rec: commit a minimal ILM policy** referenced from the ADR (AC9 implies "committed alongside Logstash config").
- **OQ-5** — Kibana dashboard import: one-shot `kibana-setup` compose helper / documented saved-objects-API script vs manual. **Rec: a small documented import step (script or one-shot service) that does not block Kibana readiness.**
- **OQ-6 (scope check)** — This is a genuinely large, mostly-infra story (compose + pipeline + dashboards + ADR + backend appender + doctor probe + D5 glue + Docker-tier ITs). Consider whether to split (e.g. 3.7a infra/compose+pipeline+ADR, 3.7b backend appender+doctor+D5+ITs). **Rec: keep as one story** per the epic definition, but flag to the architect if the slice grows.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (this story's small backend surface):** the D5 shipping-decision glue (`INFO` shipping decision + classification/byteSize; `WARN` if the Logstash ingest path is unwritable) and the doctor memory probe (`INFO`/`WARN` outcome). The Logstash appender itself is config, not code.
- **Required context keys** (MDC or structured params): `runnerExecutionId`, `workflowRunId`, `classification`.
- **Forbidden in log output:** the runner-log payload bytes being shipped, secrets/tokens, raw PII. This story is about redaction — the shipping glue logs only metadata.
- **Test contract:** new logging surfaces pinned by ≥1 focused test (list-appender / `OutputCaptureExtension`).

### Project Structure Notes

- New infra tree (under existing `infra/observability/` which currently holds only `.gitkeep`):
  - `infra/observability/logstash/pipelines/deliveryline.conf`
  - `infra/observability/kibana/dashboards/*.json`
  - `infra/observability/elasticsearch/ilm-policy.json` (or similar — OQ-4)
  - `infra/observability/README.md`
- Backend changes: `logback-spring.xml` (new `observability` block), `DoctorService` + `DoctorProbePort` + `DoctorProbeAdapter` (new memory probe), `DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json` (three-sites), the D5 shipping-glue class under `application/runner` (+ adapter under `adapters/files` if it writes to disk).
- Root/docs: `docker-compose.yml`, `.env.example`, `docs/setup-local.md`, `docs/adr/0023-elk-replaces-loki.md`, `scripts/start-all.{ps1,sh}` (comment update).
- Tests: extend `RunnerLogShippingPolicy`/capture tests (fast tier); new `*IT` ELK round-trip + filter + double-redaction (Docker tier); observability-disabled appender-absence test (fast tier); doctor probe test; `-Pfoundation-gate` for the new DomainErrorCode.
- **No Flyway migration** (no new DB columns — classification already persisted by 3.6's V11). Max stays V11.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.7] — the 12 ACs.
- [Source: _bmad-output/planning-artifacts/architecture.md#AR25] — observability optional/profile-gated (lines 524, 533, 539, 544–545, 268); separate-compose sketch (915–917, 1115–1117) superseded by ADR 0001.
- [Source: docs/adr/0001-unified-compose.md] — single-compose-file rule.
- [Source: docs/adr/0003-runner-secrets-mvp-posture.md] — the file occupying 0003 (why ELK ADR is 0023).
- [Source: deliveryline-backend/src/main/resources/logback-spring.xml] — existing appenders + Redacting* providers.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerLogShippingPolicy.java] — the classification shipping seam.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DataClassification.java] — classification values/strings.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/security/SensitivePayloadAnalyzer.java] — canonical redaction patterns for the Logstash second pass.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java] — doctor three-sites + STATIC_ORDER (15→16).
- [Source: deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json] — adversarial fixtures for grok parity.
- [Source: _bmad-output/implementation-artifacts/3-6-runner-logs-capture-and-redaction-and-classification.md] — the upstream runner-log capture/classification story (AC9 forward seam).
- [Source: docker-compose.yml] — postgres service style + `runners`-profile rationale to mirror.
- [Source: scripts/start-all.sh / start-all.ps1] — already invoke `--profile observability`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context) — bmad-dev-story workflow, 2026-06-04.

### Debug Log References

- Fast Surefire tier (PowerShell, [[rtk-hook-only-matches-bash]]): **772 tests, 0 failures, 0 errors, 11 skipped — BUILD SUCCESS**. Whole test tree (incl. all `*IT` / `*ContractTest`) compiles clean.
- `spotless:check` + `checkstyle:check`: **0 Checkstyle violations — BUILD SUCCESS**.
- Doctor check count bumped 15→16; observed `checksRun=16` in `DoctorService` finish log during the run; `DoctorLoggingContractTest` assertion updated to match.
- **Review-continuation (2026-06-04, P3/P4/P5):** fast Surefire tier **775 tests, 0 failures, 0 errors, 11 skipped — BUILD SUCCESS** (+3 vs the 772 baseline: 1 new behavioral parity test + 2 new logback-console structural tests). Targeted: `LogstashRedactionParityTest` 4/0/0, `ObservabilityLogbackAppenderTest` 7/0/0. `spotless:check` + `checkstyle:check` — **0 violations**. Kibana saved-objects: all 20 lines across the 5 `*.ndjson` validate as JSON incl. nested `visState`/`panelsJSON`/`searchSourceJSON` (node parse check).

### Completion Notes List

Implemented story 3.7 (ELK Stack Integration — Centralized Log Capture, profile-gated) across all 8 tasks + the logging contract. Built self-contained (epic-3b stays `deferred`).

- **T1 — Compose + env (AC1/2/8/12):** added `elasticsearch` + `logstash` + `kibana` to the single root `docker-compose.yml` under `profiles: ["observability"]` (version-pinned 8.15.3, `discovery.type=single-node`, healthcheck mirroring `postgres`, ES heap via `ES_JAVA_OPTS`, logstash mounts the pipeline + the D5 `runner-logs-ingest` dir read-only). Promoted `ELASTIC_HOST_PORT`/`LOGSTASH_HOST_PORT`/`KIBANA_HOST_PORT`/`ES_JAVA_OPTS` from reserved to live in `.env.example` + `docs/setup-local.md`; left `PROMETHEUS_/GRAFANA_HOST_PORT` reserved (D8). Updated `start-all.{ps1,sh}` headers + added the Linux `vm.max_map_count=262144` note.
- **T2 — Profile-gated appender (AC7/D4/T4/T9):** NEW additive `<springProfile name="observability">` block in `logback-spring.xml` with a `LogstashTcpSocketAppender` → `${LOGSTASH_HOST:-localhost}:${LOGSTASH_PORT:-5044}`, reusing the story-1.19 `Redacting{Json,Mdc,StackTrace}Provider` (no new dep — `logstash-logback-encoder:8.0` already present) + a top-level `classification` field (omitEmptyFields). `ObservabilityLogbackAppenderTest` DOM-parses the config to prove the appender lives ONLY inside the observability profile (zero-connection-when-off) and that demo/local-test STDOUT roots are intact, plus a programmatic-encoder test proving source redaction + classification field.
- **T3 — Logstash pipeline (AC3/4/5/D6/T6):** `infra/observability/logstash/pipelines/deliveryline.conf` — TCP/JSON :5044 + file `/ingest/runner-logs/*.ndjson` inputs, uniform `if [classification] == "local-only" { drop {} }` filter, and a `gsub` second redaction pass mirroring every `RedactionCategory`. `LogstashRedactionParityTest` (fast tier) asserts every category placeholder appears in the conf (drift gate T6) + pins the drop filter and both ingest paths.
- **T4 — D5 shipping seam (AC3b/4, the headline reconciliation):** fail-closed `RunnerLogShippingService` (application) → `RunnerLogShipmentSink` SPI → `LocalRunnerLogShipmentSink` (adapters/files) writing one NDJSON envelope (top-level `classification` per doc) to `{deliveryline.home}/runner-logs-ingest/{rex}.ndjson` atomically — ONLY when `RunnerLogShippingPolicy.isShippable(...)`; `local-only` is never exposed. Metadata-only logging (ids/classification/byteSize — never content). New `RunnerLogShippingPolicyTest` extends the 3.6 seam; service + sink unit tests cover fail-closed, envelope shape, logging contract, atomic overwrite, prefix guard.
- **T5 — Kibana dashboards (AC6/D8):** 4 saved-object dashboards (workflow-events, runner-executions, redaction-audit, failure-category-distribution) + shared index pattern under `infra/observability/kibana/dashboards/`; `infra/observability/README.md` documents the saved-objects `_import`, ILM apply, and stack bring-up.
- **T6 — Doctor memory WARN (AC10/D7, three-sites):** new `DOCTOR_OBSERVABILITY_LOW_MEMORY` (`DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json` — [[new-domainerrorcode-three-sites]]); `DoctorService` `CHECK_OBSERVABILITY_MEMORY` (STATIC_ORDER 15→16, switch case); `DoctorProbePort.probeObservabilityMemory()` + `DoctorProbeAdapter` impl (SKIP when observability inactive; WARN under 8 GB via `com.sun.management.OperatingSystemMXBean#getTotalMemorySize()`; PASS otherwise / when memory unavailable — never FAIL). Seam-injectable memory supplier for testing. Updated `DoctorServiceTest` + `DoctorLoggingContractTest` stubs + the 15→16 count.
- **T7 — ADR + ILM (AC9/D1):** `docs/adr/0023-elk-replaces-loki.md` (NOT 0003 — collision reconciliation) + `infra/observability/elasticsearch/ilm-policy.json` (30-day retention), referenced from the ADR.
- **T8 — ELK ITs (AC11/D9/T11):** `ElkPipelineRoundTripIT` (`@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`, `*IT` name → Failsafe Docker tier, excluded from the no-Docker PR/foundation tiers per [[springboot-testcontainers-test-must-be-IT]]) — real ES+Logstash Testcontainers: TCP index round-trip, local-only drop, and second-pass double-redaction.

**Not verified locally (Docker-tier — needs Linux/Docker CI, [[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]] / Trap T11):** `ElkPipelineRoundTripIT` (pulls real ELK images), and `-Pfoundation-gate` `RegistryContractTest` (the three-sites alignment for the new `DomainErrorCode` — manually verified the enum/catalog/placeholder JSON match; the catalog's "every code mapped" invariant is exercised by the green fast tier). Do NOT claim the Docker-tier IT passes from this Windows run.

**No new dependency, no Flyway migration** (classification already persisted by 3.6 V11; max stays V11), no OpenAPI/schema.d.ts change.

### File List

**New — backend main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerLogShippingService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerLogShipment.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerLogShipmentOutcome.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerLogShipmentSink.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerLogShipmentSink.java`

**Modified — backend main:**
- `deliveryline-backend/src/main/resources/logback-spring.xml`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`

**New — backend tests:**
- `deliveryline-backend/src/test/java/org/dradgo/observability/ObservabilityLogbackAppenderTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/LogstashRedactionParityTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorObservabilityMemoryProbeTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogShippingServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogShippingPolicyTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerLogShipmentSinkTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/observability/ElkPipelineRoundTripIT.java`

**Modified — backend tests:**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java`

**New — infra/docs:**
- `docs/adr/0023-elk-replaces-loki.md`
- `infra/observability/README.md`
- `infra/observability/logstash/pipelines/deliveryline.conf`
- `infra/observability/elasticsearch/ilm-policy.json`
- `infra/observability/kibana/dashboards/index-pattern.ndjson`
- `infra/observability/kibana/dashboards/workflow-events.ndjson`
- `infra/observability/kibana/dashboards/runner-executions.ndjson`
- `infra/observability/kibana/dashboards/redaction-audit.ndjson`
- `infra/observability/kibana/dashboards/failure-category-distribution.ndjson`

**Modified — root/docs/scripts:**
- `docker-compose.yml`
- `.env.example`
- `docs/setup-local.md`
- `scripts/start-all.sh`
- `scripts/start-all.ps1`

### Change Log

- 2026-06-04 — Story 3.7 implemented (ELK observability, profile-gated): compose services + env, profile-gated Logstash appender, Logstash pipeline (classification drop + 2nd-pass redaction), D5 fail-closed runner-log shipping seam, Kibana dashboards + ADR 0023 + ILM, doctor low-memory WARN (new three-sites DomainErrorCode, checksRun 15→16), Docker-tier ELK round-trip IT. Status → review.
- 2026-06-04 — Addressed code review findings — 3 items resolved (review-continuation dev-story): P3 made `LogstashRedactionParityTest` non-vacuous (parses + compiles + executes the conf's gsub chain over per-category secret fixtures — the real T6 drift gate); P4 made the `observability` logback block console-self-sufficient via an `observability & !demo & !local & !test`-gated STDOUT block (no duplicate console under combined profiles) + 2 structural tests; P5 replaced the empty Kibana dashboard shells with real aggregation-based visualizations across all 4 dashboards + README panel table. Gates: fast tier 775/0/11skip, spotless+checkstyle 0, 20/20 saved objects valid. Status stays review.

### Review Findings

> bmad-code-review 2026-06-04 — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the full uncommitted diff (36 files, ~1742 insertions) against the 12 ACs / D1–D9 / T1–T11. Reviewer-verified against source: the convergent "HIGH" file-codec finding was a false positive and the redaction-regex "over-broad" findings are intentional `SensitivePayloadAnalyzer` parity (dismissed). Triage: **3 decision-needed, 3 patch, 4 defer, 7 dismissed.**

**Decision-needed (resolved 2026-06-04):**

- [x] [Review][Decision→Patch] `observability` Spring profile activated ALONE wires only the Logstash appender — no console/STDOUT — `logback-spring.xml:90-129`. **Resolution: Alex chose "add a console appender to the observability block"** so it is self-sufficient (see Patch P4).
- [x] [Review][Decision→Patch] AC6 Kibana dashboards are empty shells (`"panelsJSON":"[]"`) — `infra/observability/kibana/dashboards/*.ndjson`. **Resolution: Alex chose "require the enumerated AC6 panels now"** — author real visualizations before close (see Patch P5).
- [x] [Review][Decision→Defer] D5 runner-log shipping seam has no production caller (dormant) — `RunnerLogShippingService.shipIfPermitted`. Matches the established forward-seam pattern ([[markavailable-has-no-production-caller]]). **Resolution: Alex chose "accept dormant seam for this story"** — wiring lands with a later runner-execution story (deferred; logged to deferred-work.md).

**Patch:** (batch-applied 2026-06-04 — P1, P2 fixed; P3, P4, P5 skipped as judgment-requiring, left as action items)

- [x] [Review][Patch] (P1 — APPLIED) Logstash appender targets undocumented `LOGSTASH_HOST`/`LOGSTASH_PORT`, but only `LOGSTASH_HOST_PORT` is documented/wired [`logback-spring.xml:92`]. `${LOGSTASH_HOST:-localhost}:${LOGSTASH_PORT:-5044}` — out-of-box works (defaults match `LOGSTASH_HOST_PORT=5044`), but an operator who remaps `LOGSTASH_HOST_PORT` (the only var in `.env.example`/compose/`setup-local.md`) silently does NOT redirect the appender → connection refused. Document `LOGSTASH_HOST`/`LOGSTASH_PORT` as the backend-side connection vars (and clarify the host-port-publish vs backend-dial distinction).
- [x] [Review][Patch] (P2 — APPLIED, partial) Second-pass redaction omitted the YAML `key: value` form [`infra/observability/logstash/pipelines/deliveryline.conf`]. **Fixed:** added a YAML-form `SECRET_FIELD` gsub mirroring `SensitivePayloadAnalyzer.YAML_SECRET_FIELD_PATTERN` (snake_case set). **Residual (left open intentionally):** layer-2 still scans only `message`, not the sibling `mdc`/`stack_trace` JSON fields — those are already redacted by the layer-1 `Redacting{Mdc,StackTrace}JsonProvider`, so this is a defense-in-depth nicety, not a live leak; extending gsub to a nested `mdc` object is non-trivial and deferred.
- [x] [Review][Patch] (P3 — RESOLVED 2026-06-04 dev-story) `LogstashRedactionParityTest` was vacuous — asserted only `pipeline.contains(category.placeholder())` [`LogstashRedactionParityTest.java:28-40`]. A bare comment containing the placeholder string satisfied it; it never compiled/executed a gsub regex, so a missing or broken pattern (e.g. the absent YAML variant above) passed the T6 drift gate undetected. **Fixed:** added `everySourceMissedSecretIsStrippedByTheExtractedGsubChain()` — it parses the `gsub => [...]` array out of the conf (quote-aware tokenizer that skips comments and consumes regex-internal `]`/`"`), compiles every pattern as a Java regex, and runs the FULL chain in document order over a known-secret fixture per `RedactionCategory`, asserting each secret is replaced by its placeholder AND the raw fragment is gone. A removed/broken/typo'd pattern (or a regex that fails to compile) now reds the fast tier. Also asserts the fixture map covers every category so a new `RedactionCategory` can't slip the gate. Verified: `LogstashRedactionParityTest` 4/0/0.
- [x] [Review][Patch] (P4 — RESOLVED 2026-06-04 dev-story; from Decision D1) Made the `observability` logback block self-sufficient for console output [`logback-spring.xml`]. **Fixed:** added a SECOND springProfile block gated `observability &amp; !demo &amp; !local &amp; !test` carrying a STDOUT `ConsoleAppender` (JSON composite, same Redacting* providers + `classification` field as the LOGSTASH encoder) + `<root>` ref. The `!demo & !local & !test` predicate is the key: logback attaches appenders by object identity (not name), so a same-named STDOUT in a second active block would DOUBLE every console line — the guard suppresses the obs-console exactly when a base profile already supplies STDOUT, so `demo,observability` / `local,observability` keep a single STDOUT while `observability`-alone gains one. The bare `observability` block stays LOGSTASH-only. Two new structural tests in `ObservabilityLogbackAppenderTest` pin (a) console-present-under-observability and (b) the guard excludes the base profiles + the bare observability block declares no STDOUT. Verified: `ObservabilityLogbackAppenderTest` 7/0/0.
- [x] [Review][Patch] (P5 — RESOLVED 2026-06-04 dev-story; from Decision D2) Authored the real AC6 Kibana visualizations [`infra/observability/kibana/dashboards/*.ndjson`]. **Fixed:** replaced every `"panelsJSON":"[]"` shell with aggregation-based visualization saved objects + populated dashboards: workflow-events (log volume over time by level; events by type/logger; events by failure category); runner-executions (lifecycle counts dispatched/completed/timed-out/orphaned; events over time; avg-`durationMs` duration heatmap); redaction-audit (by secret-pattern category; by classification; by source logger); failure-category-distribution (distribution pie + failures over time). Each `*.ndjson` bundles the shared index pattern + its visualizations + the dashboard with panel references. Categorical breakdowns with no dedicated field (failure categories, runner lifecycle, redaction categories) use `filters` aggregations keyed on `message` queries so they populate from real shipped logs; time/level/logger/classification use term + date-histogram aggs on the encoder's structured fields. README dashboard section updated (panel table). Validated all 20 saved objects parse including nested `visState`/`panelsJSON`/`searchSourceJSON` (live Kibana first-startup import still to be confirmed on the Docker/Linux tier — same caveat as the ELK IT).

**Deferred (pre-existing / out-of-scope-now):**

- [x] [Review][Defer] D5 runner-log shipping seam dormant — no production caller (from Decision D3) [`RunnerLogShippingService.shipIfPermitted`]. Nothing in the runner-capture path constructs a `RunnerLogShipment`, so AC3(b)'s file-ingest leg is wiring-only and never produces `runner-logs-ingest/*.ndjson` e2e. Alex accepted the dormant forward-seam for this story (consistent with [[markavailable-has-no-production-caller]]); the caller lands with a later runner-execution story. — deferred per decision.
- [x] [Review][Defer] ILM policy committed but inert — `ilm-policy.json` + `deliveryline.conf:79-86`. Output writes date-named indices (`deliveryline-logs-%{+YYYY.MM.dd}`), incompatible with the policy's `rollover` action (needs a write alias/data stream); binding is a manual README `curl`, so only the 30-day `delete` phase is reachable and only if an operator runs the step. AC9 literal ("commit a minimal ILM policy") is met; effectiveness is documentation-only. — deferred, low impact.
- [x] [Review][Defer] `ElkPipelineRoundTripIT` robustness [`ElkPipelineRoundTripIT.java`] — the local-only drop test relies on send-ordering + ES refresh timing (a not-dropped doc lagging the kept doc could yield a false PASS on the story's most important guarantee); `hitCount` string-scans the first `"value":` in the ES response. Functional for current ES, brittle. Docker-tier; dev already flagged it needs Linux/Docker CI verification (T11). — deferred, pre-existing test-tier concern.
- [x] [Review][Defer] No size ceiling on a shipment; `byteSize()` materializes full UTF-8 byte arrays [`RunnerLogShipment` / `RunnerLogShippingService`] — a multi-MB redacted stream is fully held as StringBuilder + JsonNode + byte[] with no upper bound (OOM risk on the low-memory host the new probe warns about). Dormant seam today. — deferred, perf-only, gated by the dormant-caller decision above.
- [x] [Review][Defer] `vm.max_map_count` ES precondition is documented but not auto-checked/set [`scripts/start-all.*`, `docker-compose.yml` elasticsearch] — on Linux/WSL2 without the sysctl, ES crash-loops and the `service_healthy` gate blocks logstash/kibana, with no doctor signal (the probe checks RAM only). AC12 requires only documentation, which is present. — deferred, scope expansion.

---

> bmad-code-review 2026-06-04 (re-review, different LLM — the recommended second pass) — re-ran the 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the full uncommitted diff (37 files, ~2549 diff lines) **after** the first-pass P1–P5 patches landed. **No new actionable defects → `review -> done`.** The Acceptance Auditor re-verified the high-stakes invariants as genuinely MET: AC7/AR25 (Logstash appender declared ONLY inside `<springProfile name="observability">`, so zero connection attempts when the profile is off; encoder reuses the `Redacting*` providers; no new dependency), AC10 (WARN-never-FAIL + SKIP-when-inactive, `checksRun` 15→16, new `DOCTOR_OBSERVABILITY_LOW_MEMORY` three-sites = enum + `ProblemDetailsCatalog` + schema-placeholders manifest), AC9 (`0023` ADR + ILM committed), AC4/AC5 (uniform `local-only` drop + gsub category parity, non-vacuous `LogstashRedactionParityTest` that compiles+executes the extracted chain). **Verified against source** (not just the diff): the convergent "HIGH" file-input `codec => json` finding is again a FALSE POSITIVE — the Logstash `file` input frames by newline delimiter BEFORE the codec, so `codec => json` is the canonical NDJSON-file pattern (`json_lines` is for unframed TCP streams); and the redaction-regex "under-/over-broad" findings are byte-for-byte `SensitivePayloadAnalyzer` parity (the conf's ENV_VALUE `^…=` anchor and `/Users|/home` LOCAL_PATH are identical to `ENV_SECRET_VALUE_PATTERN`/`UNIX_LOCAL_PATH_PATTERN` — any pattern change belongs in story 1.10's analyzer, not the conf, or it breaks the parity gate). Triage: **0 decision-needed, 0 patch, 5 new defer (+4 re-confirmed from pass 1), 15 dismissed.** Docker-tier ELK IT + `-Pfoundation-gate` still NOT re-run on Windows/RTK (config-only delta) — standing recommendation: WSL2/Linux+Docker CI confirm of the ELK round-trip + Kibana import before relying on the observability stack.

**Deferred (NEW this pass — minor, ALL gated by the dormant D5 file-ingest seam; none blocks `done`):**

- [x] [Review][Defer] Read-only ingest mount + `mode => "read"` leaves consumed envelopes un-reaped [`deliveryline.conf:29-34` + `docker-compose.yml` logstash `…/runner-logs-ingest:/ingest/runner-logs:ro`] — the file input sets no `file_completed_action`; `mode => "read"` defaults to `delete`, which cannot run against the `:ro` mount (the read-only is the deliberate D5 fail-closed property). Once the seam is wired, `runner-logs-ingest/` grows unbounded and Logstash logs a delete error per consumed file. Resolution belongs with the D5 caller: add explicit `file_completed_action => "log"` (+ `file_completed_log_path`) or an out-of-band reaper. — deferred, dormant-seam wiring.
- [x] [Review][Defer] Sink temp-file leak on crash [`LocalRunnerLogShipmentSink`] — `Files.createTempFile(ingestRoot, runnerExecutionId + ".", ".tmp")` orphans a `.tmp` in the watched dir if the process is SIGKILLed between create and the atomic move; not ingested (glob is `*.ndjson`) but never swept. Add a boot-time `*.tmp` reconciliation when the seam goes live. — deferred, dormant-seam robustness.
- [x] [Review][Defer] `deliveryline.home` default diverges from the compose ingest-mount default [`application.yml` `./` vs `docker-compose.yml` `${DELIVERYLINE_HOME:-./deliveryline-data}`] — if an operator relies on defaults, the backend writes `./runner-logs-ingest` while Logstash watches `./deliveryline-data/runner-logs-ingest`; paths diverge and nothing ships. Moot today (dormant), bites when D5 gets a caller; no test guards the two defaults agreeing. Mirrors the same latent convention as story 3.6's `runner-logs/`. — deferred, latent config convention.
- [x] [Review][Defer] Layer-2 gsub scans only `[message]`, not the sibling `mdc`/`stack_trace` JSON fields (re-flagged independently by all 3 layers) [`deliveryline.conf` gsub chain + `LogstashRedactionParityTest`] — this is the already-accepted P2 residual: `mdc`/`stack_trace` are redacted by the layer-1 `Redacting{Mdc,StackTrace}JsonProvider`, so it is a defense-in-depth completeness gap, not a live leak; a layer-1 regression would not be caught by layer-2, and extending the gsub also requires making the parity test field-agnostic. Re-affirmed deferred. — deferred, accepted residual.
- [x] [Review][Defer] Shippable shipment with both streams empty writes empty `"message":""` docs [`RunnerLogShippingService.shipIfPermitted`] — minor index/dashboard-count noise once the seam is wired; add an empty-content short-circuit (new SKIPPED reason) then. — deferred, dormant-seam minor.

**Re-confirmed deferrals from pass 1 (already logged):** ILM policy inert (rollover needs write-alias/data-stream); `ElkPipelineRoundTripIT` robustness (ordering/timing false-PASS risk + `hitCount` string-scan); no size ceiling on a shipment (OOM on low-mem host); D5 dormant seam (no production caller). All still valid; no change.

**Dismissed (15) — verified false positives / intentional design / cosmetic:** (1) file-input `codec => json` framing — FP, file input frames by newline before codec; (2) TCP `ecs_compatibility` field collision — FP, the json codec decodes body fields at top level so `[classification]`/`[message]` stay where the drop+gsub key on them; (3) `local-only` drop case/whitespace sensitivity — the only producer is the appender emitting the canonical `DataClassification.value()`, and the runner path is fail-closed upstream by D5; (4) ENV_VALUE `^…=` mid-line miss, (5) LOCAL_PATH `/root`/bare-home miss, (6) ENVIRONMENT_BLOCK anchor — all intentional byte-for-byte `SensitivePayloadAnalyzer` parity (fix belongs in story 1.10); (7) Kibana `<field>.keyword` aggregations need an index template — ES default dynamic mapping already provides the `.keyword` sub-field for strings and no committed template overrides it; (8) doctor exactly-8GB false-WARN — advisory WARN-only, erring toward warn on a marginal host is acceptable and never FAILs; (9) doctor `0`→PASS — `getTotalMemorySize()==0` means undetermined, PASS is correct; (10) doctor WARN message hardcodes "< 8 GB" — currently agrees with the constant, cosmetic; (11) port 5044 "Beats" collision — fully env-configurable via `LOGSTASH_HOST_PORT`; (12) Kibana service no healthcheck — cosmetic, readiness is operator-observable and ES is already health-gated; (13) sink path-containment/symlink — `runnerExecutionId` is `rex_`-prefix-validated, reporter concedes "probably safe"; (14) `mdc.classification` double-emit — duplication only, no functional/security impact; (15) parity test asserts field=="message" "brittleness" — it is the correct guard for the current message-only design (coupled to the deferred mdc/stack_trace decision).

---

> bmad-code-review 2026-06-04 (THIRD pass, user-requested re-review, different LLM) — re-ran the 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the full uncommitted diff (35 files, +1885/−27). **Found ONE new actionable defect class that BOTH prior passes structurally could not catch — because the catching test was itself the blind spot.** Blind Hunter + Edge Case Hunter independently converged on it; verified against source. Triage: **1 decision-needed, 0 patch, 1 new defer, ~14 dismissed/re-confirmed.** The Acceptance Auditor re-confirmed all 12 ACs genuinely MET (no AC regressions); the high-stakes invariants (AC7 zero-connect-when-off, AC10 WARN/SKIP three-sites, AC9 ADR+ILM, AC6 real panels) all still hold.

**Decision-needed (NEW — the headline):**

- [x] [Review][Decision→Patch, RESOLVED 2026-06-04] **Logstash second-pass redaction uses Java/PCRE regex syntax that is invalid in Logstash's JRuby/Joni `mutate { gsub }` engine — and `LogstashRedactionParityTest` validates with `java.util.regex`, so it masks the mismatch.** **Resolution: Alex chose "Full fix + Docker verify" (option a).** Conf corrected to Ruby syntax (`\1` backrefs + `(?m)`/`(?i)` flags); parity test re-pointed at the real engine (Ruby→Java translation for the behavioral chain + a `gsubUsesLogstashRubyRegexSyntaxNotJava` lint rejecting Java `$n`/`(?s)`). Fast-tier verified 5/0/0 + spotless/checkstyle clean; Docker-tier Logstash confirmation (T11) outstanding before `done`. Root cause: `LogstashRedactionParityTest.applyChain` (`LogstashRedactionParityTest.java:167`) compiles every pattern with `java.util.regex.Pattern` and applies `Matcher.replaceAll`, but Logstash `mutate { gsub }` runs Ruby `String#gsub` over a JRuby/Joni `Regexp` — a **different engine** with different replacement and flag syntax. Two concrete defects this hides:
  - **(A — CONFIRMED) `$1` backreferences must be `\1`.** `deliveryline.conf:65,67,71,73` use `$1[REDACTED_*]` replacements (QUERY_SECRET, JSON SECRET_FIELD, YAML SECRET_FIELD, ENV_VALUE). Ruby `gsub` replacement backreferences are `\1`, not `$1`; with `$1` Logstash emits the **literal characters `$1`**, mangling the preserved non-secret prefix (`?token=` → `$1`, `"password":"` → `$1`). **No secret leak** (the value is still over-redacted away), but indexed log messages on those branches are corrupted/un-searchable. The Java test passes because `Matcher.replaceAll` treats `$1` as a valid group ref — its own Javadoc (lines 176-177) even documents this Java-only assumption.
  - **(B — LIKELY, needs Docker verify) `(?s)` inline flag is rejected by Ruby/Joni.** `deliveryline.conf:57,59,61` (the PEM_CERTIFICATE_WITH_PRIVATE_KEY / SSH_PRIVATE_KEY / PEM_PRIVATE_KEY patterns) and `(?ms)` at line 75 use `(?s)` for dot-matches-newline. Ruby/Joni uses `(?m)` for that and does **not** recognize `(?s)` — `Regexp.new("(?s)…")` raises `RegexpError`. If so, those 3 private-key second-pass patterns either fail to compile (breaking pipeline load) or silently no-op in Logstash, while the Java-engine test reports green. `\R` (line 75) is fine — Ruby/Joni supports it. **Decision for Alex:** (a) fix the conf now (`$1`→`\1`; map Java flags to Ruby — `(?s)`→`(?m)`, `(?im)`→`(?i)`, `(?ms)`→`(?m)`) AND re-point the parity test at the real engine (JRuby `Regexp` via the bundled jruby on the classpath, or a Joni binding) so it would actually catch this, then verify on the Docker/Linux Logstash tier (T11); OR (b) apply only the confident `$1`→`\1` fix now and defer the flag-mapping + test-re-engine to the Docker-tier verification that is already the standing T11 gap; OR (c) accept/defer the whole item, given layer-2 is defense-in-depth over a path that is either already source-redacted (TCP) or dormant (D5 file-ingest), and pin it to the same Docker-tier verification milestone. **Note:** any conf fix that lands without re-engining the test will leave the test green-but-wrong — so (a)/(b) must touch `LogstashRedactionParityTest` too, not just the conf.

**Deferred (NEW this pass):**

- [x] [Review][Defer] Re-shipping the same runner execution re-indexes (duplicate ES docs) [`LocalRunnerLogShipmentSink.write` + `deliveryline.conf` file input] — `write()` does `Files.move(…, REPLACE_EXISTING)` keyed on `{runnerExecutionId}.ndjson`; a second shipment for the same id replaces the file with a new inode, which the `mode => "read"` file input (path-keyed sincedb) sees as new content and re-reads → the same execution indexed twice. Gated by the dormant D5 caller; resolve with the read-only-mount / completed-action item when the seam goes live. — deferred, dormant-seam.

**Re-confirmed (already logged, no change):** all pass-1 + pass-2 deferrals remain valid (ILM inert; `ElkPipelineRoundTripIT` ordering/timing robustness; no shipment size ceiling; D5 dormant seam; read-only ingest mount completed-action; sink `.tmp` leak; `deliveryline.home` vs compose-mount default divergence; layer-2 gsub message-only residual; empty-stream empty-doc). **Dismissed (~14):** all 15 from pass 2 stand (incl. the `codec => json` framing FP and the intentional `SensitivePayloadAnalyzer` parity items), plus container `mem_limit`, undocumented `LOGSTASH_JAVA_OPTS`, sub-1GiB `totalGib=0` log cosmetics, the missing-`REMEDIATION`-map-entry (matches the existing WARN-only `CHECK_GIT_BOT_IDENTITY` pattern), and `ElkPipelineRoundTripIT`-absent-from-diff (verified present on disk). **Standing caveat (T11):** the Docker-tier ELK round-trip + Kibana import remain unrun on Windows/RTK — and now the JRuby/Joni regex behavior of defect B can ONLY be confirmed there.
