#!/usr/bin/env bash
set -Eeuo pipefail

base_dir="/opt/aidiag-home"
release_root="${base_dir}/releases"
backup_root="${base_dir}/backups"
retention_dir="${base_dir}/runtime/deployment/home/retention"

[[ -d "${release_root}" && -d "${backup_root}" && -d "${retention_dir}" ]] || {
  echo "AIDiag retention roots are missing" >&2
  exit 1
}

active_container="$(docker ps -q \
  --filter label=com.docker.compose.project=aidiag \
  --filter label=com.docker.compose.service=aidiag-backend | head -n 1)"
if [[ -z "${active_container}" ]]; then
  echo "AIDiag retention cleanup skipped: no active backend container"
  exit 0
fi

active_release="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "${active_container}")"
active_release="$(realpath -e -- "${active_release}")"
case "${active_release}" in
  "${release_root}"/*) ;;
  *)
    echo "Active release is outside the managed root: ${active_release}" >&2
    exit 1
    ;;
esac
active_image="$(docker inspect -f '{{.Config.Image}}' "${active_container}")"
now_epoch="$(date -u +%s)"

read_marker_value() {
  local key="$1"
  local marker="$2"
  awk -F= -v wanted="${key}" '$1 == wanted {sub(/^[^=]*=/, ""); print; exit}' "${marker}"
}

assert_child_path() {
  local path="$1"
  local root="$2"
  [[ -n "${path}" && "${path}" == "${root}/"* ]] || return 1
  local relative="${path#${root}/}"
  [[ -n "${relative}" && "${relative}" != */* && "${relative}" != "." && "${relative}" != ".." ]]
}

for marker in "${retention_dir}"/*.env; do
  [[ -f "${marker}" ]] || continue

  keep_until_epoch="$(read_marker_value KEEP_UNTIL_EPOCH "${marker}")"
  release_path="$(read_marker_value RELEASE_PATH "${marker}")"
  backup_path="$(read_marker_value BACKUP_PATH "${marker}")"
  image="$(read_marker_value IMAGE "${marker}")"

  [[ "${keep_until_epoch}" =~ ^[0-9]+$ ]] || {
    echo "Invalid retention marker: ${marker}" >&2
    exit 1
  }
  (( keep_until_epoch <= now_epoch )) || continue
  assert_child_path "${release_path}" "${release_root}" || {
    echo "Invalid release path in ${marker}" >&2
    exit 1
  }
  assert_child_path "${backup_path}" "${backup_root}" || {
    echo "Invalid backup path in ${marker}" >&2
    exit 1
  }
  [[ "${release_path}" != "${active_release}" ]] || {
    echo "Refusing to delete active release: ${release_path}" >&2
    exit 1
  }
  [[ ! -L "${release_path}" && ! -L "${backup_path}" ]] || {
    echo "Refusing symlink deletion from ${marker}" >&2
    exit 1
  }

  if [[ -d "${release_path}" ]]; then
    rm -rf -- "${release_path}"
    echo "REMOVED_RELEASE=${release_path}"
  fi
  if [[ -f "${backup_path}" ]]; then
    rm -f -- "${backup_path}"
    echo "REMOVED_BACKUP=${backup_path}"
  fi

  if [[ "${image}" =~ ^aidiag-backend:[A-Za-z0-9._-]{3,128}$ && "${image}" != "${active_image}" ]] \
    && docker image inspect "${image}" >/dev/null 2>&1; then
    if docker ps -q --filter "ancestor=${image}" | grep -q .; then
      echo "KEPT_IMAGE_IN_USE=${image}"
    else
      docker image rm "${image}" >/dev/null
      echo "REMOVED_IMAGE=${image}"
    fi
  fi

  rm -f -- "${marker}"
  echo "REMOVED_MARKER=${marker}"
done
