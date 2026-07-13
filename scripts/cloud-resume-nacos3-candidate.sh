#!/usr/bin/env bash
set -Eeuo pipefail

[[ "${NACOS3_RESUME_CONFIRMED:-}" == "RESUME_ISOLATED_NACOS3" ]] \
  || { echo "Set NACOS3_RESUME_CONFIRMED=RESUME_ISOLATED_NACOS3 to resume the retained candidate" >&2; exit 64; }
[[ $# -eq 1 ]] || { echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2; exit 64; }
(( EUID == 0 )) || { echo "Run as root on the Docker host" >&2; exit 77; }

release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }
base_dir="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}"
runtime_dir="${base_dir}/runtime"
runtime_env="${runtime_dir}/candidate.env"
secrets_dir="${runtime_dir}/.secrets"
candidate_dir="${release}/infra/nacos-candidate"
versions_file="${release}/versions.env"
evidence_dir="${base_dir}/evidence/$(date -u +%Y%m%dT%H%M%SZ)-resume"

[[ -f "${runtime_env}" && -f "${candidate_dir}/compose.yaml" && -f "${versions_file}" ]] \
  || { echo "Retained candidate runtime or release files are missing" >&2; exit 66; }
if grep -q $'\r' "${versions_file}"; then
  echo "versions.env must use LF line endings before running on Linux" >&2
  exit 66
fi
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
for volume in nacos3-candidate-logs nacos3-candidate-data; do
  docker volume inspect "${volume}" >/dev/null \
    || { echo "Retained candidate volume is missing: ${volume}" >&2; exit 70; }
done
docker inspect nacos-v3-candidate >/dev/null 2>&1 \
  || { echo "Retained candidate container is missing" >&2; exit 70; }
[[ "$(docker inspect --format '{{.State.Running}}' nacos-v3-candidate)" != true ]] \
  || { echo "Stop the retained candidate before resuming preparation" >&2; exit 73; }

mysql_container="${NACOS3_MYSQL_CONTAINER:-mysql}"
mysql_candidate() {
  docker exec -i -e MYSQL_PWD="$(cat "${secrets_dir}/mysql_password")" "${mysql_container}" \
    mysql --protocol=socket -u"${NACOS3_DB_USER}" --batch --skip-column-names "${NACOS3_DB_NAME}" "$@"
}
for table in users roles permissions; do
  [[ "$(mysql_candidate -e "SELECT COUNT(*) FROM \`${table}\`")" == 0 ]] \
    || { echo "Candidate auth bootstrap is no longer empty: ${table}" >&2; exit 72; }
done
(( $(mysql_candidate -e 'SELECT COUNT(*) FROM config_info') > 0 )) \
  || { echo "Candidate configuration migration is empty" >&2; exit 72; }

source_restart_count="$(cat "${runtime_dir}/source-nacos-restart-count")"
[[ "$(docker inspect --format '{{.State.Running}}' nacos 2>/dev/null || true)" == true ]] \
  || { echo "Source Nacos container is not running" >&2; exit 70; }
[[ "$(docker inspect --format '{{.RestartCount}}' nacos)" == "${source_restart_count}" ]] \
  || { echo "Source Nacos restart count changed after initial preparation" >&2; exit 70; }

compose=(docker compose --project-name nacos3-candidate --project-directory "${candidate_dir}" \
  -f "${candidate_dir}/compose.yaml" --env-file "${versions_file}" --env-file "${runtime_env}")
"${compose[@]}" config --quiet
install -d -m 0700 "${evidence_dir}"
candidate_started=false
on_error() {
  local exit_code=$?
  trap - ERR
  set +e
  if [[ "${candidate_started}" == true ]]; then
    "${compose[@]}" stop --timeout 30 nacos3-candidate >/dev/null 2>&1
  fi
  echo "Nacos 3 candidate resume failed; retained data was kept and the source Nacos was not changed." >&2
  exit "${exit_code}"
}
trap on_error ERR

"${compose[@]}" up -d --force-recreate nacos3-candidate
candidate_started=true
ready=false
for _ in $(seq 1 60); do
  if curl --fail --silent --max-time 3 \
    "http://127.0.0.1:${NACOS3_HTTP_PORT}/nacos/v3/admin/core/state/readiness" >/dev/null; then
    ready=true
    break
  fi
  sleep 5
done
[[ "${ready}" == true ]] || { echo "Nacos candidate did not become ready after resume" >&2; exit 76; }

tmp_options="$(docker inspect --format '{{index .HostConfig.Tmpfs "/tmp"}}' nacos-v3-candidate)"
grep -Eq '(^|,)rw(,|$)' <<<"${tmp_options}" \
  || { echo "Candidate /tmp is not writable" >&2; exit 78; }
grep -Eq '(^|,)nosuid(,|$)' <<<"${tmp_options}" \
  || { echo "Candidate /tmp is missing nosuid" >&2; exit 78; }
grep -Eq '(^|,)nodev(,|$)' <<<"${tmp_options}" \
  || { echo "Candidate /tmp is missing nodev" >&2; exit 78; }
grep -Eq '(^|,)noexec(,|$)' <<<"${tmp_options}" \
  && { echo "Candidate /tmp still blocks RocksDB JNI execution" >&2; exit 78; }

tmp_dir="$(mktemp -d "${runtime_dir}/.auth-resume.XXXXXX")"
cleanup() { rm -rf -- "${tmp_dir}"; }
trap cleanup EXIT
api_base="http://127.0.0.1:${NACOS3_HTTP_PORT}/nacos/v3/auth"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${api_base}/user/admin" \
  --data-urlencode "password@${secrets_dir}/admin_password" --output "${tmp_dir}/admin.json"
python3 - "${tmp_dir}/admin.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("code") != 0 or payload.get("data", {}).get("username") != "nacos":
    raise SystemExit("Nacos administrator bootstrap failed")
PY

curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${api_base}/user/login" \
  --data-urlencode 'username=nacos' --data-urlencode "password@${secrets_dir}/admin_password" \
  --output "${tmp_dir}/admin-login.json"
python3 - "${tmp_dir}/admin-login.json" >"${tmp_dir}/admin-token" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
token = payload.get("accessToken")
if not isinstance(token, str) or len(token) < 20:
    raise SystemExit("Nacos administrator login did not return a token")
print(token, end="")
PY
printf 'Authorization: Bearer %s' "$(cat "${tmp_dir}/admin-token")" >"${tmp_dir}/admin-header"

api_result() {
  local output="$1"
  shift
  curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
    -H "@${tmp_dir}/admin-header" "$@" --output "${output}"
  python3 - "${output}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("code") != 0:
    raise SystemExit("Nacos auth API returned a failure result")
PY
}

api_result "${tmp_dir}/create-user.json" -X POST "${api_base}/user" \
  --data-urlencode "username@${secrets_dir}/client_username" \
  --data-urlencode "password@${secrets_dir}/client_password"
api_result "${tmp_dir}/create-role.json" -X POST "${api_base}/role" \
  --data-urlencode 'role=ROLE_AICHAT_CANDIDATE' \
  --data-urlencode "username@${secrets_dir}/client_username"
api_result "${tmp_dir}/config-read.json" -X POST "${api_base}/permission" \
  --data-urlencode 'role=ROLE_AICHAT_CANDIDATE' \
  --data-urlencode 'resource=public:DEFAULT_GROUP:config/*' --data-urlencode 'action=r'
api_result "${tmp_dir}/naming-rw.json" -X POST "${api_base}/permission" \
  --data-urlencode 'role=ROLE_AICHAT_CANDIDATE' \
  --data-urlencode 'resource=public:DEFAULT_GROUP:naming/*' --data-urlencode 'action=rw'

aichat_secret_dir="${AICHAT_CANDIDATE_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/candidate}"
install -d -m 0700 "${aichat_secret_dir}"
install -m 0600 "${secrets_dir}/client_username" "${aichat_secret_dir}/nacos_username"
install -m 0600 "${secrets_dir}/client_password" "${aichat_secret_dir}/nacos_password"

"${release}/scripts/cloud-verify-nacos3-candidate.sh" "${release}" \
  | tee "${evidence_dir}/verification.txt"
chmod 0600 "${evidence_dir}/verification.txt"
trap - ERR
echo "Retained Nacos 3.2.2 candidate resumed, bootstrapped, and verified."
