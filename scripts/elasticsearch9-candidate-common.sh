#!/usr/bin/env bash
set -Eeuo pipefail

load_elasticsearch9_candidate() {
  if [[ $# -ne 1 ]]; then
    echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
    exit 64
  fi

  release="$(readlink -f "$1")"
  [[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
    || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }

  base_dir="${ELASTICSEARCH9_BASE_DIR:-/opt/elasticsearch9-candidate}"
  runtime_dir="${base_dir}/runtime"
  runtime_env="${runtime_dir}/candidate.env"
  secrets_dir="${runtime_dir}/.secrets"
  evidence_dir="${base_dir}/evidence"
  candidate_dir="${release}/infra/elasticsearch-candidate"
  versions_file="${release}/versions.env"

  [[ -f "${runtime_env}" && -f "${candidate_dir}/compose.yaml" \
    && -f "${candidate_dir}/Dockerfile" && -f "${versions_file}" ]] \
    || { echo "Elasticsearch candidate runtime or release files are missing" >&2; exit 66; }
  [[ "$(stat -c '%a' "${runtime_env}")" == 600 ]] \
    || { echo "Elasticsearch candidate runtime env must use mode 600" >&2; exit 68; }

  set -a
  # shellcheck disable=SC1090
  source "${runtime_env}"
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
}

elasticsearch9_compose() {
  docker compose \
    --project-name "${ELASTICSEARCH9_COMPOSE_PROJECT}" \
    --project-directory "${candidate_dir}" \
    -f "${candidate_dir}/compose.yaml" \
    --env-file "${versions_file}" \
    --env-file "${runtime_env}" "$@"
}

write_basic_auth_header() {
  local username_file="$1"
  local password_file="$2"
  local output_file="$3"
  local encoded
  encoded="$(printf '%s:%s' "$(cat "${username_file}")" "$(cat "${password_file}")" | openssl base64 -A)"
  printf 'Authorization: Basic %s' "${encoded}" >"${output_file}"
  chmod 600 "${output_file}"
  unset encoded
}

wait_for_elasticsearch9_health() {
  local attempts="${1:-48}"
  for _ in $(seq 1 "${attempts}"); do
    if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
      elasticsearch-v9-candidate 2>/dev/null || true)" == healthy ]]; then
      return 0
    fi
    sleep 5
  done
  return 1
}

bootstrap_elasticsearch9_client() (
  set -Eeuo pipefail
  local tmp_dir aichat_secrets_dir
  tmp_dir="$(mktemp -d "${runtime_dir}/.bootstrap.XXXXXX")"
  trap 'rm -rf -- "${tmp_dir}"' EXIT
  aichat_secrets_dir="${AICHAT_CANDIDATE_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/candidate}"

  printf '%s' elastic >"${tmp_dir}/elastic-username"
  write_basic_auth_header "${tmp_dir}/elastic-username" "${secrets_dir}/elastic_password" \
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
)
