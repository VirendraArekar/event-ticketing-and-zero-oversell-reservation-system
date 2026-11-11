#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
OUT_FILE="$ROOT_DIR/dumps/gatekeeper-200.sql"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing .env at $ENV_FILE. Run ./setup.sh first." >&2
  exit 1
fi

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

mkdir -p "$(dirname "$OUT_FILE")"

echo "Dumping GateKeeper MySQL data tables to $OUT_FILE..."
docker compose --project-directory "$ROOT_DIR" exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql \
  mysqldump -u"$MYSQL_USER" \
  --no-create-info \
  --no-tablespaces \
  --set-gtid-purged=OFF \
  --complete-insert \
  --single-transaction \
  --skip-triggers \
  --ignore-table="${MYSQL_DATABASE}.flyway_schema_history" \
  "$MYSQL_DATABASE" > "$OUT_FILE"

echo "Wrote $(wc -l < "$OUT_FILE" | tr -d ' ') lines to $OUT_FILE"

