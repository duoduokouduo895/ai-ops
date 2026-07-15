#!/usr/bin/env bash
set -Eeuo pipefail

release_dir=${1:?usage: cloud-verify-v1-1-readiness.sh RELEASE_DIR [SECRETS_DIR]}
secrets_dir=${2:-/opt/aidiag/shared/secrets}
release_dir=$(realpath -e -- "$release_dir")
secrets_dir=$(realpath -e -- "$secrets_dir")
[[ "$release_dir" == /opt/aidiag/releases/* ]] || { echo "FAIL release_dir_outside_managed_root"; exit 1; }
[[ "$secrets_dir" == /opt/aidiag/* ]] || { echo "FAIL secrets_dir_outside_managed_root"; exit 1; }

required=(compose.yaml compose.console.yaml pom.xml versions.env infra/nginx/ops-api.conf.example
  scripts/cloud-verify-aidiag-startup.sh)
for path in "${required[@]}"; do
  [[ -f "$release_dir/$path" ]] || { echo "FAIL missing_release_file=$path"; exit 1; }
done

image_pin="$(sed -n 's/^AIDIAG_IMAGE=//p' "$release_dir/versions.env" | tr -d '\r' | tail -n 1)"
version_pin="$(sed -n 's/^AIDIAG_RELEASE_VERSION=//p' "$release_dir/versions.env" | tr -d '\r' | tail -n 1)"
[[ "$image_pin" =~ ^aidiag-backend:[a-zA-Z0-9._-]{3,128}$ \
  && "$version_pin" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$ ]] \
  || { echo "FAIL release_identity_pins"; exit 1; }
for secret in aidiag_console_password_hash aidiag_console_audit_pepper; do
  path="$secrets_dir/$secret"
  [[ -f "$path" ]] || { echo "FAIL missing_secret=$secret"; exit 1; }
  mode=$(stat -c '%a' -- "$path")
  group=$(stat -c '%g' -- "$path")
  [[ "$mode" == 640 && "$group" == 10001 ]] || {
    echo "FAIL secret_permission=$secret expected=640:10001 actual=$mode:$group"; exit 1;
  }
done

disk_percent=$(df -P /var/lib/docker | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
[[ "$disk_percent" =~ ^[0-9]+$ ]] || { echo "FAIL disk_usage_unreadable"; exit 1; }
(( disk_percent < 85 )) || { echo "FAIL docker_disk_gate=${disk_percent}% threshold=85%"; exit 1; }
nginx -t >/dev/null
systemctl is-enabled certbot.timer >/dev/null
systemctl is-active certbot.timer >/dev/null
release_count=$(find /opt/aidiag/releases -mindepth 1 -maxdepth 1 -type d | wc -l)
(( release_count >= 2 )) || { echo "FAIL rollback_release_count=$release_count expected_at_least=2"; exit 1; }

echo "PASS release_contract"
echo "PASS console_secret_permissions"
echo "PASS docker_disk_gate=${disk_percent}%"
echo "PASS nginx_config"
echo "PASS certbot_timer"
echo "PASS rollback_release_count=$release_count"
echo "READINESS mutation=none"
