#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /opt/aidiag/releases/<release-name>" >&2
  exit 64
fi

release="$(readlink -f "$1")"
base_dir="${AIDIAG_BASE_DIR:-/opt/aidiag}"
runtime_dir="${base_dir}/runtime"
current_link="${base_dir}/current"
next_link="${base_dir}/.current.next"

[[ "${release}" == "${base_dir}/releases/"* ]] || { echo "Release must be below ${base_dir}/releases" >&2; exit 65; }
[[ -f "${release}/compose.yaml" && -f "${release}/versions.env" ]] || { echo "Release is incomplete" >&2; exit 66; }
[[ -f "${runtime_dir}/.env.cloud" && -d "${runtime_dir}/.secrets" ]] || { echo "Runtime config is missing" >&2; exit 67; }

export AIDIAG_SECRETS_DIR="${runtime_dir}/.secrets"

set_compose_files() {
  local target_release="$1"
  compose_files=(-f "${target_release}/compose.yaml")
  if [[ -f "${target_release}/compose.stage4.yaml" ]]; then
    [[ -s "${runtime_dir}/.secrets/aidiag_deepseek_api_key" \
      && -s "${runtime_dir}/.secrets/aidiag_stage4_operator_secret" ]] \
      || { echo "Stage 4 secret files are missing" >&2; return 68; }
    compose_files+=(-f "${target_release}/compose.stage4.yaml")
  fi
}

declare -a compose_files
set_compose_files "${release}"
compose=(docker compose --project-directory "${release}" "${compose_files[@]}" \
  --env-file "${release}/versions.env" --env-file "${runtime_dir}/.env.cloud")

"${compose[@]}" config --quiet
"${compose[@]}" build aidiag-backend payment-service

previous_release=""
if [[ -L "${current_link}" ]]; then
  previous_release="$(readlink -f "${current_link}")"
fi

ln -s "${release}" "${next_link}"
mv -Tf "${next_link}" "${current_link}"

set_compose_files "${current_link}"
current_compose=(docker compose --project-directory "${current_link}" "${compose_files[@]}" \
  --env-file "${current_link}/versions.env" --env-file "${runtime_dir}/.env.cloud")
if ! "${current_compose[@]}" up -d --remove-orphans --wait --wait-timeout 180; then
  if [[ -n "${previous_release}" ]]; then
    ln -s "${previous_release}" "${next_link}"
    mv -Tf "${next_link}" "${current_link}"
    set_compose_files "${current_link}"
    rollback_compose=(docker compose --project-directory "${current_link}" "${compose_files[@]}" \
      --env-file "${current_link}/versions.env" --env-file "${runtime_dir}/.env.cloud")
    # The releases currently share local image tags. Rebuild from the restored source so rollback
    # cannot accidentally restart the failed release's image under the previous directory.
    "${rollback_compose[@]}" build aidiag-backend payment-service \
      && "${rollback_compose[@]}" up -d --remove-orphans --wait --wait-timeout 180 \
      || true
  fi
  echo "Activation failed; current link was restored when a previous release existed." >&2
  exit 1
fi

"${current_compose[@]}" ps
