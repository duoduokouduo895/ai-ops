#!/usr/bin/env bash
set -Eeuo pipefail

psql +  --set=ON_ERROR_STOP=1 +  --username "$POSTGRES_USER" +  --dbname "$POSTGRES_DB" +  --set=role_name="$POSTGRES_USER" <<'EOSQL'
CREATE EXTENSION IF NOT EXISTS vector;
SELECT format(
  'ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'role_name'
) gexec
EOSQL
