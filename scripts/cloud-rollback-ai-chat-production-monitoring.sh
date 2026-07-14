#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <cutover-id>" >&2
  exit 64
fi
[[ "${AIDIAG_PRODUCTION_MONITORING_ROLLBACK_CONFIRMED:-}" == ROLLBACK_AI_CHAT_PRODUCTION_MONITORING ]] \
  || { echo "Set AIDIAG_PRODUCTION_MONITORING_ROLLBACK_CONFIRMED=ROLLBACK_AI_CHAT_PRODUCTION_MONITORING" >&2; exit 64; }

cutover_id="$1"
base_dir="${AIDIAG_BASE_DIR:-/opt/aidiag}"
runtime_dir="${base_dir}/runtime"
target_dir="${runtime_dir}/prometheus-targets"
target_file="${target_dir}/ai-chat-production.json"
marker_file="${target_dir}/.ai-chat-production-monitoring.env"
lock_file="${runtime_dir}/.production-monitoring.lock"

[[ "${cutover_id}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}$ ]] \
  || { echo "Invalid cutover id" >&2; exit 65; }
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
[[ -f "${target_file}" && ! -L "${target_file}" && -f "${marker_file}" && ! -L "${marker_file}" ]] \
  || { echo "Managed production monitoring target is not active" >&2; exit 69; }
grep -qxF "CUTOVER_ID=${cutover_id}" "${marker_file}" \
  || { echo "Active production monitoring belongs to another cutover" >&2; exit 69; }
target_sha256="$(sha256sum "${target_file}" | awk '{print $1}')"
grep -qxF "TARGET_SHA256=${target_sha256}" "${marker_file}" \
  || { echo "Production monitoring target digest does not match its marker" >&2; exit 78; }

if docker ps --quiet --filter label=com.docker.compose.project=ai-chat-production | grep -q .; then
  echo "Stop AI-chat production before removing its monitoring targets" >&2
  exit 70
fi

mapfile -t prometheus_containers < <(docker ps \
  --filter label=com.docker.compose.project=aidiag \
  --filter label=com.docker.compose.service=prometheus --format '{{.ID}}')
[[ ${#prometheus_containers[@]} -eq 1 ]] \
  || { echo "Expected exactly one running AIDiag Prometheus container" >&2; exit 70; }
prometheus_container="${prometheus_containers[0]}"

archive_dir="${runtime_dir}/production-monitoring/${cutover_id}/disabled-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -o 0 -g 0 -m 0700 "${archive_dir}"
mv "${target_file}" "${archive_dir}/ai-chat-production.json"
mv "${marker_file}" "${archive_dir}/activation.env"
chmod 0600 "${archive_dir}"/*

removed=false
for _ in $(seq 1 15); do
  if docker exec "${prometheus_container}" wget -qO- 'http://localhost:9090/api/v1/targets?state=active' \
      | python3 -c 'import json,sys; p=json.load(sys.stdin); t=[x for x in p["data"]["activeTargets"] if x.get("labels",{}).get("service_name")=="ai-chat-production"]; raise SystemExit(0 if not t else 1)' ; then
    removed=true
    break
  fi
  sleep 1
done
[[ "${removed}" == true ]] \
  || { echo "Prometheus still reports active production targets; archived files were not deleted" >&2; exit 75; }

echo "production_monitoring_rollback=PASS"
echo "cutover_id=${cutover_id}"
echo "production_targets=0"
echo "candidate_targets=unchanged"
echo "archive=${archive_dir}"
