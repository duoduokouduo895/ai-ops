#!/usr/bin/env bash
set -Eeuo pipefail

# Resumable, bounded-concurrency downloader for a temporary migration endpoint.
# Usage: download-http11-parallel.sh URL OUTPUT EXPECTED_BYTES [PARALLELISM] [CHUNK_BYTES]
url=${1:?URL is required}
output=${2:?output path is required}
expected_bytes=${3:?expected byte count is required}
parallelism=${4:-8}
chunk_bytes=${5:-67108864}

parts_dir="${output}.parts"
mkdir -p "$parts_dir"
chmod 700 "$parts_dir"

chunks=$(((expected_bytes + chunk_bytes - 1) / chunk_bytes))
download_chunk() {
  local index=$1 start end wanted part actual
  start=$((index * chunk_bytes))
  end=$((start + chunk_bytes - 1))
  if (( end >= expected_bytes )); then end=$((expected_bytes - 1)); fi
  wanted=$((end - start + 1))
  part="$parts_dir/$(printf '%05d' "$index")"
  actual=$(stat -c%s "$part" 2>/dev/null || echo 0)
  if (( actual == wanted )); then
    return 0
  fi
  rm -f "$part"
  curl --http1.1 --fail --silent --show-error --location \
    --retry 5 --retry-delay 2 --connect-timeout 20 --max-time 900 \
    --resolve api.dduo.me:443:34.150.80.173 \
    --range "${start}-${end}" "$url" -o "$part"
  actual=$(stat -c%s "$part")
  [[ "$actual" == "$wanted" ]]
}
export url expected_bytes chunk_bytes parts_dir
export -f download_chunk

seq 0 $((chunks - 1)) | xargs -r -n1 -P "$parallelism" bash -c 'download_chunk "$0"'

tmp="${output}.assembling"
rm -f "$tmp"
for index in $(seq 0 $((chunks - 1))); do
  cat "$parts_dir/$(printf '%05d' "$index")" >> "$tmp"
done
[[ "$(stat -c%s "$tmp")" == "$expected_bytes" ]]
mv -f "$tmp" "$output"
rm -rf "$parts_dir"
printf 'downloaded=%s bytes=%s\n' "$output" "$expected_bytes"
