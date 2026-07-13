#!/usr/bin/env bash
# Five lifecycle smoke cycles against the isolated AI-chat telemetry contract lab.
# This script never calls the real AI-chat candidate and never deletes audit history.
set -Eeuo pipefail

[[ -f versions.env ]] || { echo 'run from an AIDiag release directory' >&2; exit 64; }
runtime_dir="${AIDIAG_RUNTIME_DIR:-/opt/aidiag/runtime}"
runtime_env="$runtime_dir/.env.cloud"
export AIDIAG_SECRETS_DIR="${AIDIAG_SECRETS_DIR:-$runtime_dir/.secrets}"
[[ -f "$runtime_env" ]] || { echo 'runtime .env.cloud is missing' >&2; exit 65; }

compose_files=(-f "$(pwd)/compose.yaml")
[[ -f compose.stage4.yaml ]] && compose_files+=(-f "$(pwd)/compose.stage4.yaml")
compose() {
  timeout 45s docker compose --project-directory "$(pwd)" "${compose_files[@]}" \
    --env-file "$(pwd)/versions.env" --env-file "$runtime_env" --profile stage5-test "$@"
}

faults=(model-timeout r2dbc-pool-exhaustion document-backlog search-unavailable)
alerts=(AiChatModelTimeout AiChatR2dbcPoolExhaustion AiChatDocumentBacklog AiChatSearchUnavailable)

fail() {
  echo "Stage 5.3 acceptance failed: $*" >&2
  exit 1
}

db_scalar() {
  local sql="$1"
  compose exec -T postgres sh -ec 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"' sh "$sql"
}

control() {
  local fault="$1" operation="$2"
  compose exec -T ai-chat-stage5-lab sh -ec '
    token="$(cat /run/secrets/aichat.stage5.fault.token)"
    curl -fsS -H "X-AI-Chat-Stage5-Fault-Token: $token" -X POST \
      "http://localhost:8080/internal/stage5-faults/$1/$2" >/dev/null
  ' sh "$fault" "$operation"
}

reset_faults() {
  compose exec -T ai-chat-stage5-lab sh -ec '
    token="$(cat /run/secrets/aichat.stage5.fault.token)"
    curl -fsS -H "X-AI-Chat-Stage5-Fault-Token: $token" -X POST \
      http://localhost:8080/internal/stage5-faults/reset >/dev/null
  ' >/dev/null 2>&1 || true
}

wait_for() {
  local description="$1" timeout_seconds="$2"
  shift 2
  local elapsed=0
  while (( elapsed < timeout_seconds )); do
    if "$@"; then
      echo "passed: $description"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  fail "timed out waiting for $description"
}

latest_incident() {
  local alert="$1" started_at="$2"
  db_scalar "SELECT id FROM incident WHERE primary_service='ai-chat-stage5-lab' AND alert_name='$alert' AND created_at >= TIMESTAMPTZ '$started_at' ORDER BY created_at DESC LIMIT 1"
}

report_supported() {
  local incident="$1"
  local report
  report="$(compose exec -T aidiag-backend curl -fsS "http://localhost:8080/api/v1/incidents/$incident/report" 2>/dev/null || true)"
  [[ "$report" == *'"verificationStatus":"SUPPORTED"'* && "$report" == *'"primaryRootCauseCandidateId":"'* ]]
}

incident_resolved() {
  local incident="$1"
  [[ "$(db_scalar "SELECT status FROM incident WHERE id='$incident'")" == RESOLVED ]]
}

lab_scrape_is_up() {
  compose exec -T prometheus wget -qO- \
    'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22ai-chat-stage5-lab%22%7D' 2>/dev/null \
    | grep -q '"value":\[[^]]*,"1"\]'
}

cleanup() {
  reset_faults
}
trap cleanup EXIT
trap 'cleanup; exit 143' HUP INT TERM

compose ps --status running ai-chat-stage5-lab | grep -q ai-chat-stage5-lab || fail 'isolated lab is not running'
reset_faults
wait_for 'Prometheus baseline scrape for isolated lab' 120 lab_scrape_is_up
# Counter-backed rules need a zero baseline sample before the first controlled
# increment; otherwise Prometheus correctly treats the first observed value as
# the baseline and increase() would not match.
sleep 20
active="$(db_scalar "SELECT count(*) FROM incident WHERE primary_service='ai-chat-stage5-lab' AND status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')")"
[[ "$active" == 0 ]] || fail 'resolve existing Stage 5.3 lab incidents before acceptance'

echo 'cycle,alert,incident,alert_to_incident_ms,alert_to_report_ms,evidence_count,trace_count'
last_incident=''
for cycle in $(seq 1 5); do
  cycle_started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for fault in "${faults[@]}"; do control "$fault" enable; done

  incidents=()
  for index in "${!alerts[@]}"; do
    alert="${alerts[$index]}"
    incident=''
    find_incident() {
      incident="$(latest_incident "$alert" "$cycle_started")"
      [[ -n "$incident" ]]
    }
    wait_for "$alert cycle $cycle Incident" 180 find_incident
    incidents+=("$incident")
  done

  for index in "${!alerts[@]}"; do
    alert="${alerts[$index]}"
    incident="${incidents[$index]}"
    wait_for "$alert cycle $cycle SUPPORTED report" 240 report_supported "$incident"
    row="$(db_scalar "SELECT ROUND(EXTRACT(EPOCH FROM (detected_at-started_at))*1000)::bigint||','||ROUND(EXTRACT(EPOCH FROM (r.generated_at-i.started_at))*1000)::bigint||','||(SELECT count(*) FROM evidence e WHERE e.diagnosis_run_id=r.diagnosis_run_id)||','||(SELECT count(*) FROM evidence e WHERE e.diagnosis_run_id=r.diagnosis_run_id AND e.evidence_type='TRACE') FROM incident i JOIN LATERAL (SELECT * FROM diagnosis_report WHERE incident_id=i.id ORDER BY generated_at DESC LIMIT 1) r ON true WHERE i.id='$incident'")"
    echo "$cycle,$alert,$incident,$row"
    last_incident="$incident"
  done

  for fault in "${faults[@]}"; do control "$fault" disable; done
  for incident in "${incidents[@]}"; do
    wait_for "Incident $incident resolved" 180 incident_resolved "$incident"
  done
done

[[ -n "$last_incident" ]] || fail 'no supported report was produced'
report_hash_before="$(db_scalar "SELECT encode(digest(report_json::text,'sha256'),'hex') FROM diagnosis_report WHERE incident_id='$last_incident' ORDER BY generated_at DESC LIMIT 1")"
budget_before="$(db_scalar "SELECT COALESCE((SELECT request_count FROM ai_model_daily_budget WHERE usage_date=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date),0)")"
preview="$(compose exec -T aidiag-backend sh -ec '
  username="${AIDIAG_STAGE4_OPERATOR_USERNAME:-aidiag-operator}"
  password="$(cat /run/secrets/aidiag.stage4.operator.secret)"
  curl -fsS -u "$username:$password" -X POST \
    "http://localhost:8080/api/v1/incidents/$1/ai-diagnosis-preview"
' sh "$last_incident")"
printf '%s' "$preview" | python3 -c '
import json,sys
d=json.load(sys.stdin)
assert d.get("status") == "COMPLETED", d
assert d.get("automatedRemediation") is False, d
assert d.get("advice") and d["advice"].get("evidenceIds"), d
print("stage4_preview=COMPLETED automatedRemediation=false evidenceRefs=%d" % len(d["advice"]["evidenceIds"]))
'
budget_after="$(db_scalar "SELECT request_count FROM ai_model_daily_budget WHERE usage_date=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date")"
report_hash_after="$(db_scalar "SELECT encode(digest(report_json::text,'sha256'),'hex') FROM diagnosis_report WHERE incident_id='$last_incident' ORDER BY generated_at DESC LIMIT 1")"
[[ "$budget_after" -eq $((budget_before + 1)) ]] || fail 'Stage 4 daily budget did not increase exactly once'
[[ "$report_hash_before" == "$report_hash_after" ]] || fail 'Stage 4 changed the deterministic report'

reset_faults
trap - EXIT
echo "Stage 5.3 acceptance passed: 5 lifecycle cycles x 4 contracts; Stage 4 preview incident=$last_incident budget_delta=1"
