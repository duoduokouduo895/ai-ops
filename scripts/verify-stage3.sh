#!/usr/bin/env sh
# Run from a cloud release directory with: bash scripts/verify-stage3.sh
# This script is intentionally limited to the isolated Fault Lab and never reads
# or prints a secret. It does not delete incidents or telemetry history.
set -eu

[ -f versions.env ] || { printf '%s\n' 'versions.env is required' >&2; exit 1; }
if [ -f .env.cloud ]; then
  runtime_env="$(pwd)/.env.cloud"
  : "${AIDIAG_SECRETS_DIR:=$(pwd)/.secrets}"
else
  runtime_dir="${AIDIAG_RUNTIME_DIR:-/opt/aidiag/runtime}"
  runtime_env="$runtime_dir/.env.cloud"
  : "${AIDIAG_SECRETS_DIR:=$runtime_dir/.secrets}"
fi
[ -f "$runtime_env" ] || { printf '%s\n' 'runtime .env.cloud is required' >&2; exit 1; }
export AIDIAG_SECRETS_DIR

compose() {
  timeout 30s docker compose --project-directory "$(pwd)" -f "$(pwd)/compose.yaml" \
    --env-file "$(pwd)/versions.env" --env-file "$runtime_env" "$@"
}
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

fail() {
  printf '%s\n' "Stage 3 acceptance failed: $*" >&2
  exit 1
}

control_fault() {
  fault="$1"
  operation="$2"
  compose exec -T payment-service sh -ec '
    token="$(cat /run/secrets/faultlab.control.token)"
    curl -fsS -H "X-Fault-Lab-Control-Token: $token" -X POST \
      "http://localhost:8080/internal/faults/$1/$2" >/dev/null
  ' sh "$fault" "$operation"
}

order_status() {
  curl -sS -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:18081/api/orders
}

db_scalar() {
  sql="$1"
  compose exec -T postgres sh -ec 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"' sh "$sql"
}

wait_for() {
  description="$1"
  timeout_seconds="$2"
  shift 2
  elapsed=0
  while [ "$elapsed" -lt "$timeout_seconds" ]; do
    if "$@"; then
      printf 'passed: %s\n' "$description"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  fail "timed out waiting for $description"
}

alert_is_firing() {
  alert="$1"
  compose exec -T alertmanager sh -ec 'wget -qO- http://localhost:9093/api/v2/alerts' |
    grep -q "\"alertname\":\"$alert\""
}

incident_id() {
  alert="$1"
  db_scalar "SELECT id FROM incident WHERE alert_name = '$alert' AND created_at >= TIMESTAMPTZ '$STARTED_AT' ORDER BY created_at DESC LIMIT 1"
}

report_is_supported() {
  alert="$1"
  incident="$(incident_id "$alert")"
  [ -n "$incident" ] || return 1
  report="$(compose exec -T aidiag-backend curl -fsS "http://localhost:8080/api/v1/incidents/$incident/report" 2>/dev/null || true)"
  printf '%s' "$report" | grep -Eq '"verificationStatus"[[:space:]]*:[[:space:]]*"SUPPORTED"' &&
    printf '%s' "$report" | grep -Eq '"primaryRootCauseCandidateId"[[:space:]]*:[[:space:]]*"'
}

incident_is_resolved() {
  alert="$1"
  incident="$(incident_id "$alert")"
  [ -n "$incident" ] || return 1
  status="$(db_scalar "SELECT status FROM incident WHERE id = '$incident'")"
  printf 'resolved-check: alert=%s incident=%s status=%s\n' "$alert" "$incident" "$status"
  [ "$status" = "RESOLVED" ]
}

disable_all() {
  for fault in downstream-timeout database-slow-query hikari-exhaustion thread-pool-exhaustion; do
    control_fault "$fault" disable || true
  done
}

cleanup() {
  disable_all
}

run_case() {
  fault="$1"
  alert="$2"
  expected_status="$3"
  printf '\n== Stage 3 case: %s ==\n' "$fault"
  control_fault "$fault" enable
  actual_status="$(order_status)"
  [ "$actual_status" = "$expected_status" ] || fail "$fault expected Order HTTP $expected_status, got $actual_status"
  wait_for "$alert firing" 180 alert_is_firing "$alert"
  wait_for "$alert report" 240 report_is_supported "$alert"
  control_fault "$fault" disable
  wait_for "$fault request recovery" 60 normal_request_succeeds
  wait_for "$alert resolved" 180 incident_is_resolved "$alert"
}

normal_request_succeeds() {
  [ "$(order_status)" = "201" ]
}

compose ps --status running | grep -q aidiag-backend || fail "aidiag-backend is not running"
trap cleanup EXIT
trap 'cleanup; exit 143' HUP INT TERM
disable_all
wait_for "Fault Lab normal request" 60 normal_request_succeeds

active_incidents="$(db_scalar "SELECT count(*) FROM incident WHERE alert_name IN ('FaultLabDownstreamTimeout', 'FaultLabDatabaseSlowQuery', 'FaultLabHikariPoolExhausted', 'FaultLabThreadPoolRejected') AND status IN ('OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED')")"
[ "$active_incidents" = "0" ] || fail "resolve existing Fault Lab incidents before running this script"

run_case downstream-timeout FaultLabDownstreamTimeout 504
run_case database-slow-query FaultLabDatabaseSlowQuery 504
run_case hikari-exhaustion FaultLabHikariPoolExhausted 503
run_case thread-pool-exhaustion FaultLabThreadPoolRejected 503

printf '\nStage 3 acceptance passed for all four controlled Fault Lab scenarios.\n'
