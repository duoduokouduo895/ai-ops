#!/usr/bin/env bash
set -Eeuo pipefail

app_url="${AICHAT_CANDIDATE_APP_URL:-http://127.0.0.1:18082}"
management_url="${AICHAT_CANDIDATE_MANAGEMENT_URL:-http://127.0.0.1:19091}"
secrets_dir="${AICHAT_CANDIDATE_SECRETS_DIR:-/opt/ai-chat/runtime/.secrets/candidate}"
evidence_dir="${AIDIAG_RUNTIME_DIR:-/opt/aidiag/runtime}"
smoke_model_alias="${AICHAT_SMOKE_MODEL_ALIAS:-chat-fast}"
[[ "${smoke_model_alias}" =~ ^[A-Za-z0-9._-]{1,120}$ ]] \
  || { echo 'AICHAT_SMOKE_MODEL_ALIAS has an unsafe format' >&2; exit 64; }
tmp_dir="$(mktemp -d "${evidence_dir}/.aichat-es9-verify.XXXXXX")"
user_id=''
jti=''
conversation_id=''
trap 'rm -rf -- "${tmp_dir}"' EXIT

container_env_value() {
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$1" \
    | sed -n "s/^$2=//p" | head -n 1
}

mysql_root_password="$(container_env_value mysql MYSQL_ROOT_PASSWORD)"
if [[ -z "${mysql_root_password}" ]]; then
  mysql_root_password_file="$(container_env_value mysql MYSQL_ROOT_PASSWORD_FILE)"
  if [[ "${mysql_root_password_file}" == /* ]]; then
    mysql_root_password="$(docker exec mysql sh -c 'cat "$1"' _ "${mysql_root_password_file}")"
  fi
fi
[[ -n "${mysql_root_password}" ]] \
  || { echo 'MySQL root credential is unavailable for exact cleanup' >&2; exit 70; }

mysql_root() {
  docker exec -i -e MYSQL_PWD="${mysql_root_password}" mysql \
    mysql --protocol=socket -uroot --batch --skip-column-names "$@"
}

database="$(mysql_root -e "
  SELECT table_schema
  FROM information_schema.tables
  WHERE table_name IN ('users', 'conversation', 'message')
    AND table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
  GROUP BY table_schema
  HAVING COUNT(DISTINCT table_name) = 3;")"
[[ -n "${database}" && "${database}" != *$'\n'* && "${database}" =~ ^[A-Za-z0-9_]+$ ]] \
  || { echo 'A unique AI-chat database could not be identified' >&2; exit 70; }

mysql_app() {
  docker exec -i -e MYSQL_PWD="${mysql_root_password}" mysql \
    mysql --protocol=socket -uroot --batch --skip-column-names "${database}" "$@"
}

redis_cli() {
  docker exec redis redis-cli --raw "$@"
}

[[ "$(redis_cli PING)" == PONG ]] \
  || { echo 'Redis cleanup channel is unavailable' >&2; exit 70; }
redis_cli --scan --pattern 'admission:guest:create:*' | sort -u >"${tmp_dir}/guest-keys-before"
mysql_app -e 'SELECT user_id FROM users WHERE is_guest=1 ORDER BY user_id' \
  | sort -u >"${tmp_dir}/guest-users-before"

cleanup() {
  local original_rc=$?
  set +e

  if [[ -z "${user_id}" && -f "${tmp_dir}/guest-users-before" ]]; then
    mysql_app -e 'SELECT user_id FROM users WHERE is_guest=1 ORDER BY user_id' \
      | sort -u >"${tmp_dir}/guest-users-after"
    comm -13 "${tmp_dir}/guest-users-before" "${tmp_dir}/guest-users-after" \
      >"${tmp_dir}/guest-users-created"
    fallback_user_id="$(cat "${tmp_dir}/guest-users-created")"
    if [[ "${fallback_user_id}" =~ ^[0-9]+$ ]]; then
      user_id="${fallback_user_id}"
    fi
  fi
  if [[ -z "${conversation_id}" && -n "${user_id}" ]]; then
    fallback_conversation_id="$(mysql_app -e \
      "SELECT id FROM conversation WHERE user_id=${user_id} ORDER BY id")"
    if [[ "${fallback_conversation_id}" =~ ^[0-9]+$ ]]; then
      conversation_id="${fallback_conversation_id}"
    fi
  fi

  if [[ -n "${conversation_id}" && -s "${tmp_dir}/auth-header" ]]; then
    curl --silent --show-error --max-time 15 -H "@${tmp_dir}/auth-header" \
      -X DELETE "${app_url}/api/conversations/${conversation_id}" \
      --output "${tmp_dir}/delete-response" \
      --write-out '%{http_code}' >"${tmp_dir}/delete-status"
  fi
  if [[ -s "${tmp_dir}/auth-header" ]]; then
    curl --silent --show-error --max-time 15 -H "@${tmp_dir}/auth-header" \
      -X POST "${app_url}/api/users/logout" \
      --output "${tmp_dir}/logout-response" \
      --write-out '%{http_code}' >"${tmp_dir}/logout-status"
  fi

  if [[ -n "${conversation_id}" ]]; then
    for _ in $(seq 1 10); do
      [[ "$(mysql_app -e "SELECT COUNT(*) FROM conversation WHERE id=${conversation_id}")" == 0 ]] \
        && break
      sleep 1
    done
    mysql_app -e "
      DELETE FROM message WHERE conversation_id=${conversation_id};
      DELETE FROM conversation WHERE id=${conversation_id} AND user_id=${user_id};" >/dev/null
    redis_cli DEL "chat-memory:conversation:${conversation_id}" >/dev/null
    while IFS= read -r key; do
      [[ -n "${key}" ]] && redis_cli DEL "${key}" >/dev/null
    done < <(redis_cli --scan --pattern "${conversation_id}:*")
  fi

  if [[ -n "${user_id}" ]]; then
    redis_cli DEL \
      "admission:chat:concurrency:user:${user_id}" \
      "admission:chat:guest-quota:${user_id}" \
      "token:user:${user_id}:jtis" \
      "token:user-revoked:${user_id}" >/dev/null
  fi
  [[ -n "${jti}" ]] && redis_cli DEL "token:jti:${jti}" >/dev/null

  redis_cli --scan --pattern 'admission:guest:create:*' | sort -u >"${tmp_dir}/guest-keys-after"
  comm -13 "${tmp_dir}/guest-keys-before" "${tmp_dir}/guest-keys-after" \
    >"${tmp_dir}/guest-keys-created"
  while IFS= read -r key; do
    [[ -n "${key}" ]] && redis_cli DEL "${key}" >/dev/null
  done <"${tmp_dir}/guest-keys-created"

  if [[ -n "${user_id}" ]]; then
    mysql_app -e "
      DELETE FROM users
      WHERE user_id=${user_id}
        AND is_guest=1
        AND NOT EXISTS (SELECT 1 FROM conversation WHERE user_id=${user_id});" >/dev/null
  fi

  if [[ -n "${conversation_id}" && -s "${secrets_dir}/elasticsearch_username" \
    && -s "${secrets_dir}/elasticsearch_password" ]]; then
    encoded="$(printf '%s:%s' \
      "$(cat "${secrets_dir}/elasticsearch_username")" \
      "$(cat "${secrets_dir}/elasticsearch_password")" | openssl base64 -A)"
    printf 'Authorization: Basic %s' "${encoded}" >"${tmp_dir}/es-header"
    unset encoded
    curl --silent --show-error --max-time 10 -H "@${tmp_dir}/es-header" \
      -X DELETE "http://127.0.0.1:19200/conversations/_doc/${conversation_id}" \
      --output /dev/null >/dev/null 2>&1
  fi

  if [[ ${original_rc} -ne 0 ]]; then
    echo 'Temporary Guest cleanup executed after application verification failed' >&2
  fi
  rm -rf -- "${tmp_dir}"
  return "${original_rc}"
}
trap cleanup EXIT

metric_sum() {
  curl --fail --silent --show-error --max-time 10 \
    "${management_url}/actuator/prometheus" \
    | python3 -c 'import sys; print(sum(float(line.rsplit(" ", 1)[1]) for line in sys.stdin if line.startswith("aichat_chat_requests_total{") and "outcome=\"success\"" in line))'
}

before_success="$(metric_sum)"
curl --fail --silent --show-error --max-time 20 \
  -X POST "${app_url}/api/users/guest" \
  -H 'Content-Type: application/json' --data '{}' \
  --output "${tmp_dir}/guest.json"
python3 - "${tmp_dir}/guest.json" >"${tmp_dir}/token" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
token = payload.get("token")
if payload.get("success") is not True or not isinstance(token, str) or len(token) < 40:
    raise SystemExit("Guest creation failed")
print(token, end="")
PY
python3 - "${tmp_dir}/token" >"${tmp_dir}/claims" <<'PY'
import base64
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    parts = handle.read().split(".")
if len(parts) != 3:
    raise SystemExit("Invalid JWT shape")
padding = "=" * ((4 - len(parts[1]) % 4) % 4)
payload = json.loads(base64.urlsafe_b64decode(parts[1] + padding))
user_id = payload.get("userId")
jti = payload.get("jti")
if not isinstance(user_id, int) or user_id <= 0 or not isinstance(jti, str) or not jti:
    raise SystemExit("Required Guest claims are missing")
print(user_id)
print(jti)
PY
user_id="$(sed -n '1p' "${tmp_dir}/claims")"
jti="$(sed -n '2p' "${tmp_dir}/claims")"
printf 'Authorization: Bearer %s' "$(cat "${tmp_dir}/token")" >"${tmp_dir}/auth-header"

curl --fail --silent --show-error --max-time 10 \
  -H "@${tmp_dir}/auth-header" "${app_url}/api/ai/models" \
  --output "${tmp_dir}/models.json"
python3 - "${tmp_dir}/models.json" "${smoke_model_alias}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    models = json.load(handle)
values = {model.get("value") for model in models if isinstance(model, dict)}
if sys.argv[2] not in values:
    raise SystemExit(f"Requested stable model alias is missing; available count={len(values)}")
print("smoke_model_catalog=PASS")
PY

trace_id="$(openssl rand -hex 16)"
span_id="$(openssl rand -hex 8)"
printf '{"prompt":"Reply only with candidate-ok.","model":"%s","saveMessage":true,"streaming":true,"clientMessageId":"candidate-es9-%s"}' \
  "${smoke_model_alias}" "$(date +%s)" >"${tmp_dir}/request.json"
http_code="$(curl --silent --show-error --no-buffer --max-time 90 \
  -H "@${tmp_dir}/auth-header" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H "traceparent: 00-${trace_id}-${span_id}-01" \
  --data-binary "@${tmp_dir}/request.json" \
  --output "${tmp_dir}/sse" --write-out '%{http_code}' \
  "${app_url}/api/ai/chat")"
[[ "${http_code}" == 200 ]] \
  || { echo "SSE HTTP gate failed: ${http_code}" >&2; exit 1; }
! grep -q '^event:error' "${tmp_dir}/sse" \
  || { echo 'SSE returned an application error event' >&2; exit 1; }
grep -q '^event:metadata' "${tmp_dir}/sse"
grep -q '^event:status' "${tmp_dir}/sse"
grep -q '^event:message' "${tmp_dir}/sse"

conversation_id="$(python3 - "${tmp_dir}/sse" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    lines = handle.read().splitlines()
for index, line in enumerate(lines):
    if line == "event:metadata" and index + 1 < len(lines) and lines[index + 1].startswith("data:"):
        payload = json.loads(lines[index + 1][5:].strip())
        conversation_id = payload.get("conversationId")
        if isinstance(conversation_id, int) and conversation_id > 0:
            print(conversation_id)
            break
else:
    raise SystemExit("conversationId is missing from metadata")
PY
)"

[[ "$(mysql_app -e "SELECT COUNT(*) FROM users WHERE user_id=${user_id} AND is_guest=1")" == 1 ]]
[[ "$(mysql_app -e "SELECT COUNT(*) FROM conversation WHERE id=${conversation_id} AND user_id=${user_id}")" == 1 ]]
message_count="$(mysql_app -e "SELECT COUNT(*) FROM message WHERE conversation_id=${conversation_id}")"
[[ "${message_count}" -ge 2 ]]

after_success="${before_success}"
for _ in $(seq 1 15); do
  after_success="$(metric_sum)"
  if python3 - "${before_success}" "${after_success}" <<'PY'
import sys
raise SystemExit(0 if float(sys.argv[2]) > float(sys.argv[1]) else 1)
PY
  then
    break
  fi
  sleep 1
done
python3 - "${before_success}" "${after_success}" <<'PY'
import sys
if float(sys.argv[2]) <= float(sys.argv[1]):
    raise SystemExit("Success metric did not increase")
PY

printf '%s' "${trace_id}" >"${evidence_dir}/last-aichat-candidate-smoke-trace-id"
chmod 600 "${evidence_dir}/last-aichat-candidate-smoke-trace-id"
echo 'guest_creation=PASS'
echo 'sse_chat_http=200'
echo 'sse_events=metadata,status,message'
echo "sse_persisted_messages=${message_count}"
echo 'chat_success_metric_delta=PASS'
echo "trace_id=${trace_id}"

cleanup
trap - EXIT
[[ "$(mysql_app -e "SELECT COUNT(*) FROM users WHERE user_id=${user_id}")" == 0 ]]
[[ "$(mysql_app -e "SELECT COUNT(*) FROM conversation WHERE id=${conversation_id}")" == 0 ]]
[[ "$(mysql_app -e "SELECT COUNT(*) FROM message WHERE conversation_id=${conversation_id}")" == 0 ]]
[[ "$(redis_cli EXISTS "chat-memory:conversation:${conversation_id}")" == 0 ]]
[[ -z "$(redis_cli --scan --pattern "${conversation_id}:*")" ]]
[[ "$(redis_cli EXISTS \
  "admission:chat:guest-quota:${user_id}" \
  "admission:chat:concurrency:user:${user_id}" \
  "token:jti:${jti}" \
  "token:user:${user_id}:jtis")" == 0 ]]
echo 'temporary_guest_cleanup=PASS'
