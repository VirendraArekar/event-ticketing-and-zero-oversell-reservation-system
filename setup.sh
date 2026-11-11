#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"

gen_secret() {
  openssl rand -hex "$1"
}

if [ ! -f "$ENV_FILE" ]; then
  echo "Generating .env with cryptographically random secrets..."
  MYSQL_PASSWORD="$(gen_secret 24)"
  MYSQL_ROOT_PASSWORD="$(gen_secret 24)"
  JWT_SECRET="$(gen_secret 48)"
  QR_HMAC_SECRET="$(gen_secret 48)"

  cat > "$ENV_FILE" <<EOF
MYSQL_DATABASE=gatekeeper
MYSQL_USER=gatekeeper
MYSQL_PASSWORD=${MYSQL_PASSWORD}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}

JWT_SECRET=${JWT_SECRET}
QR_HMAC_SECRET=${QR_HMAC_SECRET}

JWT_ACCESS_EXPIRATION_SECONDS=900
JWT_REFRESH_EXPIRATION_SECONDS=604800
HOLD_DURATION_SECONDS=600
PAYMENT_DEADLINE_SECONDS=86400
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost

NEXT_PUBLIC_API_URL=/api
EOF
  chmod 600 "$ENV_FILE"
  echo "Created $ENV_FILE"
else
  echo "Using existing $ENV_FILE"
fi

echo "Starting GateKeeper stack via Docker Compose..."
docker compose --project-directory "$ROOT_DIR" up --build -d

echo "Waiting for gateway health..."
for i in $(seq 1 60); do
  if curl -fsS http://localhost/actuator/health >/dev/null 2>&1; then
    echo "GateKeeper is healthy at http://localhost"
    exit 0
  fi
  sleep 2
done

echo "Stack started; check status with: docker compose ps"

