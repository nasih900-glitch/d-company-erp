#!/bin/bash
# D Company ERP — one-command production installer for an Ubuntu 22.04 VM
# (Oracle Cloud Always Free, AWS, GCP, DigitalOcean, anywhere with Docker support).
#
# Run this AFTER you've:
#   1. SSH'd into your fresh Ubuntu VM
#   2. Cloned (or scp'd) this repo to /opt/d-company-erp/
#   3. Pointed your domain's A record at this VM's public IP
#
# Then:
#   cd /opt/d-company-erp
#   sudo bash infra/scripts/install-on-vm.sh yourdomain.com
#
# What it does:
#   1. Installs Docker + docker compose plugin
#   2. Opens ports 80 and 443 in Ubuntu's iptables (Oracle gotcha)
#   3. Generates strong secrets via generate-secrets.sh
#   4. Sets the DOMAIN in .env
#   5. Brings up the prod stack (Caddy auto-issues Let's Encrypt cert)
#   6. Runs migrations + seed scripts
#   7. Prints the URL + first-login credentials
#
# Total time: ~5 minutes after DNS has propagated.

set -e

if [ "$EUID" -ne 0 ]; then
  echo "Run with sudo: sudo bash $0 yourdomain.com"
  exit 1
fi

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  echo "Usage: sudo bash $0 yourdomain.com"
  echo "(the domain you bought and pointed at this VM's IP)"
  exit 1
fi

cd "$(dirname "$0")/../.."
REPO_DIR="$(pwd)"
echo "=== D Company ERP — production install ==="
echo "Domain:    $DOMAIN"
echo "Repo dir:  $REPO_DIR"
echo

# ----- 1. Docker -----
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker…"
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl gnupg lsb-release
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi
echo "==> Docker version: $(docker --version)"

# ----- 2. Firewall (Oracle Cloud's gotcha) -----
# Oracle Linux/Ubuntu images ship with an iptables INPUT REJECT rule that
# blocks ports the Oracle Security List opens. Fix:
echo "==> Opening ports 80 + 443 in iptables (Oracle Cloud fix)…"
iptables -I INPUT 6 -p tcp -m state --state NEW -m tcp --dport 80  -j ACCEPT  || true
iptables -I INPUT 6 -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT  || true
iptables -I INPUT 6 -p udp -m udp --dport 443 -j ACCEPT                       || true
# Persist iptables rules across reboots.
apt-get install -y -qq iptables-persistent
netfilter-persistent save || true

# Also UFW if it's active (Ubuntu default).
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw allow 22/tcp
fi

# ----- 3. Generate secrets -----
if [ ! -f .env ]; then
  echo "==> Generating production .env with strong secrets…"
  bash infra/scripts/generate-secrets.sh .env
fi

# Set the DOMAIN + CORS in .env.
sed -i \
  -e "s|^DOMAIN=.*|DOMAIN=$DOMAIN|" \
  -e "s|CHANGE_ME\\.com|$DOMAIN|g" \
  .env

echo
echo "==> .env ready. Key values:"
grep -E "^(DOMAIN|SEED_OWNER_EMAIL|ENV)=" .env

# ----- 4. Bring up the stack -----
echo
echo "==> Building containers (5–10 min first time)…"
docker compose -f docker-compose.prod.yml --env-file .env build

echo
echo "==> Starting stack…"
docker compose -f docker-compose.prod.yml --env-file .env up -d

# ----- 5. Wait for backend healthy -----
echo
echo "==> Waiting for backend to be healthy (running migrations + seeds)…"
ATTEMPTS=0
until docker compose -f docker-compose.prod.yml exec -T backend curl -fsS http://localhost:8000/healthz >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ $ATTEMPTS -gt 120 ]; then
    echo "Backend did not come up in 2 minutes. Check logs:"
    echo "  docker compose -f docker-compose.prod.yml logs backend"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
echo "==> Backend is healthy."

# ----- 6. Wait for Caddy to issue the certificate -----
echo
echo "==> Waiting for Caddy to issue Let's Encrypt cert for $DOMAIN…"
echo "    (This can take up to 60 seconds after first DNS-correct request.)"
ATTEMPTS=0
until curl -fsS "https://$DOMAIN/healthz" >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ $ATTEMPTS -gt 120 ]; then
    echo
    echo "HTTPS not ready after 2 minutes. Common causes:"
    echo "  • DNS hasn't propagated yet — try again in 5–10 minutes."
    echo "  • Domain A record points to the wrong IP."
    echo "  • Port 80/443 isn't reachable from the internet (check Oracle Security List)."
    echo "Check Caddy logs:  docker compose -f docker-compose.prod.yml logs caddy"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
echo "==> HTTPS is live."

# ----- 7. Done -----
OWNER_EMAIL=$(grep '^SEED_OWNER_EMAIL=' .env | cut -d= -f2-)
OWNER_PW=$(grep '^SEED_OWNER_PASSWORD=' .env | cut -d= -f2-)

cat <<EOF

============================================================
  ✓ D Company ERP is live at:  https://$DOMAIN
============================================================

  First login:
    Email:    $OWNER_EMAIL
    Password: $OWNER_PW

  ⚠  CHANGE THIS PASSWORD on first login (Settings → Staff).

  To create your friend's view-only login, run:
    docker compose -f docker-compose.prod.yml exec backend \\
      python -m scripts.create_user \\
        --email friend@example.com --name "Friend Name" \\
        --role auditor --password "\$(openssl rand -base64 24)"

  Useful commands:
    docker compose -f docker-compose.prod.yml ps                 # status
    docker compose -f docker-compose.prod.yml logs -f backend    # live logs
    docker compose -f docker-compose.prod.yml restart backend    # restart
    docker compose -f docker-compose.prod.yml down               # stop everything

  Backups (run weekly via cron):
    docker compose -f docker-compose.prod.yml exec postgres \\
      pg_dump -U erp erp | gzip > /root/backups/erp-\$(date +%F).sql.gz

============================================================
EOF
