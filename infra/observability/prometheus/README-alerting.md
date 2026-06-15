# Runner-Queue Alerting (story 3.19 AC8)

The DeliveryLine observability stack ships Prometheus alert **rules** (`alerts.yml`) but **no
Alertmanager service by default** — per **AR25** the entire observability stack is optional and must
never be required for normal operation. Pilots opt in to alert _routing_ by adding an Alertmanager
service and wiring it to Slack / email / PagerDuty.

## What ships out of the box

- `prometheus.yml` — scrapes the backend's `/actuator/prometheus` every 15s.
- `alerts.yml` — four runner-queue rules:
  - `RunnerQueueDepthHigh` — `deliveryline_runner_queue_depth > 50` for 5m (warning)
  - `RunnerQueueDepthCritical` — `deliveryline_runner_queue_depth > 200` for 2m (critical)
  - `RunnerOldestQueuedStale` — `deliveryline_runner_queue_oldest_age_seconds > 1200` (2× the 600s
    stage timeout) for 1m (warning)
  - `RunnerPoolStarved` — `deliveryline_runner_active_workers >= deliveryline_runner_pool_size` for
    10m (warning)
- Grafana with a provisioned Prometheus datasource + the "Runner Queue" dashboard.

Without Alertmanager, firing alerts are visible in the Prometheus UI (`/alerts`) and on the Grafana
dashboard, but are **not routed** anywhere.

## Opting in to Alertmanager routing

1. Add an `alertmanager` service to the `observability` profile in `docker-compose.yml`:

   ```yaml
   alertmanager:
     profiles: ["observability"]
     image: prom/alertmanager:v0.27.0
     ports:
       - "${ALERTMANAGER_HOST_PORT:-9093}:9093"
     volumes:
       - ./infra/observability/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
   ```

2. Point Prometheus at it by adding to `prometheus.yml`:

   ```yaml
   alerting:
     alertmanagers:
       - static_configs:
           - targets: ["alertmanager:9093"]
   ```

3. Create `infra/observability/alertmanager/alertmanager.yml` with a receiver. Supply webhook URLs
   via `.env` (never commit them):

   ### Slack

   ```yaml
   route:
     receiver: slack
   receivers:
     - name: slack
       slack_configs:
         - api_url: ${SLACK_WEBHOOK_URL}
           channel: "#deliveryline-alerts"
           title: "{{ .CommonAnnotations.summary }}"
           text: "{{ .CommonAnnotations.description }}"
   ```

   ### Email

   ```yaml
   receivers:
     - name: email
       email_configs:
         - to: oncall@example.com
           from: alerts@example.com
           smarthost: smtp.example.com:587
           auth_username: ${SMTP_USER}
           auth_password: ${SMTP_PASSWORD}
   ```

   ### PagerDuty

   ```yaml
   receivers:
     - name: pagerduty
       pagerduty_configs:
         - routing_key: ${PAGERDUTY_ROUTING_KEY}
   ```

4. Restart the stack: `docker compose --profile observability up -d`.

## Validating the rules

```bash
docker run --rm -v "$PWD/infra/observability/prometheus:/work" \
  prom/prometheus:v2.54.1 promtool check rules /work/alerts.yml
```

The backend test suite (`RunnerQueuePromtoolRuleValidityTest`) runs the same check when `promtool`
is on the PATH, and SKIPs (does not fail) when it is absent.
