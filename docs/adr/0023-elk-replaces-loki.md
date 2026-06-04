# ADR 0023: ELK Stack for Centralized Log Capture (replacing the Loki proposal)

## Status

Accepted

## Context

DeliveryLine emits structured JSON logs (story 1.19) and durable, redacted runner logs (story 3.6).
For diagnostic and audit workflows across many workflow runs, operators need searchable log history
— not just `tail -f` over a single process. Architecture requirement AR25 calls for a local,
profile-gated observability stack and explicitly revisits the earlier Loki proposal.

The original planning artifacts sketched Grafana Loki for log aggregation and a separate
`docker-compose.observability.yml`. Two facts override that sketch:

- **ADR 0001 (unified compose)** mandates a single root `docker-compose.yml` with profile gating —
  no separate observability compose file.
- AR25 requires full-text/field search over logs (failure-category breakdowns, runner-execution
  timelines, redaction-audit trails). Loki indexes labels, not log content, and pushes full-text
  search onto LogQL filter expressions over the raw stream.

A naming reconciliation also applies: this ADR is numbered `0023`, not the `0003` the story's
acceptance criterion literally named — `0003` is already occupied by
`0003-runner-secrets-mvp-posture.md` (story 3.5). `0023` is the next free sequential number.

## Decision

Adopt **Elasticsearch + Logstash + Kibana (ELK)** as the local log-aggregation stack, added to the
single root `docker-compose.yml` under `profiles: ["observability"]`.

- **Elasticsearch over Loki** — richer query and full-text search over log content (the dashboards in
  AC6 group by failure category, secret-pattern category, runner-execution outcome), accepting a
  higher memory footprint as the trade-off. The `doctor` memory check WARNs (never FAILs) when the
  `observability` profile is active on a host with under 8 GB of RAM.
- **Profile-gated, never required** — per AR25, the backend runs byte-for-byte identically with or
  without the `observability` profile. When the profile is off, the backend ships logs to STDOUT only
  and makes zero Logstash connection attempts. ELK is never required for normal MVP operation, tests,
  or recovery.
- **Two redaction layers** — the backend's `observability`-gated `LogstashTcpSocketAppender` reuses
  the story-1.19 `Redacting*` JSON providers so logs are redacted at source (layer 1); the Logstash
  pipeline runs a second `grok`/`gsub` pass mirroring `SensitivePayloadAnalyzer` categories
  (layer 2, defense-in-depth) before indexing.
- **Classification-driven shipping** — only `shareable-redacted`-and-above logs reach Elasticsearch.
  `local-only` is dropped (TCP path) or never exposed to the file input at all (runner-log path,
  fail-closed via `RunnerLogShippingPolicy.isShippable(...)`).
- **Index lifecycle management** — a minimal ILM policy
  (`infra/observability/elasticsearch/ilm-policy.json`, 30-day retention) is committed alongside the
  Logstash config so indices do not grow unbounded on a dev host.
- **Prometheus + Grafana remain for metrics**, owned by the later metrics story (3.19). This story
  delivers ELK only; AR25's "ELK + Prometheus + Grafana" stack is realized incrementally. The AC6
  dashboards are **Kibana** dashboards.

This supersedes the earlier Loki proposal for log aggregation.

## Consequences

- Operators get full-text, field-faceted search over structured Spring Boot logs and redacted runner
  logs via Kibana, without standing up any external service.
- The stack carries a meaningful memory footprint (Elasticsearch JVM heap, default `-Xms512m
  -Xmx512m` via `ES_JAVA_OPTS`); the doctor memory probe surfaces under-provisioned hosts as a WARN.
- Logstash grok patterns must stay aligned with the Java `SensitivePayloadAnalyzer` categories; drift
  is guarded by a fixture-parity test against `redaction-fixtures/fixtures-manifest.json`.
- Linux/WSL2 hosts must set `vm.max_map_count=262144` for Elasticsearch (documented in
  `docs/setup-local.md`).
- The ILM policy bounds index growth (30-day retention); operators who want longer retention edit one
  committed JSON file referenced from this ADR.
