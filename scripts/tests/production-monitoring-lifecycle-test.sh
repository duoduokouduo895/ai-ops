#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <repository-root>" >&2
  exit 64
fi
[[ "$(id -u)" == 0 ]] || { echo "This isolated ownership test must run as root" >&2; exit 77; }

repo_root="$(readlink -f "$1")"
activation="${repo_root}/scripts/cloud-activate-ai-chat-production-monitoring.sh"
rollback="${repo_root}/scripts/cloud-rollback-ai-chat-production-monitoring.sh"
[[ -x "${activation}" && -x "${rollback}" ]] || { echo "Lifecycle scripts are missing" >&2; exit 66; }

fixture="$(mktemp -d /tmp/aidiag-production-monitoring-test.XXXXXX)"
cleanup() { rm -rf -- "${fixture}"; }
trap cleanup EXIT

aidiag_base="${fixture}/aidiag"
aichat_base="${fixture}/ai-chat"
release="${aidiag_base}/releases/test-release"
target_dir="${aidiag_base}/runtime/prometheus-targets"
cutover_id="test-cutover-1"
cutover_dir="${aichat_base}/runtime/cutovers/${cutover_id}"
production_env="${aichat_base}/runtime/production.env"
mock_bin="${fixture}/bin"
mock_state="${fixture}/state"
install -d -m 0755 "${release}/infra/prometheus/rules" "${release}/infra/alloy" \
  "${target_dir}" "${mock_bin}" "${mock_state}"
install -d -m 0700 "${cutover_dir}"
ln -s "${release}" "${aidiag_base}/current"

cat >"${release}/infra/prometheus/prometheus.yml" <<'EOF'
file_sd_configs: [/etc/prometheus/targets/ai-chat-production.json]
EOF
touch "${release}/infra/prometheus/rules/aidiag.yml"
cat >"${release}/infra/alloy/config.alloy" <<'EOF'
__path__ = "/var/log/ai-chat/production-operations.jsonl"
EOF

write_production_env() {
  local confirmed="$1"
  cat >"${production_env}" <<EOF
AICHAT_CUTOVER_ID=${cutover_id}
AICHAT_PRODUCTION_CONFIRMED=${confirmed}
AICHAT_SERVICE_NAME=ai-chat-production
AICHAT_PUBLIC_HEALTH_URL=https://api.dduo.me/healthz
NACOS_SERVER_ADDR=nacos-v3:8848
ELASTICSEARCH_SERVER_ADDR=elasticsearch-v9:9200
EOF
  chmod 0600 "${production_env}"
}
write_production_env false

cat >"${cutover_dir}/old-writers-stopped.env" <<EOF
CUTOVER_ID=${cutover_id}
OLD_PRODUCTION_WRITERS_STOPPED=true
EOF
cat >"${cutover_dir}/approved.env" <<EOF
CUTOVER_ID=${cutover_id}
PRODUCTION_ACTIVATION_APPROVED=true
EOF
printf 'fixture backup\n' | gzip -9 >"${cutover_dir}/ai_agent_db.sql.gz"
(cd "${cutover_dir}" && sha256sum ai_agent_db.sql.gz >ai_agent_db.sql.gz.sha256)
chmod 0600 "${cutover_dir}"/*

cat >"${mock_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
args="$*"
if [[ "$1" == ps ]]; then
  if [[ "${args}" == *'com.docker.compose.service=prometheus'* ]]; then
    echo prometheus-test
  elif [[ "${args}" == *'com.docker.compose.project=ai-chat-candidate'* && -e "${MOCK_STATE}/candidate-running" ]]; then
    echo candidate-test
  elif [[ "${args}" == *'com.docker.compose.project=ai-chat-production'* && -e "${MOCK_STATE}/production-running" ]]; then
    echo production-test
  fi
  exit 0
fi
if [[ "$1" == inspect ]]; then
  echo "${MOCK_TARGET_DIR}"
  exit 0
fi
if [[ "$1" == exec && "${args}" == *'promtool check config'* ]]; then
  exit 0
fi
if [[ "$1" == exec && "${args}" == *'/api/v1/targets?state=active'* ]]; then
  python3 - "${MOCK_TARGET_DIR}/ai-chat-production.json" <<'PY'
import json
import os
import sys

targets = []
for index in range(7):
    targets.append({
        "labels": {"service_name": "ai-chat-candidate"},
        "scrapePool": "ai-chat-candidate" if index == 0 else "blackbox-ai-chat",
    })
if os.path.exists(sys.argv[1]):
    for index in range(8):
        targets.append({
            "labels": {"service_name": "ai-chat-production"},
            "scrapePool": "ai-chat-production" if index == 0 else "blackbox-ai-chat",
        })
print(json.dumps({"data": {"activeTargets": targets}}))
PY
  exit 0
fi
echo "Unexpected mock docker invocation: ${args}" >&2
exit 99
EOF
chmod 0755 "${mock_bin}/docker"

export PATH="${mock_bin}:${PATH}"
export MOCK_STATE="${mock_state}"
export MOCK_TARGET_DIR="${target_dir}"
export AIDIAG_BASE_DIR="${aidiag_base}"
export AICHAT_BASE_DIR="${aichat_base}"
export AICHAT_PRODUCTION_ENV="${production_env}"
export AIDIAG_PRODUCTION_MONITORING_CONFIRMED=ACTIVATE_AI_CHAT_PRODUCTION_MONITORING
export AIDIAG_PRODUCTION_MONITORING_ROLLBACK_CONFIRMED=ROLLBACK_AI_CHAT_PRODUCTION_MONITORING

expect_exit() {
  local expected="$1"
  shift
  set +e
  "$@" >/dev/null 2>&1
  local actual=$?
  set -e
  [[ "${actual}" == "${expected}" ]] \
    || { echo "Expected exit ${expected}, got ${actual}: $*" >&2; exit 1; }
}

expect_exit 69 "${activation}" "${release}" "${cutover_id}"
[[ ! -e "${target_dir}/ai-chat-production.json" ]]

write_production_env true
touch "${mock_state}/candidate-running"
expect_exit 70 "${activation}" "${release}" "${cutover_id}"
rm -f "${mock_state}/candidate-running"

"${activation}" "${release}" "${cutover_id}" >/dev/null
[[ -s "${target_dir}/ai-chat-production.json" && -s "${target_dir}/.ai-chat-production-monitoring.env" ]]
python3 -m json.tool "${target_dir}/ai-chat-production.json" >/dev/null

touch "${mock_state}/production-running"
"${activation}" "${release}" "${cutover_id}" >/dev/null
expect_exit 70 "${rollback}" "${cutover_id}"
rm -f "${mock_state}/production-running"

"${rollback}" "${cutover_id}" >/dev/null
[[ ! -e "${target_dir}/ai-chat-production.json" && ! -e "${target_dir}/.ai-chat-production-monitoring.env" ]]
[[ "$(find "${aidiag_base}/runtime/production-monitoring/${cutover_id}" -type f | wc -l)" -ge 2 ]]

echo "production_monitoring_lifecycle_test=PASS"
