#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
  exit 64
fi
release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }

base_dir="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}"
runtime_dir="${base_dir}/runtime"
runtime_env="${runtime_dir}/candidate.env"
secrets_dir="${runtime_dir}/.secrets"
candidate_dir="${release}/infra/nacos-candidate"
versions_file="${release}/versions.env"
[[ -f "${runtime_env}" && -f "${candidate_dir}/compose.yaml" && -f "${versions_file}" \
  && -f "${runtime_dir}/source-config-info-count" \
  && -f "${runtime_dir}/source-his-config-info-count" ]] \
  || { echo "Nacos candidate runtime or release files are missing" >&2; exit 66; }
[[ "$(stat -c '%a' "${runtime_env}")" == 600 ]] \
  || { echo "Candidate runtime env must use mode 600" >&2; exit 68; }

set -a
# shellcheck disable=SC1090
source "${runtime_env}"
# shellcheck disable=SC1090
source "${versions_file}"
set +a
expected_image='nacos/nacos-server:v3.2.2@sha256:d6b3087bc2302c2b49c875e1c5ad8c916fc10696ceb355be56cffd715e178d81'
[[ "${NACOS3_CANDIDATE_IMAGE:-}" == "${expected_image}" ]] \
  || { echo "Unexpected Nacos candidate image" >&2; exit 66; }

for secret in mysql_password auth_token identity_key identity_value admin_password client_username client_password; do
  path="${secrets_dir}/${secret}"
  [[ -s "${path}" && "$(stat -c '%a' "${path}")" == 600 ]] \
    || { echo "Candidate secret is missing, empty, or has unsafe mode: ${secret}" >&2; exit 68; }
done

container='nacos-v3-candidate'
[[ "$(docker inspect --format '{{.State.Running}}' "${container}" 2>/dev/null || true)" == true ]] \
  || { echo "Nacos candidate container is not running" >&2; exit 70; }
[[ "$(docker inspect --format '{{.State.Health.Status}}' "${container}")" == healthy ]] \
  || { echo "Nacos candidate container is not healthy" >&2; exit 70; }
[[ "$(docker inspect --format '{{.RestartCount}}' "${container}")" == 0 ]] \
  || { echo "Nacos candidate restarted unexpectedly" >&2; exit 70; }
[[ "$(docker inspect --format '{{.Config.Image}}' "${container}")" == "${expected_image}" ]] \
  || { echo "Nacos candidate is not using the reviewed image digest" >&2; exit 70; }
docker inspect --format '{{range .HostConfig.Binds}}{{println .}}{{end}}' "${container}" \
  | grep -Fxq '/dev/null:/home/nacos/data/skills-data.zip:ro' \
  || { echo "Nacos built-in Skill bootstrap archive is not masked" >&2; exit 78; }
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${container}" \
  | grep -Fxq 'JAVA_OPT_EXT=-Dnacos.config.namespace.compatible.mode=false' \
  || { echo "Nacos namespace compatibility mirroring is not disabled" >&2; exit 78; }

ports="$(docker port "${container}")"
grep -Fq "127.0.0.1:${NACOS3_HTTP_PORT}" <<<"${ports}" \
  || { echo "Nacos candidate HTTP port is not loopback-only" >&2; exit 70; }
grep -Fq "127.0.0.1:${NACOS3_GRPC_PORT}" <<<"${ports}" \
  || { echo "Nacos candidate gRPC port is not loopback-only" >&2; exit 70; }
grep -Eq '0\.0\.0\.0|\[::\]' <<<"${ports}" \
  && { echo "Nacos candidate unexpectedly exposes a public listener" >&2; exit 70; }

source_container="${NACOS3_SOURCE_CONTAINER:-nacos}"
[[ "$(docker inspect --format '{{.State.Running}}' "${source_container}")" == true ]] \
  || { echo "Source Nacos container is no longer running" >&2; exit 70; }
baseline_restart="$(cat "${runtime_dir}/source-nacos-restart-count")"
[[ "$(docker inspect --format '{{.RestartCount}}' "${source_container}")" == "${baseline_restart}" ]] \
  || { echo "Source Nacos restart count changed during candidate preparation" >&2; exit 70; }

tmp_dir="$(mktemp -d "${runtime_dir}/.verify.XXXXXX")"
cleanup() { rm -rf -- "${tmp_dir}"; }
trap cleanup EXIT
docker logs --tail 5000 "${container}" >"${tmp_dir}/container.log" 2>&1
for secret in mysql_password auth_token identity_key identity_value admin_password client_password; do
  if grep -Fq -- "$(cat "${secrets_dir}/${secret}")" "${tmp_dir}/container.log"; then
    echo "A candidate secret was found in Docker logs: ${secret}" >&2
    exit 78
  fi
done

api_base="http://127.0.0.1:${NACOS3_HTTP_PORT}/nacos"
curl --fail --silent --show-error --connect-timeout 3 --max-time 5 \
  "${api_base}/v3/admin/core/state" --output "${tmp_dir}/state.json"
python3 - "${tmp_dir}/state.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
data = payload.get("data", {})
if payload.get("code") != 0 or data.get("version") != "3.2.2":
    raise SystemExit("Nacos candidate state/version check failed")
PY

curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${api_base}/v3/auth/user/login" \
  --data-urlencode "username@${secrets_dir}/client_username" \
  --data-urlencode "password@${secrets_dir}/client_password" \
  --output "${tmp_dir}/login.json"
python3 - "${tmp_dir}/login.json" >"${tmp_dir}/token" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
token = payload.get("accessToken")
if not isinstance(token, str) or len(token) < 20 or payload.get("globalAdmin") is True:
    raise SystemExit("Candidate client login or least-privilege check failed")
print(token, end="")
PY
printf 'Authorization: Bearer %s' "$(cat "${tmp_dir}/token")" >"${tmp_dir}/header"

client_username="$(cat "${secrets_dir}/client_username")"
[[ "${client_username}" =~ ^[a-zA-Z0-9_-]{3,50}$ ]] \
  || { echo "Candidate client username has an unsafe format" >&2; exit 68; }
mysql_container="${NACOS3_MYSQL_CONTAINER:-mysql}"
mysql_candidate() {
  docker exec -i -e MYSQL_PWD="$(cat "${secrets_dir}/mysql_password")" "${mysql_container}" \
    mysql --protocol=socket -u"${NACOS3_DB_USER}" --batch --skip-column-names "${NACOS3_DB_NAME}" "$@"
}
expected_config_count="$(cat "${runtime_dir}/source-config-info-count")"
expected_history_count="$(cat "${runtime_dir}/source-his-config-info-count")"
actual_config_count="$(mysql_candidate -e 'SELECT COUNT(*) FROM config_info')"
actual_history_count="$(mysql_candidate -e 'SELECT COUNT(*) FROM his_config_info')"
[[ "${actual_config_count}" == "${expected_config_count}" ]] \
  || { echo "Candidate config_info count drifted: expected=${expected_config_count}, actual=${actual_config_count}" >&2; exit 78; }
[[ "${actual_history_count}" == "${expected_history_count}" ]] \
  || { echo "Candidate his_config_info count drifted: expected=${expected_history_count}, actual=${actual_history_count}" >&2; exit 78; }
[[ "$(mysql_candidate -e "SELECT COUNT(*) FROM config_info WHERE tenant_id=''")" == 0 ]] \
  || { echo "Candidate still contains legacy empty-namespace configuration" >&2; exit 78; }
[[ "$(mysql_candidate -e "SELECT COUNT(*) FROM config_info WHERE group_id LIKE 'skill_enc.%'")" == 0 ]] \
  || { echo "Nacos built-in Skills unexpectedly polluted config_info" >&2; exit 78; }
role_rows="$(mysql_candidate -e "SELECT role FROM roles WHERE username='${client_username}' ORDER BY role")"
[[ "${role_rows}" == 'ROLE_AICHAT_CANDIDATE' ]] \
  || { echo "Candidate client role assignment is not least-privilege" >&2; exit 78; }
permission_rows="$(mysql_candidate -e "SELECT CONCAT(resource, '|', action) FROM permissions WHERE role='ROLE_AICHAT_CANDIDATE' ORDER BY resource, action")"
expected_permissions=$'public:DEFAULT_GROUP:config/*|r\npublic:DEFAULT_GROUP:naming/*|rw'
[[ "${permission_rows}" == "${expected_permissions}" ]] \
  || { echo "Candidate client permissions differ from the reviewed allowlist" >&2; exit 78; }

data_ids=(
  databaseR2DBC-config.properties
  redis-config.properties
  langchain4j-config.properties
  cache-config.properties
  rabbitmq-config.properties
  api-rate-limit-config.properties
  chat-elasticsearch-config.properties
  knowledge-base-config.properties
  jwt-config.properties
)
for data_id in "${data_ids[@]}"; do
  response="${tmp_dir}/${data_id}.json"
  curl --fail --silent --show-error --connect-timeout 3 --max-time 5 -H "@${tmp_dir}/header" \
    --get "${api_base}/v3/client/cs/config" \
    --data-urlencode "dataId=${data_id}" --data-urlencode 'groupName=DEFAULT_GROUP' \
    --output "${response}"
  digest="$(python3 - "${response}" <<'PY'
import hashlib, json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
content = payload.get("data", {}).get("content")
if payload.get("code") != 0 or not isinstance(content, str) or not content.strip():
    raise SystemExit("required configuration is empty")
print(hashlib.sha256(content.encode("utf-8")).hexdigest())
PY
)"
  echo "config=${data_id} sha256=${digest} status=nonempty"
done

echo "nacos_version=3.2.2"
echo "candidate_restart_count=0"
echo "source_nacos_restart_count=${baseline_restart}"
echo "network_exposure=loopback-and-private-only"
echo "auth=enabled least_privilege_client=true"
