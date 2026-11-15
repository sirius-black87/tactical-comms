#!/usr/bin/env bash
# One-command installer for the Tactical Comms server (IP address mode, no domain).
# Run on a fresh Linux server, from the server/ directory:   sudo bash install.sh
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1; then
  echo ">> Installing Docker..."
  curl -fsSL https://get.docker.com | sh
fi

if [ ! -f .env ]; then
  KEY=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  sed "s/^ACCESS_KEY=.*/ACCESS_KEY=${KEY}/" .env.example > .env
  chmod 600 .env
  echo ">> Created .env with a random access key"
fi

echo ">> Building and starting the server..."
docker compose up -d --build

PORT=$(grep -E '^PORT=' .env | cut -d= -f2 || true); PORT=${PORT:-443}
KEY=$(grep -E '^ACCESS_KEY=' .env | cut -d= -f2)
FP=""
for _ in $(seq 1 30); do
  FP=$(docker compose logs comms 2>/dev/null | grep -oE '([0-9A-F]{2}:){31}[0-9A-F]{2}' | tail -1 || true)
  [ -n "$FP" ] && break
  sleep 1
done
IP=$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')
[ "$PORT" = "443" ] && ADDR="$IP" || ADDR="$IP:$PORT"

if [ -z "$FP" ]; then
  echo "!! The server did not report a certificate. Check: docker compose logs comms"
  exit 1
fi

cat <<MSG

============================================================
 Tactical Comms server is running.

 Enter these in the plugin (the gear button):
   Server address : ${ADDR}
   Access key     : ${KEY}

 Certificate fingerprint (the plugin saves it on first connect;
 it should show the same value):
   ${FP}

 Make sure TCP port ${PORT} is open in the server's firewall.
 Logs: docker compose logs -f comms
============================================================
MSG
