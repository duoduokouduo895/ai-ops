#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
  exit 64
fi
[[ "${AIDIAG_V1_PRODUCTION_IDENTITIES_CONFIRMED:-}" == PROVISION_DISTINCT_PRODUCTION_IDENTITIES ]] \
  || { echo "Set AIDIAG_V1_PRODUCTION_IDENTITIES_CONFIRMED=PROVISION_DISTINCT_PRODUCTION_IDENTITIES" >&2; exit 64; }

release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }

nacos_base="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}"
elasticsearch_base="${ELASTICSEARCH9_BASE_DIR:-/opt/elasticsearch9-candidate}"
nacos_runtime_env="${nacos_base}/runtime/candidate.env"
nacos_secrets_dir="${nacos_base}/runtime/.secrets"
elasticsearch_runtime_env="${elasticsearch_base}/runtime/candidate.env"
elasticsearch_secrets_dir="${elasticsearch_base}/runtime/.secrets"
production_secrets_dir="${AICHAT_PRODUCTION_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/production}"
candidate_secrets_dir="${AICHAT_CANDIDATE_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/candidate}"
managed_marker="${production_secrets_dir}/.managed-by-aidiag-v1"

for file in "${nacos_runtime_env}" "${elasticsearch_runtime_env}"; do
  [[ -f "${file}" && ! -L "${file}" ]] \
    || { echo "Candidate runtime config is missing or unsafe: ${file}" >&2; exit 67; }
done
# shellcheck disable=SC1090
source "${nacos_runtime_env}"
# shellcheck disable=SC1090
source "${elasticsearch_runtime_env}"

for container in nacos-v3-candidate elasticsearch-v9-candidate; do
  [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container}" 2>/dev/null || true)" == healthy \
    && "$(docker inspect --format '{{.RestartCount}}' "${container}")" == 0 ]] \
    || { echo "Verified infrastructure candidate is not healthy: ${container}" >&2; exit 70; }
done

if [[ -e "${production_secrets_dir}" ]]; then
  [[ -d "${production_secrets_dir}" && ! -L "${production_secrets_dir}" \
    && -f "${managed_marker}" && "$(cat "${managed_marker}")" == aidiag-v1-production-identities ]] \
    || { echo "Existing production Secret directory is not managed by this script" >&2; exit 73; }
else
  umask 077
  production_secrets_parent="$(dirname "${production_secrets_dir}")"
  [[ -d "${production_secrets_parent}" && ! -L "${production_secrets_parent}" ]] \
    || { echo "Production Secret parent directory is missing or unsafe" >&2; exit 68; }
  pending_secrets_dir="$(mktemp -d "${production_secrets_parent}/.production.next.XXXXXX")"
  if ! (
    set -Eeuo pipefail
    printf '%s' aidiag-v1-production-identities >"${pending_secrets_dir}/.managed-by-aidiag-v1"
    printf '%s' aichat-production >"${pending_secrets_dir}/nacos_username"
    openssl rand -hex 32 >"${pending_secrets_dir}/nacos_password"
    printf '%s' aichat_production >"${pending_secrets_dir}/elasticsearch_username"
    openssl rand -hex 32 >"${pending_secrets_dir}/elasticsearch_password"
    chown -R 10001:10001 "${pending_secrets_dir}"
    chmod 0700 "${pending_secrets_dir}"
    chmod 0400 "${pending_secrets_dir}"/* "${pending_secrets_dir}/.managed-by-aidiag-v1"
  ); then
    rm -rf -- "${pending_secrets_dir}"
    echo "Unable to create production Secrets atomically" >&2
    exit 74
  fi
  mv "${pending_secrets_dir}" "${production_secrets_dir}"
fi

for name in nacos_username nacos_password elasticsearch_username elasticsearch_password; do
  path="${production_secrets_dir}/${name}"
  candidate_path="${candidate_secrets_dir}/${name}"
  [[ -f "${path}" && ! -L "${path}" && -s "${path}" \
    && "$(stat -c '%a:%u:%g' "${path}")" == '400:10001:10001' ]] \
    || { echo "Production Secret is missing or unsafe: ${name}" >&2; exit 68; }
  [[ -f "${candidate_path}" && -s "${candidate_path}" ]] \
    || { echo "Candidate rollback Secret is missing: ${name}" >&2; exit 68; }
  ! cmp --silent "${path}" "${candidate_path}" \
    || { echo "Production and candidate Secret must differ: ${name}" >&2; exit 78; }
done

tmp_dir="$(mktemp -d /opt/ai-chat/runtime/.production-identities.XXXXXX)"
cleanup() { rm -rf -- "${tmp_dir}"; }
trap cleanup EXIT

mysql_candidate() {
  docker exec -i -e MYSQL_PWD="$(cat "${nacos_secrets_dir}/mysql_password")" "${NACOS3_MYSQL_CONTAINER:-mysql}" \
    mysql --protocol=socket -u"${NACOS3_DB_USER}" --batch --skip-column-names "${NACOS3_DB_NAME}" "$@"
}

nacos_username="$(cat "${production_secrets_dir}/nacos_username")"
[[ "${nacos_username}" == aichat-production ]] \
  || { echo "Unexpected production Nacos username" >&2; exit 78; }
nacos_role=ROLE_AICHAT_PRODUCTION
nacos_api="http://127.0.0.1:${NACOS3_HTTP_PORT}/nacos/v3/auth"

curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${nacos_api}/user/login" --data-urlencode 'username=nacos' \
  --data-urlencode "password@${nacos_secrets_dir}/admin_password" \
  --output "${tmp_dir}/nacos-admin-login.json"
python3 - "${tmp_dir}/nacos-admin-login.json" >"${tmp_dir}/nacos-admin-token" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
token = payload.get("accessToken")
if not isinstance(token, str) or len(token) < 20 or payload.get("globalAdmin") is not True:
    raise SystemExit("Nacos administrator login failed")
print(token, end="")
PY
printf 'Authorization: Bearer %s' "$(cat "${tmp_dir}/nacos-admin-token")" >"${tmp_dir}/nacos-admin-header"
chmod 0600 "${tmp_dir}/nacos-admin-header"

nacos_api_result() {
  local output="$1"
  shift
  curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
    -H "@${tmp_dir}/nacos-admin-header" "$@" --output "${output}"
  python3 - "${output}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("code") != 0:
    raise SystemExit("Nacos auth API returned a failure result")
PY
}

user_count="$(mysql_candidate -e "SELECT COUNT(*) FROM users WHERE username='${nacos_username}'")"
if [[ "${user_count}" == 0 ]]; then
  nacos_api_result "${tmp_dir}/nacos-create-user.json" -X POST "${nacos_api}/user" \
    --data-urlencode "username@${production_secrets_dir}/nacos_username" \
    --data-urlencode "password@${production_secrets_dir}/nacos_password"
elif [[ "${user_count}" != 1 ]]; then
  echo "Unexpected duplicate Nacos production users" >&2
  exit 78
fi

role_rows="$(mysql_candidate -e "SELECT role FROM roles WHERE username='${nacos_username}' ORDER BY role")"
if [[ -z "${role_rows}" ]]; then
  nacos_api_result "${tmp_dir}/nacos-create-role.json" -X POST "${nacos_api}/role" \
    --data-urlencode "role=${nacos_role}" \
    --data-urlencode "username@${production_secrets_dir}/nacos_username"
elif [[ "${role_rows}" != "${nacos_role}" ]]; then
  echo "Nacos production user has unexpected roles" >&2
  exit 78
fi

add_nacos_permission_if_missing() {
  local resource="$1"
  local action="$2"
  local count
  count="$(mysql_candidate -e "SELECT COUNT(*) FROM permissions WHERE role='${nacos_role}' AND resource='${resource}' AND action='${action}'")"
  if [[ "${count}" == 0 ]]; then
    nacos_api_result "${tmp_dir}/nacos-permission-${action}-$(printf '%s' "${resource}" | sha256sum | cut -c1-8).json" \
      -X POST "${nacos_api}/permission" --data-urlencode "role=${nacos_role}" \
      --data-urlencode "resource=${resource}" --data-urlencode "action=${action}"
  elif [[ "${count}" != 1 ]]; then
    echo "Unexpected duplicate Nacos production permission" >&2
    exit 78
  fi
}
add_nacos_permission_if_missing 'public:DEFAULT_GROUP:config/*' r
add_nacos_permission_if_missing 'public:DEFAULT_GROUP:naming/*' rw

[[ "$(mysql_candidate -e "SELECT role FROM roles WHERE username='${nacos_username}' ORDER BY role")" == "${nacos_role}" ]] \
  || { echo "Nacos production role verification failed" >&2; exit 78; }
expected_permissions=$'public:DEFAULT_GROUP:config/*|r\npublic:DEFAULT_GROUP:naming/*|rw'
actual_permissions="$(mysql_candidate -e "SELECT CONCAT(resource, '|', action) FROM permissions WHERE role='${nacos_role}' ORDER BY resource, action")"
[[ "${actual_permissions}" == "${expected_permissions}" ]] \
  || { echo "Nacos production permissions differ from the reviewed allowlist" >&2; exit 78; }

curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${nacos_api}/user/login" \
  --data-urlencode "username@${production_secrets_dir}/nacos_username" \
  --data-urlencode "password@${production_secrets_dir}/nacos_password" \
  --output "${tmp_dir}/nacos-client-login.json"
python3 - "${tmp_dir}/nacos-client-login.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
token = payload.get("accessToken")
if not isinstance(token, str) or len(token) < 20 or payload.get("globalAdmin") is True:
    raise SystemExit("Nacos production least-privilege login failed")
PY

printf '%s' elastic >"${tmp_dir}/elastic-username"
printf 'Authorization: Basic %s' \
  "$(printf '%s:%s' elastic "$(cat "${elasticsearch_secrets_dir}/elastic_password")" | openssl base64 -A)" \
  >"${tmp_dir}/elastic-admin-header"
chmod 0600 "${tmp_dir}/elastic-admin-header"
cat >"${tmp_dir}/elasticsearch-role.json" <<'JSON'
{
  "cluster": ["monitor"],
  "indices": [
    {
      "names": ["conversations", "knowledge_base_chunks", "knowledge_base_embeddings*", "kb_*"],
      "privileges": ["auto_configure", "create_index", "manage", "read", "write", "view_index_metadata"]
    }
  ]
}
JSON
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/elastic-admin-header" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/role/aichat_production" \
  --data-binary "@${tmp_dir}/elasticsearch-role.json" --output "${tmp_dir}/elasticsearch-role-response.json"
printf '{"password":"%s","roles":["aichat_production"],"full_name":"AI-chat V1 production"}' \
  "$(cat "${production_secrets_dir}/elasticsearch_password")" >"${tmp_dir}/elasticsearch-user.json"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/elastic-admin-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/user/$(cat "${production_secrets_dir}/elasticsearch_username")" \
  --data-binary "@${tmp_dir}/elasticsearch-user.json" --output "${tmp_dir}/elasticsearch-user-response.json"

printf 'Authorization: Basic %s' \
  "$(printf '%s:%s' "$(cat "${production_secrets_dir}/elasticsearch_username")" "$(cat "${production_secrets_dir}/elasticsearch_password")" | openssl base64 -A)" \
  >"${tmp_dir}/elastic-client-header"
chmod 0600 "${tmp_dir}/elastic-client-header"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/elastic-client-header" \
  "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/_authenticate" \
  --output "${tmp_dir}/elasticsearch-authenticate.json"
python3 - "${tmp_dir}/elasticsearch-authenticate.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("username") != "aichat_production" or "aichat_production" not in payload.get("roles", []):
    raise SystemExit("Elasticsearch production identity verification failed")
PY

echo "production_identity_provisioning=PASS"
echo "nacos_identity=distinct-least-privilege"
echo "elasticsearch_identity=distinct-least-privilege"
echo "stable_aliases=not_changed"
echo "services=not_restarted"
