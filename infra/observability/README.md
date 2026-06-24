# DeliveryLine Observability — ELK (story 3.7) + Prometheus/Grafana (story 3.19)

Profile-gated Elasticsearch + Logstash + Kibana stack for centralized, searchable log capture, plus
a Prometheus + Grafana stack for runner-queue metrics.

> **Optional by design (AR25).** The backend runs byte-for-byte identically with or without this
> stack. It is never required for normal operation, tests, or recovery. See
> [`docs/adr/0023-elk-replaces-loki.md`](../../docs/adr/0023-elk-replaces-loki.md).

## What's here

```
infra/observability/
├── logstash/pipelines/deliveryline.conf   # ingest (TCP + file) + classification drop + 2nd redaction + ES output
├── kibana/dashboards/*.ndjson             # saved-object dashboards (AC6) + shared index pattern
├── elasticsearch/ilm-policy.json          # 30-day index lifecycle policy (AC9)
├── prometheus/prometheus.yml              # story 3.19 — scrape config (backend /actuator/prometheus)
├── prometheus/alerts.yml                  # story 3.19 — runner-queue alert rules (AC7)
├── prometheus/README-alerting.md          # story 3.19 — Alertmanager opt-in (Slack/email/PagerDuty)
├── grafana/dashboards/runner-queue.json   # story 3.19 — "Runner Queue" dashboard (AC6)
├── grafana/provisioning/                  # story 3.19 — Prometheus datasource + dashboard auto-load
└── README.md                              # this file
```

## Runner-queue metrics (story 3.19 — Prometheus + Grafana)

On the SAME `observability` profile, Prometheus scrapes the backend's Spring Boot Actuator endpoint
and Grafana renders the "Runner Queue" dashboard:

- **Prometheus** → `http://localhost:${PROMETHEUS_HOST_PORT:-9090}` (alerts at `/alerts`)
- **Grafana** → `http://localhost:${GRAFANA_HOST_PORT:-3000}` (anonymous Viewer; "Runner Queue"
  dashboard auto-provisioned under the DeliveryLine folder)

The backend must expose metrics: story 3.19 added `micrometer-registry-prometheus` +
`management.endpoints.web.exposure.include: health,prometheus`, so `/actuator/prometheus` serves
`deliveryline_runner_queue_depth` (headline), `_pool_size`, `_active_workers`, `_idle_workers`,
`_queue_oldest_age_seconds`, the `_dispatched_count_total` / `_completed_count_total{stage,outcome}`
counters, and the `_dispatch_duration_seconds{stage}` histogram.

**Scrape target:** the backend usually runs on the HOST, so `prometheus.yml` targets
`host.docker.internal:8080` (the compose service maps it to `host-gateway` for Linux). If you run the
backend as a compose service, change the target to `deliveryline-backend:8080`.

Alert routing (Slack/email/PagerDuty) is opt-in — see
[`prometheus/README-alerting.md`](prometheus/README-alerting.md). No Alertmanager ships by default.

## Bring the stack up

The stack is gated behind the `observability` Docker Compose profile. The full-stack wrapper scripts
already pass it:

```bash
scripts/start-all.sh        # or scripts/start-all.ps1 on Windows
# equivalently:
docker compose --profile observability up -d
```

Tear down with `docker compose down` (no `stop-all` wrapper exists).

- **Elasticsearch** → `http://localhost:${ELASTIC_HOST_PORT:-9200}`
- **Logstash** TCP/JSON ingest → `localhost:${LOGSTASH_HOST_PORT:-5044}`
- **Kibana** → `http://localhost:${KIBANA_HOST_PORT:-5601}`

> **Linux/WSL2:** Elasticsearch needs `sudo sysctl -w vm.max_map_count=262144` before first start.
> See [`docs/setup-local.md`](../../docs/setup-local.md).

To ship Spring Boot logs, also activate the `observability` Spring profile (it layers on top of
`local`/`demo`), e.g. `SPRING_PROFILES_ACTIVE=demo,observability`. The backend's
`LogstashTcpSocketAppender` then ships redacted JSON to Logstash; with the profile off, logs go to
STDOUT only and zero Logstash connections are attempted.

## Ingest paths (`deliveryline.conf`)

| Path | Source | Gate |
|---|---|---|
| TCP/JSON `:5044` | backend `LogstashTcpSocketAppender` (logback-spring.xml, story 1.19/3.7) | `observability` Spring profile |
| file `/ingest/runner-logs/*.ndjson` | backend `RunnerLogShippingService` → `{DELIVERYLINE_HOME}/runner-logs-ingest/` | fail-closed: only `shareable-redacted`+ logs are ever written there (Decision D5) |

Both paths pass through the same `if [classification] == "local-only" { drop {} }` filter and a
defense-in-depth `gsub` redaction pass that mirrors the Java `SensitivePayloadAnalyzer` categories
(parity enforced by `LogstashRedactionParityTest`).

## Apply the ILM policy (AC9)

Story 3.7 (AC9 / Decision D1, OQ-4) — see [`docs/adr/0023-elk-replaces-loki.md`](../../docs/adr/0023-elk-replaces-loki.md).
The 30-day retention bounds index growth on a dev host; edit `max_age` in `elasticsearch/ilm-policy.json`
to change retention. The policy JSON is kept comment-free on purpose: the ES `_ilm/policy` endpoint
strict-parses the request body and rejects unknown top-level fields (e.g. a `"//"` comment key → HTTP
400), so the commands below can be pasted verbatim.

After the cluster is healthy, register the retention policy and bind it via an index template:

```bash
ES=http://localhost:${ELASTIC_HOST_PORT:-9200}

# 1) Register the lifecycle policy (30-day retention).
curl -fsS -XPUT "$ES/_ilm/policy/deliveryline-logs" \
  -H 'Content-Type: application/json' \
  --data-binary @infra/observability/elasticsearch/ilm-policy.json

# 2) Attach it to the deliveryline-logs-* indices.
curl -fsS -XPUT "$ES/_index_template/deliveryline-logs" \
  -H 'Content-Type: application/json' -d '{
    "index_patterns": ["deliveryline-logs-*"],
    "template": { "settings": { "index.lifecycle.name": "deliveryline-logs" } }
  }'
```

## Import the Kibana dashboards (AC6, OQ-5)

Once Kibana is up, import the saved objects via the saved-objects API (idempotent — `overwrite=true`).
This is a one-shot operator step that does **not** block Kibana readiness:

```bash
KB=http://localhost:${KIBANA_HOST_PORT:-5601}
for f in infra/observability/kibana/dashboards/*.ndjson; do
  curl -fsS -XPOST "$KB/api/saved_objects/_import?overwrite=true" \
    -H 'kbn-xsrf: true' --form file=@"$f"
done
```

Each `*.ndjson` bundles the shared `deliveryline-logs-*` index pattern, the dashboard's
aggregation-based visualizations, and the dashboard itself, so any file imports independently. The
four dashboards (AC6) ship populated panels:

| Dashboard | Panels |
|---|---|
| workflow-events | log volume over time (by level); events by type (logger); events by failure category |
| runner-executions | lifecycle counts (dispatched / completed / timed-out / orphaned); events over time; duration heatmap (avg `durationMs`) |
| redaction-audit | redactions by secret-pattern category; by classification; by source logger |
| failure-category-distribution | failure-category distribution (pie); failures over time |

> The panels are built on the structured fields the backend ships (`timestamp`, `level`, `logger`,
> `classification`, `message`). Categorical breakdowns that have no dedicated field yet (failure
> categories, runner lifecycle states, redaction categories) use `filters` aggregations keyed on
> `message` queries, so they populate as soon as logs flow. The `durationMs` heatmap renders once
> runner-completion logs carry that field. Re-export from Kibana to evolve any panel.
