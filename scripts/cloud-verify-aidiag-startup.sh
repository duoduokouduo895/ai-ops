#!/usr/bin/env bash
set -Eeuo pipefail

release_dir=${1:?usage: cloud-verify-aidiag-startup.sh RELEASE_DIR}
release_dir=$(realpath -e -- "$release_dir")
[[ "$release_dir" == /opt/aidiag/releases/* ]] || { echo "FAIL release_dir_outside_managed_root"; exit 1; }
cd "$release_dir"

container_id=$(docker compose -f compose.yaml -f compose.console.yaml ps -q aidiag-backend)
[[ -n "$container_id" ]] || { echo "FAIL aidiag_container_missing"; exit 1; }
[[ "$(docker inspect -f '{{.State.Running}}' "$container_id")" == true ]] || {
  echo "FAIL aidiag_container_not_running"; exit 1;
}

docker exec "$container_id" sh -ec '
  test -s /var/log/aidiag/aidiag.log
  test "$(stat -c %u /var/log/aidiag/aidiag.log)" = 10001
  grep -q "Started AiDiagApplication" /var/log/aidiag/aidiag.log
'

if docker logs --tail 1000 "$container_id" 2>&1 \
    | grep -Eq 'AccessDeniedException.*aidiag\.log|FileNotFoundException.*aidiag\.log'; then
  echo "FAIL jvm_file_logging_initialization"
  exit 1
fi

echo "PASS jvm_file_logging_initialized_before_readiness"
echo "STARTUP_EVIDENCE mutation=none"
