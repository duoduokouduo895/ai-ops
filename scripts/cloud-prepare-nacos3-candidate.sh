#!/usr/bin/env bash
set -Eeuo pipefail

confirmation="${NACOS3_CANDIDATE_CONFIRMED:-}"
if [[ "${confirmation}" != "CREATE_ISOLATED_NACOS3_CANDIDATE" ]]; then
  echo "Refusing cloud writes. Set NACOS3_CANDIDATE_CONFIRMED=CREATE_ISOLATED_NACOS3_CANDIDATE after reviewing the impact." >&2
  exit 64
fi
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
  exit 64
fi
(( EUID == 0 )) || { echo "Run as root on the Docker host" >&2; exit 77; }

release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }
candidate_dir="${release}/infra/nacos-candidate"
versions_file="${release}/versions.env"
[[ -f "${candidate_dir}/compose.yaml" && -f "${candidate_dir}/entrypoint.sh" && -f "${versions_file}" ]] \
  || { echo "Nacos candidate release files are incomplete" >&2; exit 66; }

for command in awk curl docker flock gzip openssl python3 sha256sum ss stat; do
  command -v "${command}" >/dev/null || { echo "Required command is missing: ${command}" >&2; exit 69; }
done
docker compose version >/dev/null

base_dir="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}"
runtime_dir="${base_dir}/runtime"
secrets_dir="${runtime_dir}/.secrets"
backup_root="${base_dir}/backups"
evidence_root="${base_dir}/evidence"
install -d -m 0700 "${base_dir}" "${runtime_dir}" "${secrets_dir}" "${backup_root}" "${evidence_root}"
exec 8>"${base_dir}/prepare.lock"
flock -n 8 || { echo "Another Nacos candidate preparation is running" >&2; exit 75; }
umask 077

source_container="${NACOS3_SOURCE_CONTAINER:-nacos}"
mysql_container="${NACOS3_MYSQL_CONTAINER:-mysql}"
candidate_container="nacos-v3-candidate"
candidate_db="${NACOS3_DB_NAME:-nacos_v3_candidate}"
candidate_db_user="${NACOS3_DB_USER:-nacos_v3_candidate}"
compose_project="${NACOS3_COMPOSE_PROJECT:-nacos3-candidate}"
logs_volume="${NACOS3_LOGS_VOLUME:-nacos3-candidate-logs}"
data_volume="${NACOS3_DATA_VOLUME:-nacos3-candidate-data}"
http_port="${NACOS3_HTTP_PORT:-18848}"
grpc_port="${NACOS3_GRPC_PORT:-19848}"
aichat_secret_dir="${AICHAT_CANDIDATE_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/candidate}"

for value in "${candidate_db}" "${candidate_db_user}"; do
  [[ "${value}" =~ ^[a-z][a-z0-9_]{2,62}$ ]] \
    || { echo "Candidate database names must match ^[a-z][a-z0-9_]{2,62}$" >&2; exit 69; }
done
for value in "${compose_project}" "${logs_volume}" "${data_volume}"; do
  [[ "${value}" =~ ^[a-z0-9][a-z0-9_.-]{2,62}$ ]] \
    || { echo "Candidate Docker names must match ^[a-z0-9][a-z0-9_.-]{2,62}$" >&2; exit 69; }
done
for value in "${http_port}" "${grpc_port}"; do
  [[ "${value}" =~ ^[0-9]+$ ]] && (( value >= 1024 && value <= 65535 )) \
    || { echo "Candidate ports must be numeric and between 1024 and 65535" >&2; exit 69; }
done
[[ "${http_port}" != "${grpc_port}" ]] || { echo "Candidate ports must differ" >&2; exit 69; }

container_running() {
  [[ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" == true ]]
}
container_env_value() {
  local container="$1" key="$2"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${container}" \
    | awk -v key="${key}" 'index($0, key "=") == 1 {sub("^[^=]*=", ""); print; exit}'
}
mysql_root() {
  docker exec -i -e MYSQL_PWD="${mysql_root_password}" "${mysql_container}" \
    mysql --protocol=socket -uroot --batch --skip-column-names "$@"
}
schema_exists() {
  [[ "$(mysql_root -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${1}'")" == 1 ]]
}
table_exists() {
  [[ "$(mysql_root -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${1}' AND table_name='${2}'")" == 1 ]]
}
table_count() {
  mysql_root -e "SELECT COUNT(*) FROM \`${1}\`.\`${2}\`"
}
port_in_use() {
  ss -H -ltn | awk '{print $4}' | grep -Eq "(^|:)${1}$"
}

container_running "${source_container}" || { echo "Source Nacos container is not running" >&2; exit 70; }
container_running "${mysql_container}" || { echo "MySQL container is not running" >&2; exit 70; }
source_image="$(docker inspect --format '{{.Config.Image}}' "${source_container}")"
[[ "${source_image}" == nacos/nacos-server:v2.1.1* ]] \
  || { echo "Unexpected source Nacos image: ${source_image}" >&2; exit 70; }
source_restart_count="$(docker inspect --format '{{.RestartCount}}' "${source_container}")"

for network in middleware-net nacos-net; do
  docker network inspect "${network}" >/dev/null \
    || { echo "Required private network is missing: ${network}" >&2; exit 70; }
done
docker inspect "${candidate_container}" >/dev/null 2>&1 \
  && { echo "Candidate container already exists; use verify/start/stop instead of preparing again" >&2; exit 73; }
for volume in "${logs_volume}" "${data_volume}"; do
  docker volume inspect "${volume}" >/dev/null 2>&1 \
    && { echo "Candidate volume already exists: ${volume}" >&2; exit 73; }
done
port_in_use "${http_port}" && { echo "Candidate HTTP port is already in use: ${http_port}" >&2; exit 73; }
port_in_use "${grpc_port}" && { echo "Candidate gRPC port is already in use: ${grpc_port}" >&2; exit 73; }

available_mb="$(awk '/MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)"
(( available_mb >= 3072 )) \
  || { echo "At least 3072 MiB MemAvailable is required; found ${available_mb} MiB" >&2; exit 71; }
available_bytes="$(df --output=avail -B1 "${base_dir}" | awk 'NR==2 {print $1}')"
(( available_bytes >= 8 * 1024 * 1024 * 1024 )) \
  || { echo "At least 8 GiB free disk is required below ${base_dir}" >&2; exit 71; }

source_db="$(container_env_value "${source_container}" MYSQL_SERVICE_DB_NAME)"
[[ "${source_db}" =~ ^[a-zA-Z][a-zA-Z0-9_]{1,63}$ ]] \
  || { echo "Unable to determine a safe source Nacos database name" >&2; exit 70; }
mysql_root_password="$(container_env_value "${mysql_container}" MYSQL_ROOT_PASSWORD)"
if [[ -z "${mysql_root_password}" ]]; then
  mysql_root_password_file="$(container_env_value "${mysql_container}" MYSQL_ROOT_PASSWORD_FILE)"
  [[ "${mysql_root_password_file}" == /* ]] \
    || { echo "Unable to determine MySQL root credentials" >&2; exit 70; }
  mysql_root_password="$(docker exec "${mysql_container}" sh -c 'cat "$1"' _ "${mysql_root_password_file}")"
fi
[[ -n "${mysql_root_password}" ]] || { echo "MySQL root password must not be empty" >&2; exit 70; }
schema_exists "${source_db}" || { echo "Source Nacos database does not exist" >&2; exit 70; }
schema_exists "${candidate_db}" && { echo "Candidate database already exists: ${candidate_db}" >&2; exit 73; }
[[ "$(mysql_root -e "SELECT COUNT(*) FROM mysql.user WHERE user='${candidate_db_user}'")" == 0 ]] \
  || { echo "Candidate database user already exists: ${candidate_db_user}" >&2; exit 73; }

legacy_tables=(config_info_aggr config_info_beta config_info_tag)
for table in "${legacy_tables[@]}"; do
  if table_exists "${source_db}" "${table}"; then
    count="$(table_count "${source_db}" "${table}")"
    (( count == 0 )) \
      || { echo "Legacy table ${table} contains ${count} rows; an explicit transformation is required" >&2; exit 72; }
  fi
done

source_config_count="$(table_count "${source_db}" config_info)"
(( source_config_count > 0 )) || { echo "Source Nacos has no configuration rows" >&2; exit 72; }
null_tenant_count="$(mysql_root -e "SELECT COUNT(*) FROM \`${source_db}\`.config_info WHERE tenant_id IS NULL")"
(( null_tenant_count == 0 )) \
  || { echo "Source Nacos contains NULL tenant_id rows; namespace migration needs manual review" >&2; exit 72; }
namespace_conflicts="$(mysql_root -e "SELECT COUNT(*) FROM \`${source_db}\`.config_info empty_ns JOIN \`${source_db}\`.config_info public_ns ON empty_ns.data_id=public_ns.data_id AND empty_ns.group_id=public_ns.group_id WHERE empty_ns.tenant_id='' AND public_ns.tenant_id='public'")"
(( namespace_conflicts == 0 )) \
  || { echo "Source Nacos contains empty/public namespace conflicts; automatic migration is unsafe" >&2; exit 72; }

if grep -q $'\r' "${versions_file}"; then
  echo "versions.env must use LF line endings before running on Linux" >&2
  exit 66
fi
set -a
# shellcheck disable=SC1090
source "${versions_file}"
set +a
expected_image='nacos/nacos-server:v3.2.2@sha256:d6b3087bc2302c2b49c875e1c5ad8c916fc10696ceb355be56cffd715e178d81'
[[ "${NACOS3_CANDIDATE_IMAGE:-}" == "${expected_image}" ]] \
  || { echo "versions.env does not contain the reviewed Nacos 3.2.2 image digest" >&2; exit 66; }

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="${backup_root}/${timestamp}"
evidence_dir="${evidence_root}/${timestamp}"
install -d -m 0700 "${backup_dir}" "${evidence_dir}"
schema_file="${backup_dir}/nacos-3.2.2-mysql-schema.sql"
schema_url='https://raw.githubusercontent.com/alibaba/nacos/cdf4e1b6aa76d50839a0b8d3c8806430d14fef86/plugin-default-impl/nacos-default-datasource-plugin/nacos-datasource-plugin-mysql/src/main/resources/META-INF/mysql-schema.sql'
schema_sha256='00f3148b049cc6c5cbfbbb8c92ffa608076d3ba1b0bc3ef78d4660d3caf0de5b'
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --connect-timeout 10 --max-time 60 --retry 3 --retry-delay 2 \
  "${schema_url}" --output "${schema_file}"
echo "${schema_sha256}  ${schema_file}" | sha256sum --check --status \
  || { echo "Official Nacos schema checksum mismatch" >&2; exit 74; }

runtime_env="${runtime_dir}/candidate.env"
cat >"${runtime_env}.tmp" <<EOF
NACOS3_DB_NAME=${candidate_db}
NACOS3_DB_USER=${candidate_db_user}
NACOS3_COMPOSE_PROJECT=${compose_project}
NACOS3_LOGS_VOLUME=${logs_volume}
NACOS3_DATA_VOLUME=${data_volume}
NACOS3_HTTP_PORT=${http_port}
NACOS3_GRPC_PORT=${grpc_port}
NACOS3_SECRETS_DIR=${secrets_dir}
AICHAT_CANDIDATE_SECRETS_DIR=${aichat_secret_dir}
EOF
chmod 0600 "${runtime_env}.tmp"
mv -f "${runtime_env}.tmp" "${runtime_env}"

compose=(docker compose --project-name "${compose_project}" --project-directory "${candidate_dir}" \
  -f "${candidate_dir}/compose.yaml" --env-file "${versions_file}" --env-file "${runtime_env}")
candidate_started=false
on_error() {
  local exit_code=$?
  trap - ERR
  set +e
  if [[ "${candidate_started}" == true ]]; then
    "${compose[@]}" stop --timeout 30 nacos3-candidate >/dev/null 2>&1
  fi
  echo "Nacos 3 candidate preparation failed. The source Nacos was not changed; any new candidate database, backup, secrets, and volumes were retained for review." >&2
  exit "${exit_code}"
}
trap on_error ERR
"${compose[@]}" config --quiet
"${compose[@]}" pull nacos3-candidate

full_backup="${backup_dir}/source-nacos.sql.gz"
docker exec -i -e MYSQL_PWD="${mysql_root_password}" "${mysql_container}" \
  mysqldump --protocol=socket -uroot --single-transaction --quick --routines --events --triggers \
    --hex-blob --no-tablespaces --default-character-set=utf8mb4 "${source_db}" \
  | gzip -9 >"${full_backup}"
[[ -s "${full_backup}" ]] || { echo "Source Nacos backup is empty" >&2; exit 74; }
sha256sum "${full_backup}" >"${full_backup}.sha256"

candidate_db_password="$(openssl rand -hex 32)"
openssl rand -base64 48 | tr -d '\n' >"${secrets_dir}/auth_token"
openssl rand -hex 24 >"${secrets_dir}/identity_key"
openssl rand -hex 32 >"${secrets_dir}/identity_value"
printf '%s' "${candidate_db_password}" >"${secrets_dir}/mysql_password"
openssl rand -base64 36 | tr -d '\n' >"${secrets_dir}/admin_password"
printf '%s' 'aichat-candidate' >"${secrets_dir}/client_username"
openssl rand -base64 36 | tr -d '\n' >"${secrets_dir}/client_password"
chmod 0600 "${secrets_dir}"/*

mysql_root <<SQL
CREATE DATABASE \`${candidate_db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER '${candidate_db_user}'@'%' IDENTIFIED BY '${candidate_db_password}';
GRANT ALL PRIVILEGES ON \`${candidate_db}\`.* TO '${candidate_db_user}'@'%';
FLUSH PRIVILEGES;
SQL
mysql_root "${candidate_db}" <"${schema_file}"

migration_tables=(config_info config_tags_relation group_capacity his_config_info tenant_capacity tenant_info)
for table in "${migration_tables[@]}"; do
  table_exists "${source_db}" "${table}" || { echo "Source table is missing: ${table}" >&2; exit 72; }
  table_exists "${candidate_db}" "${table}" || { echo "Target table is missing: ${table}" >&2; exit 72; }
  count="$(table_count "${source_db}" "${table}")"
  if (( count > 0 )); then
    table_dump="${backup_dir}/${table}.sql"
    docker exec -i -e MYSQL_PWD="${mysql_root_password}" "${mysql_container}" \
      mysqldump --protocol=socket -uroot --single-transaction --quick --skip-triggers \
        --no-create-info --complete-insert --hex-blob --no-tablespaces \
        --default-character-set=utf8mb4 "${source_db}" "${table}" >"${table_dump}"
    mysql_root "${candidate_db}" <"${table_dump}"
    gzip -9 "${table_dump}"
  fi
  if [[ "$(mysql_root -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${candidate_db}' AND table_name='${table}' AND column_name='tenant_id'")" == 1 ]]; then
    mysql_root "${candidate_db}" -e \
      "UPDATE \`${table}\` SET tenant_id='public' WHERE tenant_id=''"
  fi
  target_count="$(table_count "${candidate_db}" "${table}")"
  [[ "${target_count}" == "${count}" ]] \
    || { echo "Row-count mismatch for ${table}: source=${count}, target=${target_count}" >&2; exit 72; }
done
for table in users roles permissions; do
  [[ "$(table_count "${candidate_db}" "${table}")" == 0 ]] \
    || { echo "Target auth table must be empty before bootstrap: ${table}" >&2; exit 72; }
done

printf '%s' "${source_restart_count}" >"${runtime_dir}/source-nacos-restart-count"
printf '%s' "${source_config_count}" >"${runtime_dir}/source-config-info-count"
printf '%s' "$(table_count "${source_db}" his_config_info)" >"${runtime_dir}/source-his-config-info-count"
printf '%s' "${timestamp}" >"${runtime_dir}/prepared-at"
candidate_started=true
"${compose[@]}" up -d nacos3-candidate

ready=false
for _ in $(seq 1 60); do
  if curl --fail --silent --max-time 3 \
    "http://127.0.0.1:${http_port}/nacos/v3/admin/core/state/readiness" >/dev/null; then
    ready=true
    break
  fi
  sleep 5
done
if [[ "${ready}" != true ]]; then
  "${compose[@]}" logs --tail 100 nacos3-candidate >&2 || true
  "${compose[@]}" stop --timeout 30 nacos3-candidate >/dev/null 2>&1 || true
  candidate_started=false
  echo "Nacos candidate did not become ready; it was stopped and retained for review" >&2
  exit 76
fi

tmp_dir="$(mktemp -d "${runtime_dir}/.auth-bootstrap.XXXXXX")"
cleanup() { rm -rf -- "${tmp_dir}"; }
trap cleanup EXIT
api_base="http://127.0.0.1:${http_port}/nacos/v3/auth"
admin_response="${tmp_dir}/admin.json"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${api_base}/user/admin" \
  --data-urlencode "password@${secrets_dir}/admin_password" --output "${admin_response}"
python3 - "${admin_response}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("code") != 0 or payload.get("data", {}).get("username") != "nacos":
    raise SystemExit("Nacos administrator bootstrap failed")
PY

login_response="${tmp_dir}/admin-login.json"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -X POST "${api_base}/user/login" \
  --data-urlencode 'username=nacos' --data-urlencode "password@${secrets_dir}/admin_password" \
  --output "${login_response}"
python3 - "${login_response}" >"${tmp_dir}/admin-token" <<'PY'
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

install -d -m 0700 "${aichat_secret_dir}"
install -m 0600 "${secrets_dir}/client_username" "${aichat_secret_dir}/nacos_username"
install -m 0600 "${secrets_dir}/client_password" "${aichat_secret_dir}/nacos_password"

"${release}/scripts/cloud-verify-nacos3-candidate.sh" "${release}" | tee "${evidence_dir}/verification.txt"
{
  echo "timestamp=${timestamp}"
  echo "source_image=${source_image}"
  echo "source_restart_count=${source_restart_count}"
  echo "source_config_count=${source_config_count}"
  echo "candidate_image=${expected_image}"
  echo "schema_commit=cdf4e1b6aa76d50839a0b8d3c8806430d14fef86"
  echo "schema_sha256=${schema_sha256}"
  echo "backup_sha256=$(cut -d' ' -f1 "${full_backup}.sha256")"
} >"${evidence_dir}/manifest.txt"
chmod 0600 "${evidence_dir}"/* "${backup_dir}"/*
trap - ERR
echo "Isolated Nacos 3.2.2 candidate prepared and verified; source Nacos 2.1.1 was not modified."
