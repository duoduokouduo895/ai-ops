# Cloud secret files

Create these files only on the cloud server before the first deployment:

- postgres_password
- alertmanager_webhook_password
- aidiag_alertmanager_webhook_password (same value, backend-only mount)
- grafana_admin_password
- aidiag_deepseek_api_key
- aidiag_stage4_operator_secret
- aidiag_read_api_secret
- aidiag_console_password_hash
- aidiag_console_audit_pepper
- faultlab_db_password
- faultlab_control_token
- aichat_stage5_fault_token

Each file must contain only the secret value, without labels or surrounding quotes.
Set the directory mode to 700. `grafana_admin_password` uses owner `root:root`, mode 600.
`alertmanager_webhook_password` is read by the Alertmanager container UID/GID 65534, so use owner
`65534:65534`, mode 600. The backend runs as UID/GID 10001;
set `postgres_password` and `aidiag_alertmanager_webhook_password` to owner `root`, group
`10001`, mode 640. Real secret files
are ignored by Git and must never be copied back into the repository or chat.

Stage 4 is enabled through `compose.stage4.yaml`. Both `aidiag_deepseek_api_key` and
`aidiag_stage4_operator_secret` are read by the non-root AIDiag process, so use owner
`root`, group `10001`, mode `640`. Never put either value in `.env.cloud` or a Compose
environment entry.

Stage 5.3 requires a separate `aichat_stage5_fault_token`. It is mounted only
into the profiled `ai-chat-stage5-lab` container as `aichat.stage5.fault.token`.
Use at least 32 random bytes, owner `root`, group `10001`, mode `640`. Do not
reuse the Stage 1 Fault Lab token or any AI-chat application credential.

Stage 5.4 uses `aidiag_read_api_secret` only for server-to-server read requests from
the AI-chat ADMIN BFF. Mount it into both containers as a read-only file with owner
`root`, group `10001`, mode `640`. Do not expose it to either frontend build.

V1.1 browser sessions use two separate backend-only files. `aidiag_console_password_hash`
contains a BCrypt hash (cost 12 recommended), never the plaintext administrator password.
`aidiag_console_audit_pepper` contains at least 32 random bytes and is used to HMAC bounded
login-source metadata and derive the per-session CSRF token. Both files use owner `root`,
group `10001`, mode `640`; neither value belongs in Pages variables, browser storage, logs,
`.env.cloud`, or Git.
