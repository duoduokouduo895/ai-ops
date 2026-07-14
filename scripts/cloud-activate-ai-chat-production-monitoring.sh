#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name> <cutover-id>" >&2
  exit 64
fi
[[ "${AIDIAG_PRODUCTION_MONITORING_CONFIRMED:-}" == ACTIVATE_AI_CHAT_PRODUCTION_MONITORING ]] \
  || { echo "Set AIDIAG_PRODUCTION_MONITORING_CONFIRMED=ACTIVATE_AI_CHAT_PRODUCTION_MONITORING" >&2; exit 64; }

release="$(readlink -f "$1")"
release_name="$(basename "${release}")"
cutover_id="$2"
base_dir="${AIDIAG_BASE_DIR:-/opt/aidiag}"
runtime_dir="${base_dir}/runtime"
target_dir="${runtime_dir}/prometheus-targets"
target_file="${target_dir}/ai-chat-production.json"
marker_file="${target_dir}/.ai-chat-production-monitoring.env"
production_env="${AICHAT_PRODUCTION_ENV:-/opt/ai-chat/runtime/production.env}"
aichat_base_dir="${AICHAT_BASE_DIR:-/opt/ai-chat}"
cutover_dir="${aichat_base_dir}/runtime/cutovers/${cutover_id}"
lock_file="${runtime_dir}/.production-monitoring.lock"

[[ "${release}" == "${base_dir}/releases/"* && -d "${release}" && ! -L "${release}" ]] \
  || { echo "Release must be a real directory below ${base_dir}/releases" >&2; exit 65; }
[[ "${release_name}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$ ]] \
  || { echo "Invalid release name" >&2; exit 65; }
[[ "${cutover_id}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}$ ]] \
  || { echo "Invalid cutover id" >&2; exit 65; }
[[ "$(readlink -f "${base_dir}/current")" == "${release}" ]] \
  || { echo "The monitoring-aware release must be active before production targets are enabled" >&2; exit 66; }
for path in \
  "${release}/infra/prometheus/prometheus.yml" \
  "${release}/infra/prometheus/rules/aidiag.yml" \
  "${release}/infra/alloy/config.alloy"; do
  [[ -f "${path}" && ! -L "${path}" ]] \
    || { echo "Monitoring release is incomplete or unsafe: ${path}" >&2; exit 66; }
done
grep -qF '/etc/prometheus/targets/ai-chat-production.json' "${release}/infra/prometheus/prometheus.yml" \
  || { echo "Release does not contain the production file_sd contract" >&2; exit 66; }
grep -qF '/var/log/ai-chat/production-operations.jsonl' "${release}/infra/alloy/config.alloy" \
  || { echo "Release does not collect production operations logs" >&2; exit 66; }
[[ -f "${production_env}" && ! -L "${production_env}" \
  && "$(stat -c '%a:%u:%g' "${production_env}")" == '600:0:0' ]] \
  || { echo "Production runtime config is missing or unsafe" >&2; exit 67; }
grep -qxF "AICHAT_CUTOVER_ID=${cutover_id}" "${production_env}" \
  || { echo "Cutover id does not match production.env" >&2; exit 67; }

env_value() {
  local key="$1"
  local matches=()
  mapfile -t matches < <(grep -E "^${key}=" "${production_env}" || true)
  (( ${#matches[@]} == 1 )) \
    || { echo "${key} must occur exactly once in production.env" >&2; return 69; }
  printf '%s' "${matches[0]#*=}"
}

require_env_value() {
  local key="$1"
  local expected="$2"
  [[ "$(env_value "${key}")" == "${expected}" ]] \
    || { echo "${key} must be ${expected} before production monitoring activation" >&2; exit 69; }
}

require_env_value AICHAT_PRODUCTION_CONFIRMED true
require_env_value AICHAT_SERVICE_NAME ai-chat-production
require_env_value NACOS_SERVER_ADDR nacos-v3:8848
require_env_value ELASTICSEARCH_SERVER_ADDR elasticsearch-v9:9200
public_health_url="$(env_value AICHAT_PUBLIC_HEALTH_URL)"
python3 - "${public_health_url}" <<'PY'
import sys
from urllib.parse import urlsplit

value = sys.argv[1]
parsed = urlsplit(value)
if (not value or len(value) > 2048 or any(character.isspace() for character in value)
        or parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
        or parsed.query or parsed.fragment or not parsed.path.startswith("/")):
    raise SystemExit("AICHAT_PUBLIC_HEALTH_URL must be an HTTPS URL without credentials, query, or fragment")
PY

writer_evidence="${cutover_dir}/old-writers-stopped.env"
approval_evidence="${cutover_dir}/approved.env"
backup_file="${cutover_dir}/ai_agent_db.sql.gz"
backup_checksum="${backup_file}.sha256"
for path in "${writer_evidence}" "${approval_evidence}" "${backup_file}" "${backup_checksum}"; do
  [[ -f "${path}" && ! -L "${path}" && -s "${path}" \
    && "$(stat -c '%a:%u:%g' "${path}")" == '600:0:0' ]] \
    || { echo "Cutover evidence is missing or unsafe: ${path}" >&2; exit 68; }
done
grep -qxF "CUTOVER_ID=${cutover_id}" "${writer_evidence}" \
  && grep -qxF 'OLD_PRODUCTION_WRITERS_STOPPED=true' "${writer_evidence}" \
  && grep -qxF "CUTOVER_ID=${cutover_id}" "${approval_evidence}" \
  && grep -qxF 'PRODUCTION_ACTIVATION_APPROVED=true' "${approval_evidence}" \
  || { echo "Cutover approval does not prove that old writers are stopped" >&2; exit 78; }
(cd "${cutover_dir}" && sha256sum --check "$(basename "${backup_checksum}")" >/dev/null) \
  && gzip --test "${backup_file}" \
  || { echo "Production database backup verification failed" >&2; exit 78; }

[[ -d "${target_dir}" && ! -L "${target_dir}" \
  && "$(stat -c '%a:%u:%g' "${target_dir}")" == '755:0:0' ]] \
  || { echo "Prometheus runtime target directory is missing or unsafe" >&2; exit 68; }
if [[ -e "${lock_file}" ]]; then
  [[ -f "${lock_file}" && ! -L "${lock_file}" \
    && "$(stat -c '%a:%u:%g' "${lock_file}")" == '600:0:0' ]] \
    || { echo "Production monitoring lock file is unsafe" >&2; exit 68; }
else
  install -o 0 -g 0 -m 0600 /dev/null "${lock_file}"
fi
command -v flock >/dev/null || { echo "flock is required" >&2; exit 70; }
exec 9<>"${lock_file}"
flock -n 9 || { echo "Another production monitoring operation is running" >&2; exit 75; }

if docker ps --quiet --filter label=com.docker.compose.project=ai-chat-candidate | grep -q .; then
  echo "AI-chat candidate must be stopped before production monitoring activation" >&2
  exit 70
fi

mapfile -t prometheus_containers < <(docker ps \
  --filter label=com.docker.compose.project=aidiag \
  --filter label=com.docker.compose.service=prometheus --format '{{.ID}}')
[[ ${#prometheus_containers[@]} -eq 1 ]] \
  || { echo "Expected exactly one running AIDiag Prometheus container" >&2; exit 70; }
prometheus_container="${prometheus_containers[0]}"
active_target_dir="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/etc/prometheus/targets"}}{{.Source}}{{end}}{{end}}' "${prometheus_container}")"
[[ -n "${active_target_dir}" && "$(readlink -f "${active_target_dir}")" == "$(readlink -f "${target_dir}")" ]] \
  || { echo "Running Prometheus is not using the reviewed runtime target directory" >&2; exit 70; }
docker exec "${prometheus_container}" promtool check config /etc/prometheus/prometheus.yml >/dev/null

docker exec "${prometheus_container}" wget -qO- 'http://localhost:9090/api/v1/targets?state=active' \
  | python3 -c 'import json,sys; p=json.load(sys.stdin); t=[x for x in p["data"]["activeTargets"] if x.get("labels",{}).get("service_name")=="ai-chat-candidate"]; pools={x.get("scrapePool") for x in t}; raise SystemExit(0 if len(t)==7 and pools=={"ai-chat-candidate","blackbox-ai-chat"} else 1)' \
  || { echo "Candidate monitoring definitions must remain present before production activation" >&2; exit 78; }

if [[ -e "${target_file}" || -e "${marker_file}" ]]; then
  [[ -f "${target_file}" && ! -L "${target_file}" && -f "${marker_file}" && ! -L "${marker_file}" \
    && "$(stat -c '%a:%u:%g' "${target_file}")" == '644:0:0' \
    && "$(stat -c '%a:%u:%g' "${marker_file}")" == '644:0:0' \
    && "$(grep -E '^CUTOVER_ID=' "${marker_file}")" == "CUTOVER_ID=${cutover_id}" \
    && "$(grep -E '^RELEASE=' "${marker_file}")" == "RELEASE=${release_name}" ]] \
    || { echo "Existing production monitoring targets are unmanaged or belong to another cutover" >&2; exit 73; }
  created=false
else
  if docker ps --quiet --filter label=com.docker.compose.project=ai-chat-production | grep -q .; then
    echo "Production monitoring must be activated before AI-chat production starts" >&2
    exit 70
  fi
  created=true
  next_target="$(mktemp "${target_dir}/.ai-chat-production.json.next.XXXXXX")"
  next_marker="$(mktemp "${target_dir}/.ai-chat-production.env.next.XXXXXX")"
  cleanup() { rm -f -- "${next_target}" "${next_marker}"; }
  trap cleanup EXIT
  python3 - "${next_target}" "${public_health_url}" <<'PY'
import json
import sys

service = "ai-chat-production"
targets = [
    {"targets": ["ai-chat-production:9091"], "labels": {"scrape_kind": "metrics", "service_name": service}},
    {"targets": ["http://ai-chat-production:9091/actuator/health"], "labels": {"scrape_kind": "probe", "module": "http_health", "component": "application", "target_kind": "application", "service_name": service}},
    {"targets": [sys.argv[2]], "labels": {"scrape_kind": "probe", "module": "http_health", "component": "public-ingress", "target_kind": "ingress", "service_name": service}},
    {"targets": ["mysql:3306"], "labels": {"scrape_kind": "probe", "module": "tcp_connect", "component": "mysql", "target_kind": "dependency", "service_name": service}},
    {"targets": ["redis:6379"], "labels": {"scrape_kind": "probe", "module": "tcp_connect", "component": "redis", "target_kind": "dependency", "service_name": service}},
    {"targets": ["rabbitmq:5672"], "labels": {"scrape_kind": "probe", "module": "tcp_connect", "component": "rabbitmq", "target_kind": "dependency", "service_name": service}},
    {"targets": ["elasticsearch-v9:9200"], "labels": {"scrape_kind": "probe", "module": "tcp_connect", "component": "elasticsearch", "target_kind": "dependency", "service_name": service}},
    {"targets": ["nacos-v3:8848"], "labels": {"scrape_kind": "probe", "module": "tcp_connect", "component": "nacos", "target_kind": "dependency", "service_name": service}},
]
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump(targets, handle, ensure_ascii=False, separators=(",", ":"))
    handle.write("\n")
PY
  python3 -m json.tool "${next_target}" >/dev/null
  target_sha256="$(sha256sum "${next_target}" | awk '{print $1}')"
  cat >"${next_marker}" <<EOF
CUTOVER_ID=${cutover_id}
RELEASE=${release_name}
TARGET_SHA256=${target_sha256}
ACTIVATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  chmod 0644 "${next_target}" "${next_marker}"
  mv "${next_marker}" "${marker_file}"
  if ! mv "${next_target}" "${target_file}"; then
    rm -f -- "${marker_file}"
    echo "Unable to activate the production monitoring target atomically" >&2
    exit 74
  fi
  trap - EXIT
fi

target_sha256="$(sha256sum "${target_file}" | awk '{print $1}')"
grep -qxF "TARGET_SHA256=${target_sha256}" "${marker_file}" \
  || { echo "Production monitoring target digest does not match its marker" >&2; exit 78; }

discovered=false
for _ in $(seq 1 15); do
  if docker exec "${prometheus_container}" wget -qO- 'http://localhost:9090/api/v1/targets?state=active' \
      | python3 -c 'import json,sys; p=json.load(sys.stdin); t=[x for x in p["data"]["activeTargets"] if x.get("labels",{}).get("service_name")=="ai-chat-production"]; pools={x.get("scrapePool") for x in t}; raise SystemExit(0 if len(t)==8 and pools=={"ai-chat-production","blackbox-ai-chat"} else 1)' ; then
    discovered=true
    break
  fi
  sleep 1
done
[[ "${discovered}" == true ]] \
  || {
    if [[ "${created}" == true ]]; then
      failed_dir="${runtime_dir}/production-monitoring/${cutover_id}/failed-$(date -u +%Y%m%dT%H%M%SZ)"
      install -d -o 0 -g 0 -m 0700 "${failed_dir}"
      mv "${target_file}" "${failed_dir}/ai-chat-production.json"
      mv "${marker_file}" "${failed_dir}/activation.env"
      chmod 0600 "${failed_dir}"/*
    fi
    echo "Prometheus did not discover all eight production targets; a new activation was withdrawn" >&2
    exit 75
  }

evidence_dir="${runtime_dir}/production-monitoring/${cutover_id}"
install -d -o 0 -g 0 -m 0700 "${evidence_dir}"
cp --preserve=mode,timestamps "${marker_file}" "${evidence_dir}/activation.env"
chmod 0600 "${evidence_dir}/activation.env"

echo "production_monitoring_activation=PASS"
echo "cutover_id=${cutover_id}"
echo "production_targets=8"
echo "candidate_targets=unchanged"
echo "expected_application_state=down-until-ai-chat-production-starts"
