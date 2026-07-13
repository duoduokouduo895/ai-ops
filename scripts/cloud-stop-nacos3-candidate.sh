#!/usr/bin/env bash
set -Eeuo pipefail

[[ "${NACOS3_STOP_CONFIRMED:-}" == "STOP_ISOLATED_NACOS3" ]] \
  || { echo "Set NACOS3_STOP_CONFIRMED=STOP_ISOLATED_NACOS3 to stop the isolated candidate" >&2; exit 64; }
[[ $# -eq 1 ]] || { echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2; exit 64; }
release="$(readlink -f "$1")"
[[ -n "${release}" && "${release}" == /opt/aidiag/releases/* ]] \
  || { echo "Release must exist below /opt/aidiag/releases" >&2; exit 65; }
runtime_env="${NACOS3_BASE_DIR:-/opt/nacos3-candidate}/runtime/candidate.env"
candidate_dir="${release}/infra/nacos-candidate"
[[ -f "${runtime_env}" && -f "${candidate_dir}/compose.yaml" ]] \
  || { echo "Candidate runtime or release files are missing" >&2; exit 66; }
compose=(docker compose --project-name nacos3-candidate --project-directory "${candidate_dir}" \
  -f "${candidate_dir}/compose.yaml" --env-file "${release}/versions.env" --env-file "${runtime_env}")
"${compose[@]}" stop --timeout 30 nacos3-candidate
[[ "$(docker inspect --format '{{.State.Running}}' nacos 2>/dev/null || true)" == true ]] \
  || { echo "Source Nacos is unexpectedly not running" >&2; exit 70; }
echo "Isolated Nacos 3 candidate stopped. Database, backup, secrets, and volumes were retained."
