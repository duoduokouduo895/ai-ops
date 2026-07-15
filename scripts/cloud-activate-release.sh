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
prometheus_targets_dir="${runtime_dir}/prometheus-targets"

[[ "${release}" == "${base_dir}/releases/"* ]] || { echo "Release must be below ${base_dir}/releases" >&2; exit 65; }
[[ -f "${release}/compose.yaml" && -f "${release}/versions.env" ]] || { echo "Release is incomplete" >&2; exit 66; }
[[ -f "${runtime_dir}/.env.cloud" && -d "${runtime_dir}/.secrets" ]] || { echo "Runtime config is missing" >&2; exit 67; }

export AIDIAG_SECRETS_DIR="${runtime_dir}/.secrets"
export AIDIAG_PROMETHEUS_TARGETS_DIR="${prometheus_targets_dir}"

load_release_identity() {
  local target_release="$1"
  local identity_file="${target_release}/versions.env"
  local image version
  image="$(sed -n 's/^AIDIAG_IMAGE=//p' "${identity_file}" | tr -d '\r' | tail -n 1)"
  version="$(sed -n 's/^AIDIAG_RELEASE_VERSION=//p' "${identity_file}" | tr -d '\r' | tail -n 1)"
  [[ "${image}" =~ ^aidiag-backend:[a-zA-Z0-9._-]{3,128}$ \
    && "${version}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$ ]] \
    || { echo "Release identity pins are missing or invalid" >&2; return 68; }
  export AIDIAG_IMAGE="${image}"
  export AIDIAG_RELEASE_VERSION="${version}"
}

if [[ -e "${prometheus_targets_dir}" ]]; then
  [[ -d "${prometheus_targets_dir}" && ! -L "${prometheus_targets_dir}" \
    && "$(stat -c '%u:%g' "${prometheus_targets_dir}")" == '0:0' \
    && "$(stat -c '%a' "${prometheus_targets_dir}")" == '755' ]] \
    || { echo "Prometheus runtime target directory is unsafe" >&2; exit 68; }
else
  install -d -o 0 -g 0 -m 0755 "${prometheus_targets_dir}"
fi

set_compose_files() {
  local target_release="$1"
  compose_files=(-f "${target_release}/compose.yaml")
  if [[ -f "${target_release}/compose.stage4.yaml" ]]; then
    [[ -s "${runtime_dir}/.secrets/aidiag_deepseek_api_key" \
      && -s "${runtime_dir}/.secrets/aidiag_stage4_operator_secret" ]] \
      || { echo "Stage 4 secret files are missing" >&2; return 68; }
    compose_files+=(-f "${target_release}/compose.stage4.yaml")
  fi
  if [[ -f "${target_release}/compose.console.yaml" ]]; then
    [[ -s "${runtime_dir}/.secrets/aidiag_console_password_hash" \
      && -s "${runtime_dir}/.secrets/aidiag_console_audit_pepper" ]] \
      || { echo "Console secret files are missing" >&2; return 68; }
    compose_files+=(-f "${target_release}/compose.console.yaml")
  fi
}

verify_release_mounts() {
  local target_release="$1"
  local service destination container source
  while IFS='|' read -r service destination; do
    container="$("${current_compose[@]}" ps -q "${service}")"
    [[ -n "${container}" ]] || { echo "Missing container for ${service}" >&2; return 1; }
    source="$(docker inspect -f "{{range .Mounts}}{{if eq .Destination \"${destination}\"}}{{.Source}}{{end}}{{end}}" "${container}")"
    [[ "${source}" == "${target_release}/"* ]] \
      || { echo "Release mount drift: ${service} ${destination} <- ${source}" >&2; return 1; }
  done <<'EOF'
prometheus|/etc/prometheus/prometheus.yml
alertmanager|/etc/alertmanager/alertmanager.yml
alloy|/etc/alloy/config.alloy
grafana|/etc/grafana/provisioning
loki|/etc/loki/loki.yml
tempo|/etc/tempo/tempo.yml
blackbox-exporter|/etc/blackbox_exporter/blackbox.yml
EOF
}

switch_current_link() {
  local target_release="$1"
  if [[ -e "${next_link}" || -L "${next_link}" ]]; then
    [[ -L "${next_link}" ]] || { echo "Current next-link path exists and is not a symlink" >&2; return 69; }
    unlink "${next_link}"
  fi
  ln -s "${target_release}" "${next_link}"
  mv -Tf "${next_link}" "${current_link}"
}

declare -a compose_files
load_release_identity "${release}"
set_compose_files "${release}"
compose=(docker compose --project-directory "${release}" "${compose_files[@]}" \
  --env-file "${release}/versions.env" --env-file "${runtime_dir}/.env.cloud")

"${compose[@]}" config --quiet
"${compose[@]}" build aidiag-backend payment-service

previous_release=""
previous_backend_image=""
if [[ -L "${current_link}" ]]; then
  previous_release="$(readlink -f "${current_link}")"
  previous_backend_container="$(docker ps -aq \
    --filter label=com.docker.compose.project=aidiag \
    --filter label=com.docker.compose.service=aidiag-backend)"
  if [[ -n "${previous_backend_container}" ]]; then
    previous_backend_image="$(docker inspect -f '{{.Config.Image}}' "${previous_backend_container}")"
  fi
fi

switch_current_link "${release}"

set_compose_files "${release}"
# Use the immutable release path, not the stable symlink, for bind mounts. Otherwise Docker keeps
# mounting the previous release directory and configuration-only changes never reach containers.
current_compose=(docker compose --project-directory "${release}" "${compose_files[@]}" \
  --env-file "${release}/versions.env" --env-file "${runtime_dir}/.env.cloud")
if ! "${current_compose[@]}" up -d --remove-orphans --wait --wait-timeout 180 \
  || ! verify_release_mounts "${release}"; then
  if [[ -n "${previous_release}" ]]; then
    switch_current_link "${previous_release}"
    if [[ "${previous_backend_image}" =~ ^aidiag-backend:[a-zA-Z0-9._-]{3,128}$ ]]; then
      export AIDIAG_IMAGE="${previous_backend_image}"
      export AIDIAG_RELEASE_VERSION="${previous_backend_image##*:}"
    else
      load_release_identity "${previous_release}"
    fi
    set_compose_files "${previous_release}"
    rollback_compose=(docker compose --project-directory "${previous_release}" "${compose_files[@]}" \
      --env-file "${previous_release}/versions.env" --env-file "${runtime_dir}/.env.cloud")
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
