#!/usr/bin/env bash
set -Eeuo pipefail

[[ "${NACOS3_START_CONFIRMED:-}" == "START_ISOLATED_NACOS3" ]] \
  || { echo "Set NACOS3_START_CONFIRMED=START_ISOLATED_NACOS3 to start the existing isolated candidate" >&2; exit 64; }
[[ $# -eq 1 ]] || { echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2; exit 64; }
release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }
runtime_env="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}/runtime/candidate.env"
candidate_dir="${release}/infra/nacos-candidate"
[[ -f "${runtime_env}" && -f "${candidate_dir}/compose.yaml" ]] \
  || { echo "Candidate has not been prepared" >&2; exit 66; }
set -a
# shellcheck disable=SC1090
source "${runtime_env}"
set +a
compose=(docker compose --project-name "${NACOS3_COMPOSE_PROJECT:-nacos3-candidate}" --project-directory "${candidate_dir}" \
  -f "${candidate_dir}/compose.yaml" --env-file "${release}/versions.env" --env-file "${runtime_env}")
"${compose[@]}" config --quiet
"${compose[@]}" up -d nacos3-candidate
for _ in $(seq 1 48); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' nacos-v3-candidate 2>/dev/null || true)" == healthy ]]; then
    "${release}/scripts/cloud-verify-nacos3-candidate.sh" "${release}"
    exit 0
  fi
  sleep 5
done
echo "Nacos candidate did not become healthy" >&2
exit 76
