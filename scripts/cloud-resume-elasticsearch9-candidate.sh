#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./elasticsearch9-candidate-common.sh
source "${script_dir}/elasticsearch9-candidate-common.sh"
[[ "${ELASTICSEARCH9_RESUME_CONFIRMED:-}" == RESUME_ISOLATED_ELASTICSEARCH9 ]] \
  || { echo "Set ELASTICSEARCH9_RESUME_CONFIRMED=RESUME_ISOLATED_ELASTICSEARCH9" >&2; exit 64; }
load_elasticsearch9_candidate "$@"

export ELASTICSEARCH9_CONFIG_VOLUME="${ELASTICSEARCH9_CONFIG_VOLUME:-elasticsearch9-candidate-config}"
[[ "$(docker inspect --format '{{.State.Status}}' elasticsearch-v9-candidate 2>/dev/null || true)" == exited ]] \
  || { echo "Resume requires the preserved candidate container to be stopped after a failed start" >&2; exit 69; }
[[ "$(docker volume ls --quiet --filter "name=^${ELASTICSEARCH9_DATA_VOLUME}$")" == "${ELASTICSEARCH9_DATA_VOLUME}" ]] \
  || { echo "Preserved Elasticsearch candidate data volume is missing" >&2; exit 69; }
[[ "$(docker inspect --format '{{.State.Running}}' elasticsearch)" == true \
  && "$(docker inspect --format '{{.RestartCount}}' elasticsearch)" \
    == "$(cat "${runtime_dir}/source-elasticsearch-restart-count")" ]] \
  || { echo "Existing Elasticsearch changed before candidate resume" >&2; exit 70; }

evidence_dir="${base_dir}/evidence/$(date -u +%Y%m%dT%H%M%SZ)-resume"
install -d -m 700 "${evidence_dir}"
started=false
capture_failure() {
  local exit_code=$?
  if [[ "${started}" == true ]]; then
    elasticsearch9_compose logs --no-color --timestamps --tail 1000 \
      >"${evidence_dir}/startup-failure.log" 2>&1 || true
    chmod 600 "${evidence_dir}/startup-failure.log" || true
    elasticsearch9_compose stop elasticsearch9-candidate >/dev/null 2>&1 || true
  fi
  echo "Elasticsearch 9 candidate resume failed; Elasticsearch 8 and candidate volumes were preserved" >&2
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

started=true
elasticsearch9_compose up -d --force-recreate elasticsearch9-candidate
wait_for_elasticsearch9_health 60
bootstrap_elasticsearch9_client
trap - ERR
"${release}/scripts/cloud-verify-elasticsearch9-candidate.sh" "${release}" \
  | tee "${evidence_dir}/verification.txt"
chmod 600 "${evidence_dir}/verification.txt"
echo "Elasticsearch 9.3.2 + IK candidate resumed; Elasticsearch 8.13.4 was not changed"
