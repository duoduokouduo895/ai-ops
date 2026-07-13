#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
  exit 64
fi
[[ "${AIDIAG_V1_STABLE_ALIASES_CONFIRMED:-}" == PROMOTE_VERIFIED_CANDIDATES_TO_STABLE_ALIASES ]] \
  || { echo "Set AIDIAG_V1_STABLE_ALIASES_CONFIRMED=PROMOTE_VERIFIED_CANDIDATES_TO_STABLE_ALIASES" >&2; exit 64; }

release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }
for path in \
  "${release}/versions.env" \
  "${release}/infra/nacos-candidate/compose.yaml" \
  "${release}/infra/elasticsearch-candidate/compose.yaml" \
  "${release}/scripts/cloud-verify-nacos3-candidate.sh" \
  "${release}/scripts/cloud-verify-elasticsearch9-candidate.sh"; do
  [[ -f "${path}" && ! -L "${path}" ]] \
    || { echo "Promotion release is incomplete or unsafe: ${path}" >&2; exit 66; }
done
[[ "$(stat -c '%u' "${release}")" == 0 \
  && -z "$(find "${release}" -xdev \( -not -user root -o -perm -002 -o -perm -020 \) -print -quit)" ]] \
  || { echo "Promotion release must be root-owned and not group/world writable" >&2; exit 68; }

nacos_base="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}"
elasticsearch_base="${ELASTICSEARCH9_BASE_DIR:-/opt/elasticsearch9-candidate}"
nacos_runtime_env="${nacos_base}/runtime/candidate.env"
elasticsearch_runtime_env="${elasticsearch_base}/runtime/candidate.env"
production_secrets_dir="${AICHAT_PRODUCTION_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/production}"
managed_marker="${production_secrets_dir}/.managed-by-aidiag-v1"

for path in "${nacos_runtime_env}" "${elasticsearch_runtime_env}"; do
  [[ -f "${path}" && ! -L "${path}" ]] \
    || { echo "Candidate runtime config is missing or unsafe: ${path}" >&2; exit 67; }
done
[[ -f "${managed_marker}" && "$(cat "${managed_marker}")" == aidiag-v1-production-identities ]] \
  || { echo "Managed production identities must be provisioned before alias promotion" >&2; exit 70; }
for name in nacos_username nacos_password elasticsearch_username elasticsearch_password; do
  path="${production_secrets_dir}/${name}"
  [[ -f "${path}" && ! -L "${path}" && -s "${path}" \
    && "$(stat -c '%a:%u:%g' "${path}")" == '400:10001:10001' ]] \
    || { echo "Production Secret is missing or unsafe: ${name}" >&2; exit 68; }
done

if docker ps --quiet --filter label=com.docker.compose.project=ai-chat-candidate | grep -q .; then
  echo "AI-chat candidate must be stopped before stable alias promotion" >&2
  exit 70
fi
if docker ps --quiet --filter label=com.docker.compose.project=ai-chat-production | grep -q .; then
  echo "AI-chat production must not be running during infrastructure promotion" >&2
  exit 70
fi

for container in nacos-v3-candidate elasticsearch-v9-candidate; do
  [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container}" 2>/dev/null || true)" == healthy \
    && "$(docker inspect --format '{{.RestartCount}}' "${container}")" == 0 ]] \
    || { echo "Infrastructure candidate must be healthy with zero restarts: ${container}" >&2; exit 70; }
done

# shellcheck disable=SC1090
source "${nacos_runtime_env}"
# shellcheck disable=SC1090
source "${elasticsearch_runtime_env}"

previous_release_for() {
  local container="$1"
  local suffix="$2"
  local config_file
  config_file="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "${container}")"
  [[ "${config_file}" != *,* && "${config_file}" == /opt/aidiag/releases/*"/${suffix}" ]] \
    || { echo "Unable to identify a safe previous Compose release for ${container}" >&2; return 70; }
  printf '%s' "${config_file%/${suffix}}"
}

previous_nacos_release="$(previous_release_for nacos-v3-candidate infra/nacos-candidate/compose.yaml)"
previous_elasticsearch_release="$(previous_release_for elasticsearch-v9-candidate infra/elasticsearch-candidate/compose.yaml)"

nacos_compose() {
  local target_release="$1"
  shift
  docker compose --project-name "${NACOS3_COMPOSE_PROJECT}" \
    --project-directory "${target_release}/infra/nacos-candidate" \
    -f "${target_release}/infra/nacos-candidate/compose.yaml" \
    --env-file "${target_release}/versions.env" --env-file "${nacos_runtime_env}" "$@"
}

elasticsearch_compose() {
  local target_release="$1"
  shift
  docker compose --project-name "${ELASTICSEARCH9_COMPOSE_PROJECT}" \
    --project-directory "${target_release}/infra/elasticsearch-candidate" \
    -f "${target_release}/infra/elasticsearch-candidate/compose.yaml" \
    --env-file "${target_release}/versions.env" --env-file "${elasticsearch_runtime_env}" "$@"
}

wait_for_health() {
  local container="$1"
  local attempts="$2"
  for _ in $(seq 1 "${attempts}"); do
    if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container}" 2>/dev/null || true)" == healthy ]]; then
      return 0
    fi
    sleep 5
  done
  return 1
}

has_alias() {
  local container="$1"
  local network="$2"
  local alias="$3"
  docker inspect --format "{{json (index .NetworkSettings.Networks \"${network}\").Aliases}}" "${container}" \
    | grep -Eq "\"${alias}\""
}

nacos_compose "${release}" config --quiet
elasticsearch_compose "${release}" config --quiet
render_dir="$(mktemp -d /tmp/aidiag-v1-alias-render.XXXXXX)"
trap 'rm -rf -- "${render_dir}"' EXIT
nacos_compose "${release}" config >"${render_dir}/nacos.yaml"
elasticsearch_compose "${release}" config >"${render_dir}/elasticsearch.yaml"
grep -Eq '^[[:space:]]+- nacos-v3$' "${render_dir}/nacos.yaml" \
  || { echo "Rendered Nacos Compose does not contain the stable alias" >&2; exit 78; }
grep -Eq '^[[:space:]]+- elasticsearch-v9$' "${render_dir}/elasticsearch.yaml" \
  || { echo "Rendered Elasticsearch Compose does not contain the stable alias" >&2; exit 78; }
rm -rf -- "${render_dir}"
trap - EXIT

nacos_touched=false
elasticsearch_touched=false
completed=false
rollback() {
  local exit_code=$?
  trap - EXIT
  if [[ "${completed}" != true ]]; then
    echo "Stable alias promotion failed; restoring the previous candidate Compose releases" >&2
    if [[ "${elasticsearch_touched}" == true ]]; then
      elasticsearch_compose "${previous_elasticsearch_release}" up -d --force-recreate elasticsearch9-candidate >/dev/null 2>&1 || true
      wait_for_health elasticsearch-v9-candidate 36 || true
    fi
    if [[ "${nacos_touched}" == true ]]; then
      nacos_compose "${previous_nacos_release}" up -d --force-recreate nacos3-candidate >/dev/null 2>&1 || true
      wait_for_health nacos-v3-candidate 36 || true
    fi
  fi
  exit "${exit_code}"
}
trap rollback EXIT

if ! has_alias nacos-v3-candidate nacos-net nacos-v3; then
  nacos_touched=true
  nacos_compose "${release}" up -d --force-recreate nacos3-candidate
  wait_for_health nacos-v3-candidate 36 \
    || { nacos_compose "${release}" logs --tail 100 nacos3-candidate >&2 || true; exit 75; }
fi
has_alias nacos-v3-candidate nacos-net nacos-v3 \
  || { echo "Nacos stable alias was not applied" >&2; exit 78; }
"${release}/scripts/cloud-verify-nacos3-candidate.sh" "${release}" >/dev/null

if ! has_alias elasticsearch-v9-candidate middleware-net elasticsearch-v9; then
  elasticsearch_touched=true
  elasticsearch_compose "${release}" up -d --force-recreate elasticsearch9-candidate
  wait_for_health elasticsearch-v9-candidate 36 \
    || { elasticsearch_compose "${release}" logs --tail 100 elasticsearch9-candidate >&2 || true; exit 75; }
fi
has_alias elasticsearch-v9-candidate middleware-net elasticsearch-v9 \
  || { echo "Elasticsearch stable alias was not applied" >&2; exit 78; }
"${release}/scripts/cloud-verify-elasticsearch9-candidate.sh" "${release}" >/dev/null

[[ "$(docker inspect --format '{{.RestartCount}}' nacos-v3-candidate)" == 0 \
  && "$(docker inspect --format '{{.RestartCount}}' elasticsearch-v9-candidate)" == 0 ]] \
  || { echo "Promoted infrastructure restarted unexpectedly" >&2; exit 78; }

evidence_dir="/opt/aidiag/evidence/v1-stable-aliases"
install -d -o 0 -g 0 -m 0700 "${evidence_dir}"
evidence_file="${evidence_dir}/$(date -u +%Y%m%dT%H%M%SZ).env"
umask 077
cat >"${evidence_file}" <<EOF
PROMOTION_RELEASE=$(basename "${release}")
PREVIOUS_NACOS_RELEASE=$(basename "${previous_nacos_release}")
PREVIOUS_ELASTICSEARCH_RELEASE=$(basename "${previous_elasticsearch_release}")
NACOS_STABLE_ALIAS=nacos-v3
ELASTICSEARCH_STABLE_ALIAS=elasticsearch-v9
PROMOTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
chmod 0600 "${evidence_file}"

completed=true
trap - EXIT
echo "stable_alias_promotion=PASS"
echo "nacos_alias=nacos-v3"
echo "elasticsearch_alias=elasticsearch-v9"
echo "candidate_application=stopped"
echo "old_nacos_and_elasticsearch=unchanged"
