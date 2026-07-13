#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./elasticsearch9-candidate-common.sh
source "${script_dir}/elasticsearch9-candidate-common.sh"
[[ "${ELASTICSEARCH9_STOP_CONFIRMED:-}" == STOP_ISOLATED_ELASTICSEARCH9 ]] \
  || { echo "Set ELASTICSEARCH9_STOP_CONFIRMED=STOP_ISOLATED_ELASTICSEARCH9" >&2; exit 64; }
load_elasticsearch9_candidate "$@"
elasticsearch9_compose stop elasticsearch9-candidate
echo "Elasticsearch 9 candidate stopped; data, Secrets, evidence, and Elasticsearch 8 were preserved"
