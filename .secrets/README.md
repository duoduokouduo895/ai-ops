# Cloud secret files

Create these files only on the cloud server before the first deployment:

- postgres_password
- alertmanager_webhook_password
- aidiag_alertmanager_webhook_password (same value, backend-only mount)
- grafana_admin_password
- aidiag_deepseek_api_key
- aidiag_stage4_operator_secret

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
