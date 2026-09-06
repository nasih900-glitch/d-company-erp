#!/bin/bash
# D Company ERP — localhost-only Mac developer sandbox.
#
# Double-click this in Finder. It will:
#   1. Check Docker Desktop is installed (and explain how to install it if not).
#   2. Build and start Postgres + the FastAPI backend + the React frontend.
#   3. Apply migrations and seed Kerala/India reference data.
#   4. Open the localhost app in your browser.
#
# This stack uses development credentials and plaintext HTTP. It is not a
# production/café installer and must never be exposed to the LAN.
#
# First run: ~5 minutes (downloads + builds).
# Every subsequent run: ~10 seconds.

set -euo pipefail
set +x  # Never expose generated credentials through shell tracing.
cd "$(dirname "$0")"

GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
say() { printf "${GREEN}==>${NC} %s\n" "$1"; }
warn() { printf "${YELLOW}!!${NC} %s\n" "$1"; }
err() { printf "${RED}xx${NC} %s\n" "$1" >&2; }

clear
echo "============================================================"
echo "  D Company ERP — localhost developer sandbox"
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
  read -r -p "Press Enter to open the Docker download page in your browser…" _
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
  read -r -p "Press Enter to launch Docker Desktop…" _
  open -a "Docker"
  exit 1
fi

say "Docker is running"

# ----- 2. .env -----
if [ ! -f .env ]; then
  say "First run — copying .env.example → .env"
  cp .env.example .env
fi

chmod 600 .env
if grep -Eq '^ENV=(prod|production)$' .env; then
  err "This localhost installer refuses production configuration."
  echo "Use docker-compose.prod.yml through infra/scripts/install-on-vm.sh instead."
  exit 1
fi

if ! grep -q '^SEED_OWNER_EMAIL=' .env; then
  printf '\nSEED_OWNER_EMAIL=owner@dcompany.local\n' >> .env
fi
if ! awk -F= '/^SEED_OWNER_EMAIL=/{count += 1; valid = $2 == "owner@dcompany.local"} END {exit !(count == 1 && valid)}' .env; then
  err "The localhost sandbox requires exactly one SEED_OWNER_EMAIL=owner@dcompany.local."
  exit 1
fi
if ! grep -q '^SEED_OWNER_PASSWORD=' .env; then
  GENERATED_OWNER_PASSWORD="$(openssl rand -hex 24)"
  printf '\nSEED_OWNER_PASSWORD=%s\n' "$GENERATED_OWNER_PASSWORD" >> .env
  unset GENERATED_OWNER_PASSWORD
fi
if ! awk -F= '/^SEED_OWNER_PASSWORD=/{count += 1; valid = length(substr($0, index($0, "=") + 1)) >= 10} END {exit !(count == 1 && valid)}' .env; then
  err "SEED_OWNER_PASSWORD must appear exactly once and contain at least 10 characters."
  exit 1
fi

# ----- 3. Build + start -----
say "Building images and starting containers (first run takes ~5 minutes)…"
docker compose up -d --build

# ----- 4. Wait for backend to be ready -----
say "Waiting for backend (running migrations + seeds inside container)…"
ATTEMPTS=0
until curl -fsS http://localhost:8000/readyz >/dev/null 2>&1; do
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
say "Backend is ready"

# ----- 5. Open the app -----
URL="http://localhost:5173"
say "Opening $URL"
sleep 2
open "$URL"

cat <<MSG

============================================================
  D Company ERP developer sandbox is running on this Mac.
============================================================

  App:           http://localhost:5173
  API docs:      http://localhost:8000/docs
  Owner login:   owner@dcompany.local
  Owner password is stored only in .env (mode 0600) and is not displayed.
  Retrieve it through your local secret workflow, then change it after login.

  Localhost only: do not expose this plaintext dev stack to WiFi/LAN devices.
  For live operations, use the hardened production VM installer.

  To stop the app:   docker compose down
  To start again:    double-click this installer (it's idempotent)
  To see live logs:  docker compose logs -f backend
  Data teardown:     follow docs/RUN_IT_REAL.md (backup + typed confirmation)

============================================================
MSG

read -r -p "Press Enter to close this terminal window…" _
