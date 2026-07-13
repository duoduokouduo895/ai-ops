#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./elasticsearch9-candidate-common.sh
source "${script_dir}/elasticsearch9-candidate-common.sh"
[[ "${ELASTICSEARCH9_START_CONFIRMED:-}" == START_ISOLATED_ELASTICSEARCH9 ]] \
  || { echo "Set ELASTICSEARCH9_START_CONFIRMED=START_ISOLATED_ELASTICSEARCH9" >&2; exit 64; }
load_elasticsearch9_candidate "$@"
elasticsearch9_compose up -d elasticsearch9-candidate
wait_for_elasticsearch9_health 60
"${release}/scripts/cloud-verify-elasticsearch9-candidate.sh" "${release}"
echo "Elasticsearch 9 candidate started and verified; Elasticsearch 8 was not changed"
