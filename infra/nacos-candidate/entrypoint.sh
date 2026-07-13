#!/usr/bin/env bash
set -Eeuo pipefail

load_secret() {
  local variable="$1"
  local path="$2"
  [[ -s "${path}" ]] || { echo "Required Nacos candidate secret is missing: ${path}" >&2; exit 70; }
  printf -v "${variable}" '%s' "$(cat "${path}")"
  export "${variable}"
}

load_secret MYSQL_SERVICE_PASSWORD /run/secrets/mysql.password
load_secret NACOS_AUTH_TOKEN /run/secrets/nacos.auth-token
load_secret NACOS_AUTH_IDENTITY_KEY /run/secrets/nacos.identity-key
load_secret NACOS_AUTH_IDENTITY_VALUE /run/secrets/nacos.identity-value

# The upstream image enables `set -x` in docker-startup.sh. Redirect xtrace to
# /dev/null so authentication material cannot be expanded into container logs.
exec 9>/dev/null
export BASH_XTRACEFD=9
exec /bin/bash /home/nacos/bin/docker-startup.sh
