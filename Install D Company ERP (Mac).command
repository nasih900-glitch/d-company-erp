#!/bin/bash
# D Company ERP — Mac one-click installer (REAL backend, not demo).
#
# Double-click this in Finder. It will:
#   1. Check Docker Desktop is installed (and explain how to install it if not).
#   2. Build and start Postgres + the FastAPI backend + the React frontend.
#   3. Apply migrations and seed Kerala/India reference data.
#   4. Open the app in your browser.
#
# First run: ~5 minutes (downloads + builds).
# Every subsequent run: ~10 seconds.

set -e
cd "$(dirname "$0")"

GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
say() { printf "${GREEN}==>${NC} %s\n" "$1"; }
warn() { printf "${YELLOW}!!${NC} %s\n" "$1"; }
err() { printf "${RED}xx${NC} %s\n" "$1" >&2; }

clear
echo "============================================================"
echo "  D Company ERP — Real installation"
echo "============================================================"
echo

# ----- 1. Docker check -----
if ! command -v docker >/dev/null 2>&1; then
  err "Docker is not installed."
  echo
  echo "Install Docker Desktop for Mac:"
  echo "  1. Go to https://www.docker.com/products/docker-desktop/"
  echo "  2. Download the Apple Silicon or Intel build (matches your Mac)."
  echo "  3. Open the .dmg, drag Docker to Applications."
  echo "  4. Launch Docker from Applications. Wait until the whale icon"
  echo "     in the menu bar stops animating."
  echo "  5. Double-click THIS installer again."
  echo
  read -p "Press Enter to open the Docker download page in your browser…" _
  open "https://www.docker.com/products/docker-desktop/"
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  err "Docker is installed but not running."
  echo
  echo "Open Docker Desktop (Applications → Docker) and wait until the"
  echo "whale icon in your menu bar stops animating. Then double-click"
  echo "this installer again."
  echo
  read -p "Press Enter to launch Docker Desktop…" _
  open -a "Docker"
  exit 1
fi

say "Docker is running"

# ----- 2. .env -----
if [ ! -f .env ]; then
  say "First run — copying .env.example → .env"
  cp .env.example .env
fi

if ! grep -q '^SEED_OWNER_PASSWORD=' .env; then
  GENERATED_OWNER_PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9!@#%+=' </dev/urandom | head -c 24)"
  printf '\nSEED_OWNER_PASSWORD=%s\n' "$GENERATED_OWNER_PASSWORD" >> .env
fi
OWNER_PASSWORD_DISPLAY="$(grep '^SEED_OWNER_PASSWORD=' .env | tail -1 | cut -d= -f2-)"

# ----- 3. Build + start -----
say "Building images and starting containers (first run takes ~5 minutes)…"
docker compose up -d --build

# ----- 4. Wait for backend to be ready -----
say "Waiting for backend (running migrations + seeds inside container)…"
ATTEMPTS=0
until curl -fsS http://localhost:8000/healthz >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ $ATTEMPTS -gt 90 ]; then
    err "Backend didn't come up after 90 seconds."
    echo "Check the logs with:  docker compose logs backend"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
say "Backend is healthy"

# ----- 5. Open the app -----
URL="http://localhost:5173"
say "Opening $URL"
sleep 2
open "$URL"

cat <<MSG

============================================================
  D Company ERP is now running on your Mac.
============================================================

  App:           http://localhost:5173
  API docs:      http://localhost:8000/docs
  Owner login:   owner@dcompany.local
  Owner pwd:     $OWNER_PASSWORD_DISPLAY
                 CHANGE THIS AFTER FIRST LOGIN

  To use from another device on the SAME WiFi (tablet/phone),
  find this Mac's IP address with:
      ipconfig getifaddr en0
  then open http://<that-ip>:5173 on the other device.

  To stop the app:   docker compose down
  To start again:    double-click this installer (it's idempotent)
  To see live logs:  docker compose logs -f backend
  To wipe all data:  docker compose down -v   (deletes Postgres volume)

============================================================
MSG

read -p "Press Enter to close this terminal window…" _
