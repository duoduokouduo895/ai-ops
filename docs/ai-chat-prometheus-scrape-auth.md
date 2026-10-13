# Ai-chat Prometheus scrape trust

## Default (network trust)

Ai-chat exposes `/actuator/prometheus` on management port **9091**. AIDiag Prometheus scrapes it over the private `aidiag-telemetry` (or equivalent) Docker network with **no credentials** when:

- Ai-chat `app.security.actuator.prometheus-auth-enabled=false` (default), and
- Prometheus `AI_CHAT_PROMETHEUS_USERNAME` / `AI_CHAT_PROMETHEUS_PASSWORD` are empty (default).

Health probes (`/actuator/health`, blackbox TCP) stay unauthenticated.

## Coordinated Basic Auth flip

Do **not** enable only one side. Same change / same window:

1. **Ai-chat** (external config / secrets): set
   - `app.security.actuator.prometheus-auth-enabled=true`
   - `app.security.actuator.prometheus-username=<scrape-user>`
   - `app.security.actuator.prometheus-password=<scrape-password>` (prefer secrets configtree)
2. **ai-ops Prometheus**: set matching env on the Prometheus service
   - `AI_CHAT_PROMETHEUS_USERNAME`
   - `AI_CHAT_PROMETHEUS_PASSWORD`
   then reload / recreate Prometheus so `infra/prometheus/prometheus.yml` expands the job `basic_auth` blocks for `ai-chat-candidate` and `ai-chat-production`.

## Nacos probes

Default blackbox dependency probes no longer include Nacos after Ai-chat removed Nacos. Rollback artifacts remain under `infra/nacos-candidate/` and git history; do not re-add a HomeOps Nacos tile unless production probes return.
