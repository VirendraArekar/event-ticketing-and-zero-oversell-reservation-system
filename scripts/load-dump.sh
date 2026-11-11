#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
DUMP_FILE="${1:-$ROOT_DIR/dumps/gatekeeper-200.sql}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing .env at $ENV_FILE. Run ./setup.sh first." >&2
  exit 1
fi

if [ ! -f "$DUMP_FILE" ]; then
  echo "Dump file not found: $DUMP_FILE" >&2
  exit 1
fi

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

echo "Waiting for MySQL container readiness..."
for i in $(seq 1 30); do
  if docker compose --project-directory "$ROOT_DIR" exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql mysqladmin ping -h 127.0.0.1 -u"$MYSQL_USER" --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Clearing existing application tables (preserving Flyway schema history)..."
docker compose --project-directory "$ROOT_DIR" exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE event_sales_buckets;
TRUNCATE TABLE event_checkin_buckets;
TRUNCATE TABLE ticket_type_dashboard_stats;
TRUNCATE TABLE event_dashboard_stats;
TRUNCATE TABLE processed_kafka_events;
TRUNCATE TABLE outbox_events;
TRUNCATE TABLE notifications;
TRUNCATE TABLE waitlist_entries;
TRUNCATE TABLE check_in_logs;
TRUNCATE TABLE tickets;
TRUNCATE TABLE payment_ledger;
TRUNCATE TABLE idempotency_records;
TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
TRUNCATE TABLE ticket_inventory;
TRUNCATE TABLE ticket_types;
TRUNCATE TABLE event_staff_assignments;
TRUNCATE TABLE events;
TRUNCATE TABLE refresh_tokens;
TRUNCATE TABLE user_roles;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
SQL

echo "Loading SQL dump from $DUMP_FILE..."
docker compose --project-directory "$ROOT_DIR" exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" < "$DUMP_FILE"

echo "Flushing Redis caches so inventory and event caches reconcile cleanly from MySQL..."
docker compose --project-directory "$ROOT_DIR" exec -T redis redis-cli FLUSHDB >/dev/null

echo "Verifying row counts:"
docker compose --project-directory "$ROOT_DIR" exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "
SELECT 'events' AS table_name, COUNT(*) AS rows_loaded FROM events
UNION ALL SELECT 'ticket_types', COUNT(*) FROM ticket_types
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'tickets', COUNT(*) FROM tickets
UNION ALL SELECT 'check_in_logs', COUNT(*) FROM check_in_logs
UNION ALL SELECT 'notifications', COUNT(*) FROM notifications;
"

echo "Done! Public catalog and dashboard metrics are immediately available at http://localhost"

