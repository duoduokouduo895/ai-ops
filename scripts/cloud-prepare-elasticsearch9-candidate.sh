#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./elasticsearch9-candidate-common.sh
source "${script_dir}/elasticsearch9-candidate-common.sh"

[[ "${ELASTICSEARCH9_CANDIDATE_CONFIRMED:-}" == CREATE_ISOLATED_ELASTICSEARCH9_CANDIDATE ]] \
  || { echo "Set ELASTICSEARCH9_CANDIDATE_CONFIRMED=CREATE_ISOLATED_ELASTICSEARCH9_CANDIDATE" >&2; exit 64; }
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
  exit 64
fi

release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }
candidate_dir="${release}/infra/elasticsearch-candidate"
versions_file="${release}/versions.env"
[[ -f "${candidate_dir}/compose.yaml" && -f "${candidate_dir}/Dockerfile" \
  && -f "${versions_file}" ]] \
  || { echo "Elasticsearch candidate release files are missing" >&2; exit 66; }

set -a
# shellcheck disable=SC1090
source "${versions_file}"
set +a
expected_base_image='docker.elastic.co/elasticsearch/elasticsearch:9.3.2@sha256:55a94c7ad4767f4aaa03e75d825b782bf3009a2949a34111d023b7ab43343603'
expected_candidate_image='aidiag/elasticsearch-ik:9.3.2-candidate'
expected_ik_url='https://get.infini.cloud/elasticsearch/analysis-ik/9.3.2'
expected_ik_sha256='804758ddd632fb460ca09bdd04f3c0aa70da878e03cbb53ae122137e4f18b2c5'
[[ "${ELASTICSEARCH9_BASE_IMAGE:-}" == "${expected_base_image}" \
  && "${ELASTICSEARCH9_CANDIDATE_IMAGE:-}" == "${expected_candidate_image}" \
  && "${ELASTICSEARCH9_IK_PLUGIN_URL:-}" == "${expected_ik_url}" \
  && "${ELASTICSEARCH9_IK_PLUGIN_SHA256:-}" == "${expected_ik_sha256}" ]] \
  || { echo "Elasticsearch or IK version pins differ from the reviewed 9.3.2 release" >&2; exit 66; }

base_dir="${ELASTICSEARCH9_BASE_DIR:-/opt/elasticsearch9-candidate}"
runtime_dir="${base_dir}/runtime"
runtime_env="${runtime_dir}/candidate.env"
secrets_dir="${runtime_dir}/.secrets"
evidence_dir="${base_dir}/evidence/$(date -u +%Y%m%dT%H%M%SZ)"
http_port="${ELASTICSEARCH9_HTTP_PORT:-19200}"
compose_project="${ELASTICSEARCH9_COMPOSE_PROJECT:-elasticsearch9-candidate}"
data_volume="${ELASTICSEARCH9_DATA_VOLUME:-elasticsearch9-candidate-data}"
aichat_secrets_dir="${AICHAT_CANDIDATE_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/candidate}"

[[ "${base_dir}" == /opt/elasticsearch9-candidate* \
  && "${http_port}" =~ ^[0-9]+$ && ${http_port} -ge 1024 && ${http_port} -le 65535 \
  && "${compose_project}" =~ ^[a-z0-9][a-z0-9_-]{2,62}$ \
  && "${data_volume}" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,127}$ ]] \
  || { echo "Candidate path, port, project, or volume name is unsafe" >&2; exit 68; }
[[ ! -e "${runtime_env}" ]] \
  || { echo "Candidate runtime already exists; use start/verify instead of preparing again" >&2; exit 69; }
[[ "$(docker inspect --format '{{.State.Status}}' elasticsearch-v9-candidate 2>/dev/null || true)" == "" ]] \
  || { echo "Elasticsearch 9 candidate container already exists" >&2; exit 69; }
[[ "$(docker volume ls --quiet --filter "name=^${data_volume}$")" == "" ]] \
  || { echo "Elasticsearch 9 candidate data volume already exists" >&2; exit 69; }
docker network inspect middleware-net >/dev/null
if ss -H -ltn "sport = :${http_port}" | grep -q .; then
  echo "Elasticsearch candidate loopback port is already in use: ${http_port}" >&2
  exit 69
fi

available_mib="$(awk '/MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)"
available_disk_mib="$(df -Pm /opt | awk 'NR==2 {print $4}')"
(( available_mib >= 3584 )) \
  || { echo "At least 3584 MiB available memory is required; found ${available_mib}" >&2; exit 70; }
(( available_disk_mib >= 8192 )) \
  || { echo "At least 8192 MiB free below /opt is required; found ${available_disk_mib}" >&2; exit 70; }

umask 077
install -d -m 700 "${runtime_dir}" "${secrets_dir}" "${evidence_dir}"
openssl rand -hex 32 >"${secrets_dir}/elastic_password"
printf '%s' 'aichat_candidate' >"${secrets_dir}/client_username"
openssl rand -hex 32 >"${secrets_dir}/client_password"
chown 1000:0 "${secrets_dir}/elastic_password"
chmod 400 "${secrets_dir}/elastic_password"
chmod 600 "${secrets_dir}/client_username" "${secrets_dir}/client_password"
printf '%s\n' "$(docker inspect --format '{{.RestartCount}}' elasticsearch)" \
  >"${runtime_dir}/source-elasticsearch-restart-count"

cat >"${runtime_env}" <<EOF
ELASTICSEARCH9_HTTP_PORT=${http_port}
ELASTICSEARCH9_COMPOSE_PROJECT=${compose_project}
ELASTICSEARCH9_DATA_VOLUME=${data_volume}
ELASTICSEARCH9_SECRETS_DIR=${secrets_dir}
AICHAT_CANDIDATE_SECRETS_DIR=${aichat_secrets_dir}
EOF
chmod 600 "${runtime_env}" "${runtime_dir}/source-elasticsearch-restart-count"

load_elasticsearch9_candidate "${release}"
created=false
capture_failure() {
  local exit_code=$?
  if [[ "${created}" == true ]]; then
    elasticsearch9_compose logs --no-color --timestamps --tail 1000 \
      >"${evidence_dir}/startup-failure.log" 2>&1 || true
    chmod 600 "${evidence_dir}/startup-failure.log" || true
    elasticsearch9_compose stop elasticsearch9-candidate >/dev/null 2>&1 || true
  fi
  echo "Elasticsearch 9 candidate preparation failed; existing Elasticsearch 8 and candidate data were preserved" >&2
  exit "${exit_code}"
}
trap capture_failure ERR

elasticsearch9_compose config --quiet
elasticsearch9_compose build --pull elasticsearch9-candidate
image_id="$(docker image inspect --format '{{.Id}}' "${ELASTICSEARCH9_CANDIDATE_IMAGE}")"
printf 'image=%s\nimage_id=%s\nbase_image=%s\nik_sha256=%s\n' \
  "${ELASTICSEARCH9_CANDIDATE_IMAGE}" "${image_id}" "${ELASTICSEARCH9_BASE_IMAGE}" \
  "${ELASTICSEARCH9_IK_PLUGIN_SHA256}" >"${evidence_dir}/image-manifest.txt"
chmod 600 "${evidence_dir}/image-manifest.txt"

created=true
elasticsearch9_compose up -d elasticsearch9-candidate
wait_for_elasticsearch9_health 60

tmp_dir="$(mktemp -d "${runtime_dir}/.bootstrap.XXXXXX")"
cleanup_tmp() { rm -rf -- "${tmp_dir}"; }
trap cleanup_tmp EXIT
write_basic_auth_header "${secrets_dir}/client_username" "${secrets_dir}/client_password" \
  "${tmp_dir}/client-header"
write_basic_auth_header <(printf '%s' elastic) "${secrets_dir}/elastic_password" \
  "${tmp_dir}/elastic-header"

cat >"${tmp_dir}/role.json" <<'JSON'
{
  "cluster": ["monitor"],
  "indices": [
    {
      "names": [
        "conversations",
        "knowledge_base_chunks",
        "knowledge_base_embeddings*",
        "kb_*"
      ],
      "privileges": [
        "auto_configure",
        "create_index",
        "manage",
        "read",
        "write",
        "view_index_metadata"
      ]
    }
  ]
}
JSON
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/elastic-header" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/role/aichat_candidate" \
  --data-binary "@${tmp_dir}/role.json" --output "${tmp_dir}/role-response.json"

printf '{"password":"%s","roles":["aichat_candidate"],"full_name":"AI-chat isolated candidate"}' \
  "$(cat "${secrets_dir}/client_password")" >"${tmp_dir}/user.json"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/elastic-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/user/$(cat "${secrets_dir}/client_username")" \
  --data-binary "@${tmp_dir}/user.json" --output "${tmp_dir}/user-response.json"

install -d -o 10001 -g 10001 -m 700 "${aichat_secrets_dir}"
install -o 10001 -g 10001 -m 0400 "${secrets_dir}/client_username" \
  "${aichat_secrets_dir}/elasticsearch_username"
install -o 10001 -g 10001 -m 0400 "${secrets_dir}/client_password" \
  "${aichat_secrets_dir}/elasticsearch_password"

trap - ERR
cleanup_tmp
trap - EXIT
"${release}/scripts/cloud-verify-elasticsearch9-candidate.sh" "${release}" \
  | tee "${evidence_dir}/verification.txt"
chmod 600 "${evidence_dir}/verification.txt"
echo "Elasticsearch 9.3.2 + IK candidate created; Elasticsearch 8.13.4 was not changed"
