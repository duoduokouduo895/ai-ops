#!/usr/bin/env sh
set -eu

# Run this before starting Nacos on the restored home MySQL database.
# It changes only infrastructure endpoints, never model settings, users,
# knowledge-base data, or other Nacos configuration entries.

: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:?Set MYSQL_USER to a MySQL migration operator}"
: "${MYSQL_PWD:?Set MYSQL_PWD to the MySQL migration operator password}"
: "${NACOS_DB:=nacos_v3_candidate}"
: "${SOURCE_HOST:?Set SOURCE_HOST to the previous infrastructure host or IP}"
: "${AICHAT_HOME_SECRETS_DIR:?Set AICHAT_HOME_SECRETS_DIR}"
: "${MYSQL_DOCKER_CONTAINER:=mysql}"

read_hex_secret() {
  name=$1
  value=$(cat "$AICHAT_HOME_SECRETS_DIR/$name")
  case "$value" in
    ''|*[!0-9A-Fa-f]*)
      echo "Refusing unsafe or empty secret format: $name" >&2
      exit 1
      ;;
  esac
  printf '%s' "$value"
}

mysql() {
  docker exec -i -e MYSQL_PWD="$MYSQL_PWD" "$MYSQL_DOCKER_CONTAINER" \
    sh -ec 'host=$1; port=$2; user=$3; database=$4; shift 4; exec mysql --protocol=tcp --host="$host" --port="$port" --user="$user" --database="$database" "$@"' \
    sh "$MYSQL_HOST" "$MYSQL_PORT" "$MYSQL_USER" "$NACOS_DB" "$@"
}

escaped_source=$(printf '%s' "$SOURCE_HOST" | sed "s/'/''/g")
app_db_password=$(read_hex_secret aichat_db_password)
redis_password=$(read_hex_secret redis_password)
rabbitmq_password=$(read_hex_secret rabbitmq_password)

mysql --batch --skip-column-names <<SQL
START TRANSACTION;

UPDATE config_info
SET content = REGEXP_REPLACE(
  REPLACE(
    REPLACE(
      REPLACE(
        REGEXP_REPLACE(
          REPLACE(content, '${escaped_source}:3306', 'mysql:3306'),
          'spring.r2dbc.host=[^[:cntrl:]]*', 'spring.r2dbc.host=mysql'
        ),
        'spring.r2dbc.username=root', 'spring.r2dbc.username=aichat'
      ),
      'spring.r2dbc.pool.initial-size=10', 'spring.r2dbc.pool.initial-size=2'
    ),
    'spring.r2dbc.pool.max-size=50', 'spring.r2dbc.pool.max-size=16'
  ),
  'spring.r2dbc.password=[^[:cntrl:]]*',
  'spring.r2dbc.password=${app_db_password}'
)
WHERE data_id = 'databaseR2DBC-config.properties';

UPDATE config_info
SET content = REGEXP_REPLACE(
  REGEXP_REPLACE(content, 'spring.data.redis.host=[^[:cntrl:]]*', 'spring.data.redis.host=redis'),
  'spring.data.redis.password=[^[:cntrl:]]*',
  'spring.data.redis.password=${redis_password}'
)
WHERE data_id = 'redis-config.properties';

UPDATE config_info
SET content = REGEXP_REPLACE(
  REGEXP_REPLACE(
    REGEXP_REPLACE(content, 'spring.rabbitmq.host=[^[:cntrl:]]*', 'spring.rabbitmq.host=rabbitmq'),
    'spring.rabbitmq.username=[^[:cntrl:]]*', 'spring.rabbitmq.username=aichat'
  ),
  'spring.rabbitmq.password=[^[:cntrl:]]*',
  'spring.rabbitmq.password=${rabbitmq_password}'
)
WHERE data_id = 'rabbitmq-config.properties';

UPDATE config_info
SET content = REGEXP_REPLACE(content,
  'spring.elasticsearch.uris=[^[:cntrl:]]*',
  'spring.elasticsearch.uris=http://elasticsearch-v9:9200'
)
WHERE data_id IN ('chat-elasticsearch-config.properties', 'elasticsearch-config.properties');

-- Elasticsearch 9 enables security by default.  The source configuration
-- historically left this field blank because it relied on a different
-- network boundary; the home candidate must send the controlled elastic user.
UPDATE config_info
SET content = REGEXP_REPLACE(content,
  'spring.elasticsearch.username=[^[:cntrl:]]*',
  'spring.elasticsearch.username=elastic'
)
WHERE data_id IN ('chat-elasticsearch-config.properties', 'elasticsearch-config.properties');

UPDATE config_info
SET md5 = MD5(content), gmt_modified = NOW(), src_ip = 'home-migration'
WHERE data_id IN (
  'databaseR2DBC-config.properties',
  'redis-config.properties',
  'rabbitmq-config.properties',
  'chat-elasticsearch-config.properties',
  'elasticsearch-config.properties'
);

COMMIT;
SQL

remaining=$(mysql --batch --skip-column-names -e "
  SELECT COUNT(*)
  FROM config_info
  WHERE data_id IN (
    'databaseR2DBC-config.properties',
    'redis-config.properties',
    'rabbitmq-config.properties',
    'chat-elasticsearch-config.properties',
    'elasticsearch-config.properties'
  )
    AND (content LIKE '%34.150.80.173%' OR content LIKE '%34.150.46.227%');")

if [ "$remaining" != "0" ]; then
  echo "Refusing to continue: $remaining selected Nacos entries still contain SOURCE_HOST." >&2
  exit 1
fi

echo "Nacos home endpoint migration completed for $NACOS_DB."
