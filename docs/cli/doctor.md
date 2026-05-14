# `deliveryline doctor`

`deliveryline doctor` runs a fixed set of runtime-prerequisite checks against the
current install and reports human-readable text or machine-readable JSON. It is
designed to distinguish a missing prerequisite from a product bug *before* an
operator attempts a real workflow.

## Command surface

```
deliveryline doctor
  [--format text|json]
  [--only <name>[,<name>...]]
  [--exclude <name>[,<name>...]]
  [--correlation-id <c>]
```

| Option | Default | Description |
|---|---|---|
| `--format` | `text` | `text` (default) emits one line per check plus a final `overall:` line. `json` emits a single JSON object validating against `doctor-report.v1.schema.json`. |
| `--only` | (empty) | Comma-separated list of stable check names. Only listed checks are run. Mutually exclusive with `--exclude`. |
| `--exclude` | (empty) | Comma-separated list of stable check names. Excluded checks still appear in the report with `status = SKIP` and summary `"Excluded via --exclude"` so the JSON shape stays stable for scripts. |
| `--correlation-id` | (auto) | Correlation ID surfaced in the structured log line on completion. Auto-generated UUIDv7 if omitted. |

## Exit-code semantics

| Overall status | Process exit code |
|---|---|
| `PASS` / `WARN` / SKIP-only | `0` |
| `FAIL` | `401` — infrastructure band (see [`README.md`](README.md)) |

The CLI **always prints the rendered report first** (to stdout). On `FAIL` the
command then throws a `DomainException` carrying the first failing check's
`errorCode`; the exit-status mapper translates that to `[CODE] summary` on
stderr in addition to the printed report. This means `doctor --format json | jq`
is safe even on `FAIL`.

## Check list

| Name | Verifies | Default severity when missing |
|---|---|---|
| `java-version` | JVM major version ≥ 21 | FAIL |
| `spring-profile` | At least one of `local` / `test` / `demo` active; no `prod` / `production` / `prd` | FAIL on blocked, WARN on missing |
| `postgres-connectivity` | `DataSource.getConnection().isValid()` + `SELECT 1` | FAIL |
| `flyway-state` | No `PENDING` / `FAILED` / `OUT_OF_ORDER` migrations | FAIL |
| `artifact-directory` | `${deliveryline.home}/artifacts` exists, writable, and probe file round-trips | FAIL |
| `config-file-permissions` | `.env` and `application-*.yml` not world-readable (POSIX only) | FAIL on POSIX, SKIP on Windows |
| `docker-availability` | `docker version` responds within 3s | WARN (runners are Epic 3) |
| `runner-image-availability` | Reserved (populated in story 3.1) | SKIP |
| `rest-bind-address` | `server.address` resolves to a loopback address | FAIL on non-loopback. **Port-availability sub-check is skipped** in story 1.16; production bind failures still surface on Spring Boot startup. |
| `frontend-asset-presence` | Reserved (populated in story 2.28) | SKIP |
| `supported-environment` | Reserved (populated in story 1.17) | SKIP |

## JSON schema

The JSON output validates against
[`deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json`](../../deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json).

- `schemaVersion`: const `1`. Future major versions ship a parallel
  `doctor-report.v2.schema.json`; renaming or removing fields is a breaking
  change requiring a major bump.
- Top-level `additionalProperties: false`. Each check object also has
  `additionalProperties: false`.

## Sample output — clean install

```text
$ deliveryline doctor
java-version: PASS Java 21
spring-profile: PASS Active Spring profiles: local
postgres-connectivity: PASS Postgres reachable
flyway-state: PASS Flyway up to date
artifact-directory: PASS Artifact directory writable
config-file-permissions: PASS Config file permissions are restrictive
docker-availability: PASS Docker reachable
runner-image-availability: SKIP Runner image availability check populated in story 3.1
rest-bind-address: PASS REST bind address resolves to loopback
frontend-asset-presence: SKIP Frontend asset check populated in story 2.28
supported-environment: SKIP Supported environment matrix check populated in story 1.17
overall: PASS
```

## Sample output — Postgres unreachable (text)

```text
$ deliveryline doctor
java-version: PASS Java 21
spring-profile: PASS Active Spring profiles: local
postgres-connectivity: FAIL Postgres unreachable: Connection refused
  remediation: Run 'docker compose up -d postgres' and re-check, or verify spring.datasource.url credentials.
...
overall: FAIL
$ echo $?
401
```

`[DOCTOR_POSTGRES_UNREACHABLE] Postgres unreachable: Connection refused` is
also emitted on stderr by the exit-status mapper.

## Sample output — JSON (abbreviated)

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-05-14T10:00:00Z",
  "overallStatus": "FAIL",
  "checks": [
    {
      "name": "java-version",
      "status": "PASS",
      "summary": "Java 21",
      "details": { "javaVersion": "21", "javaVendor": "Eclipse Adoptium" }
    },
    {
      "name": "postgres-connectivity",
      "status": "FAIL",
      "summary": "Postgres unreachable: Connection refused",
      "remediation": "Run 'docker compose up -d postgres' and re-check, or verify spring.datasource.url credentials.",
      "errorCode": "DOCTOR_POSTGRES_UNREACHABLE",
      "details": { "sqlState": "08001" }
    }
  ]
}
```

## Redaction

Every `summary`, `remediation`, and `details` value passes through
`RedactionPolicyService.redact(..., "shareable-redacted")` before it reaches
stdout, stderr, the JSON output, or the structured log stream. Probe
implementations are also forbidden from including raw JDBC URLs, raw `.env`
contents, raw Docker daemon stderr, or the literal probe-file path in their
results — only structural facts such as the database host and port.

## CI tuning

The `rest-bind-address` check in story 1.16 verifies only that `server.address`
resolves to a loopback. To bypass it in CI environments where the configured
address is intentionally non-loopback (for example a test fixture that boots
the app on an ephemeral interface), pass `--exclude rest-bind-address` — the
check still appears in the JSON output with `status = SKIP`.

The `docker-availability` check defaults to `WARN` on missing daemon because
runners are only required in Epic 3; CI agents without Docker do not gate the
build.
