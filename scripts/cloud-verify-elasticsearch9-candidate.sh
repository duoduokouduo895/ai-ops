#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./elasticsearch9-candidate-common.sh
source "${script_dir}/elasticsearch9-candidate-common.sh"
load_elasticsearch9_candidate "$@"

for spec in \
  'elastic_password:400:1000:0' \
  'client_username:600:0:0' \
  'client_password:600:0:0'; do
  IFS=: read -r name mode uid gid <<<"${spec}"
  path="${secrets_dir}/${name}"
  [[ -f "${path}" && ! -L "${path}" && -s "${path}" \
    && "$(stat -c '%a:%u:%g' "${path}")" == "${mode}:${uid}:${gid}" ]] \
    || { echo "Candidate Secret has unsafe ownership or mode: ${name}" >&2; exit 68; }
done
for name in elasticsearch_username elasticsearch_password; do
  path="${AICHAT_CANDIDATE_SECRETS_DIR}/${name}"
  [[ -f "${path}" && ! -L "${path}" && -s "${path}" \
    && "$(stat -c '%a:%u:%g' "${path}")" == '400:10001:10001' ]] \
    || { echo "AI-chat Elasticsearch Secret has unsafe ownership or mode: ${name}" >&2; exit 68; }
done

container=elasticsearch-v9-candidate
[[ "$(docker inspect --format '{{.State.Running}}' "${container}" 2>/dev/null || true)" == true \
  && "$(docker inspect --format '{{.State.Health.Status}}' "${container}")" == healthy \
  && "$(docker inspect --format '{{.RestartCount}}' "${container}")" == 0 ]] \
  || { echo "Elasticsearch 9 candidate is not healthy or restarted" >&2; exit 70; }
[[ "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "${container}")" == true \
  && "$(docker inspect --format '{{.HostConfig.Memory}}' "${container}")" == 2684354560 \
  && "$(docker inspect --format '{{.HostConfig.PidsLimit}}' "${container}")" == 512 ]] \
  || { echo "Elasticsearch 9 resource or read-only boundary drifted" >&2; exit 70; }
[[ "$(docker image inspect --format '{{index .Config.Labels "aidiag.elasticsearch.version"}}' \
  "${ELASTICSEARCH9_CANDIDATE_IMAGE}")" == 9.3.2 \
  && "$(docker image inspect --format '{{index .Config.Labels "aidiag.elasticsearch.base-image"}}' \
  "${ELASTICSEARCH9_CANDIDATE_IMAGE}")" == "${ELASTICSEARCH9_BASE_IMAGE}" \
  && "$(docker image inspect --format '{{index .Config.Labels "aidiag.analysis-ik.version"}}' \
  "${ELASTICSEARCH9_CANDIDATE_IMAGE}")" == 9.3.2 \
  && "$(docker image inspect --format '{{index .Config.Labels "aidiag.analysis-ik.sha256"}}' \
  "${ELASTICSEARCH9_CANDIDATE_IMAGE}")" == "${ELASTICSEARCH9_IK_PLUGIN_SHA256}" ]] \
  || { echo "Elasticsearch 9 image labels do not prove the reviewed IK artifact" >&2; exit 70; }
[[ "$(docker inspect --format '{{.Image}}' "${container}")" \
  == "$(docker image inspect --format '{{.Id}}' "${ELASTICSEARCH9_CANDIDATE_IMAGE}")" ]] \
  || { echo "Running Elasticsearch container differs from the reviewed local image" >&2; exit 70; }

ports="$(docker port "${container}")"
grep -Fq "127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}" <<<"${ports}"
grep -Eq '0\.0\.0\.0|\[::\]' <<<"${ports}" \
  && { echo "Elasticsearch 9 candidate unexpectedly exposes a public listener" >&2; exit 70; }
config_volume="${ELASTICSEARCH9_CONFIG_VOLUME:-elasticsearch9-candidate-config}"
mounts="$(docker inspect --format '{{range .Mounts}}{{printf "%s|%s|%s\n" .Type .Name .Destination}}{{end}}' "${container}")"
grep -Fxq "volume|${config_volume}|/usr/share/elasticsearch/config" <<<"${mounts}" \
  || { echo "Elasticsearch candidate config volume is missing or drifted" >&2; exit 70; }
grep -Fxq "volume|${ELASTICSEARCH9_DATA_VOLUME}|/usr/share/elasticsearch/data" <<<"${mounts}" \
  || { echo "Elasticsearch candidate data volume is missing or drifted" >&2; exit 70; }
[[ "${config_volume}" != "${ELASTICSEARCH9_DATA_VOLUME}" ]] \
  || { echo "Elasticsearch config and data must not share a volume" >&2; exit 70; }

source_restart="$(cat "${runtime_dir}/source-elasticsearch-restart-count")"
[[ "$(docker inspect --format '{{.State.Running}}' elasticsearch)" == true \
  && "$(docker inspect --format '{{.RestartCount}}' elasticsearch)" == "${source_restart}" ]] \
  || { echo "Existing Elasticsearch changed during candidate creation" >&2; exit 70; }

tmp_dir="$(mktemp -d "${runtime_dir}/.verify.XXXXXX")"
cleanup() {
  curl --silent --output /dev/null --max-time 5 -H "@${tmp_dir}/client-header" \
    -X DELETE "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/knowledge_base_embeddings_gate" || true
  rm -rf -- "${tmp_dir}"
}
trap cleanup EXIT
write_basic_auth_header "${secrets_dir}/client_username" "${secrets_dir}/client_password" \
  "${tmp_dir}/client-header"

curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/" \
  --output "${tmp_dir}/root.json"
python3 - "${tmp_dir}/root.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("version", {}).get("number") != "9.3.2":
    raise SystemExit("Elasticsearch server version is not 9.3.2")
PY

curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" \
  "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_cat/plugins?format=json&h=component,version" \
  --output "${tmp_dir}/plugins.json"
python3 - "${tmp_dir}/plugins.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    plugins = json.load(handle)
matches = [p for p in plugins if p.get("component") == "analysis-ik"]
if matches != [{"component": "analysis-ik", "version": "9.3.2"}]:
    raise SystemExit(f"Expected exactly analysis-ik 9.3.2, got: {matches}")
PY

cat >"${tmp_dir}/privileges.json" <<'JSON'
{
  "cluster": ["monitor"],
  "index": [
    {"names": ["conversations", "knowledge_base_chunks", "knowledge_base_embeddings_gate"],
     "privileges": ["create_index", "manage", "read", "write"]}
  ]
}
JSON
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/user/_has_privileges" \
  --data-binary "@${tmp_dir}/privileges.json" --output "${tmp_dir}/privileges-response.json"
python3 - "${tmp_dir}/privileges-response.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("has_all_requested") is not True:
    raise SystemExit("AI-chat candidate lacks required Elasticsearch privileges")
PY

cat >"${tmp_dir}/forbidden.json" <<'JSON'
{"cluster":["all"],"index":[{"names":[".security*"],"privileges":["all"]}]}
JSON
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/_security/user/_has_privileges" \
  --data-binary "@${tmp_dir}/forbidden.json" --output "${tmp_dir}/forbidden-response.json"
python3 - "${tmp_dir}/forbidden-response.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("has_all_requested") is not False:
    raise SystemExit("AI-chat candidate unexpectedly has administrative privileges")
PY

cat >"${tmp_dir}/index.json" <<'JSON'
{
  "mappings": {
    "properties": {
      "title": {"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
      "vector": {"type":"dense_vector","dims":4,"index":true,"similarity":"cosine"}
    }
  }
}
JSON
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/knowledge_base_embeddings_gate" \
  --data-binary "@${tmp_dir}/index.json" --output "${tmp_dir}/index-response.json"
cat >"${tmp_dir}/analyze.json" <<'JSON'
{"analyzer":"ik_smart","text":"中华人民共和国国歌"}
JSON
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/knowledge_base_embeddings_gate/_analyze" \
  --data-binary "@${tmp_dir}/analyze.json" --output "${tmp_dir}/analyze-response.json"
python3 - "${tmp_dir}/analyze-response.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
tokens = [item.get("token") for item in payload.get("tokens", [])]
if "中华人民共和国" not in tokens or "国歌" not in tokens:
    raise SystemExit(f"IK smart analyzer returned unexpected tokens: {tokens}")
PY
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/knowledge_base_embeddings_gate/_doc/1?refresh=true" \
  --data-binary '{"title":"中华人民共和国国歌","vector":[1.0,0.0,0.0,0.0]}' \
  --output "${tmp_dir}/document-response.json"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/knowledge_base_embeddings_gate/_search" \
  --data-binary '{"query":{"match":{"title":"中华人民共和国"}}}' \
  --output "${tmp_dir}/search-response.json"
python3 - "${tmp_dir}/search-response.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("hits", {}).get("total", {}).get("value", 0) < 1:
    raise SystemExit("IK-backed Chinese search did not return the gate document")
PY
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  -H "@${tmp_dir}/client-header" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:${ELASTICSEARCH9_HTTP_PORT}/knowledge_base_embeddings_gate/_search" \
  --data-binary '{"knn":{"field":"vector","query_vector":[1.0,0.0,0.0,0.0],"k":1,"num_candidates":1}}' \
  --output "${tmp_dir}/vector-response.json"
python3 - "${tmp_dir}/vector-response.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if len(payload.get("hits", {}).get("hits", [])) != 1:
    raise SystemExit("Dense-vector kNN gate did not return exactly one document")
PY

docker logs --tail 5000 "${container}" >"${tmp_dir}/container.log" 2>&1
for name in elastic_password client_password; do
  if grep -Fq -- "$(cat "${secrets_dir}/${name}")" "${tmp_dir}/container.log"; then
    echo "An Elasticsearch candidate Secret was found in Docker logs: ${name}" >&2
    exit 78
  fi
done

echo "elasticsearch_version=9.3.2"
echo "analysis_ik_version=9.3.2"
echo "ik_smart_gate=PASS"
echo "least_privilege_gate=PASS"
echo "index_mapping_and_search_gate=PASS"
echo "dense_vector_knn_gate=PASS"
echo "candidate_restart_count=0"
echo "source_elasticsearch_restart_count=${source_restart}"
echo "network_exposure=loopback-and-private-only"
