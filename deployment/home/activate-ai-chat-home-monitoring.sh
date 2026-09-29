#!/usr/bin/env bash
set -Eeuo pipefail

[[ "${AIDIAG_HOME_MONITORING_CONFIRMED:-}" == "ACTIVATE_HOME_AI_CHAT_MONITORING" ]] \
  || { echo "Set AIDIAG_HOME_MONITORING_CONFIRMED=ACTIVATE_HOME_AI_CHAT_MONITORING" >&2; exit 64; }

base_dir="${AIDIAG_HOME_BASE_DIR:-/opt/aidiag-home}"
# Home deployments keep immutable release directories but do not rely on a
# mutable `current` symlink. Require the exact release directory so a monitor
# activation cannot silently target whichever directory happens to be newest.
release_dir="${AIDIAG_HOME_RELEASE_DIR:?Set AIDIAG_HOME_RELEASE_DIR to the active immutable release}"
current_release="$(readlink -f "${release_dir}")"
target_dir="${current_release}/.runtime/prometheus-targets"
target_file="${target_dir}/ai-chat-production.json"
history_dir="${base_dir}/runtime/monitoring-history"
prometheus_container="${AIDIAG_PROMETHEUS_CONTAINER:-aidiag-prometheus-1}"
blackbox_container="${AIDIAG_BLACKBOX_CONTAINER:-aidiag-blackbox-exporter-1}"
ai_chat_container="${AICHAT_CONTAINER:-20260714-v103-dduo-me-r2-ai-chat-backend-1}"

[[ -d "${current_release}" && "${current_release}" == "${base_dir}/releases/"* ]] \
  || { echo "Current AIDiag release is unsafe" >&2; exit 65; }
[[ -d "${target_dir}" && ! -L "${target_dir}" ]] \
  || { echo "Prometheus target directory is missing or unsafe" >&2; exit 65; }

for container in "${prometheus_container}" "${blackbox_container}" "${ai_chat_container}"; do
  docker inspect "${container}" >/dev/null 2>&1 \
    || { echo "Expected running container is missing: ${container}" >&2; exit 66; }
done

docker inspect --format '{{json .NetworkSettings.Networks}}' "${ai_chat_container}" \
  | grep -q '"aidiag-telemetry"' \
  || { echo "AI-chat is not attached to the reviewed telemetry network" >&2; exit 66; }
docker inspect --format '{{json .NetworkSettings.Networks}}' "${ai_chat_container}" \
  | grep -q '"ai-chat-production"' \
  || { echo "AI-chat production alias is missing" >&2; exit 66; }

docker exec "${prometheus_container}" promtool check config /etc/prometheus/prometheus.yml >/dev/null
docker exec "${blackbox_container}" blackbox_exporter --config.check \
  --config.file=/etc/blackbox_exporter/blackbox.yml >/dev/null

tmp_file="$(mktemp "${target_dir}/.ai-chat-production.json.next.XXXXXX")"
cleanup() { rm -f -- "${tmp_file}"; }
trap cleanup EXIT

cat >"${tmp_file}" <<'JSON'
[
  {"targets":["ai-chat-production:9091"],"labels":{"scrape_kind":"metrics","service_name":"ai-chat-production"}},
  {"targets":["http://ai-chat-production:9091/actuator/health"],"labels":{"scrape_kind":"probe","module":"http_health","component":"application","target_kind":"application","service_name":"ai-chat-production"}},
  {"targets":["https://api.dduo.me/"],"labels":{"scrape_kind":"probe","module":"http_api_unauthorized","component":"public_ingress","target_kind":"ingress","service_name":"ai-chat-production"}},
  {"targets":["mysql:3306"],"labels":{"scrape_kind":"probe","module":"tcp_connect","component":"mysql","target_kind":"dependency","service_name":"ai-chat-production"}},
  {"targets":["redis:6379"],"labels":{"scrape_kind":"probe","module":"tcp_connect","component":"redis","target_kind":"dependency","service_name":"ai-chat-production"}},
  {"targets":["rabbitmq:5672"],"labels":{"scrape_kind":"probe","module":"tcp_connect","component":"rabbitmq","target_kind":"dependency","service_name":"ai-chat-production"}},
  {"targets":["elasticsearch-v9:9200"],"labels":{"scrape_kind":"probe","module":"tcp_connect","component":"elasticsearch","target_kind":"dependency","service_name":"ai-chat-production"}}
]
JSON
python3 -m json.tool "${tmp_file}" >/dev/null
chmod 0644 "${tmp_file}"

install -d -o root -g root -m 0700 "${history_dir}"
if [[ -f "${target_file}" ]]; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  cp --preserve=mode,timestamps "${target_file}" "${history_dir}/ai-chat-production.${stamp}.json"
  chmod 0600 "${history_dir}/ai-chat-production.${stamp}.json"
fi
mv -f "${tmp_file}" "${target_file}"
trap - EXIT

for _ in $(seq 1 15); do
  response="$(docker exec "${prometheus_container}" wget -qO- 'http://localhost:9090/api/v1/query?query=probe_success%7Bservice_name%3D%22ai-chat-production%22%7D' || true)"
  if python3 -c '
import json, sys
payload = json.loads(sys.stdin.read())
values = payload.get("data", {}).get("result", [])
components = {item.get("metric", {}).get("component") for item in values}
sys.exit(0 if {"application", "public_ingress", "mysql", "redis", "rabbitmq", "elasticsearch"} <= components else 1)
' <<<"${response}"; then
    echo "home_monitoring_activation=PASS"
    exit 0
  fi
  sleep 1
done

echo "Prometheus did not discover the complete home production probe set" >&2
exit 75
