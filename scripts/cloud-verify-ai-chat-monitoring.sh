#!/usr/bin/env bash
set -Eeuo pipefail

mode="${1:---candidate-only}"
[[ "${mode}" == --candidate-only || "${mode}" == --dual ]] \
  || { echo "Usage: $0 [--candidate-only|--dual]" >&2; exit 64; }

runtime_project=ai-chat-candidate
if [[ "${mode}" == --dual ]]; then
  runtime_project=ai-chat-production
fi

public_health_url=""
if [[ "${mode}" == --dual ]]; then
  production_env="${AICHAT_PRODUCTION_ENV:-/opt/ai-chat/runtime/production.env}"
  [[ -f "${production_env}" && ! -L "${production_env}" ]] \
    || { echo "Production runtime config is missing or unsafe" >&2; exit 67; }
  mapfile -t public_health_matches < <(grep -E '^AICHAT_PUBLIC_HEALTH_URL=' "${production_env}" || true)
  [[ ${#public_health_matches[@]} -eq 1 ]] \
    || { echo "AICHAT_PUBLIC_HEALTH_URL must occur exactly once in production.env" >&2; exit 69; }
  public_health_url="${public_health_matches[0]#*=}"
fi
mapfile -t runtime_containers < <(docker ps \
  --filter "label=com.docker.compose.project=${runtime_project}" \
  --filter label=com.docker.compose.service=ai-chat-backend --format '{{.ID}}')
[[ ${#runtime_containers[@]} -eq 1 ]] \
  || { echo "Expected exactly one running ${runtime_project} backend container" >&2; exit 70; }
[[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${runtime_containers[0]}")" == healthy \
  && "$(docker inspect --format '{{.RestartCount}}' "${runtime_containers[0]}")" == 0 ]] \
  || { echo "${runtime_project} backend must be healthy with zero restarts" >&2; exit 70; }

mapfile -t prometheus_containers < <(docker ps \
  --filter label=com.docker.compose.project=aidiag \
  --filter label=com.docker.compose.service=prometheus --format '{{.ID}}')
[[ ${#prometheus_containers[@]} -eq 1 ]] \
  || { echo "Expected exactly one running AIDiag Prometheus container" >&2; exit 70; }
prometheus_container="${prometheus_containers[0]}"

payload_file="$(mktemp /tmp/aidiag-monitoring-targets.XXXXXX.json)"
probe_file="$(mktemp /tmp/aidiag-monitoring-probes.XXXXXX.json)"
cleanup() { rm -f -- "${payload_file}" "${probe_file}"; }
trap cleanup EXIT
docker exec "${prometheus_container}" wget -qO- 'http://localhost:9090/api/v1/targets?state=active' >"${payload_file}"
docker exec "${prometheus_container}" wget -qO- \
  'http://localhost:9090/api/v1/query?query=probe_success%7Bjob%3D%22blackbox-ai-chat%22%7D' >"${probe_file}"
python3 - "${mode}" "${payload_file}" "${probe_file}" "${public_health_url}" <<'PY'
import json
import sys

mode = sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as handle:
    payload = json.load(handle)
with open(sys.argv[3], encoding="utf-8") as handle:
    probes = json.load(handle)
targets = payload["data"]["activeTargets"]

expected = {
    "ai-chat-candidate": {
        "ai-chat-candidate:9091",
        "http://ai-chat-candidate:9091/actuator/health",
        "mysql:3306",
        "redis:6379",
        "rabbitmq:5672",
        "elasticsearch-v9-candidate:9200",
        "nacos-v3-candidate:8848",
    },
    "ai-chat-production": {
        "ai-chat-production:9091",
        "http://ai-chat-production:9091/actuator/health",
        "mysql:3306",
        "redis:6379",
        "rabbitmq:5672",
        "elasticsearch-v9:9200",
        "nacos-v3:8848",
        sys.argv[4],
    },
}
expected_probes = {
    service: {target for target in service_targets if not target.endswith(":9091")}
    for service, service_targets in expected.items()
}

services = ["ai-chat-candidate"] if mode == "--candidate-only" else list(expected)
for service in services:
    actual = {
        target.get("discoveredLabels", {}).get("__address__") or "<missing>"
        for target in targets
        if target.get("labels", {}).get("service_name") == service
    }
    if actual != expected[service]:
        raise SystemExit(f"{service} target mismatch: expected={sorted(expected[service])}, actual={sorted(actual)}")
    must_be_healthy = mode == "--candidate-only" or service == "ai-chat-production"
    if must_be_healthy:
        unhealthy = [
            target.get("discoveredLabels", {}).get("__address__") or "<missing>"
            for target in targets
            if target.get("labels", {}).get("service_name") == service and target.get("health") != "up"
        ]
        if unhealthy:
            raise SystemExit(f"{service} has unhealthy scrape targets: {sorted(unhealthy)}")

        probe_results = [
            result
            for result in probes["data"]["result"]
            if result.get("metric", {}).get("service_name") == service
            and result.get("metric", {}).get("instance") in expected_probes[service]
        ]
        actual_probes = {
            result.get("metric", {}).get("instance") or "<missing>"
            for result in probe_results
        }
        failed_probes = [
            result.get("metric", {}).get("instance") or "<missing>"
            for result in probe_results
            if result.get("value", [None, "0"])[1] != "1"
        ]
        if actual_probes != expected_probes[service] or failed_probes:
            raise SystemExit(
                f"{service} probe mismatch: expected={sorted(expected_probes[service])}, "
                f"actual={sorted(actual_probes)}, failed={sorted(failed_probes)}"
            )

if mode == "--candidate-only":
    production = [target for target in targets if target.get("labels", {}).get("service_name") == "ai-chat-production"]
    if production:
        raise SystemExit("production targets must remain inactive during candidate-only verification")
PY
trap - EXIT
rm -f -- "${payload_file}" "${probe_file}"

echo "ai_chat_monitoring_verification=PASS"
echo "mode=${mode#--}"
if [[ "${mode}" == --dual ]]; then
  echo "candidate_targets=7-retained"
  echo "production_targets=8-up"
else
  echo "candidate_targets=7-up"
  echo "production_targets=inactive"
fi
